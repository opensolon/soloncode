# SolonCode Agent Event Protocol 2.0 (SAEP 2.0) 技术规范说明书

## 1. 规范概述 (Overview)

SolonCode Agent Event Protocol (SAEP 2.0) 是 SolonCode 体系下用于 **Agent 运行时（Backend）与客户端展示层（Web / Desktop / Third-party Client）** 之间流式事件交互的开放通信规范。

本规范定义了标准事件信封结构、事件分类命名空间、各类事件的强类型 Payload 模型以及客户端分发与渲染建议，旨在提供统一、高扩展、易消费的 Agent 交互体验。

---

## 2. 传输与信封格式 (Protocol Envelope)

所有 Agent 事件均以 JSON 格式在传输层（如 WebSocket 文本帧、SSE、标准输出流）进行推送。每个事件必须包含统一的外层信封。

### 2.1 信封结构定义

```json
{
  "event": "message.delta",
  "sessionId": "sess-user-1001",
  "runId": "run-63f82a9c",
  "taskId": "task-abc-123",
  "reasonId": "reason-7f01",
  "agentName": "general",
  "timestamp": 1771123456789,
  "payload": { ... }
}
```

### 2.2 信封通用字段说明

| 字段名 | 类型 | 必填 | 说明 |
| :--- | :--- | :---: | :--- |
| `event` | String | **是** | 事件类型全称，采用 `namespace.action` 点分格式（见下表） |
| `sessionId` | String | 否 | 会话唯一标识 |
| `runId` | String | 否 | 单次任务运行的跟踪 ID，对应底层 `AgentEvent` 的 runId（子任务事件填其父 runId） |
| `taskId` | String | 否 | 子任务 ID（可选）：有值代表该事件归属某个 task 组（子代理任务） |
| `reasonId` | String | 否 | 推理轮次标识（可选）：同一 `reasonId` 的思考 / 正文 / 工具事件归属同一 reason 组，前端据此将同一轮输出分组，避免后一轮最终消息错接到前一轮分组 |
| `agentName` | String | 否 | 当前产生事件的 Agent 名称（如 `general`, `explore`, `plan` 等） |
| `timestamp` | Long | **是** | 事件生成时的毫秒级时间戳 (Unix Epoch ms) |
| `payload` | Object | **是** | 与特定 `event` 绑定的业务载荷数据 |

### 2.3 分组归属模型（taskId / reasonId）

信封通过三级标识描述事件的展示归属：

- **子代理任务**：`taskId` 有值（对应一个 task 组）+ `reasonId` 有值（对应一个 reason 组）。
- **主代理任务**：`taskId` 无值 + `reasonId` 有值（对应一个 reason 组）。
- 一个 reason 组下可能包含：**思考信息**（`thought.delta`）、**文本信息 / 答案输出**（`message.delta`）、**工具信息**（`tool.start` / `tool.end`）。这些事件共享同一 `reasonId`。

---

## 3. 事件命名空间与 Payload 字典 (Event Catalog)

SAEP 2.0 将事件清晰划分为 6 大核心领域：

```
SAEP 2.0 Event System
 ├── message.*   (模型消息与流式输出)
 ├── thought.*   (深度推理与思考过程)
 ├── tool.*      (工具调用与执行反馈)
 ├── task.*      (任务生命周期)
 ├── hitl.*      (人机交互干预)
 └── system.*    (系统级事件与流控制)
```

---

### 3.1 消息类 (Message)

#### `message.delta` (正文流式增量)
LLM 生成正文时的实时增量文本片段。正文内容在 `delta` 字段；事件信封携带 `reasonId`，前端据此将同一轮正文归组。
```json
{
  "event": "message.delta",
  "reasonId": "reason-7f01",
  "payload": {
    "delta": "你好，这是 Agent 的流式回复增量..."
  }
}
```

> 注：`MessagePayload` 保留了 `content` 字段作为完整文本的预留位，当前后端仅通过 `delta` 推送流式增量（无独立的 `message.full` 事件）。

---

### 3.2 思考推理类 (Thought)

#### `thought.delta` (思考增量)
支持 DeepSeek-R1 / OpenAI Reasoning 等思考模型的流式增量推理内容。思考内容在 `delta` 字段，与同轮正文共享信封 `reasonId`。
```json
{
  "event": "thought.delta",
  "reasonId": "reason-7f01",
  "payload": {
    "delta": "正在分析当前工程目录结构..."
  }
}
```

