# macOS 环境基线

## 最小依赖矩阵

| 场景 | 必需 | 按需 |
|---|---|---|
| 纯 Shell/overlay 模块 | Git、`zip`/`unzip`、ADB、可恢复的 Magisk 测试设备 | ShellCheck |
| Zygisk/C/C++ | 上述工具、项目指定 NDK、CMake/Ninja 或 ndk-build | Android Studio |
| 带 Android App/Gradle | JDK、Android SDK、项目 `gradlew`、项目指定 Platform/Build Tools | Android Studio、模拟器 |
| boot image 处理 | 项目指定的 `magiskboot`/mkbootimg/AVB 工具、原厂镜像、校验和、恢复方案 | payload 提取工具 |

全局 Gradle通常不是必需项；使用仓库中的 Gradle Wrapper 以固定版本。

## 推荐环境变量

若 SDK 由 Homebrew `android-commandlinetools` 安装：

```zsh
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
export ANDROID_SDK_ROOT="$ANDROID_HOME" # 仅为兼容仍读取旧变量的工具
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

不要同时维护内容不同的 `ANDROID_HOME` 与 `ANDROID_SDK_ROOT`。修改后新开终端并执行：

```bash
printf '%s\n' "$JAVA_HOME" "$ANDROID_HOME" "$ANDROID_SDK_ROOT"
java -version
adb version
sdkmanager --list_installed
```

Homebrew 的 JDK 可以直接通过显式 `JAVA_HOME` 使用；若某 GUI 或 `/usr/libexec/java_home` 必须发现系统 JDK，再按 Homebrew caveat 创建系统级 JDK symlink。不要仅为命令行构建盲目修改 `/Library/Java/JavaVirtualMachines`。

## SDK 选择

先读项目：

- `compileSdk` / `targetSdk` 决定需要的 SDK Platform；
- Android Gradle Plugin 和项目文档决定 JDK/Gradle 兼容范围；
- `ndkVersion`、CI 或 sample 决定 NDK；
- `cmake.version` 或构建脚本决定 CMake。

然后精确安装，例如：

```bash
sdkmanager "platform-tools" "platforms;android-<API>" "build-tools;<版本>"
sdkmanager "ndk;<版本>" "cmake;<版本>"
```

不要因为某教程推荐 API 33/36 就覆盖项目约束。

## Apple Silicon 注意事项

- 主机架构是 `arm64`，Android 目标 ABI 可能是 `arm64-v8a`、`armeabi-v7a`、`x86_64` 等，两者不能混淆。
- Zygisk 库必须按目标 ABI 放在 `zygisk/<abi>.so`，文件名采用 Magisk 规定的 ABI 名称。
- 模拟器适合普通 App/API 行为检查，但不能替代已 Root、已安装兼容 Magisk 的物理设备测试。
- NDK 自带交叉编译工具链；不要用系统 `/usr/bin/clang` 直接冒充 Android 目标编译器。

## 可选工具判断

- **ShellCheck**：推荐用于复杂 Shell，但需人工复核 BusyBox ash 与 Magisk 注入变量产生的告警。
- **scrcpy**：仅改善真机操作体验，不是构建依赖。
- **Nezha / CC Switch / Claude Code**：属于 AI 工作流工具，不是 Magisk 构建依赖；已能使用当前编码代理时无需重复安装。
- **Android Emulator**：不是 Magisk 模块开发基线，且普通 AVD 不提供有效的 Magisk 模块集成验证。
- **magiskboot / avbtool / payload-dumper**：只在处理 boot/init_boot、OTA payload 或 AVB 时安装，避免把高风险工具误当通用依赖。

## 安全配置

API Key 应由系统钥匙串、专用凭据工具或权限受控的配置文件管理。不要把明文 Token 写进会被提交、共享或输出到日志的 shell 配置；发现后应迁移并轮换泄露过的密钥。
