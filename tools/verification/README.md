# 项目回归验证

普通 `gradlew build` 不包含这些服务器探针。测试实际方块、跨维度能力、蓝图序列化、建房边界、材料扣除及大炮弹道，不需要启动客户端。另覆盖预览方块与复制入口的物品提示：未绑定、主世界/工厂维度绑定、普通/高级提示，以及文本序列化。

1. 为测试准备一个**全新的**目录，例如 `run/project-audit`，按 Minecraft 服务端要求确认该目录中的 `eula.txt`。建议使用平坦世界，在 `server.properties` 中设置独立端口并关闭结构生成。
2. 运行：

   ```powershell
   .\gradlew.bat -I tools/verification/verify.init.gradle runServer --no-daemon --console=plain
   ```

   更换测试目录可追加 `-PauditRunDir=run/project-audit-next`。探针会在这个测试存档中建造工厂，结束后自动保存关服。查看输出中的 `[rfaudit] COMPLETE`，要求 `failures=0`；Gradle 成功本身不能代替这个断言。

   默认加载构建配置中的 CEE。追加 `-PauditWithoutPower` 可验证不安装 CEE 的运行环境，此时跳过电力节点的大炮测试。两种环境请分别使用新目录，顺序运行。

3. 生成正式产物并检查资源、翻译与包内容：

   ```powershell
   .\gradlew.bat build --no-daemon --console=plain
   python tools/verification/check_artifact.py
   ```

   脚本除版本号、翻译与模型引用外，还会核对 jar 内的条目只落在本模组自己的命名空间
   （`assets/recursivefactory/`、`data/recursivefactory/`、`data/minecraft/`）与几个固定元数据文件上，
   防止别的模组的贴图或模型被夹带进正式产物。

测试未覆盖客户端实际画面、长期运行性能或所有第三方模组组合。
