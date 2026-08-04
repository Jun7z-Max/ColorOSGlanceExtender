# ColorOS 负一屏扩展

ColorOS 速览（负一屏）标准 Android AppWidget 兼容模块。

模块会在运行时读取当前用户可用的桌面小组件，并将原本未出现在“全部卡片”中的标准 AppWidget 临时接入 ColorOS 卡片配置流程。应用名单、名称和图标均动态获取，不包含针对特定应用的白名单。

> [!IMPORTANT]
> 本项目是 **source-available（源码可见）** 软件，不是 OSI 定义的开源软件。仅允许非商业用途；商业使用必须另行取得授权，详见 [`LICENSE`](LICENSE)。

## 功能

- 动态发现当前用户已安装应用提供的标准 `AppWidgetProvider`；
- 仅接入支持主屏幕类别的 Widget；
- 保留 ColorOS 已有卡片配置，不重复生成同一组件；
- 动态读取应用名称，避免新增分组标题为空；
- 动态输出应用公开图标，避免新增分组缺少图标；
- 复用 ColorOS 原生 AppWidget Host、绑定和配置 Activity 流程；
- 配置仅在 UMS 重建期间注入内存，结束后立即清理；
- 不主动分配 AppWidget ID，不主动绑定 Widget，不写入 UMS 配置数据库。

## 动态刷新

新安装的应用只要提供标准桌面小组件，就会在 ColorOS 下一次刷新 Provider 列表和卡片配置时被发现，不需要更新模块。

如果没有立即出现：

1. 关闭并重新打开“卡片中心”；
2. 仍未出现时，重载“速览”和“智慧建议服务”两个作用域进程；
3. 最后再尝试重启手机。

以下情况不会加入列表：

- 应用没有提供标准 Android 桌面小组件；
- Widget 仅支持非主屏幕类别；
- Widget 属于其他用户、工作资料或应用分身；
- ColorOS/UMS 自身没有枚举到该 Provider。

## 兼容性

当前版本：`0.1.11`

| 项目 | 已验证环境 |
|---|---|
| Android | Android 16 / API 36 |
| ColorOS 速览 | `com.coloros.assistantscreen` 17.10.120 |
| 智慧建议服务 | `com.oplus.pantanal.ums` 16.59.4 |
| Xposed API | min 102 / target 102 |

ColorOS 系统组件包含混淆类名，系统更新后 Hook 点可能变化。其他版本可以尝试，但不保证兼容；若目标类不存在，模块会保持原厂行为并停止本次注入。

## 安装

前置条件：

- 已 Root 的 ColorOS 设备；
- 已安装支持 libxposed API 102 的 LSPosed 实现；
- 建议先备份重要数据。

安装步骤：

1. 从 GitHub Releases 下载已签名 APK，并核对 `SHA256SUMS`；
2. 安装 APK；
3. 在 LSPosed 中启用模块；
4. 勾选以下两个作用域：
   - `速览`：`com.coloros.assistantscreen`
   - `智慧建议服务`：`com.oplus.pantanal.ums`
5. 重载两个作用域进程或重启手机；
6. 打开负一屏卡片中心，在“全部卡片”中检查新增 Widget。

> [!NOTE]
> 如果设备此前安装的是本地 Debug 签名包，首次切换到 GitHub 正式 Release 签名时无法覆盖安装。请先卸载旧 Debug 包，再安装正式包并重新启用 LSPosed 作用域；后续只要一直使用同一 Release keystore 就可以正常覆盖升级。

不需要安装彩云天气或任何指定应用。模块会按当前设备实际安装的 AppWidget 动态生成列表。

## 权限与隐私

模块声明 `QUERY_ALL_PACKAGES`，仅用于按包名读取应用公开名称和图标。模块没有网络权限，不包含遥测、统计或远程配置。

图标通过只读 `ContentProvider` 提供给 ColorOS 图片加载链路：

```text
content://io.github.colorosglance.extender.icons/app/<packageName>
```

Provider 只接受严格校验后的包名，只支持读取 PNG，不支持新增、更新或删除数据。详细说明见 [`PRIVACY.md`](PRIVACY.md)。

## 安全边界

模块遵守以下约束：

- 不清除速览或 UMS 数据；
- 不主动调用配置 Repository、DAO、Subject 或 Provider 通知接口；
- 不主动执行“添加卡片”；
- 不主动分配、绑定或释放 AppWidget ID；
- 合成配置使用独立 marker、type 和 group 区间；
- bridge、Provider 快照或反射结构异常时 fail-closed；
- 单次最多生成 512 条合成配置，避免异常数据无限扩张。

## 构建

要求：

- JDK 17；
- Android SDK Platform 36；
- Android Build Tools 36.0.0；
- 可访问 Maven Central。

基础检查：

```bash
./gradlew clean lintDebug assembleDebug
```

生成规范化调试产物：

```bash
./scripts/package-apk.sh debug
```

产物位于 `dist/`：

```text
ColorOS-Negative-Screen-Extension-v0.1.11-debug.apk
ColorOS-Negative-Screen-Extension-v0.1.11-debug.apk.sha256
SHA256SUMS
```

libxposed API 使用官方 Maven 依赖 `io.github.libxposed:api:102.0.0`，仅参与编译，不会打入 APK。

## 发布签名

Release 构建不会回退到调试签名。需要通过环境变量显式提供签名信息：

```bash
export CGE_KEYSTORE_PATH=/absolute/path/release.jks
export CGE_KEYSTORE_PASSWORD='...'
export CGE_KEY_ALIAS='...'
export CGE_KEY_PASSWORD='...'
./scripts/package-apk.sh release
```

完整发布流程和 GitHub Secrets 配置见 [`docs/release.md`](docs/release.md)。

## LSPosed 模块仓库

首次提交包名 `io.github.colorosglance.extender` 后，官方机器人会创建对应的 `Xposed-Modules-Repo` 仓库并邀请维护者。正式 Release tag 必须使用 `<versionCode>-<versionName>`，当前版本为 `12-0.1.11`。完整步骤见 [`docs/lsposed-repository.md`](docs/lsposed-repository.md)。

## 项目结构

```text
app/src/main/java/          模块 Hook 与图标 Provider
app/src/main/resources/     libxposed 模块元数据和默认作用域
docs/hook-points.md         当前系统组件 Hook 点与安全不变量
docs/release.md             签名、打包和 GitHub Release 流程
scripts/package-apk.sh      标准化 APK、签名检查和 SHA-256 生成
.github/workflows/          CI 与标签发布流程
```

## 调试日志

```bash
adb logcat -s ColorOSGlanceExtender
```

日志可能包含包名、组件名和 Provider 数量。提交 Issue 前请删除与问题无关的应用信息、账号信息和设备标识。

## 贡献

提交代码前请阅读 [`CONTRIBUTING.md`](CONTRIBUTING.md)。兼容性问题请同时提供 Android、ColorOS、速览、智慧建议服务和 LSPosed 版本。

## 许可证

本项目使用 [PolyForm Noncommercial License 1.0.0](LICENSE)：允许个人学习、研究和其他许可证定义的非商业用途；不授予商业使用权。

第三方组件及其许可证见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

## 免责声明

本项目会 Hook 系统组件，存在系统更新后失效、卡片中心异常或需要重载进程的风险。使用者应自行评估并承担风险；项目作者不对数据丢失、系统故障或兼容性问题负责。

## 作者

- GitHub：[@Jun7z-Max](https://github.com/Jun7z-Max)
