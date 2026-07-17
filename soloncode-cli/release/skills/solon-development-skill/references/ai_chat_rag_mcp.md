# AI Chat / RAG / MCP — Solon AI 基础能力

> 适用场景：LLM 调用、Tool Calling、RAG 流水线、MCP 协议、生成模型、方言与依赖。
>
> 目标版本：4.0.3。
> - Agent / Talent / Loop → `ai_agent.md`
> - Harness → `ai_harness.md`
> - AI UI / ACP / A2A → `ai_protocol_ui.md`
> - 文档加载器 / 向量库 / 联网搜索插件表 → `ai_rag_plugins.md`

## ChatModel — LLM 调用

Dependency: `solon-ai`（含 `solon-ai-core` 及方言）或单独 `solon-ai-core`。

### 配置器构建

```yaml
solon.ai.chat:
  demo:
    apiUrl: "http://127.0.0.1:11434/api/chat" # 使用完整地址（不是 api_base）
    provider: "ollama" # ollama 服务需配置 provider
    model: "llama3.2"
```

```java
@Configuration
public class AiConfig {
    @Bean
    public ChatModel chatModel(@Inject("${solon.ai.chat.demo}") ChatConfig config) {
        return ChatModel.of(config).build();
    }
}
```

### Builder 原始构建

```java
ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
        .headerSet("x-demo", "demo1")
        .provider("ollama")
        .model("llama3.2")
        .build();
```

### 调用示例

```java
// 同步
ChatResponse resp = chatModel.prompt("你好").call();
String content = resp.getMessage().getContent();

// 流式（需 solon-web-rx）
Flux<ChatResponse> stream = chatModel.prompt("你好").stream();
```

## Tool Calling

### @ToolMapping

```java
public class WeatherTools extends AbsToolProvider {
    @ToolMapping(description = "查询天气")
    public String getWeather(@Param(description = "城市") String location) {
        return location + "：晴，14度";
    }
}
```

### 工具注册

```java
// 方式1：对象注册（推荐，自动扫描 @ToolMapping）
@Bean
public ChatModel chatModel(ChatConfig config) {
    return ChatModel.of(config).defaultToolAdd(new WeatherTools()).build();
}

// 方式2：ToolProvider（如 McpClientProvider）
@Bean
public ChatModel chatModel(ChatConfig config, McpClientProvider clientProvider) {
    return ChatModel.of(config).defaultToolAdd(clientProvider).build();
}
```

### 工具属性

| 属性 | 描述 |
|---|---|
| `name` / `title` / `description` | 名称 / MCP title / LLM 识别描述 |
| `returnDirect` | 直接返回调用者（跳过 LLM 再加工） |
| `inputSchema` / `outputSchema` / `meta` | 输入输出架构 / 元信息 |

## RAG — 检索增强生成

Dependency: `solon-ai` 或 `solon-ai-core`（含 `InMemoryRepository` / `EmbeddingModel` / `RerankingModel`）。**无**独立 `solon-ai-rag` 坐标。

Loader / 向量库 / 搜索插件表 → `ai_rag_plugins.md`。

### EmbeddingModel

```java
EmbeddingModel embeddingModel = EmbeddingModel.of("http://127.0.0.1:11434/api/embed")
    .provider("ollama").model("bge-m3:latest").build();

// 配置器：solon.ai.embed.demo → @Inject("${solon.ai.embed.demo}") EmbeddingConfig
float[] data = embeddingModel.embed("比较原始的风格");
embeddingModel.embed(documents); // 批量
```

### RerankingModel

```java
RerankingModel rerankingModel = RerankingModel.of("https://api.moark.com/v1/rerank")
    .apiKey("***").model("bge-reranker-v2-m3").build();

// 配置器：solon.ai.rerank.demo → RerankingConfig
documents = rerankingModel.rerank(query, documents);
```

### 文档加载与检索流水线

```java
InMemoryRepository repository = new InMemoryRepository(embeddingModel);

List<Document> docs = new SplitterPipeline()
    .next(new RegexTextSplitter("\n\n"))
    .next(new TokenSizeTextSplitter(500))
    .split(new PdfLoader(new File("data.pdf")).load());
repository.save(docs); // 写入用 save，不要用 insert

List<Document> context = repository.search("查询问题");
ChatMessage msg = ChatMessage.ofUserAugment("查询问题", context);
```

