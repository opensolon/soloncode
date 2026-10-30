
### vNEXT

* `CliShell` 输入链路收敛与三处行为回归修复（对照 `1f8a9b52` 逐条核对）：
  * **队列消费归属收敛（修复回归）**：改造后 `safeChatInput` → `runMessageInput` → `dispatchOutcome` 的 TASK 分支也执行 `drainCliQueue`，而改造前 `safeChatInput → performAgentTask` 不排水。loop 执行器运行在调度线程（`LoopScheduler` 同步回调 `ExecutionBridge.execute`），此时主线程仍阻塞于 `readLine`，会让队列项在调度线程上执行并与主线程争用同一 JLine reader。现 `dispatchOutcome` 增加 `allowDrain`：仅用户手输 / 单次调用为 true，队列项与 loop 注入为 false；主循环回到提示符前统一消费积压（`while` 顶部），主线程被 `terminal.raise(INT)` 唤醒后即排水，积压不会滞留
  * **HITL 审批空结果补可见提示**：`handleHitlRound` 返回 null（无挂起任务 / 续流未生成请求）原为静默 `continue`，随后因无请求直接返回，用户从“等待审批”无交代地回到提示符。现补一行 DIM 提示并显式收尾
  * **等待符 `... ` 消除竞态**：原判据 `!isFirstRound && !isTaskCompleted.get()` 依赖 `doFinally`（`boundedElastic`）与 `waitForTask` 抢跑 `countDown` 的先后，同一动作有时显示有时不显示。`isTaskCompleted` 实为“流是否终结”的异步镜像，而 `waitForTask` 返回后该信息已无用，故删除该标志与等待符打印（与改造前实际观感一致：老代码因 `isTaskCompleted` 不再复位而从不打印），收尾改为“未中断且无挂起审批即返回”
  * **结构整理**：`handleInput(session, input)` / `handleInput(..., 4参)` / `runMessageInput` 三个方法合并为 `handleInput(session, input)`（用户手输，识别命令、带终端能力、排水）与 `handleMessageInput(session, text, model)`（队列项 / loop 注入，不识别命令、不排水、不装配 terminal/factory）。`runMessageInput` 更名为 `handleMessageInput`
  * 注释订正：`addTaskExecutor` 处“由主线程执行 agent 任务”改为“在调度线程执行，主线程此刻仍阻塞于 readLine，两者共用同一 reader”；`handleHitlRound` 残留决策处“如 Web 端预置”改为“上一轮决策残留（CLI 会话 ID 固定为 cli，不与 Web/IM 共用）”
  * 验证：`soloncode-cli` 全量 1204 例 3 失败，其中 `ImageReplyLifecycleTest.abnormalRunEnd…`、`AgentSettingsControllerTest.builtinStillReadable…` 为既有基线；`SkillhubMarketTest.testInstallRealSlug` 为联网类（依赖 slug 缓存，离线环境 surefire 记 0 例执行）

* 跟进 `solon-ai-harness` 的 `HITLAction` 枚举化（决策动作 String → 枚举，转换点在宿主侧）：
  * `CliShell.handleHitlRound`：y/n 交互与外部预置决策（`HITLDecision`）均改写 `HITLAction.APPROVE/REJECT`；**顺带修复预置决策 bug**——原 `existing.isRejected() ? "reject" : "approve"` 漏了 `isSkipped()`，外部预置的 skip 会被升格为 approve 重新提交覆盖原决策，现改三态透传
  * `WebChatInputHandler`：`.hitl(HITLAction.of(hitlAction), hitlCallId)`——协议字符串在交给引擎前解析，脏值由既有 try-catch 显式报错（此前会静默按拒绝执行）
  * `DesktopInputRouter.handleHitlAction`：同模式 `.hitl(HITLAction.of(action), callId)`，脏值异常由既有 catch 回 socket 错误
  * 协议层签名（`WorkspaceRuntimePort.onChatInput` / `WebGate`）保持 String 不动：解析点统一在交给引擎前的最后一站，每端一个转换点
  * 验证：`soloncode-cli` 全量 1204 例 2 失败与基线完全相同（`ImageReplyLifecycleTest`、`AgentSettingsControllerTest`）

* 跟进 `solon-ai-harness` 的 `HarnessInput` 命名收敛（四端同步，仅调用处改名，无行为变更）：
  * `.model(x)` → `.selectedModel(x)`、`.agent(x)` → `.selectedAgent(x)`、`.mentionAware(b)` → `.agentAware(b)`：涉及 `CliShell`、`PrintMode`、`AcpLink`、`GitService`、`WebChatInputHandler`、`WsGate`、`DesktopInputRouter`
  * `engine.handle(input, factory)` 两参形式取消，改为 `HarnessInput.commandContextFactory(x)` + `handle(input)`：CLI 的 `cliCommandFactory()`、Web 的 `webCommandFactory()`、桌面的 `desktopCommandFactory()` 三处从第二参搬进链式调用；`WsGate.runGoalRoundAndCapture` 删掉尾部 `, null`
  * `ExitCommand` / `LoopCommand` 补 `execute(engine, ctx, InputOrigin)` 三参覆写（原先只实现两参，接口瘦身后已无两参抽象，编译期直接暴露）
  * `WebChatInputHandler` 忙态插话的 `SteerInterceptor.steer(...)` 由四元组改为 `InputOrigin.of(...)`（与 `enqueue` 口径对齐）
  * 验证：`soloncode-cli` 全量 1204 例仅 2 失败，均为既有基线（`ImageReplyLifecycleTest` 异常文案、`AgentSettingsControllerTest` 返回 400）

* 修复宿主迁移到 `engine.handle` 后的四处回归（审查发现，测试未覆盖）：
  * **ACP 会话丢失（严重）**：`AcpLink.toInput` 曾返回 `HarnessInput.of(null, text)`，而 `handle` 只认 `input.getSession()`，传 null 会让 `ReActRequest.init()` 回落临时 `InMemoryAgentSession` —— ACP 每轮都是新会话，多轮上下文全丢、快照不落盘。现改为先 `getSession(sessionId)` 再构造 input（`toInput` 增 session 参数），`AcpLinkTest.toInput_bindsSession` 断言 `input.getSession()` 非空
  * **Web HITL 续流代理错源（高危）**：续流曾用 `hitlOutcome.getAgentName()`，而审批请求不带 selectedAgent，会回落主代理 → trace 取不到子代理挂起的 tool_calls，续跑空转。现由引擎侧「显式优先 + 会话级 `CTX_AGENT_SELECTED` 兜底」解析，Web 侧改读本轮生效代理（`WebStreamBuilder.ATTR_EFFECTIVE_AGENT`，与 `WebEventMapper` 同源）
  * **Goal 轮空指针**：`WsGate.runGoalRoundAndCapture` 对 `outcome.getPrompt()` 未判空（原 `Prompt.of(input)` 永不 null，改为 `handle` 后空 objective 会返回 `IGNORED`），补 `prompt == null || !hasRequest()` 短路
  * **桌面命令任务回执越权（行为扩张）**：迁移后 `TASK + isCommand` 分支新增了回执发送（原实现 `if (!ctx.isAgentTask())` 才发），且直接 `socket.send` 绕过 `DesktopStreamHub` 的序号与回放缓冲，并与随后开流形成「回执先于流门复位」的脆弱时序（Web 端曾踩同一坑）。现恢复原语义：命令触发任务不发独立回执，收尾交由任务流
* 修复「过期审批卡触发重跑」回归（本轮审查发现，原实现无此路径）：
  * 桌面 `handleHitlAction` 原靠 `hitlOutcome.getKind() != HITL` 判「无挂起任务」，但 `handle` 对无挂起的审批**同样返回 HITL**（决策写入是空操作），该判据恒为假 → 过期审批卡继续走 resume，因正文为空回落 `trace.getOriginalPrompt()`，把上一轮问题原样重跑一遍。现改为在 `handle` 之前用 `HITL.getPendingTaskByCallUuid/getPendingTask` 判定（与迁移前 `task == null → 报错返回` 完全一致）
  * Web 端同源问题：`allHitlDecided(session)` 在「本来就无挂起」时返回 true（空列表即全已决策）→ 同样会 resume 重跑。现先取 `HITL.getPendingTasks(session)` 快照，要求 `hadPending && allHitlDecided(session)` 才续流
* 行为变更（已确认接受）：桌面端「斜杠命令 + 附件」不再当命令执行，改为普通消息
  * 原实现命令识别（`currentInput.startsWith("/")`）在附件处理之前，带附件的 `/cmd` 仍走命令分发，**附件被静默丢弃**（既不落盘也不提示）
  * 现由引擎统一识别，前置条件含 `!attachments.hasAny()`，与 Web 端既有口径一致（Web 原实现 `startsWith("/") && imageBlocks.isEmpty()`，且 `withFilePrefix` 会把 `/` 挤到第二行，带文件附件的命令本就不会命中）。附件正常落盘并作为多模态输入
  * 代价：桌面端若确需「命令 + 附件」，需先发命令再单独发附件；此为三端口径统一的结果，不作为回归
* 修复 headless（`PrintMode`）迁移后的提及解析漂移：原实现直接取 `getMainAgent()`，不解析输入里的 `@子代理`（子代理走 `--agent` 参数由调用方拼接），迁移后 `HarnessInput` 默认 `mentionAware=true` 会改变行为。现补 `.mentionAware(false)`，与 `commandAware(false)` 同理（文本非用户手输语义）
* 清理：`InputAttachments.isEmpty()` 与 `hasAny()` 二义合并（随 harness 同名变更）、`AcpLink` / `WebStreamBuilder` / `DesktopInputRouter` / `DesktopStreamTurn` / `PrintMode` / `WebChatInputHandler` 中迁移后失效的 import（`ReActAgent`、`Contents`、`TextBlock`、`UserMessage`、`ArrayList`、`Prompt`、`Collectors` 等）、`WebStreamBuilder` 硬编码 attr 名提为 `ATTR_EFFECTIVE_AGENT` 常量
* 测试：`AcpLinkTest` +1（会话绑定）、harness `HitlAgentResolutionTest` +3（HITL 代理来源）、`WebAttachmentStoreTest` 断言跟随 `hasAny()`
* 验证：cli 全量 1204 例 2 失败（`ImageReplyLifecycleTest.abnormalRunEnd…`、`AgentSettingsControllerTest.builtinStillReadable…`，均为既有基线）；harness 全量 364 例 20F+8E 与基线逐项一致

