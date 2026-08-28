# soloncode web — Web API 模式使用指南

> 面向第三方系统集成、远程调用与自定义客户端开发的 HTTP + WebSocket 交互入口。
>
> 事件协议详见 [SAEP 2.0 技术规范](../../../docs/saep-2.0-refactor-blueprint.md)。

## 概述

`soloncode serve` / `soloncode web` 启动一个常驻的 Web 服务（默认端口 `4808`，可通过 `soloncode serve 1212` 或 `-server.port=1212` 指定；纯 API 场景推荐 `serve`，见下文「启动模式」）。相比无头模式（`soloncode run`，一次性进程），Web 模式是**有状态的长驻服务**：

| 维度 | `soloncode run`（无头模式） | `soloncode web`（Web 模式） |
|------|---------------------------|---------------------------|
| 生命周期 | 单次执行后退出 | 常驻服务，多会话并发 |
| 提交方式 | 命令行参数 / stdin | HTTP POST |
| 接收方式 | stdout（text/json/stream-json） | WebSocket 事件流（SAEP 2.0） |
| 会话管理 | `--resume` 重新起进程 | 服务端持久会话，ID 复用 |
| 适用场景 | CI/CD、脚本编排 | 第三方系统对接、自定义 UI、IM 集成 |

核心交互模型：**HTTP 提交输入 → WebSocket 接收输出**（双通道）。

```
第三方客户端                          soloncode web
     │                                     │
     │  POST /web/chat/input  (提交问题)    │
     │────────────────────────────────────►│
     │                                     │  Agent 异步执行
     │  WS  /web/gate?workspaceId=xxx      │
     │◄────────────────────────────────────│  SAEP 2.0 事件流推送
     │        message.delta / tool.* ...    │
     │        system.done                   │
```

## 启动模式：`serve` 与 `web` 的选择

两种启动方式都会提供**完全相同的 Web API 端点**，区别在副作用和进程形态：

| 维度 | `soloncode serve [port]`（推荐） | `soloncode web [port]` |
|------|----------------------------------|------------------------|
| 浏览器 | **不自动弹出** | 启动后自动 `openBrowser()` |
| CLI 交互线程 | 不启动 | 不启动 |
| `/desktop/*` 端点 | 额外挂载（WsGate + WsController） | 不挂载 |
| 终端输出 | 安静（不打印欢迎横幅） | 打印 Web interface 提示 |
| 适用场景 | **纯 API 后台服务、第三方集成** | 本地交互式开发调试 |

> `serve` 模式因为不打印欢迎横幅，端口信息只能从启动参数中得知，请自行记录。
>
> `serve` 额外暴露的 `/desktop/*` 端点与 `web` 的端点同样受到 `WebAuthFilter`（全局过滤器，`index=-99`）的 Basic Auth 保护，鉴权边界一致；若不需要 Desktop 通道，可用防火墙规则屏蔽 `/desktop/*` 或直接使用 `web` 启动。

```bash
# 纯 API 场景（推荐）：无头、无浏览器副作用
cd /path/to/your-project
soloncode serve 4808

# 本地开发交互场景：会弹浏览器
soloncode web 4808
```

## 快速开始

```bash
# 1. 启动服务（在目标项目目录下执行，API 场景推荐 serve）
cd /path/to/your-project
soloncode serve 4808

# 2. 建立 WebSocket 连接接收事件（新终端）
#    wscat -c "ws://localhost:4808/web/gate?workspaceId=default"

# 3. 提交一条消息
curl -X POST "http://localhost:4808/web/chat/input" \
  -H "X-Session-Id: my-session-001" \
  -d "input=总结这个项目的架构"

# 4. 在 WebSocket 通道上依次收到：
#    {"event":"message.delta","sessionId":"my-session-001","payload":{"delta":"这个项目..."}}
#    {"event":"system.trace","sessionId":"my-session-001","payload":{...}}
#    {"event":"system.done","sessionId":"my-session-001","payload":null}
```

## 认证

若在设置中配置了 `webAuthUser` / `webAuthPass`（通用设置），所有接口启用 **HTTP Basic 认证**：

