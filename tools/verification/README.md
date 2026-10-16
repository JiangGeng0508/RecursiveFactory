# 项目回归验证

普通 `gradlew build` 不包含这些服务器探针。测试实际方块、跨维度能力、蓝图序列化、建房边界、材料扣除及大炮弹道，不需要启动客户端。另覆盖预览方块与复制入口的物品提示：未绑定、主世界/工厂维度绑定、普通/高级提示，以及文本序列化。

1. 为测试准备一个**全新的**目录，例如 `run/project-audit`，按 Minecraft 服务端要求确认该目录中的 `eula.txt`。建议使用平坦世界，在 `server.properties` 中设置独立端口并关闭结构生成。
2. 运行：

   ```powershell
   .\gradlew.bat -I tools/verification/verify.init.gradle runServer --no-daemon --console=plain
   ```

   更换测试目录可追加 `-PauditRunDir=run/project-audit-next`。探针会在这个测试存档中建造工厂，结束后自动保存关服。查看输出中的 `[rfaudit] COMPLETE`，要求 `failures=0`；Gradle 成功本身不能代替这个断言。

   默认加载构建配置中的 CEE。追加 `-PauditWithoutPower` 可验证不安装 CEE 的运行环境，此时跳过电力节点的大炮测试。两种环境请分别使用新目录，顺序运行。

   入口传输检查覆盖三个开关的八种组合、缓存能力接口、缓冲保留与恢复、红石独立性，以及真实 Create 动力网络的断开和重连。默认值检查使用实际加载的模组列表：未装末影传输时为 `111`，将 NeoForge 1.21.1 的 `createendertransmission` jar 放入测试目录的 `mods/` 后为 `000`。
   若要验证已保存设置优先，在新测试目录的 `config/recursivefactory-common.toml` 预置 `[transfer]` 三个值，并追加 `-PauditExpectedTransfers=101`（依次为应力、流体、物品，按预置值调整）。探针只在内存中切换设置，后续回归按三项全开运行，不保存这些临时选择。

3. 生成正式产物并检查资源、翻译与包内容：

   ```powershell
   .\gradlew.bat build --no-daemon --console=plain
   python tools/verification/check_artifact.py
   ```

   脚本除版本号、翻译与模型引用外，还会核对 jar 内的条目只落在本模组自己的命名空间
   （`assets/recursivefactory/`、`data/recursivefactory/`、`data/minecraft/`）与几个固定元数据文件上，
   防止别的模组的贴图或模型被夹带进正式产物。

红石回归通过实际右击事件切换连接，覆盖扳手不潜行/空手潜行、重复切换、创造/副手/非目标操作、六面双向传输、关闭任一端立即断电和重新开启恢复、实际红石粉连线形状与强度、存档、扩容复制，以及大炮清单与恢复设置不收红石粉、真实建材缺料暂停和保存后续打。红石不依赖 CEE，两种测试环境均执行。

预览持久化回归覆盖存档/F3+I/结构蓝图不含显示缓存、旧缓存加载后丢弃并从真实房间重建、预览变化不标记区块待保存，以及工厂绑定/红石/物品/流体数据保留。初次区块同步和专用预览包检查六类预览数据，另验证嵌套入口与远程展示的同步路径；日志输出含 2048 方块的合成预览在分离前后的 NBT 字符数。

测试未覆盖客户端实际画面、长期运行性能或所有第三方模组组合。
