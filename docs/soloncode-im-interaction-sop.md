# SolonCode IM 交互 SOP（微信 / 飞书 / 钉钉）

> 状态：已落地（2026-10）
> 范围：soloncode-cli 的 IM 通道交互链路——入口受理、忙态排队、状态信号、终态投递、文案与国际化
> 读者：后续维护/扩展 IM 通道、调整回执文案、排查"IM 没反应"类问题的人

---

## 1. 背景与设计原则

web 用户能看到：消息入队、排队位次、loading、工具过程卡、错误弹窗。IM 用户此前只能"盲发"——
除微信 typing 外零反馈，排队成功无提示，任务失败可能永久等待。IM 的感知必须靠**主动推送生命周期状态**补齐。

四条原则，所有改动都必须遵守：

1. **IM 只收非流式消息**。IM 的合法入站只有两类：`ReasonEndEvent`（过程消息，只投来源端）与
   `RunEndEvent`（终态，广播所有绑定通道）。禁止把流式 chunk 投给 IM（见 `WebStreamBuilder` 类注释）。
2. **状态信号 ≠ 会话内容**。`ImStatus` 信号不是聊天消息：不进会话历史、不走流式、不产生独立答复，
   只是补感知。宁可少发，不可刷屏。
3. **单点实现、三端一致**。信号由统一出口 `WebStreamBuilder.signalOriginChannel(...)` 下发，
   文案由 `ImMessages` 统一出口；各通道 Link 只做渲染适配与能力降级，**不得**各自发明提示文案。
4. **能力降级**。不支持某能力的通道直接忽略对应信号（如微信忽略 ACCEPTED）。降级不能靠 Link 加逻辑，
   出口已经保证"没有匹配通道就不发"。

---

## 2. 全景图

```
IM 通道（WeChat/Feishu/DingTalk Link）
   │  消息回调（文本）
   ▼
WorkspaceMessageGateway.acceptInput          ← 各 Link 唯一入口（channel → web 的中立端口）
   │
   ▼
WebChatInputHandler.safeChatInput            ← 统一受理关口（忙态判定在这里）
   ├── 空闲：acceptedHook → 发 ImStatus.ACCEPTED → onChatInput 异步开流
   ├── 忙 + 斜杠命令（runnableWhenBusy）：旁路受理，不占启动闩、不改运行任务路由
   ├── 忙 + 普通文本：SessionQueue.enqueue（直发即排队）
   │      ├── 成功 → ImStatus.QUEUED（带位次 + 首条附带命令引导）
   │      └── 失败（队列满）→ ImStatus.REJECTED
   └──
       ▼
   onChatInput → setReplyRoute（写 session.replyRoute / 复位终态去重门）→ 异步执行任务
       │
       ├── 过程（ReasonEndEvent）→ replyPartialToOriginChannel → 只投来源端
       ├── 状态（ImStatus）      → signalOriginChannel          → 只投来源端
       │      └── ACCEPTED 时挂长任务心跳（60s 无终态 → LONG_RUNNING）
       └── 终态（RunEndEvent）   → replyToBoundChannel          → 广播全部绑定通道
              └── 终态去重门 ATTR_IM_TERMINAL_SENT：一轮只落一条 final/error/canceled
                 同时取消心跳、复位命令引导标记、清 replyRoute
```

关键 session attrs（都在 `WebChatInputHandler.setReplyRoute` / `WebStreamBuilder`）：

| attr 键 | 作用 | 写入 / 清除时机 |
|---|---|---|
| `session.replyRoute` | 本轮来源端路由（source/sourceUserId/replyTarget/messageId） | 受理时写；终态或 WEB/Loop 来源时清 |
| `session.im.terminal.sent` | 终态去重门 | 每轮受理复位；终态置位 |
| `session.im.heartbeat` | 长任务心跳 ScheduledFuture | ACCEPTED 挂；终态取消 |
| `session.im.cmdHint.shown` | 命令引导"同轮只教一次"标记 | 首条入队置位；终态/下轮 ACCEPTED 复位 |

