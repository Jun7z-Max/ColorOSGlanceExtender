# Changelog

本项目遵循语义化版本号。日期使用 `YYYY-MM-DD`。

## [Unreleased]

暂无。

## [0.1.11] - 2026-08-04

### Added

- 将当前用户标准桌面 AppWidget 动态接入 ColorOS 负一屏卡片中心；
- 为新增 AppWidget 分组动态读取应用名称；
- 名称读取失败时回退到包名，保证标题不为空；
- 增加只读图标 `ContentProvider`，按包名动态输出应用公开图标；
- 增加 GitHub CI、正式签名、SHA-256 和 LSPosed Release 自动化；
- 增加 `SUMMARY`、隐私说明、安全策略、贡献指南和 LSPosed 上架文档。

### Changed

- 正式名称统一为“ColorOS 负一屏扩展”；
- libxposed API 改为官方 Maven `compileOnly` 依赖；
- Xposed 最低和目标 API 统一为 102；
- 项目许可证改为 PolyForm Noncommercial 1.0.0；
- Release tag 统一为 `<versionCode>-<versionName>`。

### Fixed

- 修复新增 AppWidget 分组不显示应用图标的问题；
- 修复部分新增应用分组在深色背景下名称为空或不可见的问题；
- 保持合成配置仅存在于 UMS 重建内存阶段，不写入配置数据库；
- 保持未点击添加前不产生新的 AppWidget ID、绑定或 Host。
