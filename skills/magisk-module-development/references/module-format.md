# Magisk 模块格式速查

依据 Magisk 官方 Developer Guides 整理；实现前仍需核对当前项目锁定的 Magisk 版本与上游文档。

## 标准结构

```text
module-root/
├── module.prop
├── customize.sh       # 可选，安装时被 source
├── post-fs-data.sh    # 可选，早期、阻塞阶段
├── service.sh         # 可选，late_start、非阻塞，通常优先
├── uninstall.sh       # 可选
├── action.sh          # 可选
├── system.prop        # 可选
├── sepolicy.rule      # 可选
├── skip_mount         # 可选状态文件
├── system/            # 可选系统 overlay
└── zygisk/            # 可选原生库
    ├── arm64-v8a.so
    ├── armeabi-v7a.so
    ├── riscv64.so
    ├── x86.so
    └── x86_64.so
```

`vendor`、`product`、`system_ext` 应分别放入 `system/vendor`、`system/product`、`system/system_ext`，不要手工创建 Magisk 自动生成的顶层链接。

## module.prop

必需字段：

```properties
id=example.module
name=Example Module
version=v1.0.0
versionCode=10000
author=author
description=Single-line description
```

可选：

```properties
updateJson=https://example.com/update.json
```

约束：

- `id` 匹配 `^[a-zA-Z][a-zA-Z0-9._-]+$`，发布后保持稳定；
- `versionCode` 是整数，并随发布单调增加；
- 每个值单行，文件使用 UTF-8/Unix LF；
- 更新 JSON 包含 `version`、整数 `versionCode`、`zipUrl`、`changelog`。

## Shell 与生命周期

- Magisk 脚本运行在 BusyBox `ash` standalone mode，不按本机 Bash/Zsh 行为推断。
- 模块脚本使用 `MODDIR=${0%/*}` 定位自身。
- `service.sh` 用于大多数后台启动任务。
- `post-fs-data.sh` 会阻塞启动，只用于必须早于 Zygote/模块挂载处理的逻辑；避免慢操作和网络请求。
- `customize.sh` 可读取 `MAGISK_VER`、`MAGISK_VER_CODE`、`BOOTMODE`、`MODPATH`、`TMPDIR`、`ZIPFILE`、`ARCH`、`IS64BIT`、`API`，并使用 `ui_print`、`abort`、`set_perm`、`set_perm_recursive`。
- `customize.sh` 末尾不要 `exit`；需要完全自行解压时才设置 `SKIPUNZIP=1`。

## Overlay

- `system/` 内容递归合并到真实系统视图；
- 目录中的 `.replace` 表示替换整个目标目录，风险高，必须核对范围；
- 安装时可在 `customize.sh` 中通过 `REPLACE` / `REMOVE` 声明，但删除系统文件属于高风险变更；
- 不要直接修改只读系统分区。

## 安装 ZIP

Magisk App 安装的简单模块通常可直接将模块根内容压到 ZIP 根层。只有明确支持 recovery 刷入时才需要：

```text
META-INF/com/google/android/update-binary
META-INF/com/google/android/updater-script  # 内容仅为 #MAGISK
```

不要在 `update-binary` 中放自定义逻辑；不要创建 `install.sh`。

## 上游资料

- Developer Guides: `https://topjohnwu.github.io/Magisk/guides.html`
- Magisk repository docs: `https://github.com/topjohnwu/Magisk/tree/master/docs`
- Zygisk sample: `https://github.com/topjohnwu/zygisk-module-sample`