```bash
curl -u user:pass "http://localhost:4808/web/chat/input" ...
```

- 未配置账号密码时直接放行（默认本地开发场景）。
- 认证失败返回 `401` + `WWW-Authenticate: Basic realm="SolonCode"`。
- 静态资源（/css、/js 等）在白名单内不受限。
- WebSocket 握手同样经过该过滤器，连接 URL 需带上 Basic 凭证（`ws://user:pass@host/...`）或在握手 Header 中携带。

## 工作区（workspaceId）

服务支持多工作区隔离。每个工作区拥有独立的引擎、会话、文件监听与 Git 服务。

- `soloncode web` 启动目录即为**默认工作区**。
- 通过 `GET /web/workspace/list` 列出、`POST /web/workspace/open` 打开其它目录。
- WebSocket 连接必须携带 `?workspaceId=xxx` 参数；无效的 workspaceId 会被拒绝握手（连接关闭）。
- 未指定时回退默认工作区。

## 如何提交：`POST /web/chat/input`

主输入入口。注意：该接口**立即返回**，AI 结果全部走 WebSocket 推送。

| 参数 | 类型 | 说明 |
|------|------|------|
| `input` | string | 用户输入的文本消息 |
| `attachments` | file[] | 可选附件（multipart 上传） |
| `attachmentTypes` | string[] | 与 attachments 一一对应的类型 |
| `model` | string | 指定模型，空则用默认 |
| `sessionId` | string | 会话 ID；为空则取请求头 `X-Session-Id`，再缺省为 `web` |
| `reasoningEffort` | string | 可选，推理力度 |
| `thinkingMode` | string | 可选，思考模式 |
| `selectedAgent` | string | 可选，指定子代理执行 |
| `hitlAction` | string | 可选，HITL 审批决策（见下文） |
| `hitlCallId` | string | 可选，HITL 审批对应的调用 ID |

请求头：

| Header | 说明 |
|--------|------|
| `X-Session-Id` | 备选的会话 ID 传递方式 |
| `X-Session-Cwd` | 可选，会话工作子目录（不允许包含 `..`） |

响应：`{"code":200,...}` 仅表示**已受理**。校验失败返回 400（非法 Session ID / Session Cwd）。

### 多轮对话

`sessionId` 就是会话状态键。使用同一个 sessionId 多次提交，即自动延续上下文：

```bash
curl -X POST ".../web/chat/input" -H "X-Session-Id: task-a" -d "input=分析这个模块"
# 等 system.done
curl -X POST ".../web/chat/input" -H "X-Session-Id: task-a" -d "input=根据刚才的分析写测试"
```

### 会话管理接口

| 接口 | 说明 |
|------|------|
| `GET /web/chat/sessions` | 会话列表 |
| `GET /web/chat/messages?sessionId=` | 拉取会话历史消息 |
| `POST /web/chat/sessions/delete` / `rename` / `pin` / `fork` | 会话操作 |
| `POST /web/chat/interrupt?sessionId=` | 中断当前运行 |
| `POST /web/chat/rewind` (`sessionId`, `count` 默认 2) | 回退最近 N 条消息（重试/编辑重发场景） |
| `GET /web/chat/models` / `POST /web/chat/models/select` | 模型列表与切换 |
| `GET /web/workspace/list` / `POST /web/workspace/open` / `current` / `remove` | 工作区管理 |

## 如何接收：WebSocket `/web/gate`

连接：`ws(s)://host:port/web/gate?workspaceId=<id>`

要点（对应 `WebGate` 实现）：

1. **单连接多会话**：一个 WebSocket 连接不绑定 sessionId，服务端推送的每条消息都带 `sessionId` 字段，客户端按此分发到对应会话面板。
2. **心跳**：客户端发送文本 `ping`，服务端回 `pong`。
3. **输出通道只有 WS**：`/web/chat/input` 的结果不会出现在 HTTP 响应里。
4. **done 只发一次**：正常完成 / 异常 / interrupt 均以一条 `system.done` 收尾，不会重复。

推送的消息即 **SAEP 2.0 信封**：