> PdfLoader 等需引入对应 `solon-ai-load-*`；完整插件表见 `ai_rag_plugins.md`。

## MCP — Model Context Protocol

Dependency: `solon-ai-mcp`。协议 MCP_2025-03-26；可嵌入 Solon / SpringBoot 等。

### 传输方式

| 服务端 | 客户端 | 备注 |
|---|---|---|
| `STDIO` | `STDIO` | 有状态，支持反向通讯 |
| `SSE` | `SSE` | 有状态（官方已弃用） |
| `STREAMABLE` | `STREAMABLE` | 有状态，支持反向通讯（v3.5.0+） |
| `STREAMABLE_STATELESS` | `STREAMABLE` | 无状态，集群友好（v3.8.0+） |

### Server / Client

```java
@McpServerEndpoint(channel = McpChannel.STREAMABLE, mcpEndpoint = "/mcp")
public class McpServerTool {
    @ToolMapping(description = "查询天气")
    public String getWeather(@Param(description = "城市") String location) {
        return location + "：晴，14度";
    }
}

McpClientProvider client = McpClientProvider.builder()
    .channel(McpChannel.STREAMABLE)
    .url("http://localhost:8080/mcp").build();

String result = client.callTool("getWeather", Map.of("location", "杭州")).getContent();

ChatModel chatModel = ChatModel.of(chatConfig).defaultToolAdd(client).build();
```

### Client 配置器

```yaml
solon.ai.mcp.client.demo:
  channel: "streamable"
  url: "http://localhost:8080/mcp"
```

```java
@Bean
public McpClientProvider mcpClient(
        @Inject("${solon.ai.mcp.client.demo}") McpClientProvider clientProvider) {
    return clientProvider;
}
```

## GenerateModel — 生成模型（图/音/视）

由 GenerateModel 体系替代原 ImageModel：

```java
GenerateModel generateModel = GenerateModel.of(apiUrl)
        .provider(...)
        .model(...)
        .build();
GenerateResponse resp = generateModel.prompt("一只猫的插画").call();
```

## AI 注解与核心 API

| Annotation | Description |
|---|---|
| `@ToolMapping` | AI 工具方法（必填 description；可选 name / returnDirect） |
| `@McpServerEndpoint` | MCP 服务端点（必填 channel, mcpEndpoint） |
| `@Param(description=...)` | 工具参数描述 |

| Class | Description |
|---|---|
| `ChatModel` / `ChatConfig` / `ChatResponse` / `ChatMessage` / `ChatSession` | 聊天核心 |
| `ChatDialect` / `FunctionTool` / `ToolProvider` / `MethodToolProvider` | 方言与工具 |
| `EmbeddingModel` / `RerankingModel` / `InMemoryRepository` / `SplitterPipeline` | RAG 核心 |
| `McpClientProvider` / `McpChannel` | MCP |
| `GenerateModel` | 图/音/视生成 |

## 核心依赖与方言

| Artifact | Description |
|---|---|
| `solon-ai-core` | Chat/Tool/Session/**RAG 核心** |
| `solon-ai` | 聚合（core + 方言） |
| `solon-ai-load-*` / `solon-ai-repo-*` / `solon-ai-search-*` | 插件表见 `ai_rag_plugins.md` |
| `solon-ai-mcp` | MCP |
| `solon-ai-flow` | AI + Flow |

| Artifact | Provider | 描述 |
|---|---|---|
| `solon-ai-dialect-openai` | `openai`（默认） | DeepSeek / QWen / GLM / Kimi / GPT 等兼容 |
| `solon-ai-dialect-ollama` | `ollama` | Ollama |
| `solon-ai-dialect-gemini` | `gemini` | Google Gemini（v3.8.1+ 试用） |
| `solon-ai-dialect-anthropic` | `anthropic` | Claude（v3.9.1+ 试用） |
| `solon-ai-dialect-dashscope` | `dashscope` | 阿里云百炼 |

> 匹配不到方言：检查 provider 配置或 pom 是否缺方言包。