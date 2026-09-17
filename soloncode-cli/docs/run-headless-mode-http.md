# soloncode run — HTTP 远程执行方案（/web/run）

> 面向 `soloncode run` 的 HTTP 实现版：把无头模式的输入输出完整搬到 `/web/run` 端点，
> 让 SDK（及任意语言编写的客户端）以 HTTP/SSE 与远程 soloncode 服务通讯，
> 而不必在调用方机器上安装 CLI。
>
> **实现状态（2026-08-28）：服务端已落地。** 实现代与本方案的一处有意偏离见
> 《实现说明》一节：执行模型采用**每请求子进程**（`java -cp ... App run`，cwd=工作区路径），
> 而非进程内直接调 `PrintMode.execute()`——理由与契约影响详见文末。

## 概述

`/web/run` 与 `soloncode run` 共享同一套执行内核（`portal/printmode/PrintMode`）：

- **同输入**：提示词 + 全部 15 个无头选项，字段名与 CLI flag 一一对应
- **同输出**：`text` / `json` / `stream-json` 三种输出格式，事件结构与 CLI 逐字节一致
- **同语义**：退出码语义映射为 HTTP 状态码 + 结果载荷中的 `exit_code` 字段
- **同会话**：`--session-id` / `--resume` / `--continue` 在服务端有同样的效果

```
SDK (Java/其它)                服务端 (soloncode-cli web)
    │  POST /web/run ─────────────▶ RunEndpoint
    │  (prompt + options JSON)        │
    │                                 ├── PrintModeOptions.fromJson()
    │                                 ├── PrintMode.execute()   ← 与 CLI 同一内核
    │◀── SSE: system/init ────────────┤
    │◀── SSE: assistant ... ──────────┤
    │◀── SSE: result ─────────────────┘
```

与 stdio 通道的本质差异只有两点：**进程边界变成了网络边界**，**工作目录从本机路径变成了服务端工作区标识**。其余语义（一次性执行、多轮 `--resume` 串接、退出码含义）完全保持。

## 端点定义

### POST /web/run

**请求体**（`application/json`）：

```json
{
  "prompt": "分析这个模块的结构和问题",
  "options": {
    "output_format": "stream-json",
    "model": "sonnet",
    "max_turns": 15,
    "session_id": "my-task-001",
    "resume": null,
    "continue": false,
    "allowed_tools": ["Read", "Grep", "Bash(git log *)"],
    "disallowed_tools": ["Bash(rm *)"],
    "permission_mode": "dontAsk",
    "fallback_model": "haiku",
    "json_schema": null,
    "max_budget_usd": 2.0,
    "bare": true,
    "add_dirs": ["/srv/repos/repo-a"]
  },
  "workspace": "my-project",
  "metadata": { "request_id": "ci-20260828-001" }
}
```

字段规约：

| 字段 | 必填 | 说明 |
|------|------|------|
| `prompt` | 是 | 提示词纯文本。JSON 体内不存在 argv 转义问题，无需 stdin 回退分支 |
| `options` | 否 | 与 CLI flag 一一对应（snake_case）。见下表 |
| `workspace` | 否 | 服务端工作区标识（`/web/workspace/list` 返回的 name）；缺省用服务端默认工作区 |
| `metadata` | 否 | 透传字段，原样回显在响应尾事件中，用于链路追踪 |

**options 字段 ↔ CLI flag 映射**：

| JSON 字段 | CLI flag | 类型 |
|-----------|----------|------|
| `output_format` | `--output-format` | `text` / `json` / `stream-json` |
| `model` | `--model` | string |
| `max_turns` | `--max-turns` | int |
| `session_id` | `--session-id` | string |
| `resume` | `--resume` | string |
| `continue` | `--continue` | bool |
| `allowed_tools` | `--allowedTools` | string[]，支持 `ToolName(pattern)` 语法 |
| `disallowed_tools` | `--disallowedTools` | string[] |
| `permission_mode` | `--permission-mode` | enum |
| `fallback_model` | `--fallback-model` | string |
| `json_schema` | `--json-schema` | object |
| `max_budget_usd` | `--max-budget-usd` | double |
| `bare` | `--bare` | bool |
| `add_dirs` | `--add-dir`（可重复） | string[] |

未识别字段：拒绝（HTTP 400），而不是静默忽略——与 SDK 侧「CLI 不支持的选项必须告警」的契约对齐，防止调用方以为某个参数生效了。

### 响应

