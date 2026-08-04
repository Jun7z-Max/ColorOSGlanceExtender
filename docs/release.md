# 发布流程

## 1. 版本号

正式发布前同时修改：

```kotlin
versionCode = 12
versionName = "0.1.11"
```

规则：

- `versionCode` 必须单调递增；
- `versionName` 使用语义化版本；
- LSPosed Release tag 使用 `<versionCode>-<versionName>`，例如 `12-0.1.11`；
- 同步更新 `CHANGELOG.md`。

## 2. 创建 Release keystore

只需创建一次：

```bash
keytool -genkeypair \
  -keystore coloros-glance-release.jks \
  -alias coloros-glance \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

请自行设置高强度密码。keystore 和密码丢失后无法继续对同一 applicationId 发布可覆盖安装的更新。

禁止把以下内容提交到 Git：

- `*.jks` / `*.keystore`；
- keystore 密码；
- key alias 密码；
- base64 编码后的 keystore；
- GitHub Token。

## 3. 本地签名构建

```bash
export CGE_KEYSTORE_PATH=/absolute/path/coloros-glance-release.jks
export CGE_KEYSTORE_PASSWORD='store password'
export CGE_KEY_ALIAS='coloros-glance'
export CGE_KEY_PASSWORD='key password'

./scripts/package-apk.sh release
```

脚本会：

1. 执行 Release Lint 和构建；
2. 拒绝未签名 APK；
3. 拒绝 Android Debug 证书；
4. 读取 APK 的 package、versionCode 和 versionName；
5. 生成标准文件名；
6. 生成独立 SHA-256 和 `SHA256SUMS`；
7. 输出 LSPosed Release tag。

## Debug 签名迁移

本地调试 APK 使用 Android Debug 证书，正式 Release 使用独立 keystore。两者签名不同，Android 不允许直接覆盖安装。

首次发布正式包时，已安装调试版的测试设备需要：

1. 记住当前 LSPosed 作用域；
2. 卸载调试签名版本；
3. 安装正式签名 APK；
4. 重新启用模块并勾选两个作用域。

正式发布后不得更换 Release keystore，否则所有用户都无法覆盖升级。

## 4. GitHub Secrets

在模块仓库的 Actions Secrets 中添加：

| Secret | 内容 |
|---|---|
| `CGE_KEYSTORE_BASE64` | Release keystore 的 base64 内容 |
| `CGE_KEYSTORE_PASSWORD` | keystore 密码 |
| `CGE_KEY_ALIAS` | key alias |
| `CGE_KEY_PASSWORD` | key 密码 |

生成单行 base64：

```bash
base64 < coloros-glance-release.jks | tr -d '\n'
```

## 5. GitHub Release

确认 CI 通过后创建并推送 LSPosed 格式 tag：

```bash
git tag 12-0.1.11
git push origin 12-0.1.11
```

`.github/workflows/release.yml` 会验证 tag 与 APK 元数据一致，然后创建：

```text
Release title: 0.1.11
Release tag: 12-0.1.11
Asset: ColorOS-Negative-Screen-Extension-v0.1.11.apk
```

执行 `git push` 前必须再次确认版本号、签名证书和变更记录。

## 6. LSPosed 仓库

首次提交和后续同步规则见 [`lsposed-repository.md`](lsposed-repository.md)。