* soloncode-cli 宿主不再直接调用 `agent.prompt` / `engine.prompt` 发起请求，一律 `engine.handle(HarnessInput)` 装配（`HostPromptBoundaryTest` 守卫，白名单仅 ChatModel 连通性探测两处）
  * 桌面：`DesktopStreamTurn.run` 改收 `ReActRequest`（请求主体由 handle 装配，方法内只补请求级 options：模型/协作提示/运行模式/cwd/effort）；HITL 续跑由手工空 Prompt（`Prompt.of().attrPut("start_time")`）改为 `HarnessInput.of(session, null).resume(true)`，与 CLI 同口径（`start_time` 本就读首轮 trace，实测值不变）
  * Web：消除双重装配——原 `handle()` 装配后丢弃 request、`WebStreamBuilder` 再用 `engine.prompt(prompt, agent)` 重装；现 `InputOutcome` 全程直传，`buildStreamFlux` 改收 `outcome`，只补推理强度/steer/图片拦截器/cwd；`performAgentTaskAsync/Sync` 签名从 `(prompt, model, agentName)` 缩为 `outcome`，模型解析下沉 handle 自动处理
  * Web HITL 续跑：原传 null prompt → 空提示词回落续流，改为显式 `.resume(true)`；历史前端以文本 `/resume` 触发续流的口径在入口处前移转换（等价原 buildStreamFlux 里的 /resume 剥离）
  * ACP：`toPrompt`（手工 Contents 多块拼接）改为 `toInput`（文本与图片分流：文本/[path] 内联拼正文，图片走 `InputAttachments.media`）；`agentRuntime.prompt(prompt).session(...)` 改为 `handle(toInput(...).commandAware(false).mentionAware(false))` + `outcome.getRequest().options(cwd)`，行为差异（retry/maxTurns/sessionWindowSize 引擎级生效、纯图片补默认文本、图片后置不交错）已确认接受
  * 测试：`AcpLinkTest` 21 例 toPrompt 适配为 toInput；`ImageReplyLifecycleTest` fixture 增加 `outcome(ReActRequest)` 辅助并复用同一 mock request（测试体对 `request.stream()` 的打桩必须作用于 buildStream 实际使用的实例）；新增 `HostPromptBoundaryTest`（正则拦截 agent/engine 句柄的 prompt 调用）
  * 验证：cli 全量 1181 例（offline）2 失败均为既有基线（`ImageReplyLifecycleTest.abnormalRunEnd…`、`AgentSettingsControllerTest.builtinStillReadable…`）；残留扫描 `engine.prompt/agentRuntime.prompt/targetAgent.prompt` 生产代码零命中

* 附件落盘下沉到宿主，harness `input` 包不再触碰文件系统（`InputAttachment` 删除）
  * 根因：引擎侧 `InputAttachments.save()` 实际只服务 Web 一个宿主——CLI / headless / Git / IM 渠道均不带附件，桌面端本就自己落盘——却把 `.uploads` 目录语义、路径穿越校验与三张 mime 表带进了通用库
  * `harness.input.InputAttachment` **删除**：它是「文件名 + 字节 + 声明类型」的落盘前载荷，与「已装配结果」混用同一个 `Attachment` 词根，反而掩盖了真正的分工
  * `InputAttachments` 改为纯装配器：`of()` + `media(MediaBlock)` + `reference(path)`；字段收敛为 `List<MediaBlock>` + `List<String>`，`mediaTypes` 冗余消除（类型改由块的实际类型推断），`buildAttachmentMeta` 的越界回落随之消失
  * 路径归属到块：`MediaBlock` 新增 `getFilePath()`（`default` 返回 null，`4.2.0`，不破坏 3.9.2 以来的外部实现），`AbsMedia` 以字段覆盖并提供链式 `filePath(...)`。本地副本路径本就是块自身的属性（同一张图落盘了就有、url 形态就没有），因此取消 `MediaItem` 包装——`List<MediaItem>` → `List<MediaBlock>`，`item.getBlock()`/`item.getPath()`/`item.getType()` 分别退化为块自身、`block.getFilePath()` 与 `instanceof` 推断。`filePath` 仅为历史标签元数据，不参与任何 dialect 的请求载荷（`MediaBlockFilePathTest` 锁定往返与请求隔离）
  * `fileAttachments` → `FileReference` → `String`：它本就是工作区相对路径、以 `[附件: path]` 注入提示词，是「引用」而非「附件」；去掉值类型后，原有的拒绝 `..` 与绝对路径校验搬到 `InputAttachments.reference(String)`（该类是往提示词注入文本的唯一通道，`fileReferenceRejectsPathTraversal` 锁死）
  * `HarnessInput`：`attachments`（字节列表）与 `preAttached`（宿主已装配结果）两个近义字段合并为单个 `attachments(InputAttachments)`，删除 `attach/attachAll/attachmentRoot/getAttachmentRoot/preAttached/getPreAttached`
  * `HarnessEngine.handle`：删除附件装配分支（不再判断 `preAttached`、不再调用 `save`），判空与命令短路统一改用 `attachments.isEmpty()/hasAny()`
  * Web：新增 `WebAttachmentStore`（落盘 + `.uploads` 路径穿越校验 + 「声明类型 × 扩展名」双校验 + 图片 mime），承接重构时被并入引擎的 `WebAttachments.saveAttachments`；`WebChatInputHandler` 只留一行转换
  * 桌面：`DesktopInputRouter` 三个平行 list（`imageBlocks`/`savedImageNames`/`fileNames`）与 `ofExisting(...)` 换为 builder，`savedImageNames` 的同序对齐负担消失；已落盘图片改为 `ImageBlock.ofBase64(...).filePath(savedName)`
  * 护栏：`InputBoundaryTest.inputPackageMustNotTouchFileSystem` 扫描 `java.nio.file`/`Files.`/`Paths.`/`java.io.File`，并拦截 `InputAttachment`、`MediaItem`、`FileReference` 三类已取消包装的回潮，锁定「落盘在宿主 + 路径属于块」两个决策
  * 测试：新增 `WebAttachmentStoreTest` 8 例（承接原 `InputAttachmentsTest` 的双校验与路径穿越两条口径，防重构静默丢失）；`InputAttachmentsTest` 保留 16 例（url 形态、`filePath` 为空时元数据跳过、引用拒绝路径穿越）；solon-ai 新增 `MediaBlockFilePathTest` 2 例（JSON 往返保留 `filePath`、请求载荷不含 `filePath`）
  * 验证：`harness.input` 75/75；harness 全量 365 例 20F+8E 与基线逐项一致；cli 全量 1202 例仅 2 失败（`ImageReplyLifecycleTest.abnormalRunEnd…`、`AgentSettingsControllerTest.builtinStillReadable…`，均为既有基线）

* 统一 soloncode CLI / Web / 桌面三端「命令触发 Agent 任务」链路：命令上下文不再持有宿主回调（原 `CliCommandContext`/`WebCommandContext` 各定义一份 `AgentTaskRunner`，并把 sessionCwd/selectedModel/agentName/socket 等端内变量闭包捕获进工厂），改为命令只声明意图、引擎二段装配、宿主按 `kind` 统一消费
  * `CliCommandContext`：删 `AgentTaskRunner` 与构造参，只保留 Terminal/Reader 与意图记录；`cliCommandFactory()` 不再需要 session 参数与 `runMessageInput` 递归闭包
  * `WebCommandContext`：改为继承 harness `BufferedCommandContext`，自身只补 source/sourceUserId/replyTarget/messageId 四个渠道元数据；`webCommandFactory(...)` 从 9 参缩为 4 参
  * `DesktopInputRouter`：`desktopCommandFactory(...)` 从 6 参缩为空参，删 `runFallbackPrompt` 辅助方法；命令回执改为「先发 command 事件、`done` 交给任务流」（此前命令触发任务时直接 return，前端连回执都收不到）
  * `WebChatInputHandler.handleCommandTurn`：新增 命令触发任务分支——回执照发但**不抢发 done**，并把原先藏在命令回调里的忙态防重入（`SessionActivity.hasActiveStream`）前移到开流之前；普通消息与命令触发任务统一用引擎生效的 `outcome.getModelName()/getAgentName()`
    * 该分支内必须先 `beginStreamTurn`（复位前端流门）再发回执：上一轮 done 后前端已置 `_streamClosed` 并丢弃后续 chunk，命令事件不带 runId 用不上自愈判据，顺序颠倒会让回执静默消失
    * 回执的 IM 转发必须按「非终态」发送（`replyToBoundChannel(..., false)`）：`isFinal=true` 会封闭 `ATTR_IM_TERMINAL_SENT` 去重门，随后任务流的最终答复被该门吞掉，IM 端只看得到命令回执
  * `CliShell.handleInput`：`COMMAND_AGENT` 分支由「什么都不做」改为执行 `performAgentTask(session, turn)`（命令声明的任务此前在 CLI 侧根本不跑）
* 修复三端 `/continue` 静默失效（命令做了 trace 手术但任务不启动）、`/continue <sessionId>`、`/rerun <sessionId>` 手术与开流落在不同会话
* 适配 harness 命名定稿：`InputResult` → `InputOutcome`、`engine.input(...)` → `engine.handle(...)`（4.2 未发布，无兼容影响）
  * 三端局部变量统一 `turn` → `outcome`（含 `hitlTurn` → `hitlOutcome`）：`turn` 在本项目专指「一次 agent 执行轮次」（`maxTurns`、`TurnOutcome`、`turnsExecuted`），而 `REPLY`/`IGNORED` 根本不是轮次
  * `CliShell` / `WebChatInputHandler` / `WsGate` / `DesktopInputRouter` / `PrintMode` / `GitService` 共 12 处 `engine.input` 调用改为 `engine.handle`
  * 同步 `docs/HARNESS_INPUT_API.md`：API 签名、`Kind` 与 `stream()` 映射表（`MESSAGE`/`COMMAND`/`COMMAND_AGENT`/`CLI_ONLY_BLOCKED` 六值 → `TASK`/`REPLY`/`HITL`/`IGNORED` 四值）、三端消费表格、流示例

* 适配 harness `InputOutcome.Kind` 收敛为 4 值（`TASK`/`REPLY`/`HITL`/`IGNORED`），三端宿主统一为「一值一动作」的单层 switch：
  * `CliShell.handleInput`：`COMMAND` + `COMMAND_AGENT` → `TASK` + `REPLY`；命令触发的任务与普通消息共用 `TASK` 分支（CLI 侧命令回执由命令自己即时写终端，不需要再渲染一遍）。`runMessageInput` 的 kind 判定同步改为 `TASK`
  * `WebChatInputHandler`：删除 `isCommand()` 外层派发 + `hasRequest()` 二次判断，改为 `switch(turn.getKind())`。原 `handleCommandTurn` 拆为 `startCommandTaskTurn`（命令触发任务：忙态防重入 → `beginStreamTurn` → 非终态回执 → 开流，不发 done）与 `startReplyTurn`（原 `CLI_ONLY_BLOCKED` 与 `COMMAND` 两块合并，二者动作本就相同）
  * `DesktopInputRouter`：删除 `kind != COMMAND_AGENT` 的穿透写法，改为 `switch(turn.getKind())`；`handleCommandResult` 拆为 `handleCommandReceipt`（只发 command/rewind 事件，不发 done）与 `sendDone`
* 行为变更：Web/IM 下执行 CLI 专属命令（如 `/exit`）时，不可用提示现在也会经 `replyToBoundChannel` 转发到 IM（此前仅 Web 前端可见）
* 调整 soloncode `SteerCommandTest`：断言从「回调未被调用」改为「未声明任务意图」（`getAgentTaskIntent()` 为空），并适配 `WebCommandContext` 新构造器

