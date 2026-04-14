# AI Development — Solon AI 开发

> 适用场景：LLM 调用、Tool Calling、RAG、MCP 协议、智能体 Agent。

## ChatModel — LLM 调用

app.yml:
```yaml
solon.ai.chat:
  demo:
    apiUrl: "http://127.0.0.1:11434/api/chat"
    provider: "ollama"
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

// 同步调用
ChatResponse resp = chatModel.prompt("你好").call();
String content = resp.getMessage().getContent();

// 流式调用（需 solon-web-rx）
Flux<ChatResponse> stream = chatModel.prompt("你好").stream();
```

## Tool Calling

```java
@ToolMapping(description = "查询天气")
public String getWeather(@Param(description = "城市") String location) {
    return location + "：晴，14度";
}

@Bean
public ChatModel chatModel(ChatConfig config) {
    return ChatModel.of(config).defaultToolAdd(new WeatherTools()).build();
}
```

## RAG — 检索增强生成

Dependency: `solon-ai-rag`

```java
EmbeddingModel embeddingModel = EmbeddingModel.of("http://127.0.0.1:11434/api/embed")
    .provider("ollama").model("nomic-embed-text").build();

InMemoryRepository repository = new InMemoryRepository(embeddingModel);

// 文档加载与切分
List<Document> docs = new SplitterPipeline()
    .next(new RegexTextSplitter("\n\n"))
    .next(new TokenSizeTextSplitter(500))
    .split(new PdfLoader(new File("data.pdf")).load());
repository.save(docs);

// 检索并构造增强 Prompt
List<Document> context = repository.search("查询问题");
ChatMessage msg = ChatMessage.ofUserAugment("查询问题", context);
```

## MCP — Model Context Protocol

Dependency: `solon-ai-mcp`

Server:
```java
@McpServerEndpoint(channel = McpChannel.STREAMABLE, mcpEndpoint = "/mcp")
public class McpServerTool {
    @ToolMapping(description = "查询天气")
    public String getWeather(@Param(description = "城市") String location) {
        return location + "：晴，14度";
    }
}
```

Client:
```java
McpClientProvider client = McpClientProvider.builder()
    .channel(McpChannel.STREAMABLE)
    .url("http://localhost:8080/mcp").build();
ChatModel chatModel = ChatModel.of(config).defaultToolsAdd(client).build();
```

## Agent — 智能体

Dependency: `solon-ai-agent`

```java
// ReActAgent（自主推理 + 工具调用）
ReActAgent agent = ReActAgent.of(chatModel)
    .name("assistant")
    .defaultToolAdd(new SearchTools())
    .maxSteps(5)
    .build();
String answer = agent.prompt("搜索并总结...").call().getContent();

// TeamAgent（多 Agent 协作）
TeamAgent team = TeamAgent.of(chatModel)
    .name("DevTeam")
    .agentAdd(coder, reviewer)
    .maxTurns(5)
    .build();
String result = team.call(FlowContext.of(), "写一个单例模式");
```

## AI 注解参考

| Annotation | Target | Description |
|---|---|---|
| `@ToolMapping` | Method | 声明 AI 工具方法（用于 Tool Call） |
| `@ToolMapping(name="...")` | Method | 指定工具名称 |
| `@McpServerEndpoint` | Class | 声明 MCP 服务端点 |
| `@Param(description="...")` | Parameter | 工具参数描述 |

## AI 核心 API 参考

| Class/Interface | Description |
|---|---|
| `ChatModel` | LLM 调用核心接口，支持 call/stream |
| `ChatConfig` | ChatModel 配置类，可从 yml 注入 |
| `ChatResponse` | 聊天响应，含 message |
| `ChatMessage` | 消息构建，支持 ofUserAugment |
| `ChatSession` | 会话管理，支持多轮对话 |
| `InMemoryChatSession` | 内存会话实现 |
| `EmbeddingModel` | 嵌入模型接口 |
| `InMemoryRepository` | 内存向量知识库 |
| `SplitterPipeline` | 文档分割管道 |
| `RegexTextSplitter` | 正则分割器 |
| `TokenSizeTextSplitter` | Token 大小分割器 |
| `PdfLoader` / `WordLoader` 等 | 文档加载器 |
| `RerankingModel` | 重排模型 |
| `ReActAgent` | 推理行动 Agent |
| `TeamAgent` | 多 Agent 协作 |
| `McpClientProvider` | MCP 客户端 |
| `McpChannel` | MCP 通道类型（STREAMABLE/SSE/STDIO） |

## AI 核心依赖

| Artifact | Description |
|---|---|
| `solon-ai` | 核心 AI 模块（ChatModel/ToolCall） |
| `solon-ai-rag` | RAG 检索增强生成 |
| `solon-ai-mcp` | MCP 协议支持 |
| `solon-ai-agent` | Agent 框架（ReAct/Team） |
| `solon-ai-flow` | AI + Flow 集成 |

## LLM Dialects

| Artifact | Provider |
|---|---|
| `solon-ai-dialect-openai` | OpenAI / compatible APIs (DeepSeek, etc.) |
| `solon-ai-dialect-ollama` | Ollama |
| `solon-ai-dialect-gemini` | Google Gemini |
| `solon-ai-dialect-claude` | Anthropic Claude |
| `solon-ai-dialect-dashscope` | Alibaba DashScope |

## RAG Document Loaders

| Artifact | Format |
|---|---|
| `solon-ai-load-pdf` | PDF |
| `solon-ai-load-word` | Word |
| `solon-ai-load-excel` | Excel |
| `solon-ai-load-html` | HTML |
| `solon-ai-load-markdown` | Markdown |
| `solon-ai-load-ppt` | PowerPoint |

## RAG Vector Repositories

| Artifact | Backend |
|---|---|
| `solon-ai-repo-milvus` | Milvus |
| `solon-ai-repo-pgvector` | PgVector |
| `solon-ai-repo-elasticsearch` | Elasticsearch |
| `solon-ai-repo-redis` | Redis |
| `solon-ai-repo-qdrant` | Qdrant |
| `solon-ai-repo-chroma` | Chroma |
| `solon-ai-repo-weaviate` | Weaviate |
