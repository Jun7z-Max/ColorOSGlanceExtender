# 隐私说明

## 数据访问

ColorOS 负一屏扩展 仅在本机运行。模块会读取：

- UMS 已枚举的 `AppWidgetProviderInfo`；
- 应用包名、组件名、公开应用名称和公开应用图标；
- ColorOS 速览与 UMS 的内存配置状态；
- 用于兼容性诊断的 AppWidget Host 和 resident 状态摘要。

## `QUERY_ALL_PACKAGES`

Android Manifest 声明 `android.permission.QUERY_ALL_PACKAGES`，用途仅限于根据 UMS 返回的包名读取应用公开名称和图标。该权限不会被用于生成已安装应用清单上传、广告画像或遥测。

## 网络与遥测

模块没有声明网络权限，不包含：

- 遥测或统计 SDK；
- 广告 SDK；
- 远程配置；
- 自动更新接口；
- 账号系统或云同步。

## 图标 Provider

导出的 `IconContentProvider` 是 ColorOS 宿主进程读取模块图标的兼容入口。它：

- 只接受 `content://io.github.yunshan.colorosglance.icons/app/<packageName>`；
- 严格限制路径结构和包名字符；
- 只支持读取 PNG；
- 不支持 `insert`、`update` 或 `delete`；
- 只返回 Android `PackageManager` 可公开读取的应用图标；
- 仅使用 4 MiB 进程内缓存，不写入文件。

## 日志

调试日志可能包含包名、组件名、Provider 数量和系统组件状态。公开提交日志前请主动删除：

- 与问题无关的应用包名；
- 账号、手机号、通知内容等个人信息；
- Android ID、序列号、IP 地址和其他设备标识。

## 数据持久化

模块不维护自己的业务数据库，也不会主动将合成 AppWidget 配置写入 UMS 数据库。合成配置只在 UMS 原生重建调用期间存在于内存中，调用结束后清理。
