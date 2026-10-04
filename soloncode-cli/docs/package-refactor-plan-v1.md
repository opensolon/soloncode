# soloncode-cli 包结构优化方案（v1，已执行）

> 2026-10-04 执行完毕。三阶段独立可回滚，全部验证通过。
> 背景：包结构体检发现 4 项问题（2~5），逐项判定后落地本方案。

## P1：依赖倒置收尾（workspace → api.web）

问题：`WorkspaceManager/WorkspaceContext` 反向 import `api.web.WebGate`，残留 `setWebGate/getWebGate` 旧通道。

改动：
- `WorkspaceRuntimePort` 增补方法：`broadcastRaw(WorkspaceContext,String)`、`drainSessionQueue(ctx,AgentSession)`、`interruptSession(ctx,sessionId)`、`onChatInput(...)` 12 参重载。
- `WebGate` 对应 4 方法补 `@Override`（实现体零改动）。
- `AbstractWebController.webGate()` → `runtimePort()`；`BaseSettingsController.webGate()` → `runtimePort()`；均返回 `WorkspaceRuntimePort`。
- `WorkspaceContext.getWebGate()` → `getRuntimePort()`；删除 `WorkspaceManager.setWebGate/getWebGate`。
- `Configurator` 改用 `setRuntimePort(webGate)`。
- 消费方 `ChatWebController/QueueWebController/SessionWebController/MountSettingsController/WebSettingsController` 的 `webGate().xxx` 全部改 `runtimePort().xxx`。

验证：编译通过；`workspace` 包内无任何 `api.web.WebGate` import（grep 为 0）。

## P2：包归位

问题 5：
- `WebSettingsController` 位于 `api.web` 根下，与兄弟 8 个 Settings 控制器（`api.web.settings`）归位不一致；
- `workspace.file.FileService` 与 `workspace.fs.FileWatchService/FilerIgnoreRules` 分裂且互相依赖，共用同一份排除规则。

改动：
- `WebSettingsController` 迁入 `api.web.settings`（package 改名，Configurator import 更新）。
- `workspace.file` + `workspace.fs` 合并为 `workspace.filer`（与既有词汇 `FilerWebController/FilerIgnoreRules` 统一），三类同迁，`FilerIgnoreRulesTest/FileWatchServiceTest` 随迁；反射字符串 `Class.forName("...workspace.file.FileService")` 同步更新为 `filer`。

验证：编译通过；grep `workspace\.(fs|file)\.` 为 0。

## P3：拆出 loop 调度域

问题 2：`command.builtin` 职责过载——调度内核被 6 个顶层包当公共基础设施引用，不是"命令"。

改动：
- 新建 `org.noear.solon.codecli.loop`，迁入 16 类：调度内核（LoopScheduler/LoopTask/LoopExecutionResult/LoopPromptBuilder/AutomationMeta）、Talent 与扩展（LoopTalent/GoalTalent/LoopExtension/GoalExtension/GoalState）、校验器（BuildPassValidator/TestsPassValidator/NoopValidator/ValidatorFactory/GoalValidator）。
- `automation/AutomationManager` 迁入 `loop`，`automation` 包删除（与 loop 调度同域的薄门面）。
- `LoopCommand/GoalCommand` 留守 `command.builtin`（命令壳），补 loop 包 import。
- 全量修正引用方 import（entry/cli、entry/acp、workspace、api/web×8、api/desktop×8、Configurator）；9 个 Loop*/Goal* 测试随迁至 `codecli.loop`。
- 顺带消灭单类包：`memory/MemoryProvider` 并入 `workspace`（唯一消费者是 WorkspaceManager）。

验证：编译通过；grep `command\.builtin\.(LoopScheduler|GoalTalent|LoopTalent|...)` 为 0；`command.builtin` 只剩命令类。

## 回归结果

`mvn test`：967 run，1 failure（`AgentSettingsControllerTest.builtinStillReadableAfterOverrideFileExists`）。
该失败经 `git stash` 在重构前代码上复现，为既有失败（settings 域），与本次重构无关。

## 教训

- 批量 sed 改 package 时，若测试文件头部有 license 注释，`startswith('package')` 判断会漏改/误改（本轮 SteerCommandTest/GoalCommandTest/HelpCommandTest 三处返工），应按 `^package` 行锚定。
- glob `**/*.java` 在部分 shell 下只展开一层目录，跨包批处理必须用 `find -print0 | xargs`。
- Java 同包引用免 import：把类迁出原包后，留守类会集中爆发"找不到符号"，属预期收尾项，不是迁移方向问题。
