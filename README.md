# ColorOS 负一屏扩展

ColorOS 速览（负一屏）标准 Android AppWidget 兼容模块。

模块会在运行时读取当前用户可用的桌面小组件，并将原本未出现在“全部卡片”中的标准 AppWidget 临时接入 ColorOS 卡片配置流程。应用名单、名称和图标均动态获取，不包含针对特定应用的白名单。

## 功能

- 动态发现当前用户已安装应用提供的标准 `AppWidgetProvider`；
- 仅接入支持主屏幕类别的 Widget；
- 保留 ColorOS 已有卡片配置，不重复生成同一组件；
- 复用 ColorOS 原生 AppWidget Host、绑定和配置 Activity 流程；
- 提供简约的模块管理页，仅展示第三方卡片并按应用分组；
- 支持按应用展开或收起，并为每张卡片单独设置是否出现在速览搜索中；
- 右上角提供太阳/月亮按钮切换浅色与深色主题，并记住用户选择；
- 使用新的“速览窗口”应用图标，列表布局保持图标、名称和数量垂直对齐；
- 应用名称、应用图标和 Widget 标签均从系统动态读取，不硬编码应用名单。

## 管理页面

从系统应用列表打开“ColorOS 负一屏扩展”即可进入管理页面。页面中的开关状态保存在模块本地：

- 新发现的卡片默认开启；
- 关闭卡片后，下一次 UMS 配置重建会临时过滤它，不写入模块外部数据库；
- 重新打开开关后，页面会自动请求 UMS 刷新并恢复到速览搜索；
- 深浅色切换采用渐变过渡和太阳/月亮左右滑动动画；
- 如果 UMS 尚未启动，刷新请求会在下一次速览进程启动或自然重建时生效。

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

当前版本：`0.1.14`

| 项目 | 已验证环境 |
|---|---|
| Android | Android 16 / API 36 |
| ColorOS 速览 | `com.coloros.assistantscreen` 17.10.120（结构回归） / 17.11.80（实机） |
| 智慧建议服务 | `com.oplus.pantanal.ums` 16.59.4 |
| Xposed API | min 102 / target 102 |

模块不读取机型、设备代号或系统组件版本号，也不包含 X9 等机型分支。速览侧优先使用稳定 Provider/调用签名，并通过字段类型、公开语义和对象结构回退识别混淆实现；UMS 侧同样在未匹配到目标结构时保持原厂行为并停止本次注入。系统更新若改变公开调用链或对象结构，仍可能需要更新模块。

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

构建：

```bash
./gradlew clean lintDebug assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## Codex Skill

仓库内置项目专用 Skill：`skills/coloros-glance-extender/`。需要在 Codex 中复用时，将该目录复制到本机 Skill 目录：

```bash
mkdir -p "${CODEX_HOME:-$HOME/.codex}/skills"
cp -R skills/coloros-glance-extender "${CODEX_HOME:-$HOME/.codex}/skills/"
```

该 Skill 包含模块注入边界、动态卡片目录、UI 主题、ADB 验证和 GitHub Release 流程，不包含任何签名密钥或设备私有数据。


## 免责声明

本项目会 Hook 系统组件，存在系统更新后失效、卡片中心异常或需要重载进程的风险。使用者应自行评估并承担风险；项目作者不对数据丢失、系统故障或兼容性问题负责。

## 作者

- GitHub：[@Jun7z-Max](https://github.com/Jun7z-Max)
