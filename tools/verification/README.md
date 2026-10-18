# 门户分支回归

执行：

```powershell
python tools/verification/run_audit.py --offline
.\gradlew.bat build --offline --console=plain
python tools/verification/check_artifact.py
```

首次下载依赖时去掉 `--offline`。Python 需要 3.11 或以上版本。
包装器在 `run/immersive-portals/audit-时间戳/` 创建新世界，使用本机回环地址和系统分配的端口，
不读写玩家存档。日志位于 `build/portal-audit-时间戳.log`；测试结束后自动保存并停服。
超时默认 300 秒，可以用 `--timeout 600` 调整。

检查覆盖九槽混合缓存、模拟／执行一致性、旧单槽数据迁移、六面跨维度双向物流、
远端非首槽抽取、阻塞恢复、嵌套和循环库存路由、房间分配／扩容防重叠、世界边界、
预览存档与网络分离、请求限流、地板改动保留和修补、高／矮门户生成／重建与水平扩容。

必须有且仅有一条 `[ip-audit] COMPLETE checks=正数 failures=0`，并且 Gradle 正常退出。
编译失败、断言失败、未完成或超时均返回非零状态。CI 先跑此回归，再执行普通 build
移除测试类，最后检查 jar，防止将测试探针带入正式包。

普通构建不包含 `tools/verification/src`。进存档后的客户端画面、门户穿越体验、
长期性能和完整第三方模组组合仍需实机验证。
