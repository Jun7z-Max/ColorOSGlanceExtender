#!/usr/bin/env python3
"""对标准 Magisk 模块做保守的静态结构检查。"""

from __future__ import annotations

import argparse
import json
import re
import stat
import sys
from pathlib import Path

REQUIRED = ("id", "name", "version", "versionCode", "author", "description")
SCRIPT_NAMES = ("customize.sh", "post-fs-data.sh", "service.sh", "uninstall.sh", "action.sh")
ID_PATTERN = re.compile(r"^[a-zA-Z][a-zA-Z0-9._-]+$")
ALLOWED_ZYGISK = {
    "arm64-v8a.so",
    "armeabi-v7a.so",
    "riscv64.so",
    "x86.so",
    "x86_64.so",
    "unloaded",
}


def main() -> int:
    parser = argparse.ArgumentParser(description="验证 Magisk 模块目录")
    parser.add_argument("module_dir", type=Path)
    args = parser.parse_args()
    root = args.module_dir.expanduser().resolve()

    errors: list[str] = []
    warnings: list[str] = []

    if not root.is_dir():
        print(f"ERROR: 目录不存在: {root}")
        return 2

    prop = root / "module.prop"
    values: dict[str, str] = {}
    if not prop.is_file():
        errors.append("模块根目录缺少 module.prop")
    else:
        raw = prop.read_bytes()
        if b"\r" in raw:
            errors.append("module.prop 含 CR/CRLF，请改为 Unix LF")
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            errors.append("module.prop 不是有效 UTF-8")
            text = ""
        for number, line in enumerate(text.splitlines(), 1):
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                warnings.append(f"module.prop:{number} 不是 key=value")
                continue
            key, value = line.split("=", 1)
            if key in values:
                errors.append(f"module.prop 重复字段: {key}")
            values[key] = value
        for key in REQUIRED:
            if not values.get(key):
                errors.append(f"module.prop 缺少必需字段或值为空: {key}")
        module_id = values.get("id", "")
        if module_id and not ID_PATTERN.fullmatch(module_id):
            errors.append("module.prop 的 id 不匹配 ^[a-zA-Z][a-zA-Z0-9._-]+$")
        version_code = values.get("versionCode", "")
        if version_code and not version_code.isdigit():
            errors.append("module.prop 的 versionCode 必须是非负整数")
        for key, value in values.items():
            if "\n" in value or "\r" in value:
                errors.append(f"module.prop 字段必须单行: {key}")

    for name in SCRIPT_NAMES:
        path = root / name
        if not path.is_file():
            continue
        raw = path.read_bytes()
        if b"\r" in raw:
            errors.append(f"{name} 含 CR/CRLF，请改为 Unix LF")
        first = raw.splitlines()[0] if raw.splitlines() else b""
        if not first.startswith(b"#!"):
            warnings.append(f"{name} 没有 shebang")
        text = raw.decode("utf-8", errors="replace")
        if "/data/adb/modules/" in text:
            warnings.append(f"{name} 疑似硬编码模块路径；应使用 MODDIR=${{0%/*}}")
        if name == "customize.sh" and re.search(r"(?m)^\s*exit(?:\s|$)", text):
            errors.append("customize.sh 不应调用 exit；失败时使用 abort")
        if name == "post-fs-data.sh" and re.search(r"(?m)^\s*setprop(?:\s|$)", text):
            warnings.append("post-fs-data.sh 使用 setprop 可能死锁；核对是否应使用 resetprop -n")
        mode = path.stat().st_mode
        if name != "customize.sh" and not mode & stat.S_IXUSR:
            warnings.append(f"{name} 在源码中不可执行；确认构建/安装过程会设置权限")

    if (root / "install.sh").exists():
        errors.append("不应在模块 ZIP 中包含 install.sh")

    zygisk = root / "zygisk"
    if zygisk.is_dir():
        for item in zygisk.iterdir():
            if item.is_file() and item.name not in ALLOWED_ZYGISK:
                warnings.append(f"非标准 Zygisk 根文件名: zygisk/{item.name}")

    update_json = root / "update.json"
    if update_json.is_file():
        try:
            data = json.loads(update_json.read_text(encoding="utf-8"))
            for key in ("version", "versionCode", "zipUrl", "changelog"):
                if key not in data:
                    errors.append(f"update.json 缺少字段: {key}")
            if "versionCode" in data and not isinstance(data["versionCode"], int):
                errors.append("update.json 的 versionCode 必须是整数")
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            errors.append(f"update.json 无效: {exc}")

    for path in root.rglob("*"):
        if path.is_file() and path.name in {".DS_Store"}:
            warnings.append(f"应从发布 ZIP 排除: {path.relative_to(root)}")

    for message in errors:
        print(f"ERROR: {message}")
    for message in warnings:
        print(f"WARN:  {message}")
    if not errors and not warnings:
        print("OK: 模块静态结构检查通过")
    else:
        print(f"汇总: ERROR={len(errors)} WARN={len(warnings)}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