```json
{
  "event": "message.delta",
  "sessionId": "my-session-001",
  "runId": "run-63f82a9c",
  "taskId": null,
  "reasonId": "reason-7f01",
  "agentName": "general",
  "timestamp": 1771123456789,
  "payload": { "delta": "流式正文增量..." }
}
```

### 典型事件序列（一轮对话）

```
system.user_input      ← 回显你的输入
thought.delta * N      ← 思考模型才有
tool.start             ┐ 工具调用（可能多轮，
tool.end               ┘ 与 message.delta 交错）
message.delta * N      ← 正文流式增量
system.context         ← 上下文占用统计
system.trace           ← 主代理收尾统计（含 finalAnswer、tokens、耗时）
system.done            ← 本轮流结束
```

> 子代理任务：事件带 `taskId`，结束时发 `task.done`（此时不发 system.trace）。
> 分组渲染：按 `reasonId` 归组同一轮思考/正文/工具。

### HITL 人工审批流

高危操作会阻塞并推送 `hitl.pending`（含 `callId`、`toolName`、`args`、`comment`）。客户端通过 `/web/chat/input` 回传决策：

```bash
curl -X POST ".../web/chat/input" \
  -H "X-Session-Id: task-a" \
  -d "input=" \
  -d "hitlAction=approve" \
  -d "hitlCallId=call-uuid-88"
```

`hitlCallId` 必须与 `hitl.pending` 中的 `callId` 一致，Agent 随后继续或放弃该调用。

## 客户端集成示例（JavaScript）

```javascript
// 1. WebSocket 接收
const ws = new WebSocket("ws://localhost:4808/web/gate?workspaceId=default");
ws.onopen = () => setInterval(() => ws.send("ping"), 25000);

const handlers = new Map();
function on(evt, fn) { handlers.set(evt, fn); }

ws.onmessage = (e) => {
    const evt = JSON.parse(e.data);
    handlers.get(evt.event)?.(evt.payload, evt);   // 第二参为完整信封
};

on("message.delta", (p, env) => appendTo(env.sessionId, p.delta));
on("tool.start",    (p)    => showToolCard(p.callId, p.title, p.args));
on("tool.end",      (p)    => finishToolCard(p.callId, p.result, p.isError));
on("system.error",  (p)    => console.error(p.code, p.message));
on("system.trace",  (p)    => console.log("tokens:", p.totalTokens, "answer:", p.finalAnswer));
on("hitl.pending",  (p)    => askUserApprove(p.callId, p.comment));
on("system.done",   ()     => setBusy(false));

// 2. HTTP 提交
async function ask(sessionId, text) {
    await fetch("http://localhost:4808/web/chat/input", {
        method: "POST",
        headers: { "X-Session-Id": sessionId },
        body: new URLSearchParams({ input: text })
    });
}
```

> 高频 `message.delta` 建议用队列缓冲 + `requestAnimationFrame` 批量刷新，避免 DOM 重排卡顿（见 SAEP 2.0 §5.3）。

## 错误处理

运行期错误通过 `system.error` 事件推送，`payload.code` 取值：

| 错误码 | 含义 |
|--------|------|
| `AUTH_FAILED` | 模型 API Key 鉴权失败或配额耗尽 |
| `RATE_LIMIT` | 模型提供方限流 |
| `TIMEOUT` | 工具/网络超时 |
| `BAD_REQUEST` | 参数不合法或上下文超长 |
| `EXEC_FAILED` | 本地工具执行失败 |
| `INTERNAL_ERROR` | 未知异常 |

HTTP 层：401（认证失败）、400（非法 sessionId/cwd）、404（workspaceId 不存在）。

## RPC 模式：`POST /web/chat/run`（同步 / SSE）

针对没有 WebSocket 能力的第三方集成方，`/web/chat/run` 提供单次 HTTP 请求内拿到全部输出的能力。参数与 `/web/chat/input` 完全一致（input、model、sessionId/X-Session-Id、X-Session-Cwd、attachments/attachmentTypes、reasoningEffort、thinkingMode、selectedAgent、hitlAction/hitlCallId），额外多一个 `stream` 开关：

### stream=true（默认）：SSE 流式