* 扩展 soloncode harness `InputAttachments` 至多媒体：`imageBlocks` → `mediaBlocks`（`List<MediaBlock>`），支持 `AudioBlock`/`VideoBlock`。此前只认图片，mp3/mp4 即使上传也只能落盘成 `[附件: path]` 文本前缀，模型无法直接感知。现按 `declaredType`（image/audio/video）与扩展名双重匹配转块（二者缺一仍按文件处理，与既有口径一致，`imageDeclaredTypeRequiredForImageBlock` 语义未变）；`hasImages()` → `hasMedia()`，另增 `isAllImages()` 供「空文本时的默认提示」区分图片与其他媒体。`ofExisting` 入参放宽为 `List<? extends MediaBlock>`，宿主传 `List<ImageBlock>` 无需改动，多媒体类型由块的实际类型推断
* 优化 soloncode harness `buildAttachmentMeta`：`type` 字段不再写死 `"image"`，按实际媒体类型输出 image/audio/video（前端 `att.type === 'image'` 分支不受影响，非 image 落入文件标签分支）
* 添加 soloncode harness InputAttachmentsTest 5 例：音频转 `AudioBlock`、视频转 `VideoBlock`、声明为 file 的 .mp3 按文件处理、图片+音频混合时 `isAllImages()` 为 false 且元数据按实际类型输出、纯图片时 `isAllImages()` 为 true（共 15 例）
* 补充 soloncode harness `InputAttachment` javadoc：说明该类只是字节载体、不预设内容形态，以及 image/audio/video 转块与其余类型落盘随路径的两条通道

* 简化 soloncode harness：`HarnessEngine` 的两个 4 参 `prompt(...)` 重载（`Prompt` 版与 `String` 版）从 public 降为 private `promptDo(AgentSession, Prompt, String, String)`，对外开放的输入入口收敛为**只有 `handle(...)`**；`handle()` 末行改为直调 `promptDo`，不再依赖自身的公开 API
* 添加 soloncode harness `HarnessInput.resume(boolean)`：续流模式（无新输入，按会话挂起的 `tool_calls` 继续执行，文本与附件均被忽略）。此前 `input(session, null)` 恒返回 `IGNORED`，HITL 决策后的续轮无法用 `input` 表达，只能绕过入口调 `engine.prompt(...)` —— 这是“`input` 作为唯一入口”的最后一个缺口
* 添加 soloncode harness `HarnessInput.mentionAware(boolean)` 与 `AgentMentions.Options.mentionAware`（默认 true）：置 false 时原文原样保留，子代理只取显式指定值与选择器兜底值。用于「文本由程序生成」的路径（如桌面 goal objective）：即使以 `@xxx` 开头也作为普通提示词交给代理自己识别，不被当作提及剥离
* 收编 soloncode CLI 三处残余的 `engine.prompt(...)` 至 `engine.handle(...)`：`CliShell` HITL 续轮改用 `.resume(true)`（模型/子代理沿用首轮生效值）、`WsGate.runGoalRoundAndCapture` 改用 `.commandAware(false).mentionAware(false)` 且 `start_time`/reasoning 改为后置 `attrPut` 到 `turn.getPrompt()`、`GitService` git-summary 子代理改用 `.model(...).agent("git-summary")` + `.getRequest()`。**至此 CLI 全部输入路径只经 `input`**
* 添加 soloncode harness InputStreamTest 3 例：`resume(true)` 无文本也拿到请求且 `getPrompt()` 为 null、resume 模式下文本被忽略、`mentionAware(false)` 时不剥离 `@xxx` 且 `getAgentName()` 为 null（共 8 例）
* 同步更新 `docs/HARNESS_INPUT_API.md`（三处续流写法）与 `InputOutcome`/`InputBoundaryTest` 中对已删 API 的指引
* 重命名 soloncode harness `InputTurn` → `InputOutcome`（当时先定为 `InputResult`，后按「与 `RunOutcome`/`TurnOutcome`/`PollOutcome` 同族」定稿为 `InputOutcome`）：原名 `Turn` 在本仓库专指轮次（`maxTurns`/`getTurnCount`），而该类承载的是一次输入的解析与装配结果、本身不是一轮。改后与 `runtime/RoundResult` 语汇对称（都表示「一次处理的客观产物」，完成与否的解释权在使用方）；`Kind` 枚举、工厂方法与 getter 均未变动，属纯改名

* 收编 soloncode Web 主聊天链路至 `engine.handle(...)`：`WebChatInputHandler.onChatInput` 原自己重做一遍引擎的全部能力（附件落盘、`[附件:]` 前缀、`Contents`/`TextBlock`/`ImageBlock` 拼装、`metadata("source")`、子代理元数据、请求装配），现改为一次 `handle()` 调用；命令回执渲染逻辑抽为 `handleCommandTurn`（保留 `reset…done` 信封、rewind 特判、忙态旁路），命令上下文工厂抽为 `webCommandFactory`（保留 `runnableWhenBusy` 防重入）。**目标达成状态：Web 不再有第二条装配路径**
* 删除 soloncode `WebAttachments`（185 行）：其约 110 行是 harness `InputAttachments` 的逐字副本（`.uploads` 落盘与 `..` 校验、`withFilePrefix`、`buildAttachmentMeta`、`extensionToMime`、`isImageAttachment` 各一份）。Web 现仅保留 `toInputAttachments(UploadedFile[], String[])` 一行类型转换。迁移前已核对 `metadata("source")` 的 null 语义（旧端无条件写、引擎仅非 null 写，`SessionWebController.buildMessageItem` 用 `metaString` 后判非空，产出一致）
* 添加 soloncode harness `HarnessInput.hitlOutput(String)` 与引擎侧改写通道：非空时作为决策 comment 写入（`HITL.approve/reject(session, task, comment)`），由 `HITLInterceptor` 以 `(Note: ...)` 覆写工具返回。桌面端「批准并改写工具结果」原先只能自己调三个重载，现在可声明式表达，因此桌面 HITL 得以迁入 `handle()`
* 收编 soloncode Web / 桌面 HITL 至 `engine.handle(HarnessInput.of(session, null).hitl(...))`：命中任务判定与「批准/拒绝并改写」全部下沉引擎；Web 的 `allHitlDecided` 批量恢复判定与桌面的 `desktopHitlInterceptor` 重挂**刻意留在宿主**（引擎不替任何一端决定续流时机）
* 修复 soloncode harness `handle()` 的 HITL 顺序缺陷：HITL 分支原排在「空输入判空」之后，而审批请求通常不带文本，决策会被静默丢弃返回 `IGNORED`（真机表现：点了批准毫无反应）。现 HITL 为第一步、判空为第二步
* 收编 soloncode headless（`PrintMode.runAgent`）至 `engine.handle(..., commandAware(false)).getRequest()`：装配统一走 input，`maxTurns` 仍以 `options(...)` 追加；该路径本就不支持斜杠命令，用 `commandAware(false)` 保持既有行为不变
* 添加 soloncode harness 测试 2 例：无文本审批不被 `IGNORED` 吞掉（`Kind.HITL`）、`hitlOutput` 不改变 kind；`HarnessInputTest` 链式可读性用例补 `hitlOutput` 断言
* 添加 soloncode harness `InputTurn.stream()`：把本次输入的处理结果忠实映射成一条事件流（`MESSAGE` → `request.stream()` 冷流；`COMMAND`/`COMMAND_AGENT`/`CLI_ONLY_BLOCKED` → `InputTextEvent`，无回执时为空流；`HITL`/`IGNORED` 空流），使 `handle()` 成为唯一入口，宿主不需要 kind 时无需再写 switch。两点刻意不做：**不内置 `subscribeOn`**（Web 端必须先注册 Disposable 再订阅，调度权属于宿主，与 `getRequest().stream()` 同构）、**不对 HITL 自动续流**（Web 整批审批完才 resume、桌面续流要重挂拦截器、CLI 是多轮循环，引擎替任何一端选择都是错的；续流统一显式调用 `engine.prompt(session, null, modelName, agentName)`）
* 添加 soloncode harness `InputTextEvent`：把命令回执这类纯文本结果也放进 AgentEvent 流，使 `InputTurn.stream()` 的调用方只面对一种数据形态
* 删除 soloncode harness `HarnessEngine.chat(...)` 三个重载：能力已完整下沉到 `InputTurn.stream()`。删除理由是实测结论而非口味 —— 三端生产调用点均为 0（CLI 用 `getRequest()` + 四类事件分类渲染、Web 用 `getPrompt()` 喂自有 WebEvent 管道、桌面用 `gate.streamTurn.run(...)` 需要 socket/reasoningEffort/拦截器），且它隐藏了 `subscribeOn`（"三行起步"的示例在阻塞模型下原地阻塞）、吞掉了 HITL 决策（返回空流让调用方以为"批准了但没反应"）
* 改写 soloncode harness `InputBoundaryTest`：原「`InputTurn` 不得出现 `Flux`/`reactor.core`」改为三条窄断言（不 `import HarnessEngine`、不出现 `Schedulers`、不出现 `prompt(`）。禁 Flux 的初衷（三端消费模型不同、担心统一消费口退化为满屏回调）仍成立，但 `stream()` 作为**可选出口**不强迫任何宿主使用；真正不能回退的是「能力靠注入而非反向依赖」「不抢调度权」「不自动续流」三件事
* 裁决 soloncode harness `CommandContext.getSession()` 保留、不搬进 `Command.execute` 参数：方向对（宿主实现的上下文不应承载业务元数据），但只搬 session 会让 `execute` 涨到 3 参，而 `getRawInput`/`getCommandName`/`getArgs`/`argAt`/`argCount`/`getArgsJoined` 六个方法仍是 `ParsedCommand` 的重复投影。正确做法是一次做全（引入 `CommandInput(session + ParsedCommand)` 值对象、工厂退化为 `create()`），单独搬 session 只是把 1 处重复换成 4 处改动、零行为收益
* 简化 soloncode harness `InputTurn`：删除 `getText()` 与 `notCommand()`。前者当初的理由是「桌面端 Goal 拦截与规划模式前缀需要剥提及后的文本」，复核发现桌面端实际是先自己 `AgentMentions.parse` 再用 `explicitAgent` 透传，该方法全仓零调用（仅一条测试在用）；后者让「不是命令」被表达两次且第一次是错的（`Kind.MESSAGE` 但 `getRequest()==null`，宿主易踩空）。现 `CommandDispatcher.dispatch` 非命令一律返回 `null`，`Kind.MESSAGE` 从此只表示「真的有请求」；`InputTurn` 同步去掉 `text` 字段与构造参数

* 添加 soloncode harness `HarnessInput.commandAware` 开关（默认 true）：置 false 时引擎跳过命令分发，输入原样按消息处理。用于「文本来源不是用户手输」的路径 —— 定时任务提示词、已受理的排队输入、命令内部自造提示词。这些文本即使以 `/` 开头也不应被当作命令执行；不加此开关则这类路径只能继续保留内联解析，重复无法清零
* 收敛 soloncode CLI 主链路至 `engine.handle(...)` + `switch(kind)`：原 `isCommand()` 与 `performAgentTask()` 两段手工拼接（普通输入会白跑一遍提及解析与请求装配）合并为 `handleInput` 单入口，命令上下文工厂抽为 `cliCommandFactory`；`call()` 与主循环共用同一入口
* 重构 soloncode `CliShell.performAgentTask` 为 `performAgentTask(AgentSession, InputTurn)`：**入口一次，续轮用字段** —— 首轮直接用 `turn.getRequest()`（模型回退/子代理/Prompt 全由引擎装配），HITL 恢复后的续轮用 `outcome.getModelName()` + `turn.getAgentName()` + `engine.prompt(session, null, ...)` 驱动，不再每轮重新解析提及、重新回退模型；内联的 `AgentMentions.parse` 消失，CLI 侧的 input 包 import 随之清理
* 收敛 soloncode CLI 三条旁路至 `runMessageInput`（队列 drain、loop 定时任务、命令内自造提示词）：统一走 `engine.handle(..., commandAware(false))`，队列项文本里的 `@子代理` 仍按原语义生效，但代码里不再有第二份解析
* 收敛 soloncode Web loop 入口 `safeChatInputAndCaptureLoop` 至 `engine.handle(..., commandAware(false))`：原内联的提及解析与 `ChatMessage.ofUser(...).addMetadata("source", ...)` 手工拼装由引擎完成，返回 `turn.getPrompt()/getModelName()/getAgentName()`
* 添加 soloncode harness 引擎级测试 InputCommandAwareTest 3 例：默认语境下 `/ping` 判为命令并执行、`commandAware(false)` 时同文本按消息处理且原文不被剥离、`dispatchCommand` 的快速判断不受该开关影响
* 添加 soloncode harness HarnessInputTest 2 例：commandAware 默认 true、可显式关闭（共 9 例）