---

## 3. 入站：从通道到受理

### 3.1 通道侧职责（Link 层）

1. **绑定校验**：`isBound(sessionId)` 为 false 的消息不进链路，回 `HINT_UNBOUND` 引导（三端统一文案）。
2. **非文本消息**：不能静默丢弃，回 `HINT_NON_TEXT`（微信 `WeChatLink.java:547`、飞书 `:546`、钉钉 `:520`）。
3. **提交入口**：一律走 `gateway.acceptInput(wsContext, sessionId, text, source, sourceUserId, replyTarget, messageId)`。
   微信传 `"WeChat"` + context_token 作为 replyTarget；飞书/钉钉传 `"Feishu"`/`"DingTalk"` + openId/userId + messageId。
4. **消息网关判空**：`wsContext.getMessageGateway() == null` 时（消息入口仅在 web 模式初始化）记 warn 并丢弃，
   防止 NPE 静默吞消息（三端一致，见 `WeChatLink.dispatchToAgent` 注释）。
5. **微信特有**：
   - 无 context_token 直接丢弃（无法回复，处理只会"已读不回"）。
   - 受理成功后 `startTypingKeeper` 持续 typing，直到最终回复发出。
   - 凭据过期走 `notifyExpired` → `emitErrorAndDone`。

### 3.2 受理关口（`safeChatInput`，`WebChatInputHandler.java:450`）

判定顺序（同一把 session 锁内）：

```
isBusy(session)?
├── 是 + input 是 "/" 开头
│      查 CommandRegistry：command.runnableWhenBusy() && !command.cliOnly()
│      ├── 是 → 旁路受理（onChatInput，不占启动闩、不改运行任务的 replyRoute）
│      └── 否 → 落到普通输入处理（即排队）
├── 是 + 普通文本
│      SessionQueue.bindStorage → enqueue
│      ├── position >= 0 → emitUserInput + ImStatus.QUEUED(position-1) + drainSessionQueue
│      └── position < 0（队列满/落盘失败，上限 20 条）→ ImStatus.REJECTED
└── 否（空闲）
       acceptedHook（先于异步调度执行，防快速任务先输出）
       → emitUserInput
       → 非斜杠命令时发 ImStatus.ACCEPTED（斜杠命令自带回执，不重复提示）
       → onChatInput
```

要点：

- **`enqueue` 返回值是队列长度（1-based），不是位次**。本项位居队尾，"前面还有 N 条" = `position - 1`
  （`WebChatInputHandler.java:479` 注释）。失败返回 -1。
- **受理成功的回执顺序**：先 `emitUserInput`（用户消息上屏）再发状态信号，最后才异步开流——保证顺序与用户感知一致。
- **IM 忙态默认语义是排队，不是插话**。这是与 web 的刻意差异（web 前端流式中 Enter 默认 steer），
  理由见 §5.3；用户教育与入口补齐靠 QUEUED 回执中的命令引导，而不是改默认。
- `onChatInput`（带附件/模型等完整参数的路径，`:108-144`）里也有一个忙态排队分支，逻辑同上但参数更全
  （可携带 selectedModel 等），并额外处理 `session.queue.executing` 的路由改写。两个入口殊途同归。

---

## 4. 出站：信号与终态

### 4.1  两个出口，一张表看全

| 出口 | 投递范围 | 触发事件 | 通道实现 |
|---|---|---|---|
| `signalOriginChannel` | 仅来源端 | ImStatus 状态信号 | `sendStatus(...)` |
| `replyToBoundChannel` | 全部绑定通道 | RunEndEvent（final/error/canceled） | `sendReply(isFinal=true)` |

- **信号只投来源端**：匹配规则是 `route.source` 等于 Link 的 channelName（忽略大小写）；WEB/Loop 发起时
  route 为空 / 不匹配任何 IM，自然不下发——这就是"降级不靠 Link 加逻辑"的实现方式。