#### stream-json（默认，`Accept: text/event-stream`）

SSE 逐事件推送，事件体与 CLI 的 stream-json 事件**完全一致**（`system/init`、`assistant`、`user`、`result`、`error`），复用 `PrintMode` 现有的事件序列化，不做二次包装：

```
event: message
data: {"type":"system","subtype":"init","session_id":"print-a1b2c3d4","model":"sonnet",...}

event: message
data: {"type":"assistant","message":{"content":[{"type":"text","text":"正在分析..."}]}}

event: message
data: {"type":"result","result":"分析完成。","session_id":"print-a1b2c3d4","is_error":false,...}
```

- 一个 SSE `data:` 行 = CLI 的一行 JSONL。客户端把 `data:` 内容喂给与 stdio 相同的
  `RobustStreamParser` / `MessageParser` 即可，**解析层零改动**
- 连接在 `result` 或 `error` 事件后关闭，对应进程的一次性退出
- `verbose` 不需要等价物：HTTP 请求本身就是显式的结构化消费，SSE 恒输出全量事件

#### json / text

一次性返回（非流式）：

- `text`：`200`，body 为最终答复纯文本
- `json`：`200`，body 为与 CLI json 模式相同的对象（`result` / `is_error` / `session_id` / `metrics` / `total_cost_usd` / `structured_output`）

### 退出码 → HTTP 状态码

| CLI 退出码 | HTTP 状态 | 说明 |
|-----------|-----------|------|
| 0 | `200` | 成功 |
| 1 | `500`（`is_error:true` 时）| 运行出错；body 含 `error` 字段 |
| 2 | `200` + `is_error:true`，`error.code="max_turns"` | 超过轮次——这是「执行了但没跑完」，不是请求失败，不应触发客户端 HTTP 层重试 |
| 3 | `400`，`error.code="no_prompt"` | 未提供提示词 |
| 4 | `200` + `is_error:true`，`error.code="budget_exceeded"` | 超预算，同 2 的处理思路 |

> 设计取舍：`2`/`4` 保留 200，是因为它们语义上是「本次执行的结论」而非「请求本身的故障」。客户端按 `is_error` + `error.code` 分支处理，这与 CLI 侧「检查退出码决定流水线走向」的用法一一对应。

### 中断

一次性请求内无法表达中断，独立端点承担：

```
POST /web/run/interrupt
{ "session_id": "print-a1b2c3d4" }
```

返回 `202`。服务端对该会话当前执行触发与 CLI 进程 `SIGTERM` 等价的取消；SSE 流随后以
`error` 事件（`error.code="interrupted"`）结束。多轮续接场景中客户端 interrupt 后不再发
`--resume`，与 stdio 通道行为一致。

## 鉴权与安全（上线硬门槛）

`/web/run` 在服务端以**完整工具权限**运行 Agent（Bash、文件读写、目录挂载），等于一个
可远程驱动的执行引擎，安全边界必须先于功能落地：

1. **默认只绑 `127.0.0.1`**。`soloncode web` 的 server.host 默认 loopback，开放外网是
   显式配置行为（配置文件 + 启动日志双确认）。
2. **Token 必须**。`Authorization: Bearer <token>`；token 由服务端首次启动生成并写入
   用户配置目录，也允许显式配置。无 token 请求一律 `401`。不提供「关闭鉴权」选项。
3. **permission-mode 服务端收口**。请求体里的 `permission_mode` 只能收紧、不能放宽：
   服务端配置一个上限（默认 `dontAsk`），请求传 `bypassPermissions` 会被拒绝（`403`），
   而不是像 CLI 那样透传。
4. **workspace 白名单**。`workspace` 只能取服务端已注册的工作区（`/web/workspace/list`
   可见者），请求无法用路径任意指定目录；`add_dirs` 同样受工作区配置的允许范围约束。
5. **审计日志**。每次执行记录：时间、来源 IP、token 标识、workspace、prompt 长度（不落
   prompt 内容）、session_id、退出码、token 用量与估算费用。

## 服务端实现要点

在 `soloncode-cli` 现有 `web` 模式（`portal/web/`）内新增 `RunEndpoint`，执行路径**复用
PrintMode 内核**，只在外围做 HTTP 适配：

```
portal/web/RunEndpoint.java      ← 新增：请求校验、鉴权、SSE 适配
portal/printmode/PrintMode.java  ← 复用：execute() 主流程不变
portal/printmode/PrintModeOptions.java ← 增补：fromJson() 反序列化（与 parse() 同一约束）
```