> 思考块的收尾不依赖独立的 `thought.full` / `thought.end` 事件（两者后端均不产出），而是由后续正文到达或 `system.*` 收尾事件驱动前端关闭。

---

### 3.3 工具调用类 (Tool)

#### `tool.start` (工具调用开始)
Agent 发起外部工具调用，包含入参。`args` 为结构化入参对象（非 JSON 字符串）；`title` 为展示用标题（子代理场景下为 `agentName/toolName`）。信封携带 `reasonId`。
```json
{
  "event": "tool.start",
  "reasonId": "reason-7f01",
  "payload": {
    "callId": "call_12345",
    "name": "bash",
    "title": "bash",
    "args": { "command": "git status" }
  }
}
```

#### `tool.end` (工具执行结束)
工具调用完成，返回执行结果与状态。`isError` 标识是否异常；`diff` 为展示层（ToolPresentationFilter）为文件修改类工具注入的可选 Diff 文本。信封携带 `reasonId`。
```json
{
  "event": "tool.end",
  "reasonId": "reason-7f01",
  "payload": {
    "callId": "call_12345",
    "name": "bash",
    "title": "bash",
    "result": "On branch main\nnothing to commit, working tree clean",
    "isError": false,
    "diff": null,
    "args": { "command": "git status" }
  }
}
```

---

### 3.4 任务生命周期类 (Task)

> `task.start` 仅定义了常量，当前后端映射器并未产出该事件（子代理 task 组由首条携带 `taskId` 的事件隐式开启）。

#### `task.done` (子代理任务完成)
子代理 ReAct 运行结束时发出，用于前端结算对应的 task 组。`status` 取 `done` / `error`；`isMultitask` 标识是否属于并行多任务。

> 关键：只有子代理（`taskId != null`）才发 `task.done`；主代理整轮结束发的是 `system.trace`。两者在映射器里互斥，以避免“子代理任务完成后也输出 trace”。
```json
{
  "event": "task.done",
  "taskId": "task-build-1",
  "payload": {
    "taskId": "task-build-1",
    "parentTaskId": "run-root",
    "title": "重构用户认证逻辑",
    "status": "done",
    "isMultitask": false
  }
}
```

---

### 3.5 人机协同干预类 (HITL - Human in the Loop)

#### `hitl.pending` (等待人工确认/输入)
Agent 遇到高危操作或需要用户决策，阻塞流并请求客户端干预。每一个待确认工具调用产出一条 `hitl.pending`；`callId` 用于后续回传用户决策。
```json
{
  "event": "hitl.pending",
  "payload": {
    "callId": "call-uuid-88",
    "toolName": "bash",
    "toolTitle": "bash",
    "args": { "command": "rm -rf ./dist" },
    "command": "rm -rf ./dist",
    "comment": "检测到危险指令，是否允许执行？"
  }
}
```

> `hitl.resolved` 常量已定义，供客户端 / 后续链路回报决策结果使用；当前映射器未主动产出。

---

### 3.6 系统控制与状态类 (System)

#### `system.trace` (主代理整轮收尾统计)
主代理整轮 ReAct 结束时发出（仅主代理，`taskId == null`），携带模型、Token 消耗、耗时与最终答案。
```json
{
  "event": "system.trace",
  "payload": {
    "model": "glm-4.6",
    "totalTokens": 1370,
    "inputTokens": 1050,
    "outputTokens": 320,
    "elapsedSeconds": 12,
    "finalAnswer": "已完成主代理本轮任务。"
  }
}
```

#### `system.context` (上下文容量状态)
同步当前上下文 Token 占用与消息数，驱动前端 Context 状态条。`tokens` 当前占用、`count` 消息条数、`contextLimit` 上下文窗口（默认 128000）、`cacheRate` 缓存命中率（%，驱动 "Cache: N%" 指示）。
```json
{
  "event": "system.context",
  "payload": {
    "tokens": 8600,
    "count": 24,
    "contextLimit": 128000,
    "cacheRate": 42
  }
}
```