- **终态必须全端一致**：web 与 IM 是同一 session 的等价输出端。来源通道带定向参数（群聊/线程回复），
  其它通道退回绑定用户（binding.openId）。
- **终态去重门**：`ATTR_IM_TERMINAL_SENT`，final/error/canceled 共用，保证 IM 每轮只收一条终态。
  门在 `setReplyRoute`（每次受理）复位；`replyErrorToBoundChannels`、`replyCanceledToBoundChannels`、
  两处 `doOnError` 都最终汇入 `replyToBoundChannel(isFinal=true)`，不会绕过。
- **命令回执**：忙态命令用 `replyToBoundChannel(..., source, ...)` 重载，自带定向参数，
  **不消费也不覆盖**运行任务的路由（`WebChatInputHandler.java:427-429`）。

### 4.2 ImStatus 信号清单（最终实现）

| 状态 | 触发点 | 默认文案（zh） | 微信 | 飞书 | 钉钉 |
|---|---|---|---|---|---|
| ACCEPTED | 空闲受理成功（非命令） | 收到，马上开始处理 | 忽略（typing 已表达） | hint | hint |
| QUEUED | 忙态入队成功（含位次） | 收到，已作为新任务排队，前面还有 N 条，处理完就轮到你。〔命令引导〕 | 文本 | hint | hint |
| LONG_RUNNING | 受理 60s 无终态（一次性） | 还在处理中，请再稍等一下 | 文本 | hint | hint |
| REJECTED | 入队失败（队列满） | 还有任务没忙完，暂时接不了新的。想立刻处理，可发送 /interrupt 中断当前任务 | 文本 | hint | hint |

通道侧实现（均为非流式文本，见 `WeChatLink.java:701` / `FeishuLink.java:136` / `DingTalkLink.java:144`）：

- 三端共同的收件人解析：`replyTarget > sourceUserId > binding 默认`（`replyBinding` 快照固定凭据，
  防止异步任务读到后来变更的绑定）。
- 微信：无 context_token（replyTarget）时发不出去，`warnNoReplyTarget` 后放弃——这是通道能力边界，
  不是 bug。
- `ImMessages.textOf(status, detail)`：detail 非空优先 detail，QUEUED 必须由调用方传 detail（位次），
  其余状态 detail 为空时用默认文案；返回 null 表示该状态无需文本。

### 4.3 心跳与"同轮只教一次"

- **长任务心跳**：`signalOriginChannel` 收到 ACCEPTED 即挂 60s 一次性 `ScheduledFuture`
  （存 `session.im.heartbeat`），到点发 LONG_RUNNING。终态投递（`replyToBoundChannel` isFinal 分支）
  统一取消——final/error/canceled 任一终态到达就停，不会重复提醒。
- **命令引导节流**：`claimCommandHint(session)` 首次 true 后置位；连发多条只随第一条入队回执附
  `/steer` `/interrupt` 引导，后续只报位次。标记在本轮终态与下一轮 ACCEPTED 两个时点复位，
  语义是"每个忙态周期最多教学一次"。

---

## 5. 忙态语义：排队 vs 插话（决策记录）

### 5.1 两端默认不同的真相

- **web 默认插话**：会话流式中 Enter 走 `steerMessage`（POST /web/chat/steer），这是**前端决定的**，
  不经过 `safeChatInput` 忙态分支；仅附件降级排队。
- **IM 默认排队**：IM 没有前端，落到后端兜底（`safeChatInput` 忙态 → enqueue）。

两者语义差异很大，不是同一动作的两种叫法：

| 维度 | 插话 /steer | 排队（忙态直发） |
|---|---|---|
| 作用对象 | 注入**当前任务**工作记忆，改走向 | **新任务**排在当前任务之后 |
| 持久化 | 零持久化（SteerInterceptor 不写 ndjson） | 落盘（SessionQueueStore，上限 20） |
| 独立答复 | 无（只在原任务流里显示 steer-note） | 有（独立 RunEndEvent） |
| 生效时机 | 下个采样边界（延迟） | 当前任务结束后 |

