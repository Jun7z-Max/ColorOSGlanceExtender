---
name: magisk-module-development
description: 在 macOS（尤其 Apple Silicon）上开发、检查、构建、打包和调试 Magisk 模块，涵盖 module.prop、customize.sh、service.sh、post-fs-data.sh、system overlay、system.prop、sepolicy.rule、Zygisk/NDK 与 ADB 真机验证。用户提到 Magisk 模块、Root 模块、Zygisk、module.prop、/data/adb/modules、模块安装 ZIP、开机脚本或需要检查 Android/Magisk 开发环境时使用。不用于普通 Android App 开发，也不得未经用户明确确认刷写 boot/init_boot、安装模块或修改真机系统。
---

# Magisk 模块开发

## 核心原则

- 先判断模块类型，再决定依赖；不要把完整 Android App 环境强加给纯 Shell 模块。
- 以项目现有构建脚本、Gradle Wrapper 和版本约束为准，不擅自升级 AGP、Gradle、JDK、NDK 或 Magisk API。
- 将 macOS 作为编辑、编译和打包主机；Magisk 脚本实际运行于 Android 上的 BusyBox `ash`，不能用本机 Bash 成功代替真机验证。
- 所有文本使用 UTF-8、Unix LF；ZIP 内必须直接以 `module.prop` 等模块文件为根，不能多包一层目录。
- 不硬编码密钥、设备序列号或 `/data/adb/modules/<id>`；脚本中用 `MODDIR=${0%/*}`。
- 未获得用户明确确认前，不执行安装 ZIP、`adb shell su -c` 写操作、分区刷写、重启或删除设备文件。

## 工作流

### 1. 读取上下文并分类

先读取项目的 `AGENTS.md`、`CLAUDE.md`、README、构建脚本和现有模块文件。按以下类型分类：

1. **纯 Shell/overlay 模块**：`module.prop`、Shell 脚本、`system/`；通常只需 Git、ZIP、ADB 和测试设备。
2. **Zygisk 原生模块**：存在 `zygisk/`、C/C++、CMake/ndk-build；额外需要项目指定 NDK、CMake/Ninja 和对应 ABI 构建。
3. **带管理 App 的模块**：存在 `settings.gradle*`、`gradlew`、Android App；额外需要项目指定 JDK、SDK Platform、Build Tools，优先执行 `./gradlew`。
4. **boot/init_boot 或内核相关项目**：涉及 `magiskboot`、AVB、boot image；视为高风险独立流程，先核对机型、分区和原厂镜像，任何写设备操作都必须再次确认。

不确定时先向用户说明判断依据并询问，不要悄悄选择。

### 2. 检查环境

运行：

```bash
bash ~/.agents/skills/magisk-module-development/scripts/check_environment.sh [项目目录]
```

根据类型解读结果：

- 纯 Shell 模块不要求全局 Gradle、Android Studio、模拟器、NDK 或 `magiskboot`。
- Gradle 项目优先检查 `./gradlew --version`，不要仅因全局 `gradle` 缺失而安装它。
- Zygisk 项目读取 `build.gradle*`、`CMakeLists.txt`、`Application.mk` 或 CI 中的 NDK 版本后再安装。
- `apksigner`、`aapt2` 在 SDK 的版本化 `build-tools/<version>/` 内即可，不强制加入全局 PATH。
- 无已连接设备不代表主机环境失败，但安装、启动和日志验证仍未完成。

需要调整 macOS 配置时读取 [references/macos-environment.md](references/macos-environment.md)。

### 3. 修改模块

- 保持改动最小，沿用现有目录、命名和构建方式。
- 默认把普通开机任务放入 `service.sh`；只有确有早期启动需求才使用阻塞启动的 `post-fs-data.sh`。
- 等待系统启动完成时可用 `resetprop -w sys.boot_completed 0`。
- `post-fs-data.sh` 中不要使用可能死锁的 `setprop`；需要设置属性时使用合适的 `resetprop` 形式。
- `customize.sh` 是被安装器 `source` 的，不要在末尾调用 `exit`；失败时使用安装器提供的 `abort`。
- 不修改 `update-binary` 注入自定义逻辑，不创建历史遗留的 `install.sh`。
- 涉及模块格式、安装变量、overlay 或更新 JSON 时，读取 [references/module-format.md](references/module-format.md)。

### 4. 静态验证

运行：

```bash
python3 ~/.agents/skills/magisk-module-development/scripts/validate_module.py <模块根目录>
```

修复所有 `ERROR`。逐项评估 `WARN`，不能只为消除提示而改变有意设计。若项目已有测试、lint 或 CI 命令，也必须执行。

ShellCheck 只能补充发现引号、未定义变量等问题；它不完全理解 Magisk BusyBox `ash` 和安装器注入的变量/函数，不能作为唯一结论。

### 5. 构建与打包

- 有项目构建入口时使用项目入口，例如 `./gradlew module:zipRelease`、`make` 或仓库脚本。
- 没有构建入口的标准模块可运行：

```bash
bash ~/.agents/skills/magisk-module-development/scripts/package_module.sh <模块根目录> [输出.zip]
```

打包后再次检查：

```bash
unzip -l <输出.zip>
unzip -p <输出.zip> module.prop
```

确认 ZIP 根层级、版本号、ABI 产物、脚本和排除项正确。

### 6. 真机验证

优先使用非主力、可恢复、已解锁且安装兼容 Magisk 的物理设备。普通未 Root 模拟器不能证明模块可用。

在任何写操作前：

1. 运行 `adb devices -l`，向用户展示并确认目标序列号。
2. 确认可恢复手段：原厂 boot/init_boot、备用槽位或 Magisk 安全模式/模块移除方案。
3. 先 `adb push` 到普通存储；让用户在 Magisk App 中安装，或单独征得同意后再执行 Root 写操作。
4. 重启后检查模块状态、目标行为、`logcat`、模块自有日志和 SELinux 拒绝信息。
5. 验证卸载/禁用路径，确认不会 bootloop 或留下不可逆改动。

## 交付要求

完成后报告：

- 模块类型与目标 Android/Magisk/ABI；
- 修改文件及原因；
- 执行过的验证命令和结果；
- 尚未完成的真机验证；
- 风险、回滚方式和需要用户确认的下一步。

不要把“主机静态检查通过”描述为“模块已在真机验证通过”。