关键点：

- **不复制执行逻辑**。RunEndpoint 拿到 `PrintModeOptions` 后直接调 `PrintMode.execute()`，
  只是把「写 stdout」换成「写 Sinks → SSE emitter」。`output_format=text/json` 时聚合后
  一次性返回，`stream-json` 时逐事件透传。
- **会话存储共用**。`--session-id` / `--resume` 依赖的会话仓库与 CLI 同一份，HTTP 客户端
  与本机 CLI 用户可以互相续接同一会话。
- **超时与背压**。单请求执行时长上限取服务端配置（默认 10 分钟，与 SDK 默认超时对齐）；
  SSE emitter 慢消费时按 Reactor `onBackpressureDrop + 计数告警` 处理，不阻塞 Agent 线程。
- **并发**。允许多个请求并发执行（各自独立 `PrintMode` 实例），同 `session_id` 的并发
  请求由会话仓库加锁拒绝后到者（`409`），防止上下文交叉污染。

## SDK 侧对齐（Java）

SDK 已完成传输层抽象（`soloncode-sdk-java`）：

- `transport/Transport` 接口：`startSession` / `waitForCompletion` / `sendUserMessage` /
  `interrupt` / `close` + 五态状态机，`StdioTransport` 为默认实现
- `transport/TransportSpec`：通道声明，现有 `stdio()` / `stdio(path)`

服务端 `/web/run` 落地后，SDK 侧新增 `HttpTransport`：

```java
try (SolonCodeClient client = SolonCodeClient.builder()
        .http("http://x.x.x:18080/web/run")
        .authToken("...")
        .workspace("my-project")
        .build()) {
    client.prompt("分析代码质量").stream().subscribe(System.out::println);
}
```

> **已落地（2026-08-29）**：`HttpTransport`、`HttpSpec` 与统一 builder 的 `http(url)`/
> `authToken()`/`workspace()` 均已实现。补充两条实现时的关键决策：
>
> 1. **permission_mode 前置回落**：`CLIOptions.builder()` 默认值是
>    `bypassPermissions`（stdio 场景合理），但 /web/run 服务端收口 403。SDK 侧不区分
>    「显式指定」与「默认值」，bypass 系一律在请求体中回落为 `default` 并告警，
>    避免调用方吃 403。显式指定的 `default`/`acceptEdits`/`plan`/`dontAsk` 照常透传。
> 2. **顶层 error 事件解析**：服务端「异常退出且无 result」时补发
>    `{"type":"error","message":...,"code":...}`，`MessageParser` 将其投递为
>    `SystemMessage(subtype="error")`，不再静默丢弃（HTTP 通道下它是故障的唯一通知）。

HttpTransport 的职责边界：

- 把 `CLIOptions` 序列化为请求体 `options`（字段名按上表映射）
- SSE `data:` 行直接喂给现有 `MessageParser`，消息类型层零改动
- `interrupt()` 调用 `/web/run/interrupt`，而非销毁进程
- `sendUserMessage()` 首轮之后的行为与 stdio 一致：抛 `TransportException`（one-shot），
  多轮由客户端层 `--resume` 串接
- **workingDirectory 语义变化**：HTTP 通道下 `workingDirectory()` 被拒绝（抛
  `IllegalArgumentException`），改用 `workspace()`。stdio 下相反。这一约束在两个通道的
  builder 层就分开，而不是运行期告警。

## 客户端兼容性（curl 示例）

```bash
# 流式 — 与 CLI stream-json 消费方式完全一致
curl -N -X POST http://127.0.0.1:18080/web/run \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"分析代码质量","options":{"output_format":"stream-json"}}' \
  | grep '^data:' | jq -r 'select(.type=="result") | .result'

# 两阶段任务 — 与 CLI --resume 用法一致
S1=$(curl -s -X POST http://127.0.0.1:18080/web/run \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"prompt":"分析模块","options":{"output_format":"json"}}' | jq -r '.session_id')
curl -s -X POST http://127.0.0.1:18080/web/run \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"prompt\":\"基于分析写测试\",\"options\":{\"output_format\":\"json\",\"resume\":\"$S1\"}}"
```

## 实施顺序

1. **RunEndpoint + PrintModeOptions.fromJson()**：请求校验、鉴权骨架、SSE 透传，
   options 覆盖 15 个 flag 的全量字段（含 `ToolName(pattern)` 语法解析复用）