### 5.2 为什么 IM 不改成默认插话

1. IM 用户期待"一问一答"；插话不产生独立答复，用户会一直等不到回复。
2. IM 连发多条多半期待每条都被处理；默认插话会让第二条悄悄变成对第一条的补充。
3. IM 缺插话反馈出口：`steer_applied`/`steer_dropped` 只走 web WebSocket（`emitSteerApplied`），
   IM 渲染不了 steer-note，默认插话等于黑箱。（走 `/steer` 命令时 IM **有**回执，所以命令路径是可感知的。）

### 5.3 落地策略：不改默认，改"默认的可见性"

入队回执主动点明"已作为新任务排队"，消除"以为自己在插话"的误解，同时给出插话入口：

```
收到，已作为新任务排队，前面还有 N 条，处理完就轮到你。
想补充或调整当前任务，可发送 /steer <内容>；想中断当前任务，可发送 /interrupt
```

**刻意不引导 `/queue`**：IM 忙态直发即排队，再教显式排队命令会让用户以为"不敲命令消息就丢"。
此决策已写入 `ImMessages.BUSY_COMMAND_HINT` 注释，防止后人好心加回去。

### 5.4 IM 可用的忙态命令（runnableWhenBusy 白名单）

- `/steer <内容>`：插话当前任务（IM 缺插话途径的能力补位，命令自带回执"已插话，将在下一步注入当前任务"）。
- `/interrupt`：中断当前任务。
- `/queue`、`/exit`、`/help` 等也可穿透，但对 IM 是冗余的（直发即排队）。
- `cliOnly` 命令（如 `/model`）仅对 web 拦截、对 IM 放行与否见 `isCommand`；命令回执含 ANSI 的在 IM 边界 `stripAnsi`。

---

## 6. 文案与国际化（ImMessages）

### 6.1 架构

- 资源包：`soloncode-cli/src/main/resources/i18n/im-messages.properties`（默认语言=简体中文）+
  `im-messages_en.properties`（英文覆盖）。依赖 `solon-i18n`（版本由 solon-parent 管理）。
- solon bundle 约定：无后缀文件即默认语言，`_{lang}` / `_{lang}_{country}` 逐层覆盖；
  未提供文件的语言（如 fr）回落默认（中文）。
- 默认语言放中文是刻意的：它同时是所有未覆盖地区的兜底，发布即零行为回归，与前端 DEFAULT_LOCALE=zh-CN 一致。
- 地区解析优先级：`ImMessages.setLocale` 显式指定 > `Solon.cfg().locale()`（读 solon.locale）> JVM 默认。
  `setLocale(null)` 恢复运行时解析。测试用 `setLocale` 固定语言。

### 6.2 三条实现红线（踩过的坑）

1. **显式传 Locale，不用线程上下文**：`I18nUtil.getMessage` 依赖 `Context.current()` 非空，IM 异步路径上
   常为 null；`I18nBundle.get(key)` 缺键抛 `MissingResourceException`。所以一律
   `I18nUtil.getBundle(BUNDLE_NAME, locale)` + try/catch。
2. **内嵌 FALLBACK Map**：资源包缺失/加载失败时仍输出可读中文，绝不让资源键或占位符泄漏给用户。
   **增删资源键必须同步 FALLBACK**。
3. **MessageFormat 陷阱**：单引号是转义符——英文文案全程避开撇号（you're → you are）；
   数字以字符串传入避免千分位；占位符被空串替换后 `trim()` 去残留空白。

### 6.3 维护规约（新增/修改文案的标准动作）