响应为 `text/event-stream`，逐条输出与 WebSocket 完全同源的 SAEP 2.0 事件帧，收到 `system.done` 后追加 `data: [DONE]` 结束：

```bash
curl -N -X POST "http://localhost:4808/web/chat/run" \
  -H "X-Session-Id: rpc-demo" -d "input=分析当前项目结构"
```

```text
event: system.user_input
data: {"event":"system.user_input",...}

event: thought.delta
data: {"event":"thought.delta",...}

event: message.delta
data: {"event":"message.delta",...}

event: system.trace
data: {"event":"system.trace",...}

event: system.done
data: {"event":"system.done",...}

data: [DONE]
```

### stream=false：同步聚合

阻塞等待本轮结束，一次性返回 JSON：

```bash
curl -X POST "http://localhost:4808/web/chat/run?stream=false" \
  -H "X-Session-Id: rpc-demo" -d "input=用一句话总结项目"
```

```json
{
  "code": 200,
  "data": {
    "sessionId": "rpc-demo",
    "finalAnswer": "...聚合后的完整回答..."
  }
}
```

### 说明与限制

- **独立执行，不依赖 WebSocket**：接口直接消费 `WebStreamBuilder` 的 agent 事件流（不经过 WS 网关的连接管理）。若同 sessionId 恰有 WS 连接在线，事件会同步广播至 WS（多端一致）；没有 WS 在线也完全正常工作。
- **鉴权**：与其它 `/web/**` 接口一样走 HTTP Basic。
- **超时**：阻塞上限 10 分钟，超时后取消订阅并结束响应（同步模式下若期间出现过 `hitl.pending`，返回 `status=hitl_pending` 而非 `timeout`）。
- **空输入返回 400**：不同于 `/web/chat/input` 的宽容语义，run 模式要求 input / attachments / hitlAction 至少一项非空。
- **HITL**：同步模式下若审批未决策，返回 `"status": "hitl_pending"`；流式模式下会先收到 `hitl.pending` 事件。此时再次 POST 本接口（或 `/web/chat/input`）回传 `hitlAction=approve|reject&hitlCallId=xxx` 继续，多轮往返即完成审批闭环。
- **斜杠命令**：`/command` 会走命令分发且不产生 agent 流，输出仅推送给 WebSocket；本接口对命令输入返回 `"status": "handled"`（流式模式直接 `[DONE]`），命令请改用 `/web/chat/input`。
- **占用连接**：SSE/同步模式会占用一个 HTTP 连接直到本轮结束，并发调用同一 sessionId 建议串行。轮次可通过 `/web/interrupt` 中断（会话级 composite 注册）。

## 关于 RPC 模式的后续演进（Roadmap）

`/web/chat/run` 已落地（见上节），后续可继续：

1. **OpenAPI 化**：已有 `OpenapiSettingsController`，可将现有 `/web/**` 接口暴露 OpenAPI 文档，第三方直接生成 SDK。
2. **Nami RPC**（Solon 生态内）：若第三方同为 Solon/Java 应用，可用 Nami 声明式接口直接调用上述 HTTP 接口，无需额外协议。
3. **事件过滤参数**：`/web/chat/run` 可加 `events=message.delta,system.trace` 过滤，减少噪声。

## 相关文件

| 文件 | 说明 |
|------|------|
| `portal/web/WebController.java` | 全部 HTTP 接口（input/interrupt/rewind/sessions 等） |
| `portal/web/WebGate.java` | WebSocket 网关：连接池、输入路由、事件推送、HITL |
| `portal/web/WebStreamBuilder.java` | Agent 事件流 → SAEP 2.0 WebEvent 组装 |
| `portal/web/pipeline/WebEventMapper.java` 等 | 事件映射/工具展示修饰/多渠道广播/指标统计管道 |
| `portal/web/WebAuthFilter.java` | HTTP Basic 认证过滤器 |
| `Configurator.java` | WebSocket 路由注册（`/web/gate`、`/desktop/ws`） |
| `docs/run-headless-mode.md` | 无头模式（`soloncode run`）用户指南 |
| `../../docs/saep-2.0-refactor-blueprint.md` | SAEP 2.0 事件协议完整规范 |
