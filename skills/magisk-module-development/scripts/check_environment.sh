#!/usr/bin/env bash
set -uo pipefail

project="${1:-}"
pass=0
warn=0
fail=0

ok() { printf 'OK    %s\n' "$*"; pass=$((pass + 1)); }
warning() { printf 'WARN  %s\n' "$*"; warn=$((warn + 1)); }
error() { printf 'ERROR %s\n' "$*"; fail=$((fail + 1)); }

has() {
  if command -v "$1" >/dev/null 2>&1; then
    ok "$1: $(command -v "$1")"
  else
    warning "$1 未找到"
  fi
}

printf 'Magisk 模块开发环境检查\n'
printf '系统: %s / %s\n\n' "$(uname -s)" "$(uname -m)"

for cmd in git zip unzip python3 java adb fastboot sdkmanager; do has "$cmd"; done

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
android_home="${ANDROID_HOME:-}"
android_sdk_root="${ANDROID_SDK_ROOT:-}"
if [[ -z "$sdk" && -d /opt/homebrew/share/android-commandlinetools ]]; then
  sdk=/opt/homebrew/share/android-commandlinetools
  warning "ANDROID_HOME/ANDROID_SDK_ROOT 未设置；检测到 Homebrew SDK: $sdk"
elif [[ -n "$sdk" && -d "$sdk" ]]; then
  ok "Android SDK: $sdk"
elif [[ -n "$sdk" ]]; then
  error "Android SDK 变量指向不存在目录: $sdk"
else
  warning '未检测到 Android SDK 根目录'
fi

if [[ -n "$android_home" && -n "$android_sdk_root" && "$android_home" != "$android_sdk_root" ]]; then
  error 'ANDROID_HOME 与 ANDROID_SDK_ROOT 指向不同目录'
fi

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  ok "JAVA_HOME: $JAVA_HOME"
else
  warning 'JAVA_HOME 未设置或无效'
fi

if command -v adb >/dev/null 2>&1; then
  devices="$(adb devices -l 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')"
  if [[ -n "$devices" ]]; then
    ok "已连接设备: $(tr '\n' ' ' <<<"$devices")"
  else
    warning '没有处于 device 状态的 ADB 设备；真机验证尚不可执行'
  fi
fi

if [[ -n "$sdk" && -d "$sdk" ]]; then
  build_tools="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)"
  platforms="$(find "$sdk/platforms" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | xargs -n1 basename 2>/dev/null | tr '\n' ' ' || true)"
  ndks="$(find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | xargs -n1 basename 2>/dev/null | tr '\n' ' ' || true)"
  [[ -n "$build_tools" ]] && ok "Build Tools: $(basename "$build_tools")" || warning '未安装 Build Tools（纯 Shell 模块可不需要）'
  [[ -n "$platforms" ]] && ok "SDK Platforms: $platforms" || warning '未安装 SDK Platform（纯 Shell 模块可不需要）'
  [[ -n "$ndks" ]] && ok "NDK: $ndks" || warning '未安装 NDK（仅 Zygisk/原生模块需要）'
  [[ -x "$build_tools/apksigner" ]] && ok "apksigner 位于 $build_tools" || warning 'apksigner 未在最新 Build Tools 中找到（纯模块 ZIP 通常不需要）'
fi

if command -v shellcheck >/dev/null 2>&1; then
  ok "ShellCheck: $(shellcheck --version | awk -F': ' '/version:/ {print $2}')"
else
  warning 'ShellCheck 未安装（推荐但非必需）'
fi

if [[ -n "$project" ]]; then
  project="$(cd "$project" 2>/dev/null && pwd)" || { error "项目目录不存在: $1"; project=''; }
fi

if [[ -n "$project" ]]; then
  printf '\n项目: %s\n' "$project"
  [[ -f "$project/module.prop" ]] && ok '检测到 module.prop' || warning '项目根目录没有 module.prop'

  if [[ -x "$project/gradlew" ]]; then
    ok '检测到 Gradle Wrapper（优先于全局 Gradle）'
  elif find "$project" -maxdepth 2 -type f \( -name 'build.gradle' -o -name 'build.gradle.kts' \) -print -quit 2>/dev/null | grep -q .; then
    warning '检测到 Gradle 构建文件但没有可执行的 gradlew'
  fi

  native=0
  [[ -d "$project/zygisk" ]] && native=1
  if find "$project" -maxdepth 3 -type f \( -name 'CMakeLists.txt' -o -name 'Android.mk' -o -name 'Application.mk' \) -print -quit 2>/dev/null | grep -q .; then native=1; fi
  if (( native )); then
    [[ -n "$sdk" && -d "$sdk/ndk" ]] && ok '原生/Zygisk 项目具备 NDK 基础' || error '原生/Zygisk 项目未检测到 NDK'
  fi
fi

printf '\n汇总: OK=%d WARN=%d ERROR=%d\n' "$pass" "$warn" "$fail"
(( fail == 0 ))