2. **中断与会话并发**：`/web/run/interrupt`、同 session 加锁
3. **SDK HttpTransport + `http()` builder**：对齐上表映射，SSE 解析复用现有 parser；
   补契约测试（与 `StdioTransportIntegrationIT` 同构）
4. **安全项**：默认 loopback、token、permission-mode 收口、workspace 白名单、审计日志
   ——与 1 同批合入，不作为后续补丁

## 与 CLI 无头模式的关系

`/web/run` 不取代 `soloncode run`，两者是同一内核的两种前置：

| 维度 | `soloncode run` | `POST /web/run` |
|------|-----------------|-----------------|
| 调用方 | 本机 shell / CI 脚本 | 任意 HTTP 客户端 |
| 提示词投递 | argv / stdin 管道 | JSON 请求体（无转义问题） |
| 工作目录 | `--add-dir` + cwd | `workspace` 标识 + 白名单 |
| 输出 | stdout JSONL | SSE 事件流（同构） |
| 结果语义 | 进程退出码 | HTTP 状态码 + `error.code` |
| 中断 | kill 进程 | `/web/run/interrupt` |

CLI 文档（`run-headless-mode.md`）中的退出码表、选项语义、stream-json 事件结构、CI 实践
对 HTTP 通道全部适用，仅投递与承载方式不同。

## 实现说明（2026-08-28 落地版）

服务端实现位于 `portal/web/run/`（`RunController` / `RunRequestService` /
`RunTokenService` / `RunSessionRegistry`），与本方案契约一致，一处有意偏离：

### 执行模型：每请求子进程

方案原文设想进程内调 `PrintModeOptions.fromJson()` + `PrintMode.execute()`；实现改为
**每请求 fork 子进程**（`java -cp <classpath> App run <prompt> <flags>`，cwd=工作区路径），
`RunRequestService` 把 JSON options 规范化为 CLI argv：

- **隔离性**：`PrintMode.applyOptions()` 会改写 engine 的权限规则/挂载点/MCP/HITL 开关，
  而 web 进程的默认 engine 与交互式会话共享——进程内复用会污染交互会话；
- **零漂移**：argv 解析、退出码、`--bare`/`--add-dir` 语义与 CLI 完全同源，
  CLI 内核的任何变更自动对 HTTP 生效，不存在第二份解析代码需要同步；
- **跨通道 resume**：子进程与交互式 web 会话、本机 CLI 按同一会话仓库（按 cwd 定位）
  读写，HTTP 客户端与本机 CLI 可互相续接同一会话。

代价是每次请求有 JVM 冷启动开销（实测本机约 2-4s）；对交互式使用可接受，
后续如需优化，可在进程内为 `/web/run` 构建隔离 engine（不动默认 engine）复刻
`applyOptions`，当前不做。

### 各安全项的落点

- token：`RunTokenService`（`~/.soloncode/run.token` 首启生成；`SOLONCODE_RUN_TOKEN`
  环境变量或 `soloncode.run.token` 系统属性可显式指定；无 token 一律 401）
- permission-mode 收口：`bypassPermissions` → 403（`RunRequestService`）
- workspace 白名单：仅服务端已注册工作区可被引用（含默认 launch），任意路径 → 404
- 审计：`run.audit` logger（action/ip/workspace/prompt 长度/session/exit）
- WebAuthFilter 对 `/web/run` 前缀放行（该端点自带 Bearer 校验，不叠 Basic Auth）

### 中断语义

`RunSessionRegistry` 两阶段登记：受理即占位（409 判定先行），子进程启动后回填句柄；
`interrupt` 落在两阶段之间时置 killPending，回填时立即 `destroy()`（SIGTERM 等价），
与 CLI kill 进程的行为一致。

### 部署修复记录（2026-08-29）

- **fat jar 主类加载**：`execSubprocess` 原用 `java -cp <classpath> App`，fat jar 部署
  （`BOOT-INF/classes`）下 `-cp` 找不到主类，请求 0.5s 内返回 `ERR_SUBPROCESS`。改为：
  classpath 无路径分隔符且以 `.jar` 结尾时走 `java -jar`，否则维持 `-cp`（IDE/腿本场景）。
- **token 惰性生成触发**：`RunTokenService.verify()` 对空 token 直接返回 false，不触发
  `loadToken()` —— 首次部署无人带 token 访问过则 `run.token` 永不生成。改为首次 verify
  即触发生成。
