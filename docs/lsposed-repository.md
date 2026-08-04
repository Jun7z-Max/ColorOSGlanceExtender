# 发布到 LSPosed 模块仓库

LSPosed 模块仓库的数据来自 `Xposed-Modules-Repo` GitHub 组织中的独立仓库。模块不是在网页中反复手动上传 APK，而是通过 GitHub Release 自动同步。

## 本模块标识

| 项目 | 值 |
|---|---|
| 个人源码仓库 | `Jun7z-Max/ColorOSGlanceExtender` |
| Android applicationId | `io.github.colorosglance.extender` |
| LSPosed 官方仓库名 | `io.github.colorosglance.extender` |
| 当前 versionCode | `12` |
| 当前 versionName | `0.1.11` |
| 当前 Release tag | `12-0.1.11` |

applicationId 发布后应视为永久标识，不要随意修改，否则 LSPosed 会认为是另一个模块。

个人源码仓库使用易读名称 `ColorOSGlanceExtender`；Android applicationId 和 LSPosed 官方仓库使用稳定标识 `io.github.colorosglance.extender`。两者用途不同，不需要同名。

## 首次提交

1. 登录维护者 GitHub 账号 `Jun7z-Max`；
2. 打开 LSPosed Module Repository 的 Submission 页面；
3. 选择 `Submit a new package`；
4. Package name 填写 `io.github.colorosglance.extender`；
5. Description/Reason 简要说明：

   ```text
   ColorOS 速览标准 Android AppWidget 兼容模块；动态接入当前用户桌面小组件，仅支持非商业用途。
   ```

6. 提交后，机器人会创建：

   ```text
   Xposed-Modules-Repo/io.github.colorosglance.extender
   ```

7. 接受机器人发送的 GitHub 仓库管理员邀请；
8. 将本项目源码推送到该仓库；
9. 设置仓库元数据：
   - Repository name：`io.github.colorosglance.extender`
   - Description：`ColorOS 负一屏扩展`
   - Homepage：`https://github.com/Jun7z-Max/ColorOSGlanceExtender`
   - Collaborators：模块作者
10. 确认根目录包含：
    - `SUMMARY`：仓库首页使用的短描述；
    - `README.md`：模块详情页使用的完整说明；
    - `LICENSE`：PolyForm Noncommercial 1.0.0；
11. 创建首个正式 GitHub Release。

也可以直接在 `Xposed-Modules-Repo/submission` 仓库创建标题为以下内容的 Issue：

```text
[submission] io.github.colorosglance.extender
```

## GitHub Release 规则

LSPosed 模块仓库要求 Release 使用以下结构：

| 字段 | 当前值 |
|---|---|
| Release title | `0.1.11` |
| Release tag | `12-0.1.11` |
| Release body | 本版本变更记录 |
| Asset | 已签名的 `ColorOS-Negative-Screen-Extension-v0.1.11.apk` |

发布后机器人会读取 APK 元数据、Release 和仓库文档。仓库完整时，通常约 5 分钟后出现在模块网站。

不要只替换旧 Release 中的 APK。每次更新都应：

1. 增加 `versionCode`；
2. 更新 `versionName`；
3. 更新 `CHANGELOG.md`；
4. 创建新的 `<versionCode>-<versionName>` tag；
5. 创建新的 Release 并同时上传 APK。

## 个人仓库与官方仓库

本项目采用 `[submission]` 流程：

- 个人源码仓库保持易读名称 `Jun7z-Max/ColorOSGlanceExtender`；
- 官方机器人创建 `Xposed-Modules-Repo/io.github.colorosglance.extender`；
- 接受管理员邀请后，将源码、标签和 Release 同步到官方仓库；
- 不需要把个人仓库改成 Android 包名，也不需要转移个人仓库所有权。

## 发布前检查

```bash
./gradlew clean lintRelease assembleRelease
./scripts/package-apk.sh release --skip-build
```

检查：

- APK 包名为 `io.github.colorosglance.extender`；
- APK 为正式签名，不是 `Android Debug`；
- APK 内包含 `META-INF/xposed/java_init.list`；
- APK 内包含 `META-INF/xposed/module.prop`；
- APK 内包含 `META-INF/xposed/scope.list`；
- Manifest 包含 `android:label` 和 `android:description`；
- Release tag 等于 APK 的 `<versionCode>-<versionName>`；
- Release 中同时上传 APK 与 SHA-256 文件；
- `SUMMARY` 和 `README.md` 已更新；
- `LICENSE` 仍明确禁止商业使用。

## 上架后的页面

成功同步后，模块页面路径形式为：

```text
https://modules.lsposed.org/module/io.github.colorosglance.extender/
```

后续版本只需继续在模块仓库中创建符合规则的新 Release，无需重复提交 package。