1. 改 `i18n/im-messages.properties`（默认）与 `_en`（英文）两个文件。
2. 同步 `ImMessages.FALLBACK` 与 `ImMessages` 中的 key 常量。
3. 公开成员是**方法**不是常量（名字与历史常量一致，调用点补括号）：`ImMessages.REJECTED()`。
4. 文案风格：第一人称、口语、短句、不用「队列/位次/阈值」等系统术语；回答用户"我这条消息怎么样了"。
5. 测试断言用 `ImMessages.XXX()` 取值而不是字面量，避免文案微调连坐测试
   （`WeChatLinkBindingTest.rejectedStatusSendsUnifiedHint` 已示范）。

### 6.4 当前文案全集（默认语言）

| key | 文案 |
|---|---|
| im.accepted | 收到，马上开始处理 |
| im.longRunning | 还在处理中，请再稍等一下 |
| im.rejected | 还有任务没忙完，暂时接不了新的。想立刻处理，可发送 /interrupt 中断当前任务 |
| im.hint.unbound | 还没有绑定会话，请先在 Web 端扫码绑定，然后我就能陪你聊了。 |
| im.hint.nonText | 我暂时只看得懂文字，换文字发给我吧。 |
| im.hint.steer | 想补充或调整当前任务，可发送 /steer <内容> |
| im.hint.interrupt | 想中断当前任务，可发送 /interrupt |
| im.hint.busyCommand | {0}；{1}（steer + interrupt 组合） |
| im.queued.behind | 收到，已作为新任务排队，前面还有 {1} 条，处理完就轮到你。{0} |
| im.queued.immediate | 收到，已作为新任务排队，马上轮到你了。{0} |

---

## 7. 排队与调度补充（与 IM 直接相关的部分）

- 队列上限 `SessionQueue.MAX_QUEUE_SIZE = 20`，超限 enqueue 返回 -1 → REJECTED。
- 入队成功后立即 `drainSessionQueue`：当前任务空闲即刻执行队首，不等待额外触发。
- 队列项持久化到 session 路径（`SessionQueueStore`）；drainer 出队执行时用队列项里保存的
  source/sourceUserId/replyTarget/messageId 重设 `replyRoute`（`WebChatInputHandler.java:139`）——
  **排队消息的终态会回到当初入队的那一端**。
- 未消费的插话（steer box）在任务结束时若入队失败，留在易失邮箱等待下次开流重试，并 emitError 告知
  （`WebGate.handleDroppedSteers`）。

---

## 8. 排查手册（"IM 没反应"怎么查）

按链路顺序排查，每步都有明确的"看不到什么→查哪里"：

1. **消息根本没进来**：Link 日志 `[WeChat]/[Feishu]/[DingTalk] Received`。没有 → 查绑定是否过期
   （isBound）、微信 context_token 是否为空（为空直接丢）、消息网关是否初始化（web 模式才有）。
2. **进来了没回执**：`safeChatInput` 忙态分支——队列满会打 `[WebGate] event could not be queued`
   并发 REJECTED；若用户连 REJECTED 都没收到，查该端是否支持无 replyTarget 的主动推送（微信不支持）。
3. **排队后不执行**：`WebQueueDispatcher` 日志；`session.queue.executing` attr 是否残留（残留会阻塞 drain）。
4. **终态没到 IM**：终态去重门是否被上一轮置位未复位（受理时 `setReplyRoute` 会复位，若异常路径没走到
   setReplyRoute 就会卡）；`replyRoute` 是否被清（WEB/Loop 来源发起的轮次，终态广播走 binding 兜底）。
5. **收到两条终态/重复提示**：检查是否绕过了统一出口（任何直接调 `link.sendReply` 的新代码都是嫌疑）。
6. **文案不对/出现资源键**：查 `ImMessages` 是否漏同步 FALLBACK、properties 是否打进 classpath
   （`messagesResolveByLocale` 用例会红）。
7. **中文用户收到英文**：`setLocale` 是否被某处误设；solon.locale 配置；JVM 默认地区。

---

## 9. 新增 IM 通道接入清单（SOP）

