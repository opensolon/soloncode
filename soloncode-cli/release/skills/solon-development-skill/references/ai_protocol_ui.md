# AI Protocol / UI — AI UI · ACP · A2A

> 适用场景：Vercel AI SDK UI 流式协议、ACP 传输、A2A 多 Agent 协议。
>
> 目标版本：4.0.3。Agent / Talent / Loop 见 `ai_agent.md`；Harness 见 `ai_harness.md`；Chat/RAG/MCP 见 `ai_chat_rag_mcp.md`。

## AI UI — 对接 Vercel AI SDK

Dependency: `solon-ai-ui-aisdk`

将 `ChatModel.prompt().stream()` 的 `Flux<ChatResponse>` 自动转换为 [UI Message Stream Protocol v1](https://ai-sdk.dev/docs/ai-sdk-ui/stream-protocol) 格式的 SSE 事件流，前端可直接使用 `@ai-sdk/vue` 或 `@ai-sdk/react` 的 `useChat`。

支持：文本流、深度思考(reasoning)、工具调用(tool-calls)、搜索结果引用(source-url)、文档引用(source-document)、文件(file)、自定义数据(data-*)、元数据(metadata)。

### 后端示例

```java
@Controller
public class AiChatController {
    @Inject ChatModel chatModel;
    private final AiSdkStreamWrapper wrapper = AiSdkStreamWrapper.of();

    @Produces(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE)
    @Mapping("/ai/chat/stream")
    public Flux<SseEvent> stream(String prompt, Context ctx) {
        ctx.headerSet("x-vercel-ai-ui-message-stream", "v1");
        return wrapper.toAiSdkStream(chatModel.prompt(prompt).stream());
    }
}
```

### 带会话记忆 + 元数据

```java
@Controller
public class AiChatController {
    @Inject ChatModel chatModel;
    private final AiSdkStreamWrapper wrapper = AiSdkStreamWrapper.of();
    private final Map<String, ChatSession> sessionMap = new ConcurrentHashMap<>();

    @Produces(MimeType.TEXT_EVENT_STREAM_UTF8_VALUE)
    @Mapping("/ai/chat/stream")
    public Flux<SseEvent> stream(@Header("sessionId") String sessionId,
                                 String prompt, Context ctx) {
        ctx.headerSet("x-vercel-ai-ui-message-stream", "v1");
        ChatSession session = sessionMap.computeIfAbsent(sessionId,
                k -> InMemoryChatSession.builder().sessionId(k).build());
        Map<String, Object> metadata = Map.of("sessionId", sessionId);
        return wrapper.toAiSdkStream(
                chatModel.prompt(prompt).session(session).stream(), metadata);
    }
}
```

### 前端对接（Vue 3 + @ai-sdk/vue）

```vue
<script setup lang="ts">
import { useChat } from '@ai-sdk/vue'
const { messages, input, handleSubmit, status } = useChat({
  api: '/ai/chat/stream'
})
</script>
```

### 核心 Part 类

| Part 类 | type 值 | 说明 |
|---|---|---|
| `StartPart` | `start` | 流开始（含 messageId） |
| `TextStartPart` / `TextDeltaPart` / `TextEndPart` | `text-start` / `text-delta` / `text-end` | 正文流 |
| `ReasoningStartPart` / `ReasoningDeltaPart` / `ReasoningEndPart` | `reasoning-*` | 深度思考流 |
| `ToolInputStartPart` / `ToolInputDeltaPart` / `ToolInputAvailablePart` / `ToolOutputAvailablePart` | `tool-*` | 工具调用流 |
| `SourceUrlPart` / `SourceDocumentPart` | `source-url` / `source-document` | 引用来源 |
| `FilePart` | `file` | 文件附件 |
| `DataPart` | `data-*` | 自定义数据 |
| `FinishPart` | `finish` | 流结束（含 usage） |
| `ErrorPart` | `error` | 错误 |

### 自定义 Data Part

```java
// JDK8：用 HashMap，不要 Map.of（Java 9+）
Map<String, Object> data = new HashMap<>();
data.put("location", "SF");
data.put("temperature", 100);
DataPart weatherPart = DataPart.of("weather", data);
// → {"type":"data-weather","data":{"location":"SF","temperature":100}}
```

## ACP — Agent Client Protocol

Dependency: `solon-ai-acp`

提供 ACP 协议支持（stdio、websocket）。最小 WebSocket Agent 传输示例：

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-acp</artifactId>
</dependency>
```

```java
// 在 Solon 应用内注册 ACP WebSocket 传输（路径可自定义）
McpJsonMapper jsonMapper = ...;
WebSocketSolonAcpAgentTransport transport =
        new WebSocketSolonAcpAgentTransport("/acp", jsonMapper);
// 客户端使用 WebSocketSolonAcpClientTransport 连接 ws://host:port/acp
```

> 完整会话/工具能力以 `solon-ai-acp` 与 ACP SDK 为准；本 skill 只保证依赖与传输入口正确。

## A2A — Agent to Agent

A2A **不是**独立 artifact。入口在 `solon-ai-agent` 的 `TeamProtocols.A2A`：

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-agent</artifactId>
</dependency>
```

```java
TeamAgent team = TeamAgent.of(chatModel)
        .protocol(TeamProtocols.A2A)
        .agentAdd(designer, developer)
        .build();
String result = team.prompt("设计并实现一个登录接口").call().getContent();
```
