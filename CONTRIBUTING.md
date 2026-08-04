# 贡献指南

感谢你帮助改进 ColorOS 负一屏扩展。

## 开发环境

- JDK 17；
- Android SDK Platform 36；
- Android Build Tools 36.0.0；
- 支持 libxposed API 102 的测试环境。

开始前运行：

```bash
./gradlew clean lintDebug assembleDebug
```

## 修改原则

- 保持 Hook 范围最小，优先修复根因；
- 任何异常都必须保持原厂流程可继续执行；
- 不主动写 UMS 数据库，不主动通知 Repository/Subject/Provider；
- 不主动分配、绑定或释放 AppWidget ID；
- 不加入特定应用白名单，通用能力必须基于动态 Provider 信息；
- 新 Hook 点必须记录适用的系统组件版本和 fail-closed 条件；
- 不提交 OEM APK、反编译产物、签名文件或包含个人信息的日志。

## Pull Request 检查

提交前请确认：

```bash
./gradlew clean lintDebug assembleDebug
./scripts/package-apk.sh debug --skip-build
```

PR 描述应包含：

- 修改目的和影响范围；
- 已验证的 Android、ColorOS、速览和 UMS 版本；
- Hook 点变化；
- 数据库和 AppWidget ID 副作用检查；
- 必要的脱敏日志或截图。

提交贡献即表示你同意该贡献按仓库当前的 PolyForm Noncommercial License 1.0.0 提供。
