# 开发与验证（master）

本分支面向 Minecraft 1.21.1、NeoForge 21.1.249、Create 6.0.10 和 Java 21。构建版本取自 `gradle.properties`，不会因构建或提交自动递增。

## 构建与启动

首次运行先联网下载依赖：

```powershell
.\gradlew.bat build --no-daemon --console=plain
.\gradlew.bat runClient --console=plain
```

已有依赖缓存时可使用 `--offline`；`run-client.bat` 默认离线编译并启动。Linux 使用 `./gradlew`。产物在 `build/libs/`，开发客户端目录为 `run/client/`。

CEE 是可选运行依赖，但开发环境默认加载。不要把 Gradle 已声明的 Create、CEE 等 jar 再复制到开发运行目录的 `mods/`。门户分支使用独立运行目录和依赖，不能混用其模组文件夹。

## 自动回归

需要 Python 3.11+：

```powershell
python -m unittest discover -s tools/verification -p test_run_audit.py -v
python tools/verification/run_audit.py --matrix --offline
.\gradlew.bat build --offline --no-daemon --console=plain
python tools/verification/check_artifact.py
```

矩阵顺序运行四个新世界：含 CEE 的构造式回归、含 CEE 的真实 tick 场景、无 CEE 的构造式回归、无 CEE 的真实 tick 场景。构造式探针包含不适合持续运行的临时房间布局；不要把它们与运行时场景放进同一存档。

测试世界在 `run/verification/`，完整日志在 `build/verification/`，不使用玩家存档。`--runtime` 单独运行真实 tick 场景，`--without-power` 选择无 CEE 环境，`--timeout` 设置每轮超时。

成功必须同时满足进程正常退出、唯一完成标记、正检查数和零失败。打印了完成标记但保存退出卡住仍然失败。超时只终止本次测试启动的进程树。

探针只通过 verification init 脚本加入编译；回归后必须重新正常构建并检查产物，防止测试类进入发布包。CI 运行相同流程并保留回归日志。更多覆盖细节见 [验证说明](../tools/verification/README.md)。

## 预览架构与性能

`preview` 包分别负责快照、采样、显示字段、预算、增量同步和递归保护。入口保留旧采样接口的委托方法，物流、红石和动力不依赖显示快照。

观看需求以来源入口为单位合并，远程展示共享来源快照；嵌套需求最多传播八层。无人观看时停止采样。实体、机器和物理结构的动态采样与静态方块扫描分开，超额任务按等待时间公平排队，距离用于同等等待时间下的排序。

服务端 `preview.*` 默认设置：

| 距离 | 静态扫描间隔 | 动态采样间隔 |
|---|---:|---:|
| ≤32 格 | 10 tick | 1 tick |
| 32～80 格 | 20 tick | 4 tick |
| 80～160 格 | 40 tick | 10 tick |

每 tick 静态扫描最多四个房间格，动态采样最多 32 个房间格；首次静态扫描也占用动态预算。配置可调整。客户端按实际采样间隔插值；纯动态更新不重新发送未变化的静态几何。

显示数据使用客户端更新字段；普通容器库存和战利品信息不下发，传送带等可见物品保留。扩展第三方渲染兼容时，在显示数据适配器中明确需要的字段，不回退完整存档 NBT。

预览协议为版本 2，客户端和服务端需安装同一构建；模组版本号保持用户指定值并不代表旧构建的网络协议相同。完整快照建立基线，后续结构化 NBT 增量带基线版本；不匹配时重新请求完整快照。显示缓存仍不进入世界存档、F3+I 或蓝图。

自动性能检查使用 1／16／64 来源的调度回放和合成载荷，输出扫描数、采样数、队列耗时及 NBT 文本长度。这些数据用于检查预算和增量效果，不能代替实际服务器 TPS、网络字节数或客户端 FPS 测量。

## 游戏内反馈与素材

工程师护目镜按指向面显示对应墙面、红石连接、服务端传输设置，以及本端缓冲数量和最近转发结果。诊断请求限制在附近已加载入口，每名玩家最多每十 tick 一次；不查询远端完整库存。

入口提供三个原创 Ponder 教程：内外连接、扩容与嵌套、蓝图大炮打印。场景结构由 `python tools/gen_ponder_scenes.py` 生成，文字维护在中英文语言文件中。不要复制其他模组的场景或美术资源。

客户端验收清单：近处／远处实体插值，传送带物品、储罐液面、保险库连接材质，嵌套与远程展示更新，护目镜切换指向面，三个教程的布局和文案，CEE／Sable／Simulated 组合。无头回归只能验证数据与逻辑，不能证明这些画面正确。

## 发布与维护

构建后检查 jar 版本、资源引用、翻译参数、命名空间和测试类隔离。只有用户要求时修改版本号；本地约定为验证后提交，不自动推送。

`.agents/` 是本机忽略目录，记录当前任务和历史排查，不随克隆分发。新克隆以本说明、README 和源码为准。更新功能时同时维护公开文档以及本机 roadmap、git-status、known-issues；历史分支结论不能直接套用到当前分支。