* 添加 soloncode harness 完整输入入口 `HarnessEngine.input(...)`：一次调用完成「命令识别 → 命令执行 → HITL 决策 → 附件装配 → 子代理解析 → Prompt 构建 → 请求装配」，宿主只保留「回执怎么渲染」与「流怎么消费」。返回 `InputTurn`（承载 kind / 命令回执 / 最终文本 / 生效子代理与模型 / 装配好的 ReActRequest）而非 Flux —— 三端消费模型本质不同（CLI 一次输入多轮流 + rawMode 轮询、Web 消费自有 WebEvent 管道、桌面裸 AgentEvent），统一流不可行但「拿到配置好的请求」是三端共同起点
* 添加 soloncode harness 专用输入上下文 `HarnessInput`：文本 / 附件 / 模型 / 选择器兜底子代理 / 请求体显式子代理 / 名字校验 / trim 策略 / 附件落盘根 / 宿主已落盘结果 / HITL 决策 / 回复路由；三端差异全部显式声明，不再靠各写一份
* 添加 soloncode harness 可配置前缀：`Builder.commandPrefix(...)`（默认 `/`）与 `Builder.agentPrefix(...)`（默认 `@`），均支持多字符，空值抛 IllegalArgumentException；改前缀后原前缀自动降级为普通文本（有测试锁定）
* 添加 soloncode harness 附件装配 `InputAttachments`：工作区 `.uploads` 落盘（含 `..`、路径分隔双重校验）、图片转 ImageBlock（Base64 + 按扩展名定 MIME）、非图片写 `[附件: x]` 前缀、元数据 JSON 转义；`ofExisting(...)` 供宿主已自行落盘场景（桌面端的符号链接/大小校验严于引擎，故保留自身写盘）
* 三端接入 `engine.handle(...)`：CliShell.isCommand、WebChatInputHandler.isCommand、DesktopInputRouter.handleChat 改为单一入口，命令分发不再由各端直接调用 CommandDispatcher
* 添加 soloncode harness 输入层单测：HarnessInputTest 7 例（链式字段、HITL 判空、附件累加、preAttached 免二次落盘）、InputPrefixTest 12 例（默认/多字符/自定义前缀互斥、`::` 与 `@` 降级为文本、单独前缀不构成命令）、InputAttachmentsTest 10 例（文件前缀、默认提示、图片块、声明类型必需、JSON 转义、路径穿越跳过、空列表）

* 收敛 soloncode 三端 @子代理 解析至 `AgentMentions`（harness input 包）：原 CLI/Web/桌面端各自内联 indexOf(" ") + hasAgent 判断，桌面端还额外持有 WsGate.firstWhitespaceIndex / removeLeadingAgentMention 两个私有 helper；现统一为「识别 + 剥离 + 兜底」，三端差异（请求体显式 agent 优先、名字格式校验、内容 trim）通过 Options 显式声明，不再靠各写一份维持
* 重构 AgentMentions 为策略式契约：核心 parse 只依赖 `Predicate<String>`（名字是否有效）而非 HarnessEngine，可脱离引擎单测；原 parse(engine, input, selectedAgent) 保留为便利重载，语义不变
* 删除 soloncode WsGate.firstWhitespaceIndex / removeLeadingAgentMention 两个 helper（已被 AgentMentions 覆盖）；WsGate.isValidAgentName 保留，作为 nameValidator 传入
* 添加 soloncode harness AgentMentionsTest 16 例：逐条锁定迁移前三端实际行为（CLI/Web 只吃一个分隔符、无效 @ 整段保留且不阻断兜底、无空格不构成提及、选择器兜底、桌面端显式 agent 剥离与边界不误剥、名字格式校验、trimContent 只影响首尾空白、null/单 @ 边界）
* 收敛 soloncode 各端手拼 request 的重复代码至 `HarnessEngine.prompt(session, prompt, modelName, agentName)`：CliShell（含 HITL 多轮循环）、PrintMode、WsGate Goal 轮次、GitService 摘要四处改为一行装配；WebSessionRunner / WebStreamBuilder / DesktopStreamTurn / DesktopInputRouter 保留原写法（其下游需独立持有 ChatModel 实例做模型名回填与能力探测，替换会使签名退化）
* 添加 soloncode harness 完整输入装配入口：`HarnessEngine.prompt(AgentSession, Prompt, modelName, agentName)`（含文本重载）收敛各端「取模型 → 取子代理 → 手拼 options」的重复代码，模型名空缺时回退会话级选择（CTX_MODEL_SELECTED）再回退默认模型，子代理名空缺时使用主代理；`promptDo` 原有的 retry/maxTurns/sessionWindowSize 保持设置，追加的 options 为累加语义
* 添加 soloncode harness 命令分发层 org.noear.solon.ai.harness.input：ParsedCommand（解析口径，单独输入 `/` 安全返回 null）、CommandContextFactory（宿主实现，isRemote 决定 cliOnly 拦截）、BufferedCommandContext（默认缓冲实现）、CommandDispatcher（解析→查找→渠道限制拦截→执行→回执收集）、InputTurn（分发结果，只承载同步态信息）、AgentMentions（@子代理解析，显式提及优先、selectedAgent 兜底）；三端命令链路改为共用同一套口径，CLI 专属命令的提示文案统一由 harness 生成
* 修复 soloncode 桌面端可直接执行 `/exit` 终止桌面进程：DesktopInputRouter.handleCommand 原只判 command == null，未校验 cliOnly()，现随上条的分发层一并获得拦截（cliOnly 命令只回执提示，不进入 execute）
* 修复 soloncode CLI 输入单个 `/` 抛 IndexOutOfBoundsException：原实现解析后直接 parts.get(0)，现由 ParsedCommand.parse 统一判空返回 null 走普通输入
* 简化 soloncode WebCommandContext：回执缓冲与 agentTask 标记改由 BufferedCommandContext 提供，本类只保留渠道元数据（source/sourceUserId/replyTarget/messageId）；顺带修正其类注释误写为「CLI 命令执行上下文」
* 加固 soloncode input 包架构护栏（InputBoundaryTest）：input 不得 import 宿主 codecli 包（负向验证已确认会真实失败）；InputTurn 不得依赖响应式流库或暴露 Flux —— 三端消费模型本质不同（CLI 一次输入多轮流、Web 消费 WebEvent 管道、桌面消费裸 AgentEvent），统一流消费口不可行
* 添加 soloncode 输入层单测：ParsedCommandTest 五例（非斜杠/单独斜杠/名称小写与参数切分/无参数/引号参数）、CommandDispatcherTest 四例（统一提示文案、缓冲回执、agentTask 标记、无回调不标记）、InputBoundaryTest 三例