实现 `Channel` 接口（`channel/Channel.java`）并注册到 ChannelHub：

1. `isBound(sessionId)`：绑定判断；未绑定的入站消息回 `ImMessages.HINT_UNBOUND()`。
2. `sendReply(...)` 两重载：终态/过程文本。**isFinal=true 仅在终态时出现**，通道可据此做收尾
   （如微信停 typing）。
3. `sendStatus(...)`：状态信号渲染。评估通道能力后决定哪些状态忽略（参照微信忽略 ACCEPTED）。
4. 入站消息 → `gateway.acceptInput(...)`，source 用通道名（与 `isImSource` 白名单对齐，见
   `WebChatInputHandler.java:672`：wechat/feishu/dingtalk——**新增通道要同步这个白名单**，
   否则 replyRoute 不会被设置，终态只能走 binding 兜底）。
5. 非文本消息回 `ImMessages.HINT_NON_TEXT()`，不静默丢弃。
6. 文案一律取 `ImMessages`，禁止通道内自定义提示文案。
7. 回执文本经过 IM 边界时 `stripAnsi`（命令回执可能带 ANSI）。

---

## 10. 已知边界与未做项（截至本文档落笔）

- **队列位次前进不实时刷新**：只在入队时报一次位次（QUEUE_PROGRESS 未实现，刻意——防刷屏）。
- **飞书/钉钉无可更新消息**：accepted→running→final 仍是多条独立消息（v2 可选优化，需新增 API）。
- **/steer 的 IM 反馈缺口**：命令回执有，但 steer_applied/dropped 事件不会投 IM（只走 web WS）。
  若将来 IM 默认插话，必须先补此反馈，否则黑箱。
- **per-user locale**：i18n 目前按全局配置解析；将来拿到渠道侧语言（如飞书用户 locale）时接入
  `setLocale` 或加 Locale 重载，解析逻辑已预留。
- **zh_TW**：会拿到简体（`_zh` 层继承，无繁体文件）。
- **AgentSettingsControllerTest.builtinStillReadableAfterOverrideFileExists**：settings 域既有失败
  （期望 200 实得 400），与本链路无关，待另行处理。

### 10.1 绑定归属协议（2026-10-04 事故修复，必读）

**事故**：用户在 web 端有任务运行中，飞书发消息却收到「收到，马上开始处理」+「模型服务响应出错（CODE: 404）」。

**根因**：`<x>-bindings.json` 是全局单文件，历史版本无工作区归属；多工作区各自的 Link 会为同一
appId 各建一条 WS 连接，飞书服务端**随机路由**消息。落到错误工作区时在那里凭空
`computeIfAbsent` 出空 session：

1. 空会话不忙 → 走空闲分支 → 回 ACCEPTED（即「马上开始处理」）；
2. 空 context 无 `CTX_MODEL_SELECTED` → 回落错误工作区默认模型（供应商 404）。

一次误投，两个症状。

**微信侧的风险形态不同但更隐蔽**：长轮询模型下，两个工作区用同一 botToken 各起一条长轮询，
同一条消息会被两个工作区**重复处理（重复回复）**、游标互相覆盖回退；重启后旧游标还会重复消费。

**协议（铁律）**：
   
1. 绑定必须携带 `workspaceId` 归属字段（`FeishuBinding`/`DingTalkBinding`/`WeChatBinding`）；
   `bindSession` 打标。
2. `loadBindings` 只装载归属本工作区的条目；遗留无归属条目按 sessions 目录探测认领（存在即归我），
   认领后回写归属并持久化。微信侧 `ownsBinding` 在 wsContext 为空（测试）时不认领无主条目。
