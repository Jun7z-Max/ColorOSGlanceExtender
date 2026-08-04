# Security Policy

## 支持范围

当前仅维护最新发布版本。旧版本出现安全问题时，修复通常会进入下一版本，不承诺回移。

## 报告安全问题

仓库创建后，请优先使用 GitHub Security Advisories 私密报告。不要在公开 Issue 中提交：

- 可直接导致系统组件崩溃或权限绕过的完整利用细节；
- 未脱敏的设备日志、账号信息或应用清单；
- Release keystore、密码、令牌或其他凭据。

报告请包含受影响版本、系统组件版本、复现步骤、影响评估和最小化日志。作者确认问题前，请勿公开披露。

## 签名安全

Release keystore 不得提交到仓库。GitHub Actions 只通过加密 Secrets 注入签名信息，并在任务结束时删除临时 keystore。
