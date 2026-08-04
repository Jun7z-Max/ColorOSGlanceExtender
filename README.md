# ColorOS 负一屏扩展

ColorOS 速览（负一屏）标准 Android AppWidget 兼容模块。

模块会在运行时读取当前用户可用的桌面小组件，并将原本未出现在“全部卡片”中的标准 AppWidget 临时接入 ColorOS 卡片配置流程。应用名单、名称和图标均动态获取，不包含针对特定应用的白名单。

## 功能

- 动态发现当前用户已安装应用提供的标准 `AppWidgetProvider`；
- 仅接入支持主屏幕类别的 Widget；
- 保留 ColorOS 已有卡片配置，不重复生成同一组件；
- 复用 ColorOS 原生 AppWidget Host、绑定和配置 Activity 流程；

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

## 权限与隐私

模块声明 `QUERY_ALL_PACKAGES`，仅用于按包名读取应用公开名称和图标。模块没有网络权限，不包含遥测、统计或远程配置。

图标通过只读 `ContentProvider` 提供给 ColorOS 图片加载链路：

```text
content://io.github.colorosglance.extender.icons/app/<packageName>
```

Provider 只接受严格校验后的包名，只支持读取 PNG，不支持新增、更新或删除数据。


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


## 免责声明

本项目会 Hook 系统组件，存在系统更新后失效、卡片中心异常或需要重载进程的风险。使用者应自行评估并承担风险；项目作者不对数据丢失、系统故障或兼容性问题负责。

## 作者

- GitHub：[@Jun7z-Max](https://github.com/Jun7z-Max)