3. **合并持久化**：save 先 load 全局文件、只删自己的无主条目、再覆盖自己的绑定。严禁整文件覆盖
   （旧实现会把其它工作区的绑定抹掉，重启即丢）。实现细节：
   - `load()` 文件不存在时返回 `Collections.emptyMap()`（不可变），必须 `new LinkedHashMap<>(load())`
     包装后才能 putAll——三个 store 都曾踩过这个坑（新用户首次绑定即 UnsupportedOperationException）；
   - store 自身未指定 workspaceId（测试或旧路径）时 `isMine` 返回 true（不过滤、可全量管理），
     与字段注释「null 表示不做工作区过滤（兼容旧行为）」对齐。
4. 连接只为本工作区拥有的 appId/appKey/botToken 建立（`run()` 遍历的已是过滤后的 bindings，天然满足）。
5. `isAppInUse` 的跨工作区冲突检查仍然保留，作为第二道防线（`WebChannel` 绑定时调用）。

**验证**：`FeishuBindingOwnershipTest`、`WeChatBindingOwnershipTest`（合并不互删、只删自己的、
无主不被他人认领，两渠道同构）。

### 10.2 模型选择写入守卫

会话级 `CTX_MODEL_SELECTED` 只在输入**显式携带模型**时写入；未携带时保持既有选择，
禁止 null 覆盖（否则后续轮次回落默认模型）。web 侧 `WebChatInputHandler:147` 原有守卫，
2026-10-04 补齐 desktop 侧 `DesktopInputRouter:199` 同款守卫。

---

## 11. 关键代码索引

| 职责                     | 位置                                                                                                                                               |
|--------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| 统一受理关口             | `api/web/WebChatInputHandler.java` — `safeChatInput`(:450)、`onChatInput`(:108)、`setReplyRoute`(:677)、`isImSource`(:672)                         |
| 两出口 + 心跳 + 引导节流 | `api/web/WebStreamBuilder.java` — `signalOriginChannel`(:194)、`replyToBoundChannel`(:77)、`claimCommandHint`(:220)、`startLongRunningWatch`(:237) |
| 状态枚举 / 文案出口      | `channel/ImStatus.java`、`channel/ImMessages.java`                                                                                                 |
| 通道抽象                 | `channel/Channel.java`（`sendStatus` 为 default 空实现，新通道不实现也不会编译失败）                                                               |
| 三端实现                 | `channel/wechat/WeChatLink.java`、`channel/feishu/FeishuLink.java`、`channel/dingtalk/DingTalkLink.java`                                           |
| 队列                     | `session/queue/SessionQueue.java`（MAX_QUEUE_SIZE=20、enqueue 返回 1-based 长度）、`SessionQueueStore.java`、`SessionQueueDrainer.java`            |
| 忙态命令                 | `command/builtin/SteerCommand.java`、`InterruptCommand.java`、`QueueCommand.java`                                                                  |
| i18n 资源                | `src/main/resources/i18n/im-messages.properties`、`im-messages_en.properties`                                                                      |
| 入口端口                 | `api/web/WebGate.java` — `acceptInput`(:136)、`emitSteerApplied`(:157)、`handleDroppedSteers`(:163)                                                |
| 测试                     | `api/web/WebStreamBuilderReplyRouteTest.java`（信号仅投来源端、心跳、引导节流、i18n）、`channel/wechat/WeChatLinkBindingTest.java`（Link 侧契约）  |

---

## 12. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-10 | 初版：整理 IM 感知优化方案的全部落地成果（状态信号、文案收敛、i18n、忙态语义决策） |
| 2026-10-04 | 新增 §10.1 绑定归属协议（多工作区双连接随机路由事故修复）与 §10.2 模型选择写入守卫；Feishu/DingTalk 绑定增加 workspaceId 归属、合并持久化；`DesktopInputRouter` 补模型选择非空守卫 |
| 2026-10-04 | §10.1 协议覆盖微信通道（长轮询重复消费风险）；修复三个 store 的 `emptyMap()` 不可变陷阱与 null 归属不过滤语义；`WeChatLink` 修正两处过时的「ChannelHub.start 无人调用」注释（实际已由 WorkspaceManager 拉起）；新增 `WeChatBindingOwnershipTest` |