#### `system.filer_change` (文件变动通知)
文件监听服务检测到文件修改时触发，用于客户端刷新文件树或 Diff 视图。必须采用标准 SAEP 2.0 信封（`event` + `payload`），旧格式 `{"type":"filer_change"}` 已废弃（前端分发器会因缺 `event` 字段丢弃）。
```json
{
  "event": "system.filer_change",
  "payload": {
    "path": "src/main/resources/app.yml",
    "changeType": "modify"
  }
}
```

#### `system.user_input` (用户输入回显)
用于在流式通道中回显用户的问题输入（多渠道场景）。`source` 为来源标识（渠道侧直接传官方英文名 WeChat/Feishu/DingTalk/Loop，另有 steer 等内部来源），`sourceLabel` 为其展示标签——直通 `source`，仅空值归一为 `Web`；`images` 为可选图片附件。服务端不做本地化，前端原样展示、不走 i18n。
```json
{
  "event": "system.user_input",
  "payload": {
    "text": "帮我优化一下数据库查询",
    "source": "feishu",
    "sourceLabel": "Feishu",
    "images": []
  }
}
```

#### `system.command` (客户端指令触发)
后端向前端发出的 UI 控制指令（如清理控制台、重置视图）。
```json
{
  "event": "system.command",
  "payload": {
    "command": "clear_console"
  }
}
```

#### `system.rewind` (会话回滚指令)
告知客户端回滚最近 `count` 条消息（用于重试 / 编辑重发场景）。
```json
{
  "event": "system.rewind",
  "payload": {
    "count": 2
  }
}
```

#### `system.error` (错误/异常事件)
执行过程中发生的错误，提供机器可读的 `code` 与友好提示 `message`。
```json
{
  "event": "system.error",
  "payload": {
    "code": "AUTH_FAILED",
    "message": "模型 API Key 无效或已欠费，请检查配置"
  }
}
```

**常见错误码定义 (Error Codes)：**
- `AUTH_FAILED`: API Key 鉴权失败或配额耗尽
- `RATE_LIMIT`: 触发模型提供方频率限制
- `TIMEOUT`: 工具或网络请求超时
- `BAD_REQUEST`: 参数格式不合法或上下文超长
- `EXEC_FAILED`: 本地工具执行失败
- `INTERNAL_ERROR`: 未知系统异常

#### `system.reset` (流重置)
告知客户端重置当前流式输出状态（无 payload）。
```json
{ "event": "system.reset", "payload": null }
```

#### `system.done` (整体交互完成)
告知客户端本次流式交互已结束（无 payload）。
```json
{ "event": "system.done", "payload": null }
```

---

### 3.7 事件总览表 (Event Summary)

| 事件名 | Payload 字段 | 后端是否产出 |
| :--- | :--- | :---: |
| `message.delta` | `delta`, `content`(预留) | ✅ |
| `thought.delta` | `delta` | ✅ |
| `tool.start` | `callId`, `name`, `title`, `args` | ✅ |
| `tool.end` | `callId`, `name`, `title`, `result`, `isError`, `diff`, `args` | ✅ |
| `task.start` | — | ❌（仅常量） |
| `task.done` | `taskId`, `parentTaskId`, `title`, `status`, `isMultitask` | ✅（仅子代理） |
| `hitl.pending` | `callId`, `toolName`, `toolTitle`, `args`, `command`, `comment` | ✅ |
| `hitl.resolved` | — | ❌（仅常量） |
| `system.trace` | `model`, `totalTokens`, `inputTokens`, `outputTokens`, `elapsedSeconds`, `finalAnswer` | ✅（仅主代理） |
| `system.context` | `tokens`, `count`, `contextLimit`, `cacheRate` | ✅ |
| `system.filer_change` | `path`, `changeType` | ✅ |
| `system.user_input` | `text`, `source`, `sourceLabel`, `images` | ✅ |
| `system.command` | `command` | ✅ |
| `system.rewind` | `count` | ✅ |
| `system.reset` | — | ✅ |
| `system.done` | — | ✅ |
| `system.error` | `code`, `message` | ✅ |

> 已移除：`message.full` / `thought.full` / `thought.end` 在当前实现中从不产出，不属于 SAEP 2.0 有效事件集。

---

## 4. 架构与管道处理模型 (Backend Pipeline)

在 Solon 框架与 Reactor 响应式体系下，后端通过清晰的 Pipeline 处理 Agent 事件流：

