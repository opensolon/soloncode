# soloncode-sdk-java

Java SDK for the **soloncode CLI** headless modes (`soloncode stream` / `soloncode run`). Forked and adapted
from [claude-agent-sdk-java](https://github.com/anthropics/claude-agent-sdk-java) (Apache License 2.0).

- Requires **JDK 8+**（与仓库其余模块同基线：`maven.compiler.source/target=8`）
- JSON 序列化用 **snack4**（`org.noear:snack4`）；不依赖 Jackson
- 两条通讯通道：**stdio**（默认拉起常驻 `soloncode stream` 子进程；可用 `stdio(path)` 指定 CLI，
  `stdioOneShot(path)` 回退为每轮 `soloncode run`）与 **http**（投递到服务端 `/web/run`）

## Quick Start

```java
// 统一入口：由 call() / stream() 选择执行方式，不再区分 SyncClient 与 AsyncClient
try (SolonCodeClient client = SolonCodeClient.builder()
        .stdio()                              // 默认复用一个常驻 soloncode stream 进程
        .workingDirectory(Paths.get("."))
        .model("sonnet")
        .permissionMode(PermissionMode.DONT_ASK)
        .bare(true)
        .maxTurns(10)
        .build()) {
    QueryResult result = client.prompt("总结最近一次提交的变更内容").call();
    System.out.println(result);

    // 同一 client 的后续 prompt 是同一会话的下一轮
    client.prompt("再用三句话解释").stream().subscribe(System.out::println);
}
```

```java
// HTTP 通道：统一入口同样由 call() / stream() 决定模式
try (SolonCodeClient client = SolonCodeClient.builder()
        .http("http://127.0.0.1:18080/web/run")
        .authToken(token)
        .workspace("my-project")
        .build()) {
    QueryResult result = client.prompt("总结最近一次提交的变更内容").call();
}
```

`call()` 是阻塞聚合，`stream()` 返回 Reactor `Flux`；两者共享同一套 prompt、终态、超时与取消语义。

## call() / stream()：prompt 风格入口

与 solon-ai 的 `ChatModel.prompt(...).call() / .stream()` 对齐：`call()` 阻塞到本轮结束、
返回聚合结果；`stream()` 返回**真流式** `Flux`，消息一到就下发。

```java
// 阻塞聚合（含 metadata / cost / status）
QueryResult result = SolonCode.prompt("写一首俿句").call();

// 真流式：边生成边消费
SolonCode.prompt("解释递归")
    .options(QueryOptions.builder().model("sonnet").build())
    .stream()
    .subscribe(System.out::println);

// 走 http 通道：通道与凭证由统一 client builder 配
try (SolonCodeClient client = SolonCodeClient.builder()
        .http("http://127.0.0.1:18080/web/run")
        .authToken(token)
        .workspace("my-project")
        .build()) {
    client.prompt("分析这个模块")
        .stream()
        .subscribe(System.out::println);
}
```

语义要点：

- 已构建客户端的 `prompt(...).stream()` 使用该 client 的会话，客户端由调用方负责关闭；正常结束、异常或取消不会自动关闭 client。
- `SolonCode.prompt(...).stream()` 是兼容的单轮冷流，每次订阅发起一次新执行并在结束时释放临时客户端。
- 旧的 `Query.stream(prompt)` 是「先跑完再把列表转成 Stream」的**伪流式**，为兼容保留；新代码用统一入口的 `stream()`。

语义要点：

- `stream()` 在下游有 demand 时才从阻塞响应迭代器继续拉取，取消会主动 interrupt；Reactor 桥接层使用 `ERROR` 溢出策略，不做无界预取。
- 同步接收器的每轮缓冲上限为 1000 条；慢消费者超过上限会收到明确错误并中断当前轮，不会无限占用内存。
- transport 的迟订阅回放窗口限制为最近 1000 条，不再永久保留常驻会话的全部历史。
- 这属于 SDK 边界的 demand-aware、有界背压；CLI 子进程或远端 SSE 已经写入操作系统管道/网络缓冲的数据无法反向撤回。

## 两条通讯通道

通道是必选项（有默认值），builder 层一级方法选择：

```java
SolonCodeClient.builder().stdio()                                 // 默认：本机常驻 stream，自动发现 CLI
SolonCodeClient.builder().stdio("/usr/local/bin/soloncode")       // 常驻 stream，指定可执行文件
SolonCodeClient.builder().stdioOneShot("/usr/local/bin/soloncode") // 兼容：每轮 run + resume
SolonCodeClient.builder().http("http://127.0.0.1:18080/web/run")  // 服务端通道
```

| 维度 | stdio（默认） | http |
|------|---------------|------|
| 承载方式 | 本机拉起一个常驻 `soloncode stream` 子进程 | POST `/web/run`（`soloncode web` 启动），SSE 回流 |
| 工作目录 | `workingDirectory(path)`（本机路径） | `workspace(id)`（服务端已注册工作区标识；两者 builder 层互斥） |
| 鉴权 | 无（本地进程） | `authToken(token)` Bearer（服务端 `~/.soloncode/run.token`） |
| 权限模式 | 全量支持（含 bypassPermissions） | 服务端收口：bypass 系一律回落 `default`（SDK 前置告警替换，避免吃 403） |
| 事件解析 | stream-json 逐行 | SSE 每个 `data:` 行即 CLI 的一行 JSONL，解析层完全复用 |
| 中断 | 写 `control_request/interrupt`，只取消当前轮、进程继续存活 | 独立端点 `POST /web/run/interrupt`（按 session 定位） |
| 多轮串接 | 同一进程、同一 AgentSession；每个 `result` 切分一轮 | 每轮新请求：`session_id` / `resume` |
| 回写通道 | stdin JSONL（user / control_request） | 不存在（one-shot） |

HTTP 通道下 MCP 注册、权限审批回调等在服务端配置，客户端同名选项仅告警忽略。
协议契约详见 `soloncode-cli/docs/run-headless-mode-http.md`。

### 网络层：代理、SSL/TLS 与自定义请求头（仅 http 通道）

```java
// HTTP 正向代理（含 HTTPS CONNECT 隧道），带 Basic 认证
SolonCodeClient.builder()
    .http("https://run.internal.example/web/run")
    .authToken(token)
    .httpOptions(HttpOptions.proxy("proxy.corp.example", 3128).proxyAuth("user", "pass"))
    .build();

// SOCKS5 代理
HttpOptions.proxy("proxy.corp.example", 1080, HttpOptions.ProxyType.SOCKS)

// 自签/私有 CA 证书：keytool -importcert 导入 JKS 后指定信任库
HttpOptions.tls().trustStore("/path/ca-trust.jks", "changeit")

// 客户端证书（mTLS）
HttpOptions.tls().trustStore(caPath, caPass).keyStore("/path/client.p12", "changeit")

// 跳过证书校验 / 主机名校验（危险，仅限内网联调；启用时打 WARN 日志）
HttpOptions.tls().trustAll(true)
HttpOptions.tls().skipHostnameVerify(true)

// 自定义请求头：网关路由、租户标识、链路追踪
HttpOptions.create().header("X-Tenant-Id", "t-1024").header("X-Trace-Id", traceId)
HttpOptions.create().headers(headerMap)          // 批量

// 组合（代理 + TLS + 自定义头）
HttpOptions.proxy("proxy.corp.example", 3128)
    .proxyAuth("user", "pass")
    .trustStore(caPath, caPass)
    .header("X-Tenant-Id", "t-1024")
```

要点：

- 所有选项经 `httpOptions(...)` 构建器传入（stdio 通道设置会在首次创建会话时报错）；`HttpOptions` 不可变，wither 返回新实例。
- 代理认证走 `Proxy-Authorization` 头，`/web/run` 与 `/web/run/interrupt` 每个连接都带；SOCKS 认证需调用方自行 `Authenticator.setDefault`（SDK 不改 JVM 全局状态）。
- trustStore/keyStore 支持 JKS 与 PKCS12（按文件内容自动识别）；密码字段不参与 equals/toString，防泄漏。
- trustAll 与 trustStore 互斥；SSL 初始化失败（文件不存在/密码错/格式不对）在传输实例创建时立即抛 `TransportException`，不留到首次请求。
- 自定义头同样带到 `/web/run` 与 `/web/run/interrupt` 两条链路；头名大小写不敏感、同名覆盖。`Content-Type`/`Accept`/`Content-Length`/`Proxy-Authorization` 是协议关键头或 SDK 自管头，设置即抛 `IllegalArgumentException`；`Authorization` 允许覆盖 `authToken`（对接前置网关时需要），会打 WARN。`Authorization`/`Cookie` 的值在 `toString()` 中脱敏为 `***`。

> SDK 本身不读任何 `soloncode.http.*` 环境配置——服务地址、token、workspace 一律构建器传入；
> 需要从外部配置注入时，由调用方读出来再传给 builder。

## 执行模型：默认常驻 stream，保留 one-shot run

`soloncode stream` 在 stdin/stdout 上使用 JSONL：进程和 AgentSession 保持到 stdin EOF；每个 user
信封启动一轮，每个 `result` 是轮次边界。SDK 默认 stdio 使用这条路径，因此多轮不再重复支付 JVM 与引擎
冷启动成本，`interrupt()` 也只取消当前轮。构建客户端不会启动进程，首次执行
`prompt(...).call()/stream()` 时才建立 transport。

需要旧 CLI 兼容或进程级隔离时，使用 `stdioOneShot(path)`。该模式继续执行每轮
`soloncode run`，首轮 `--session-id`、后续 `--resume`，并在中断时终止该轮进程。HTTP `/web/run`
仍是 one-shot 请求模型。

| 模式 | 进程生命周期 | 提示词投递 | 多轮上下文 | interrupt |
|------|--------------|------------|----------|-----------|
| `stdio()` / `stdio(path)` | 一个 `soloncode stream` 进程 | stdin JSONL user 信封 | 同一个 AgentSession | 控制帧，保留进程 |
| `stdioOneShot(path)` | 每轮一个 `soloncode run` | argv；危险位置参数回退纯文本 stdin | `--session-id` / `--resume` | 终止进程 |
| `http(url)` | 每轮一个 HTTP 请求 | POST JSON | session/resume | interrupt 端点 |

## 与 claude-agent-sdk-java 的差异

soloncode CLI 的 `stream` 子命令支持 stdin JSON 双向协议；`run` 和 HTTP `/web/run` 仍保持 one-shot。

| 能力 | 状态 |
|------|------|
| `call()` / `stream()`、stream-json 事件流解析 | 支持 |
| 会话管理（`--resume` / `--continue`） | 支持 |
| 权限模式预设（default/dontAsk/plan/acceptEdits/bypassPermissions） | 支持 |
| `--allowedTools` / `--disallowedTools`（含 `Tool(pattern)` 规则） | 支持 |
| `--json-schema` 结构化输出、`--max-budget-usd`、`--max-turns`、`--fallback-model` | 支持 |
| `--add-dir` 多目录、`--bare`、`--session-id`、`--fallback-model` | 支持（统一 Builder 一级方法） |
| `toolPermissionCallback` / `permissionPromptToolName`（stdin 审批回调） | **不支持**，配置后仅告警忽略 |
| `systemPrompt` / `appendSystemPrompt` / `agents` / `forkSession` / `mcpServers` / `settings` / `plugins` / `tools` / `maxThinkingTokens` | **不支持**，配置后仅告警忽略 |
| `maxTokens` | **不支持**，执行启动时明确抛出 `UnsupportedOperationException` |
| Hook / permission / SDK MCP 回调、动态 `setModel` / `setPermissionMode` | **不支持**；当前 stream 控制面仅支持 `interrupt`，不会静默假装成功 |

同一客户端只允许一个活动轮次；当前轮的响应必须消费完成或取消后才能开始下一轮。客户端 `timeout`
是每轮从启动到终态的响应期限，超时会中断当前执行并向调用方传播错误。

## one-shot 提示词投递通道

仅 `stdioOneShot(path)` 默认把提示词作为 `run` 之后的第一个位置参数。但 Solon 的 `argx` 解析会把 argv 里含 `=`
的词当成 `key=value`、把 `-` 开头的词当成选项名，这两类提示词作为位置参数会丢失（CLI 报
`No prompt provided` 并退出码 3），因此 SDK 对它们自动改走 stdin 管道（纯文本，写完即关闭）。

## 退出码

| 退出码 | 含义 |
|--------|------|
| 0 | 成功 |
| 1 | 运行错误 |
| 2 | 超过 `--max-turns` |
| 3 | 未提供提示词 |
| 4 | 超过 `--max-budget-usd` |

> 需要 soloncode **v2026.9.10+**：更早版本的 `soloncode run` / `--version` 走 `Solon.stop()`，
> 而它内部固定 `System.exit(1)`，即便成功也返回退出码 1。若必须对接旧版 CLI，请以 `result`
> 事件的 `is_error` 判定成功与否，不要依赖退出码。v2026.9.10 同时引入 `soloncode help`
> 与 `soloncode run --help`，`CLIFlagParityIT` 以后者为基准校验 SDK 与 CLI 的选项对齐。

## 构建

```bash
mvn test          # JDK 8 基线，无需切换 JAVA_HOME；同时生成 target/site/jacoco 覆盖率报告
```

单元测试完全离线可重复：`StdioTransportFakeCliTest` 用假 CLI 脚本（bash 模拟
`soloncode stream` / `soloncode run` 的 stdout JSONL / stdin JSONL / stderr / 退出码）覆盖进程生命周期分支，
`HttpTransportTest` 用 JDK 内嵌 HttpServer 模拟 `/web/run` 的 SSE 与错误码。

集成测试（`*IT`）需要本机安装 soloncode CLI：

```bash
# 全部 IT
mvn verify -DskipITs=false

# HTTP 通道真实链路（对已部署的 soloncode web 实例；token 缺省读 ~/.soloncode/run.token，
# 无 token 文件或服务不可达则自动跳过）
mvn test-compile failsafe:integration-test -DskipITs=false -Dit.test=HttpRunIT \
    -Dsoloncode.http.url=http://127.0.0.1:18080/web/run

# 只跑真实 CLI 连通性验证（不依赖模型可用性：模型 503 时也应通过）
mvn test-compile failsafe:integration-test -DskipITs=false -Dit.test=SolonCodeRealCliIT

# 指定 CLI（例如本地构建的 soloncode-cli jar 包装脚本）
mvn test-compile failsafe:integration-test -DskipITs=false -Dsoloncode.cli.path=/path/to/soloncode
```

CLI 探测（`soloncode --version`）默认给 20 秒超时——它是 JVM 程序，冷启动常需 4~8 秒；
可用 `-Dsoloncode.cli.probe-timeout=<秒>` 调整。

IT 一律在临时目录里跑 CLI，而不是仓库目录：CLI 会把工作区的 `AGENTS.md`/技能/历史会话注入
系统提示（实测提示词从 5.5k tokens 涨到 9.3k+，单轮耗时从 5 秒涨到 190 秒甚至超时）。

`SolonCodeRealCliIT` 只断言 SDK 与 CLI 之间的协议契约（进程启动、`system.init` / `result`
事件解析、会话 ID 贯通、`--resume` 续接、stdin 回退路径），不断言模型答案 —— 因此后端模型限流或
未配置 Key 时它依然是对 SDK 链路的有效验证。
