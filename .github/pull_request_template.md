## 修改内容

<!-- 简述目的和实现 -->

## 验证环境

- Android / ColorOS：
- 速览版本：
- 智慧建议服务版本：
- LSPosed 实现与版本：

## 安全检查

- [ ] 未写入 UMS 数据库
- [ ] 未主动通知 Repository / Subject / Provider
- [ ] 未主动分配、绑定或释放 AppWidget ID
- [ ] 未加入特定应用白名单
- [ ] 异常路径保持 fail-closed
- [ ] 日志和截图已脱敏

## 构建检查

- [ ] `./gradlew clean lintDebug assembleDebug`
- [ ] `./scripts/package-apk.sh debug --skip-build`