```
[Agent Engine Raw Events]
         │
         ▼
 1. WebEventMapper (纯事件映射，转换为 WebEvent<T>)
         │
         ▼
 2. ToolPresentationFilter (工具展示层修饰，如 Git Diff、Todo 高亮)
         │
         ▼
 3. ChannelBroadcastSink (多渠道可选广播，如飞书/钉钉 IM 同步)
         │
         ▼
 4. SessionMetricsRecorder (Token 消耗统计与会话审计)
         │
         ▼
[WebSocket / SSE 客户端推送]
```

### 4.1 管道装配参考实现 (Java)

```java
public Flux<WebEvent<?>> build(Flux<AgentEvent> engineEventFlux, HarnessEngine engine, AgentSession session, ChatModel chatModel) {
    // WebEventMapper.mapEvent(AgentEvent) 返回 List<WebEvent<?>>（一个底层事件可展开为 0..N 个 WebEvent），故用 flatMapIterable 展平
    WebEventMapper mapper = new WebEventMapper(engine, session, chatModel);
    ToolPresentationFilter toolFilter = new ToolPresentationFilter(session);
    ChannelBroadcastSink broadcastSink = new ChannelBroadcastSink(session);
    SessionMetricsRecorder metricsRecorder = new SessionMetricsRecorder(session);

    return engineEventFlux
        .flatMapIterable(mapper::mapEvent)
        .filter(WebEvent::isNotEmpty)
        .map(toolFilter::decorate)
        .doOnNext(broadcastSink::broadcast)
        .doOnNext(metricsRecorder::record)
        .onErrorResume(err -> Flux.just(WebEvent.ofError(err)));
}
```

---

## 5. 客户端集成指南 (Client Integration Guide)

### 5.1 事件分发器推荐实现 (JavaScript/TypeScript)

客户端推荐通过注册表模式实现解耦分发：

```javascript
class AgentEventDispatcher {
    constructor() {
        this.handlers = new Map();
    }

    on(event, handler) {
        this.handlers.set(event, handler);
        return this;
    }

    dispatch(eventObj) {
        if (!eventObj || !eventObj.event) return;
        const handler = this.handlers.get(eventObj.event);
        if (handler) {
            handler(eventObj.payload, eventObj);
        } else {
            console.debug(`[SAEP] Unhandled event: ${eventObj.event}`, eventObj);
        }
    }
}
```

### 5.2 消费示例

```javascript
const dispatcher = new AgentEventDispatcher();

// handler 第二参 evt 为完整信封，可取 reasonId / taskId / runId 用于分组
// 注册消息增量（正文在 delta，按 reasonId 归组）
dispatcher.on("message.delta", (payload, evt) => {
    appendBotMessageDelta(payload.delta, evt.reasonId, evt.taskId);
});

// 注册思考增量（思考在 delta，与同轮正文共享 reasonId）
dispatcher.on("thought.delta", (payload, evt) => {
    appendThinkingDelta(payload.delta, evt.reasonId);
});

// 注册工具调用开始
dispatcher.on("tool.start", (payload, evt) => {
    renderToolRunningCard(payload.callId, payload.title, payload.args, evt.reasonId);
});

// 注册工具调用结束
dispatcher.on("tool.end", (payload) => {
    updateToolResult(payload.callId, payload.result, payload.isError, payload.diff);
});

// 注册子代理任务完成
dispatcher.on("task.done", (payload) => {
    finishTaskGroup(payload.taskId, payload.status);
});

// 注册主代理整轮收尾统计
dispatcher.on("system.trace", (payload) => {
    renderSessionStats(payload.model, payload.totalTokens, payload.elapsedSeconds);
});

// 注册系统错误
dispatcher.on("system.error", (payload) => {
    showErrorToast(`[${payload.code || 'ERROR'}] ${payload.message}`);
});

// WebSocket 消息接收
ws.onmessage = (event) => {
    const data = JSON.parse(event.data);
    dispatcher.dispatch(data);
};
```

### 5.3 性能优化建议（UI 帧排空机制）
对于高频推送的 `message.delta` 与 `thought.delta`，建议客户端使用队列缓冲，并通过 `requestAnimationFrame` 批量排空更新 DOM，避免高频字符流导致浏览器重排卡顿。