* 修复 soloncode /help 在远程渠道泄露 CLI 专属命令：`HelpCommand.formatHelp` 的命令清单分支未按 `cliOnly()` 过滤（此前只处理了 topic 分支且用 `continue` 落到“未找到命令”），导致 Web/IM 端 `/help` 仍列出 `/exit`；现清单分支整条跳过 CLI 专属命令，topic 分支改为明确提示“仅支持 CLI”而非伪装成不存在，抽取 `appendCommandLine` 消除两处重复拼接
* 修复 soloncode Web 端可直接执行 `/exit` 终止服务进程：`WebChatInputHandler.isCommand` 查到命令后未校验 `cliOnly()` 就直接 `command.execute()`（忙态旁路与提示列表两处已有过滤，唯独真正执行这条链路漏了），现拒绝执行并给出“仅支持 CLI”回执，回执仍用 `reset...done` 信封包住以免前端把它当作上一轮尾巴丢弃；同时为 `HelpCommandTest` 补上 `cliOnly()` 覆盖（原测试辅助方法从不设置该值，导致两条用例在实现正确时也必然失败）并新增 CLI 侧正向用例
* 迁移 soloncode goal 域下沉 harness：GoalState/BudgetThresholds/GoalPromptBuilder/GoalEngine/GoalScheduler/GoalTalent/GoalExtension/validate/* 由 codecli.goal 迁至 org.noear.solon.ai.harness.session.goal（与 session/queue、session/steer 同级），执行桥三件（ErrorClassifier/ExecutionBridge/RoundResult）迁至 harness.runtime，/goal 命令迁至 harness.command.builtin；宿主 codecli.goal 仅保留 GoalDataMigrator（读 tasks.json + 直写 sessions/<id>/<id>.snapshot.json 属 codecli 私有存储知识）。harness 是被多端复用的库，goal 域不新增任何 HarnessEngine 入口，装配仍由宿主完成，HarnessEngine 本轮零改动
* 加固 soloncode 架构护栏（随 goal 下沉重建）：原 PackageBoundaryTest 的 6 条 goal 断言依赖 src/main/java/.../codecli/goal 目录且带 assumeTrue，目录搬走后会静默跳过而非失败；现迁至 harness 的 GoalBoundaryTest，并把「goal 不得 import loop」强化为「goal 不得 import 宿主 codecli 包」，负向验证已确认会真实失败；codecli 侧新增「codecli.goal 只允许保留 GoalDataMigrator」正向锁定，防领域实现回流宿主

* 清理 soloncode goal 与 loop 残余耦合：执行桥（ExecutionBridge）归属上提至组合根，由 WorkspaceManager 创建后同时注入 LoopScheduler 与 GoalEngine，删除 LoopScheduler.getBridge()（本类是桥的使用者而非拥有者，goal 的构造期不再依赖 loop 实例存活）；WorkspaceContext 新增 getExecutionBridge()，CliShell/WsGate/WorkspaceManager 六处注册点改为直接持有桥
* 清理 soloncode 桌面端无价值类型耦合：AbstractDesktopController 删除零读取的 loopScheduler 字段与构造参数，四个子 Controller 与 Configurator 装配同步瘦身；WsGate 改收 ExecutionBridge（原先仅通过 LoopScheduler.getBridge() 取桥，是它持有 loop 的唯一理由），桌面 goal 链从此完全不接触 loop
* 修复 soloncode goal 验证器跨工作区污染缺陷：ValidatorFactory 原为全局静态注册表且 register 只 append 不去重，而每个工作区创建时都会注册一次默认验证器，两个工作区后注册项累积为 4 条并分别携带各自 workspace 路径，goal 完成判定按 AND 语义遍历全部匹配项，导致在 A 工作区跑 goal 会连 B 工作区的 mvn test 一起跑、B 失败则 A 无法完成；现删除 ValidatorFactory，改为 GoalEngine 实例持有注册表（构造期按自身 workspace 注册 TestsPass/BuildPass 两个默认验证器），新增 resolveValidator(condition) 供 GoalTalent 调用，顺带去掉 GoalValidator.validate 中三个实现均未使用的 sessionId 参数
* 修复 soloncode GoalEngine 会话解析的半初始化隐患：删除 volatile sessionResolver 字段与 setSessionResolver 注入点（漏调用时静默返回 null），改为委托 ExecutionBridge.sessionOf()（桥本就持有 HarnessEngine）
* 清理 soloncode goal 包三处重构残留：GoalEngine 死常量 ROUND_INTERVAL_MS 与死 import RunUtil、GoalPromptBuilder 的自包 import
* 加固 soloncode 架构护栏：PackageBoundaryTest 新增「goal 包不得持有 static List 注册表」（锁定验证器实例化）与「goal 包不得引用 LoopScheduler」（锁定执行桥归属）两条断言；新增 GoalValidatorIsolationTest 四例覆盖多工作区注册隔离、验证器只绑定自身工作区、无工作区不注册、无匹配回退 Noop

* 修复 soloncode 启动失败：挂载发现阶段遇到 Windows 目录符号链接（junction / 目录重解析点，NIO 不识别为 SymbolicLink，walkFileTree 会进入其子树）时报 `Symbolic link escapes mount root` 并抛 SecurityException，一路冒泡到 WorkspaceManager 导致默认工作区初始化失败、web 无法启动；现改为发现阶段尽力而为——find 中对越界目录返回 SKIP_SUBTREE、单条目异常（越界/无权限/瞬时消失）与 visitFileFailed 仅跳过、技能与子代理目录扫描对单个来源/单个条目异常只记日志，工作区注册挂载统一走 addMountSafely 兜底，任一挂载点异常都不再阻断启动
* 添加 soloncode 挂载点跟随外部链接能力：本地目录上的符号链接/Junction 视为普通目录（典型场景：C 盘空间紧张，把技能/子代理目录整体放到 D/E 盘再 Junction 链回），启动路径的全部默认挂载、mountPools 配置项、设置界面添加挂载、设置跨工作区同步重放、`--add-dir` 额外目录统一开启；FileMountSource 新增 followExternalLinks 开关（库默认仍为 false，不改变既有安全语义），同时修正 MountManager.checkedLocalPath 不识别该开关导致「发现可列出、显式访问 @alias/path 却拒绝」的不一致；跟随模式下 find 不再解析挂载根真实路径（省一次 toRealPath，且避免根不可解析时整次扫描被跳过）；`..` 与绝对路径的语法级越界拒绝保持不变
* 修复 soloncode web 文件面板与挂载层链接策略不一致：树构建、关键词搜索、文本读取、原始二进制读取、写回、“打开文件夹”等仅按“最后一段是否为符号链接”一律 403/跳过的判定，现统一改为遵循所在挂载来源的 followExternalLinks（FileService.shouldFollowExternalLinks 解析来源策略，默认工作区按挂载侧同口径跟随），消除“文件树里能看见、点开却读不到”的半开半闭状态；严格来源仍完整保留原有拦截，链接目录也不得被当作树展开根（防止绕开列表过滤直读根外内容）；跟随模式下写回先解析到链接目标再原子写，避免把链接本体替换成普通文件
* 精简 soloncode loop/goal 共享层：删除 LoopExecutionResult 死包装（唯一调用方只消费 2/4 字段，doTrigger 直用 RoundResult）；重复的 classifyError/getFullMessage 收敛为 runtime.ErrorClassifier；删除 LoopScheduler 转发接口（TaskHandler/BusyChecker），入口统一经 getBridge() 注册
* 重构 soloncode goal 为会话级能力（包 codecli.goal）：每会话最多一个，状态随会话快照持久化（不再写 tasks.json）；loop 纯化为定时任务体系，二者仅共享执行桥
* 添加 soloncode `/goal` 独立命令（创建/pause/resume/clear/状态查看），`/loop goal` 提示迁移；`/loop pause|resume` 只作用于定时任务
* 添加 soloncode web 循环任务列表合并展示 goal（恒为第一条），删除/启停/立即执行按钮对 goal 行自动分流
* 添加 soloncode goal 重启恢复：快照中 PURSUING 的 goal 自动重新挂调度，PAUSED/BLOCKED 保持静默等用户恢复
* 添加 soloncode goal 配置节点 soloncode.goal.*（兼容旧 soloncode.loop.* 回填）
* 添加 soloncode goal 存量数据迁移（tasks.json 历史条目 → 会话快照，启动时一次性、幂等）
* 修复 soloncode goal job 跨工作区同名会话互相覆盖的问题（job 名加工作区前缀）
* 修复 soloncode 工作区关闭/LRU 释放时 goal job 泄漏在全局调度器的问题；有活跃 goal 的工作区不再被闲置释放
* 修复 soloncode `/loop` 帮助文本残留的 goal 用法（goal 已迁至 `/goal`，帮助内不再教已迁走的命令）
* 修复 soloncode web 循环任务列表中 goal 状态显示错误：resolveTaskState 先判 loop 的 cancelled/enabled，而后端为复用展示契约把 goal 语义映射进了这两个字段（enabled=是否 PURSUING，cancelled=是否终态），导致 ACHIEVED/BUDGET_LIMITED 显示为“已取消”、PAUSED/BLOCKED 显示为“已停用”，goal 分支永远不可达；改为 goal.status 优先判定，并补全 ITERATION_LIMITED 状态与 blocked/limited 样式
* 修复 soloncode web 面板 goal 创建/编辑链路断裂（重构回归）：loop/add 与 loop/update 硬性拒绝 GOAL 后，前端 goal 标签页仍走这两个端点，创建必失败、编辑在 getTaskById 处先返回 404；改为在两端点内按 GoalState.id 分流到 GoalEngine（与桌面版 goals/add、goals/update 同语义），前端零改动
* 清理 soloncode goal 迁移残留：WorkspaceManager 装配段变量 loopCfg→goalCfg 正名，及 WebQueueDispatcher/SessionWebController/WsGate/DesktopInputRouter/Configurator 五处行内全限定名改标准 import

### v2026.10.23

* 优化 soloncode `/model` 命令输出
* 调整 soloncode im 任务异常结束时优先展示实际输出文本，仅在文本为空时使用默认异常提示
* 调整 soloncode 打开目录 更名为 打开文件夹
* 细节优化
* mcp-java-sdk 升为 v2.1.0

### v2026.10.20

* 添加 soloncode web 输出本地链接可点击支持（查看文件内容）
* 添加 soloncode im 图片推送支持
* 优化 soloncode web msg-nav 导航样式
* 细节优化

### v2026.10.19

* 添加 soloncode web 启用实例同端口复用机制
* 优化 soloncode web 心智记忆列表没有记录时的提示
* 修复 soloncode web git-diff 视图不能滚动的问题
* 细节优化

### v2026.10.15

* 添加 soloncode web 隐藏智能体的管理
* 添加 soloncode web 文件编辑功能
* 添加 soloncode web 文件树打开目录菜单
* 修复 soloncode web 在 window 下打开目录选择器后浏览器会被置顶的问题
* 修复 soloncode web 多进程实例登录引起冲突问题
* 细节优化

### v2026.10.7

* 调整 soloncode web im 绑定处理（之前与 session 绑；新版与 app 绑）
* 调整 soloncode web 对话忙时，im 新消息默认转为“插话”
* 修复 soloncode web 由 localStorage 引起的状态失效问题（port 变，状态就失效了）
* 细节优化

### v2026.10.4

* 添加 soloncode web 全局自动任务
* 优化 soloncode web im `插话`与`排队`支持
* 修复 soloncode 非启动目录的工作区的 AGENTS.md 失效问题
* 细节优化

### v2026.9.29

* 添加 soloncode web im `插话`与`排队`支持
* 添加 soloncode classpath 挂载支持
* 细节优化

### v2026.9.25

* 添加 soloncode web 管理控制台
* 添加 soloncode web 多用户管理（之前有，开放出来）
* 细节优化


### v2026.9.23

* 优化 soloncode desktop 「自动编辑」模式完全免审批（write/edit/bash 一律直接放行，不再弹确认）
* 新增 soloncode desktop 「完全访问」执行模式（任何工具调用都不进入审批流程，全自动免打扰；执行模式现为四个：审批执行 / 自动编辑 / 完全访问 / 仅规划）
* 优化 soloncode desktop 编辑总结页文件列表行间距减半，多文件展示更紧凑
* 修复 soloncode-cli MountSettingsControllerTest 因构造器变更导致模块测试无法编译的问题
* 细节优化

### v2026.9.15

* 添加 soloncode web 输入草案隔离
* 优化 soloncode web 浏览本地目录界面 for window
* 细节优化

### v2026.9.14

* 优化 soloncode web 工作区共享设置同步
* 优化 soloncode web 浏览本地目录界面 for window
* 优化 soloncode web `~` 目录启动时弱化文件扫描
* 细节优化

### v2026.9.12

* 优化 soloncode llm 方言适配
* 调整 soloncode web 排队任务面板转到输入面板上方
* 修复 soloncode web 添加文件挂载后，文件树不能展开问题
* 修复 soloncode web 排队任务不能删除问题
* 细节优化

### v2026.9.8

* 添加 soloncode web 选中文本右键菜单功能
* 细节优化

### v2026.9.5

* 添加 soloncode web 设置「配置备份」
* 添加 soloncode web loop llm 自动管理支持
* 添加 soloncode web 文件树手动刷新功能
* 优化 soloncode-sdk-java 模块
* 优化 soloncode 适配 solon-ai ChatEvent 新机制，增加 "thought.done" 新事件
* 优化 soloncode 安装脚本带上 md 命令文件
* 修复 soloncode web IM 通道会收到 delta 数据的问题
* 修复 soloncode web IM 通道（微信、钉钉、飞书）二维码显示失败
* 细节优化 

### v2026.8.30

* 添加 soloncode web 输入超大提示（100K）
* 优化 soloncode web 供应商拉取失败的提示
* 优化 soloncode llm 方言适配
* 优化 soloncode-sdk-java 模块
* 细节优化

### v2026.8.29

* 新增 soloncode-sdk-java 模块
* 修复 soloncode web 子代理任务显示描述丢失问题
* 修复 soloncode web 如果是子代理执行，继续执行会失败的问题
* 细节优化

### v2026.8.28

* 新增 soloncode `~` 启动时锁定工作区面板的设计
* 添加 soloncode help 命令与 `--help`/`-h` 选项（支持 `soloncode help run` 与 `soloncode run --help` 查看无头模式选项）
* 添加 soloncode-sdk-java 传输层抽象：`Transport` 接口 + `StdioTransport` 实现（原 StreamingTransport），builder 的 `cliPath()` 改为 `stdio()`/`stdio(path)`（SDK 未发布，直接替换不留过渡）；预留 `http()` 通道，配套 `/web/run` 方案见 `soloncode-cli/docs/run-headless-mode-http.md`
* 添加 soloncode web 文件树右键菜单
* 优化 soloncode web 提示，统一样式
* 细节优化

### v2026.8.27

* 修复 soloncode run 与 --version 成功时退出码为 1 的问题（改为 0，对齐无头模式退出码约定）
* 添加 soloncode web 更新提示（启动时）
* 优化 soloncode web 最后一条历史消息改用 trace 里获取（显示过程记录）
* 优化 soloncode llm 协议兼容处理
* 优化 soloncode llm 缓存率
* 细节优化

### v2026.8.27

* 添加 soloncode web 对话即时 "插话" 功能（起到，“调整方式”或“补充想法”）
* 优化 soloncode web 对话的最后一条历史消息采用流式风格展示
* 细节优化

### v2026.8.26

* 优化 soloncode 大模型上下文的默认长度配置为 1M（之前是 128K）
* 优化 soloncode lsp 体验（集成在 edit, write 工具中）
* 优化 soloncode glob 工具摘要同时展示匹配模式与搜索路径
* 优化 soloncode web 文件树、Git文件列表深层缩进支持横向滚动，待办文本改为自动换行不再截断省略
* 优化 soloncode code.md 扫描层数
* 细节优化

### v2026.8.24

* 调整 soloncode 工作区本地数据布局，按工作区聚合到 "~/.soloncode/workspaces/{workspaceKey}/" 下面（logs、sessions）
* 调整 soloncode 会话存放位置，转出工作区（旧会话在工作区上下文创建时自动迁移，日志不迁移仅清理）
* 优化 soloncode web 系统打开处理（浏览器，目录，文件）
* 优化 soloncode web 文件树展开效果（之前到第6层就平层展开了）
* 优化 soloncode web 用户侧消息增加子代理标识（方便识别是向谁请求的）
* 优化 soloncode code 引导处理（增加4层扫描和缓存机制）
* 细节优化

### v2026.8.23

* 添加 soloncode web 设置 Manage 工具支持开关（默认开启会占位置）
* 添加 soloncode web 首次使用引导
* 优化 soloncode web bash window 深度体验（之前的体验不好）
* 优化 soloncode web 文件树体验（只有一个目录节点时，自动展开）
* 修复 soloncode web 多语言有些 key 没有翻译的问题
* 细节优化

### v2026.8.22

* 添加 soloncode web .uploads 等隐藏目录白名单使其在文件树中可见并统一目录过滤逻辑
* 修复 soloncode web 有工具调用时输出文本会重复的问题（v2026.8.20 引起的）
* 修复 soloncode web 当前任务列表没有时实同步的问题
* 修复 soloncode web 文件节点名称溢出及箭头图标被压缩问题
* 优化 soloncode llm 测试连接提示方式
* 细节优化

### v2026.8.21

* 优化 soloncode llm 缓存率，并保持1位小数
* 修复 soloncode llm anthropic 缓存率计算错误的问题
* 修复 soloncode web 子代理执行时没有显示子代理名的问题
* 细节优化

### v2026.8.20

* 新增 soloncode web 多工作区支持（一个工作区一个浏览器tab）
* 添加 soloncode web 智能体选择模型支持
* 优化 soloncode web 设置/供应商/模型管理体验
* 重构 soloncode web 流式输出消息格式（SAEP 2.0 更规范）
* 细节优化

### v2026.8.17

* 添加 soloncode web 供应商 google 方言支持
* 优化 soloncode 缓存处理
* 细节优化

### v2026.8.15

* 添加 soloncode web 缓存率显示
* 添加 soloncode web 思考模式开关控制
* 优化 soloncode llm 方言的兼容处理（有些 toolCalls 可能参数格式缺）
* 优化 soloncode llm 思考与强度的兼容处理
* 细节优化

### v2026.8.11

* 优化 soloncode proxy 和 user-agent 的传导（改为全局控制）
* 细节优化

### v2026.8.07

* 添加 soloncode web diff 视图（文件路径）加入对话的功能
* 优化 soloncode proxy 和 user-agent 的传导
* 修复 soloncode websearch 总是失败的问题（默认 ua 被拦截了）
* 修复 soloncode web 设置挂载添加时描述信息丢失的问题
* 修复 soloncode web 初始加载后模型面板失真问题
* 细节优化

### v2026.8.6

* 添加 soloncode web http 代理设置
* 添加 soloncode web Modelscope 技能市场适配 
* 优化 soloncode `/goal` 更新状态时的检测
* 优化 soloncode openai-resposes 方言适配
* 细节优化

### v2026.8.5

* 添加 soloncode http 代理支持
* 添加 soloncode web 技能管理 启用开关
* 优化 soloncode 沙盒处理细节
* 修复 soloncode Windows 环境变量可能识别失误的问题
* 修复 soloncode openapi2 可能无限递归解析的问题
* 细节优化

### v2026.8.4

* 添加 soloncode web 调整字体功能
* 优化 soloncode 心智记忆引导词和搜索能力

### v2026.8.3

* 添加 soloncode web 兼容 `glm-5.2[1m]` 风格的模型名解析
* 优化 soloncode web 国际化多语言支持

### v2026.8.2

* 优化 soloncode run 模式（参考 run-headless-mode.md）
* 添加 soloncode web 国际化多语言支持
* 添加 soloncode web 消息列表消息导航支持
* 修复 soloncode web 文件详情初次显示时语法高亮失效的问题
* 修复 soloncode web 审查详情文件名中文乱码的问题
* 细节优化

### v2026.7.30

* 优化 soloncode acp 模式
* 优化 soloncode run 模式（参考 run-headless-mode.md）
* 添加 soloncode web 文件详情支持图片与视频预览
* 添加 soloncode web 历史消息中的图片附件支持预览
* 添加 soloncode web 审查面板对目录的识别
* 修复 soloncode web 新对话下发 `/goal xx` 可能收不到流消息的问题
* 细节优化

### v2026.7.29

* 添加 soloncode web 对话置顶功能
* 优化 soloncode web 技能管理功能
* 细节优化

### v2026.7.28

* 添加 soloncode web 心智记忆管理作用域功能
* 细节优化

### v2026.7.27

* 添加 soloncode web 长期记忆管理（心智记忆）
* 修复 soloncode grep 使用虚拟路径时返回失真的问题
* 细节优化

### v2026.7.26

* 添加 soloncode  `/goal` 命令作为 `/loop goal` 的简化模式
* 添加 soloncode web 输入面板，输入左键可显示循环任务面板
* 优化 soloncode 上下文压缩算法
* 调整 soloncode 压缩阀值由数值改为比例（默认 75%）
* 细节优化

### v2026.7.25

* 调整 soloncode web 输入面板布局，突出子代理的权限
* 细节优化

### v2026.7.24

* 添加 soloncode web 输入面板 Alt + Enter 换行支持
* 添加 soloncode web 智能体（子代理）管理
* 优化 soloncode web macOS Safari 输入法选词 Enter 时序问题
* 优化 soloncode web 移动任务排队面板至会话任务区域，优化布局结构
* 优化 soloncode web mcp 导入解析
* 优化 soloncode web 流式消息列表取消线框
* 细节优化

### v2026.7.22

* 添加 soloncode web 刷新配置的功能（方便多实例同步）
* 添加 soloncode web 最后条用户消息 rerun 按钮支持
* 添加 soloncode web 在任务运行时，可以添加消息队列
* 添加 soloncode web git 审查面板回滚按钮
* 修复 soloncode web 日志级别设置后，重启失效的问题
* 细节优化

### v2026.7.21

* 修复 soloncode 上下文压缩没有使用会话模型的问题
* 调整 soloncode 日志文件默认级别改为 INFO（减少磁盘写频率）
* 细节优化

### v2026.7.20

* 添加 soloncode web 未跟踪文件按整文件新增生成 Diff 与 stat；
* 优化 soloncode llm 缓存处理
* 细节优化

### v2026.7.19

* 修复 soloncode web 历史消息加载期间缓冲并回放流式chunk，避免DOM重建冲掉内容；
* 修复 soloncode web 浏览器刷新后流状态恢复与迟到包丢弃逻辑

### v2026.7.17

* 添加 soloncode web 皮肤功能（自带生成的 skill），可开启花花世界
* 优化 soloncode web 流式消息处理性能
* 优化 soloncode web 流式消息 done 原子处理
* 改进 solon-development-skill
* 改进 spring-to-solon-skill
* 新增 soloncode-skin-skill
* 细节优化

### v2026.7.16

* 优化 soloncode web 任务全停止时，同时停目当前对话
* 优化 soloncode web 停止任务时，同时停止 loop goal（如果有）
* 修复 soloncode 可能会因为 llm 输出不规范造成 json 流式解析高 cpu 运行
* 细节优化

### v2026.7.14

* 调整 soloncode web 移除思考块计时器功能及时长显示
* 修复 soloncode web 手动停止时，没有提示消息
* 细节优化

### v2026.7.13

* 优化 soloncode web 会话删除逻辑（同时删掉内存）
* 优化 soloncode web 线程配置
* 优化 soloncode web 线程池配置
* 优化 soloncode web 子代理任务分组体验
* 修复 soloncode web 文件树展开后，可能会收回的问题（被刷新了）。新案改为动态删增
* 细节优化

### v2026.7.12

* 添加 soloncode web 模型选择器推理水平（自动/快速/均衡/深入/最强）
* 优化 soloncode web 子代理任务分组体验（收起时，有任务运行状态；展开时，可点左侧边收起）
* 细节优化

### v2026.7.11

* 添加 soloncode web mcp json 字符串导入方式
* 添加 soloncode web 文件详情支持 md 视图模式和全屏模式
* 添加 soloncode web 流式消息分组：思考与工具分组，子代理任务分组
* 添加 soloncode web 输入面板技能搜索
* 细节优化

### v2026.7.9

* 添加 soloncode web 挂载文件监听，实时通知文件变化
* 添加 soloncode web 对话分叉（复制）功能
* 优化 soloncode web 消息渲染，避免 html 输出破坏界面
* 调整 soloncode web 上传文件统一放到 ".uploads" 目录下
* 细节优化

### v2026.7.8

* 添加 soloncode web 多工作区支持（基于文件类型的挂载，实现自然的跨区协同工作）
* 细节优化

### v2026.7.7

* 添加 soloncode web 对话消息删除功能
* 优化 soloncode 上下文压缩算法
* 优化 soloncode hitl 实现
* 优化 soloncode permission 实现
* 优化 soloncode web 跨域处理
* 优化 soloncode web 附件显示
* 细节优化

### v2026.7.3

* 修复 soloncode web mcp 不能检测的问题
* 修复 soloncode 安装通过 Invoke-Expression 绕过执行策略以解决 PowerShell 脚本禁止运行错误

### v2026.7.1

* 添加 soloncode web 设置/供应商 模型“全选”“反选”
* 优化 soloncode web md 链接渲染改为新窗口打开
* 优化 soloncode web 适配studio，优化超链接跳转机制
* 细节优化

### v2026.6.28

* 添加 soloncode web clear 命令执行后的前端清理
* 优化 soloncode web 设置/供应商 同步细节
* 优化 soloncode web 设置/日志选择、接口规范选择器的表单样式
* 优化 soloncode serve 启动时打印
* 细节优化

### v2026.6.27

* 优化 soloncode web 钉钉与微信绑定
* 优化 soloncode web mcp 编辑提示
* 优化 soloncode web 循环任务编辑提示
* 细节优化

### v2026.6.26

* 添加 soloncode web 用户消息来源显示（飞书、Web）
* 优化 soloncode web 钉钉推送标题显示
* 优化 soloncode web 模型选择框架
* 修复 soloncode web 重启后钉钉不能直接收消息的问题
* 细节优化

### v2026.6.25

* 添加 soloncode web 设置/通用/日志保存
* 添加 soloncode web 飞书扫码绑定
* 添加 soloncode web 钉钉扫码绑定
* 优化 soloncode loop goal 完成识别（改为 tool 单一机制，之前还有消息标识）
* 优化 soloncode loop goal 移除 maxIterations 限制（严格使用预算机制）
* 修复 soloncode web 添加供应商时，没有同步输入面板的模型选择
* 修复 soloncode web 附件显示不正常的问题
* 细节优化

### v2026.6.24

* 新增 soloncode /interpret 命令（支持跨会话中断）
* 添加 soloncode /continue 命令 sessionId 参数支持（支持跨会话继续）
* 添加 soloncode StopLoopInterceptor 拦截器（A->A->A 或 A->B->A->B ...频繁做相同的事）
* 添加 soloncode web 输入面板的模型选择添加搜索功能
* 添加 soloncode web 对话消息 mermaid 渲染支持
* 添加 soloncode web WWW-Authenticate 认证
* 添加 soloncode web 技能市场重新安装支持（达到更新升级效果）
* 优化 soloncode WebGate 允许中断命令通过忙碌检查
* 优化 soloncode web 面板拖拽性能；延迟消息流代码高亮；为滚动区域和面板添加contain布局
* 优化 soloncode web 任务面板数据显示
* 优化 soloncode web mac 组合输入法的兼容性
* 优化 soloncode 上下文压缩算法
* 重构 soloncode loop goal 实现
* 细节优化

### v2026.6.21

* 修复 soloncode web 设置/默认模型后没有马上生效的问题
* 添加 soloncode web 设置/供应商管理面板
* 添加 soloncode anthropic 上下文缓存显示支持
* 优化 soloncode web 对话切换性能
* 优化 soloncode web 关闭浏览器密码填充提示
* 细节优化

### v2026.6.20

* 添加 soloncode web 设置 mcp 导入功能
* 添加 soloncode web 设置 供应商功能
* 优化 soloncode web 右侧面板的 emoji 图标改为 svg 图标（跨系统更稳定）
* 优化 soloncode edit 工具匹配算法（同行号可以乎略缩进）
* 细节优化

### v2026.6.18

* 添加 soloncode web 设置/通用/子代理开发
* 添加 soloncode web 设置/工具权限
* 添加 soloncode 命令 `/rerun`
* 优化 soloncode 心智记忆处理
* 优化 soloncode 任务闭合管理
* 优化 soloncode 代码环境版本识别
* 调整 soloncode `/resume` 命令更名为 `/continue`
* 修复 soloncode `/loop goal` 可能重复执行、不能停止等问题
* 细节优化


### v2026.6.17

* 修复 soloncode 安装时，会清掉 `~/.soloncode/skills/` 技能的问题
* 添加 soloncode `/resume` 命令实现，可恢复任何任务（手动中断，异常结束）
* 添加 soloncode web 对话的最后一条消息：重新运行、继续运行按钮
* 细节优化

### v2026.6.16

* 添加 soloncode bash 输出大小限制机制
* 添加 soloncode web 界面效果设置块（支持工具显示展开或收起）
* 添加 soloncode loop 立好执行机制
* 优化 soloncode 上下文压缩算法
* 优化 soloncode edit 匹配算法
* 优化 soloncode web tool 调用显示效果
* 优化 soloncode web edit（git diff） 和 bash（terminal） 显示效果
* 优化 soloncode web 消息列表的复制按钮效果
* 调整 soloncode loop 机制
* 细节优化

### v2026.6.15

* 添加 soloncode web edit 工具（gitdiff 显示）
* 添加 soloncode web write 工具语法亮显显示
* 调整 soloncode loop 任务列表与编辑简化
* 修复 soloncode web 左侧面板 todo 进度（非当前对话）不更新的问题
* 细节优化

### v2026.6.14

* 调整 soloncode 向去 config.yml 过度（当产生 setting.json 后移除 config.yml）
* 修复 soloncode web 新对话发送 command 对话记录没名字的问题
* 修复 soloncode loop maker/checker 模式下无法停止的问题
* 细节优化

### v2026.6.13

* 添加 soloncode web 左侧面板拖动大小的功能（同时取消展开与收起的动画）
* 添加 soloncode loop goal 模式支持
* 优化 soloncode web loop 管理面板
* 修复 soloncode 在用户根目录启动时，配置错乱问题
* 修复 soloncode web 默认模型禁用后，对话列表没有更新的问题
* 细节优化

### v2026.6.12

* 新增 soloncode Loop Engineering 概念支持
* 添加 soloncode web loop 管理面板
* 优化 soloncode web highlight.js 由 cdn 引用，改为本地引用（快多了）
* 优化 soloncode 启动时文件发现扫描改为异步（快多了）
* 细节优化

### v2026.6.11

* 修复 soloncode web 发送消息时，格式被清的问题
* 修复 soloncode web 复制“历史消息”时，格式被清的问题
* 修复 soloncode web 历史记录选对时，格式被清的问题
* 细节优化


### v2026.6.10

* 添加 soloncode web 任务面板（待办事清单）
* 添加 soloncode web 上下文使用情况实时展示
* 添加 soloncode web 大语言模型默认设置
* 添加 soloncode 消盒模式系统内核级支持
* 优化 soloncode ConfigTalent 引导词和参数校验
* 优化 soloncode 上下文压缩算法（增加 tool 计数）
* 细节优化


### v2026.6.9

* 优化 soloncode web json 输出时间段格式
* 优化 soloncode 沙盒模式（减少误伤）
* 修复 soloncode web 设置/大模型检测失败的问题（有些模型需要有 user-agent）
* 修复 soloncode 首次自动添加 npx mcp 后，工具加载失败的问题（超时了）
* 细节优化

### v2026.6.8

* 新增 soloncode 通过提示词自动添加 config 的支持
* 优化 soloncode web 设置体验细节
* 重构 soloncode 沙盒模式
* 修复 soloncode 因 mcp, openapi 配置错误而无法启动的问题
* 细节优化

### v2026.6.6

* 添加 soloncode web 文件树 tooltip
* 优化 soloncode web 设置添加模型时，对话框架模型同步刷新
* 优化 soloncode web 设置作用域选择框样式
* 优化 soloncode web 设置 LSP 样式
* 优化 soloncode web 设置 挂截 样式
* 优化 soloncode OpenAI Responses 接口兼容性
* 优化 soloncode 压缩算法
* 细节优化

### v2026.6.5

* 添加 soloncode web 专门的 trace 显示块
* 添加 soloncode web lsp 管理能力
* 添加 soloncode web 更多通用设置
* 添加 soloncode web 设置 “作用域” 权念（有效结合，全局设置和工作区设置）
* 优化 soloncode web 代码语法高亮看不清的问题（改成了浅底色）
* 优化 soloncode web 挂载点编辑逻辑
* 修复 soloncode web 历史对话里没有代码语法高亮的问题
* 修复 soloncode web 模型列表为空时仍尝试获取选中模型导致的逻辑问题
* 细节优化

### v2026.6.3

* 添加 soloncode web 通用设置“重试策略”功能
* 添加 soloncode web 技能市场“安装到”功能（可选择技能池）
* 添加 soloncode web 技能市场点击查看详情的功能
* 添加 soloncode web 挂载点击内容查看目录的功能
* 添加 soloncode web 挂载启用开关
* 添加 soloncode mcp 服务器权限控制（禁用工具）
* 添加 soloncode openapi 服务器权限控制（禁用工具）
* 优化 soloncode mcp 协议兼容性（兼容更多非规范协议）
* 优化 soloncode 上下文压缩算法
* 细节优化

### v2026.5.31

* 添加 soloncode web 挂载池管理
* 添加 soloncode web 技能市场安装到（挂载池）选择功能
* 优化 soloncode web 设置面板样式
* 调整 soloncode 技能标识由 aliasPath 统一改为 name，并优化技能池加载逻辑及代码格式
* 修复 soloncode 压缩后可能会引起 ToolMessage 无法对齐的问题
* 细节优化

### v2026.5.30

* 添加 soloncode web 对话 “历史” 功能（复用，定位）
* 添加 soloncode web 审查 “生成摘要” 功能
* 添加 soloncode web 设置/大语言模型 配置功能
* 添加 soloncode web 设置/技能市场 功能（查找、安装）
* 添加 soloncode web 设置/MCP 服务器 配置功能
* 添加 soloncode web 设置/OpenApi 服务器 配置功能
* 细节优化

### v2026.5.26

* 优化 soloncode ActionTask 增加原子性（避免单工具失败时，影响整个工作记忆）
* 优化 soloncode skill 提示加去重处理
* 细节优化

### v2026.5.22

* 优化 soloncode mcp 兼容性（有些 mcp server 不完全按 mcp 规范来）
* 升级 mcp sdk 到 v1.1.3

### v2026.5.21

* 添加 soloncode web 文件查看与复制功能
* 添加 soloncode web 文件搜索功能
* 添加 soloncode web 右侧面板的拖动功能
* 添加 soloncode web 技能提示
* 添加 soloncode cli 技能提示

### v2026.5.20

* 添加 soloncode web 文件树功能（方便知道有哪些文件）
* 添加 soloncode web git diff 功能

### v2026.5.19

* 优化 soloncode 压缩时机和压缩算法
* 优化 soloncode edit 组合权限控制
* 细节优化


### v2026.5.15

* 修复 soloncode llm 返空重试的可能卡死问题
* 细节优化

### v2026.5.13

* 修复 soloncode WebfetchTool 超时失效造成卡死的问题
* 细节优化

### v2026.5.12

* 优化 soloncode web 飞书绑定
* 优化 soloncode web 钉钉绑定
* 优化 soloncode ReActAgent 架构（由计算图改为更简单的 while）
* 细节优化

### v2026.5.11

* 新增 soloncode web 飞书链接
* 新增 soloncode web 钉钉链接
* 添加 soloncode 时间显示
* 优化 soloncode web 微信链接体验
* 修复 soloncode web loop 任务失效的问题（只运行了一次）
* 细节优化

### v2026.5.9

* 添加 soloncode `/rewind` 命令（对话回退）
* 添加 soloncode web （输入框空时）上下键选历史任务功能
* 添加 soloncode web 微信通道命令支持
* 添加 soloncode web icon
* 调整 soloncode 心智记忆改为 md 文件保存（之前的会失效）
* 调整 soloncode web 改用 websocket 统一输出
* 修复 soloncode web `/loop` 任务没有用会话选中模型的问题
* 细节优化

### v2026.5.6

* 新增 soloncode web 微信接入
* 添加 soloncode `/loop` cron 表达式支持
* 优化 soloncode acp 输出

```
/loop cron:'0 */5 * * * ? *' check status
```

### v2026.5.5

* 优化 soloncode 启动打印（非 cli 模式，改用 err 通道打印）
* acp-sdk 升为 0.11.0

### v2026.5.4

* 优化 soloncode cli 思考转为灰色
* 优化 soloncode web 取消 thymeleaf 依赖包（缩减 2.5Mb）
* 修复 soloncode mac 安装时，会不断添加新的环境配置记录
* 修复 soloncode 执行恢复命令会出错的问题（v2026.5.3 时出现）

### v2026.5.3

* 添加 soloncode `@agent` 功能

### v2026.5.2

* 优化 soloncode 内置 skill.sh 的 find-skills 专家技能（可以自然语言查找 skill 和安装 skill。先查找再安装）
* 优化 soloncode web 附件上传功能（原附件分为：普通文件，多模态图片文件）
* 优化 soloncode web 语音输入体验，改成微信风格（按住说话，松开结束）
* 优化 soloncode 大模型兼容性（原上下文摘要由系统消息，改为用户消息）
* 调整 soloncode cli 取消回车中断（容易误输）


### v2026.5.1

* 添加 soloncode 心智记忆功能（越用越聪明）

### v2026.4.30

* 新增 soloncode `/loop` 命令支持
* 优化 soloncode `code` skill （增加更多编程语言支持）
* 优化 soloncode 可能意外退出的提示输出

### v2026.4.29

* 添加 soloncode openai baseUrl 配置支持（其它的仍要配置全地址）
* 添加 soloncode commands 命令支持
* 添加 soloncode web 会话缩进支持

### v2026.4.28

* 优化 soloncode web tool-call 打印
* 优化 soloncode cli 安全引导词
* 修复 soloncode 多模态格式拼接错误问题

### v2026.4.27

* 添加 soloncode userAgent 缺省配置（不需要每个 llm 都配置了）
* 优化 soloncode deepseek-v4 适配

### v2026.4.26

* 新增 soloncode web 附件上传功能
* 修复 sololcode 没有加载工作区的 skills 问题（25 引起的）

### v2026.4.25

* 新增 soloncode web 交互界面多模型选择功能
* 新增 soloncode cli 交互界面多模型选择功能

### v2026.4.24

* 新增 soloncode 扩展机制（支持手动配置，与自动装配）
* 其它细节优化

### v2026.4.22

* 新增 soloncode lsp 功能
* 添加 soloncode agentPools（子代理池） 配置支持
* 添加 soloncode chatModel.internalStream 添加流接收超时处理（避免因 llm 服务原因一直卡着）
* 优化 soloncode ExpertSkill 引导词

### v2026.4.20

* 新增 soloncode web 交互模式
* 优化 soloncode-cli 启动时打印主代理模型
* 添加 soloncode 重试次数配置支持
* 添加 新指令 `soloncode web`（默认端口） `soloncode web 1212`（指定端口）
* 调整 soloncode 取消 isCliEnabled, isWebEnabled, isWsEnabled, isAcpEnabled 配置。统一由命令控制：`soloncode server`（启动后端服务，cli 会自动关闭）, 统一由命令控制：`soloncode web`（启动 web 服务）, `soloncode acp`（启动 acp 服务）
* 调整 soloncode-web 合并到 soloncode-cli （作为一个发布包发布）

### v2026.4.18

* 优化 soloncode-cli anthropic, openai-response 方言的异常兼容处理
* 优化 solon-development-skill

### v2026.4.16

* 修复 soloncode-cli 因 snackjson 自动解包引起的部分 llm 不兼容问题

### v2026.4.15

* 优化 soloncode-cli chunk 的打印，不再需要 isNormal 判断了（内部溶进了 ReasonChunk）
* 修复 soloncode-cli 在某些 llm 工具调用时会失败的问题（v2026.4.14 引起的）

### v2026.4.14

* 添加 soloncode-cli 更新检测与提示
* 优化 soloncode-cli agent 日志打印级别
* 修复 soloncode-cli 在 window git bash 环境下 backspace 删除输入文字乱的问题
* 修复 soloncode-cli 因 llm 参数格式问题造成 multitask 工具失败的问题（加了自动修正）
* 其它细节优化

### v2026.4.11

* 添加 soloncode-core subagent 切换模型的能力; 
* 添加 soloncode-core models 配置属性（用于替代 chatModel 单配置）;
* 优化 soloncode-core model 重试条件（改用新的空判断）
* 优化 soloncode-cli ws 通道的输出打印
* 调整 soloncode-core CLAUDE.md 更名为 CODE.md（内部自动生成）
* 调整 soloncode-core restApis 更名为 apiServers；取消 chatModel（由 models 替代）
* 修复 soloncode-core CodeSearchTool 失效的问题（mcp.exa.ai 变了地址）

### v2026.4.6

* 添加 `soloncode --session=test` 指令（启动时指定默认会话id）
* 添加 `soloncode serve` 指令或 `soloncode serve -server.port=4808` 启动无界面服务（作为 http 和 ws 服务）
* 优化 `soloncode-cli` websocket 的消息处理
* 调整 `soloncode-desktop` 更名为 `soloncode-ide`
* 完成 `soloncode-ide` 的上下文功能，通过文件树双击附加上下文
* 取消 `soloncode-ide` 输入框的“#”（由双击取代）和“@”功能（通过自然语言按需调用）
* 取消 `soloncode-ide` 底部栏的 llm 名字显示（由对话框的显示替代）
* 调整 `soloncode` http 端口默认改为：4808（之前是 8080）

### v2026.4.5

* 添加 soloncode-cli pid 打印
* 添加 soloncode-cli skillhub 自动索引
* 优化 soloncode-cli edit 失败时的提示细节
* 优化 java21+ 环境，去除启动时的 System.load() 警告
* 调整 soloncode-cli 日志输出位置到 `.soloncode` 下面（这样，不会产生多余的目录）
* 调整 soloncode-cli TODO 机制，主代理用文件模式（避免开发时冲突，或产生文件太多），次代理用内存模式。次代理定位偏临时性
* 调整 soloncode-core CLAUDE.md 文件位置到 `.soloncode` 下面（这样，不会产生多余的文件）
* 调整 soloncode-core config.yml 和 AGENTS.md 安装位置（从 bin 移到上一级）//与工作区位置相同

### v2026.4.1

* 完成 soloncode-cli “系统命令”化改造（重要）
* 添加 soloncode-code AgentRuntime.createSubagent 方法
* 添加 soloncode-cli `soloncode run xxx` 脚本命令运行支持
* 优化 soloncode-code cwd 动态传递
* 优化 soloncode-code 引入工具网关，解决 mcp 过多时的问题
* 优化 soloncode-web ReActChunk 输出(token 与 sec)
* 调整 soloncode-core 进一步简化代码（合并一部分类）
* 调整 soloncode-cli 取消 init 初始化命令（改为自动了）
* 调整 soloncode-cli 默认会话改为 'default'
* 调整 `solon.code.cli` 配置节，更名为 `soloncode`
* 修复 soloncode-cli 缩放窗口时，User 提示词会多次渲染的问题

### v2026.3.26

* 添加 工作区 skills 目录自动索引
* 优化 explore.md 和 TaskSkill
* 优化 cli 打印添加执行秒数（方便看）
* 移除 ApplyPatchTool，LuceneSkill （减少工具上下文，工件包缩小为：27MB）
* 调整 子代理模式改为弱模式（不再强模式使用，否则会让简单的体务变复杂）
* 调整 子代理增加 code skill 支持

### v2026.3.23

* 优化 子代理模式相关细节（子代理模式下，只做计划与调度）
* 优化 项目结构，成熟的技能转为 `solon-ai-skill-*`
* 优化 上下文摘要压缩处理
* 添加 恢复命令（恢复这前的快照并继续执行）
* 添加 并行子代理支持
* 调整 取消 browser 插件（包可以小100多m，以小优先）
* 调整 取消 agent-team 代码实现，改由自然语言驱动 agent-team（以小优先）
* 调整 general-purpose 更名为 general
* 修复 Windows 下创建中文文件，文件夹及执行中文命令 bat 的乱码问题

### v0.0.23

* 添加 TeamAgent 支持

### v0.0.22

* 添加 KeyInfoExtractionStrategy 处理
* 添加 summaryWindowToken 配置
* 优化 TaskSkill 子代理引导指令

### v0.0.21

* 优化 浏览器能力（不需要下载了，改用系统浏览器的能力）

### v0.0.20

* 添加 子智能体（子代理）流式输出
* 添加 BrowserSkill（浏览器能力。支持通过浏览器测试，或者淘宝买东西）

### v0.0.19

* 添加 沙盒模式对 `~/` （用户根目录）的支持，有些 skill 会要求安装在用户目录下
* 添加 子智能体模式支持（默认启用，可通过配置关闭）

### v0.0.18

* 添加 anthropic 接口兼容支持
* 优化 与 api.minimax.io 平台接口的兼容性

### v0.0.17

* 添加 summaryWindowSize 摘要窗口大小（即，工作上下文压缩时保留几条消息），一般 12 或 15（更吃 token，但保留更多最近交互）
* 添加 sandboxMode 配置。`:true`（沙盒模式，只能访问工作区内的相对路径）, `:false`（开放模式，支持工作区外的绝对路径）
* 添加 thinkPrinted 配置（关闭界面更清爽）
* 优化 CodeSkill 增加三级扫描（之前为二级）

### v0.0.16

* 添加 自动加载工作区下的 `.opencode/skills` 作为 `@opencode_skills` 只读池
* 添加 自动加载工作区下的 `.claude/skills` 作为 `@claude_skills` 只读池
* 优化 TerminalSkill 预置环境变量 `$PYTHON`，`$NODE`
* 优化 TerminalSkill bash 添加超时控制（由 llm 控制），之前只能默认（造成有些脚本执行超时）
* 优化 ExpertSkill skillread 时添加文件在`沙盒`内的别名，并引导使用沙盒别名

### v0.0.15

* 添加 skillPools 配置替代 mountPool 配置（仍可用）
* 添加 TodoSkill（独立出来）
* 添加 AGENTS.md 配置支持
* 优化 CliSkill 拆分为：TerminalSkill + ExpertSkill
* 优化 简化系统提示词，拆散到各工具里
* 调整 工件包 `SolonCodeCLI.jar` 改为 `soloncode-cli.jar`
* 调整 系统目录 `.system` 改为 `.soloncode`（后者更有标识性）
* 调整 配置文件 `cli.yml` 改为 `config.yml`（后都更通用）
* 调整 配置项 `config/nickname` 取消（由 AGENTS.md 替代，更自由全面）
* 调整 配置项 `config/instruction` 取消（由 AGENTS.md 替代，更自由全面）


关于 `AGENTS.md` 的存放位置：

* 放在工作区根目录下，表示工作区内有效
* 放在程序目录下，表示默认（工作区内没有时，会被启用）

关于 `.soloncode` 目录：

* 智能体启动后，工作区根目录会自动创建 `.soloncode` 目录（也可以提前创建）
* `.soloncode/sessoins` 存放会话记录（自动）
* `.soloncode/skills` 存放工作区内技能（手动），技能可以放在此处，也可以外部挂载
* `.soloncode/agents` 预留

### v0.0.14

* 添加 mcpServers 配置支持（支持 mcp 配置）
* 添加 apply_patch 内置工具（支持批量操作文件），替代 diff 工具
* 添加 cli.yaml userAgent 默认配置（用于支持阿里云的 coding plan，它需要 UA） 
* 优化 ssl 处理（方便支持任意证书）
* 优化 codesearch 工具描述（强调是远程查询，避免 llm 错用）
* 优化 init 提示词
* 优化 简化系统提示词
* 优化 取消 ReActAgent 自带的计划模式，改用 TODO.md 纯文件模式（可简化系统提示词）

### v0.0.13

* 添加 codesearch 内置工具
* 添加 websearch 内置工具
* 添加 webfetch 内置工具
* 优化 systemPrompt 引导约束
* 优化 summarizationInterceptor 增加策略机制并内置4个策略
* 修复 ChatModel.stream 过程异常时会破坏流响应的问题
* 修复 ReActAgent.ReasonTask.callWithRetry 网络异常时会中断工作流的问题
* 修复 ReActAgent.stream 流式请求时，可能无法记忆结果内容的问题

### v0.0.12

* 优化 命名（方便画图）
* 修复 HITL 可能会出现2次确认界面的问题

### v0.0.11

* 优化 instruction 机制，开放用户可配置定制