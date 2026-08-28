# AI Agent / Talent / Loop — Solon AI 智能体

> 适用场景：Agent（Simple/ReAct/Team）、Talent 才能、Loop。
>
> 目标版本：4.0.4。Harness 见 `ai_harness.md`；ChatModel / RAG / MCP 见 `ai_chat_rag_mcp.md`；AI UI / ACP / A2A 见 `ai_protocol_ui.md`。

## Agent — 智能体

Dependency: `solon-ai-agent`

v3.8.1 后支持。框架内置三种模式的智能体：

| 智能体 | 模式描述 |
|---|---|
| `SimpleAgent` | 简单模式，适用于简单的指令响应 |
| `ReActAgent` | 自省模式，“思考-行动-观察”循环的自反思智能体，支持工具调用 |
| `TeamAgent` | 协作模式，指挥成员按协议（如 A2A、Swarm、Sequential、Hierarchical）进行协作 |

### SimpleAgent Hello World

```java
ChatModel chatModel = ChatModel.of("https://api.moark.com/v1/chat/completions")
        .apiKey("***")
        .model("Qwen3-32B")
        .build();

// 工具有两种写法（二选一）：
// 1) 继承 AbsToolProvider，可直接 defaultToolAdd
// 2) 普通 POJO + MethodToolProvider 包装后再 defaultToolAdd
SimpleAgent robot = SimpleAgent.of(chatModel)
        .role("你是一个时间助手")
        .defaultToolAdd(new TimeTool())
        .build();

String answer = robot.prompt("现在几点了？")
        .call()
        .getContent();

public static class TimeTool extends AbsToolProvider {
    @ToolMapping(description = "获取当前系统时间")
    public String getTime() {
        return LocalDateTime.now().toString();
    }
}

// 普通 POJO 写法：
// public class SearchTools { @ToolMapping ... }
// .defaultToolAdd(new MethodToolProvider(new SearchTools()))
```

### ReActAgent（自主推理 + 工具调用）

```java
ReActAgent agent = ReActAgent.of(chatModel)
    .name("assistant")
    .defaultToolAdd(new MethodToolProvider(new SearchTools())) // 普通 POJO 需包装
    .maxTurns(5)        // v4：原 maxSteps 已更名为 maxTurns
    .autoRethink(true)  // 最大步数自动续航（由 LLM 反思控制）
    .build();
String answer = agent.prompt("搜索并总结...").call().getContent();
```

### TeamAgent（多 Agent 协作）

```java
// TeamProtocols 预置：NONE / SEQUENTIAL / HIERARCHICAL / MARKET_BASED /
// CONTRACT_NET / BLACKBOARD / SWARM / A2A（共 8 种）
TeamAgent team = TeamAgent.of(chatModel)
    .name("DevTeam")
    .protocol(TeamProtocols.SEQUENTIAL)
    .agentAdd(coder, reviewer)
    .build();
String result = team.prompt("写一个单例模式").call().getContent();
```

### Agent 接口核心属性

| 维度 | 属性 | 描述 |
|---|---|---|
| 身份 | `name` | 唯一标识：智能体在团队中的名字 |
| 角色 | `role` | 智能体角色职责（用于 Prompt 提示与协作分发参考） |
| 画像 | `profile` | 交互契约：定义能力画像、输入限制等约束条件 |
| 执行 | `call` | 核心逻辑：具体的推理与工具执行过程 |

### Agent 事件体系（AgentEvent, 4.0.4+）

Agent 流式输出统一为 `AgentEvent` 体系（原 `AgentChunk` 已更名）。`stream()` 返回 `Flux<AgentEvent>`。

**事件类层次（按生命周期分组）：**

| 阶段 | 事件类 | 说明 |
|---|---|---|
| **ReAct 运行** | `RunStartEvent` | 任务运行开始 |
| | `RunEndEvent` | 任务运行结束 |
| **思考 (Reason)** | `ReasonStartEvent` | 思考运行开始 |
| | `ReasonDeltaEvent` | 思考流式片段 |
| | `ReasonEndEvent` | 思考运行结束（含 durationMs） |
| **动作 (Action)** | `ActionStartEvent` | 动作阶段开始 |
| | `ToolCallStartEvent` | 工具调用开始 |
| | `ToolCallEndEvent` | 工具调用结束（含 observation/error） |
| | `ActionEndEvent` | 动作阶段结束 |
| **计划** | `PlanEvent` | ReAct 计划块（Planning） |
| **HITL** | `HITLPendingEvent` | 人工审核挂起 |
| | `HITLDecidedEvent` | 人工审核决策生效 |
| **上下文** | `ContextSizeEvent` | 上下文大小状态 |
| **Team** | `TeamStartEvent` / `TeamEndEvent` | 团队运行开始/结束 |
| | `NodeStartEvent` / `NodeEndEvent` | 协作节点开始/结束 |
| | `SupervisorDeltaEvent` | 团队指导者决策片段 |
| **Simple** | `SimpleStartEvent` / `SimpleEndEvent` | 简单智能体运行开始/结束 |
| | `ChatDeltaEvent` | 智能体对话片段 |

```java
agent.prompt("搜索并总结...").stream()  // 返回 Flux<AgentEvent>
    .subscribe(event -> {
        if (event instanceof ReasonDeltaEvent) {
            System.out.print(((ReasonDeltaEvent) event).getContent());
        } else if (event instanceof ToolCallEndEvent) {
            System.out.println("工具完成: " + event.getMessage());
        }
    });
```

### ReActInterceptor 拦截器

ReAct 循环的拦截器接口，提供细粒度的生命周期钩子：

| 方法 | 说明 |
|---|---|
| `onAgentStart(trace)` | 智能体开始执行前 |
| `onReasonStart(trace, systemPromptBuf)` | Reason 阶段开始（systemPrompt 构建前） |
| `onReasonRetry(trace, error, attempt, systemPrompt)` | Reason 请求失败重试，返回 true 表示已修改上下文（4.0.4+） |
| `onReasonEnd(trace, resp, message, durationMs)` | 接收 LLM 返回的原始推理消息 |
| `onPlan(trace, message)` | 计划节点：接收 LLM 返回的原始推理消息 |
| `onActionStart(trace, toolCalls)` | 动作节点开始（4.0.4+） |
| `onToolCallStart(trace, toolExchanger)` | 调用工具前（权限控制、参数预检） |
| `onToolCallEnd(trace, toolExchanger, observation, error, durationMs)` | 工具执行完成后（finally 块，强闭环） |
| `onActionEnd(trace, toolCalls)` | 动作节点结束（4.0.4+） |
| `onAgentEnd(trace)` | 智能体任务结束 |

> **@Deprecated 方法**（4.0.4+）：`onThought` → 由 `onReasonEnd` 替代；`onAction` → 由 `onToolCallStart` 替代；`onObservation` → 由 `onToolCallEnd` 替代。

**执行时序：**

```
onAgentStart
  ├─ onReasonStart(systemPromptBuf)
  │    ├─ [流式] onReasonEnd(resp, message, durationMs)
  │    └─ [失败重试] onReasonRetry(error, attempt, systemPrompt) → 循环
  ├─ onPlan(message)
  ├─ onActionStart(toolCalls)
  │    ├─ onToolCallStart(toolExchanger)
  │    └─ onToolCallEnd(toolExchanger, observation, error, durationMs)
  ├─ onActionEnd(toolCalls)
  └─ [循环 Reason→Action 直到完成]
onAgentEnd
```

### HITL 策略接口（HITLStrategy, 4.0.4+）

`HITLStrategy` 是函数式接口，替代原 `HITLInterceptor.InterventionStrategy`（后者已标 `@Deprecated`）：

```java
@FunctionalInterface
public interface HITLStrategy {
    /**
     * @return 拦截原因（触发拦截）；null（不拦截，直接执行）
     */
    String evaluate(ReActTrace trace, Map<String, Object> args);
}
```

```java
HarnessEngine engine = HarnessEngine.of("work", ".soloncode/")
        .hitlStrategy((trace, args) -> {
            if (args.containsKey("danger")) {
                return "危险操作需确认";
            }
            return null; // 不拦截
        })
        .build();
```

### 模型选项统一 API（4.0.4+）

通过 `ModelOptionsAmend` 提供统一的推理控制选项（跨方言通用）：

```java
ChatModel chatModel = ChatModel.of(config)
        .reasoning_effort("high")  // 推理水平：low / medium / high / max
        .thinking(true)            // 思考模式开关
        .build();

// thinking(false) 关闭优先，压过 reasoning_effort
// 仅设 reasoning_effort 时多数方言会隐式开启 thinking
```

> AI UI / ACP / A2A 协议对接见 **`ai_protocol_ui.md`**。
>
> A2A 协议入口：`TeamProtocols.A2A`（依赖 `solon-ai-agent`，无独立 `solon-ai-a2a` 模块）。

## AI Talents — 才能体系

Dependency: 各 `solon-ai-talent-*` 插件

v4.0.0 起，原 "Skill 技能" 体系正式更名为 "Talent 才能" 体系（概念原型参考 Claude Code Agent Skills，但从"运行时学习"翻转为"开发时注入"）。Talent 是一种可插拔的能力扩展机制，可动态加载到 ChatModel 或 Agent 中使用。

> 命名迁移提示（v3 → v4）：插件 `solon-ai-skill-*` → `solon-ai-talent-*`；添加方法 `defaultSkillAdd(...)` → `defaultTalentAdd(...)`。

### Talent 接口（开发时注入）

Talent 通过生命周期钩子，在开发时定义激活条件、指令策略与工具集。常用做法是继承 `AbsTalent`：

```java
@Component
public class WeatherTalent extends AbsTalent {
    // 准入检查：当前对话上下文中该才能是否被激活
    @Override
    public boolean isSupported(Prompt prompt) {
        String role = prompt.attrAs("role"); // 可取属性做准入控制
        return prompt.getUserContent().contains("天气");
    }

    // 动态指令注入：生成并注入到 System Message 的描述性文本
    @Override
    public String getInstruction(Prompt prompt) {
        return "如果有什么天气问题，可以问我";
    }

    // 动态能力注入：通过 @ToolMapping 暴露工具方法
    @ToolMapping(description = "查询天气预报")
    public String getWeather(@Param(description = "城市位置") String location) {
        return "晴，14度";
    }
}
```

Talent 接口核心方法：`name()`、`description()`、`metadata()`、`isSupported(Prompt)`、`onAttach(Prompt)`、`getInstruction(Prompt)`、`getTools(Prompt)`。

### Talent 注册（添加方式与 tool 一致）

```java
@Bean
public ChatModel chatModel(WeatherTalent weatherTalent) {
    return ChatModel.of(config)
            .defaultTalentAdd(weatherTalent) // v4：原 defaultSkillAdd
            .build();
}
```

### 预置才能（部分常用包）

| Artifact | 代表 Talent | 描述 |
|---|---|---|
| `solon-ai-talent-cli` | `TerminalTalent` / `SkillTalent` / `TodoTalent` / `ClockTalent` | 终端命令、技能管理、任务进度、时钟 |
| `solon-ai-talent-web` | `WebsearchTalent` / `WebfetchTalent` / `CodeSearchTalent` / `WebCrawlerDriverTalent` / `WebSearchDriverTalent` | 网络搜索、网页抓取、代码搜索、爬虫/搜索驱动 |
| `solon-ai-talent-gateway` | `ToolGatewayTalent` / `McpGatewayTalent` / `OpenApiGatewayTalent` | 工具/MCP/OpenAPI 网关 |
| `solon-ai-talent-text2sql` | `Text2SqlTalent` | 自然语言转 SQL |
| `solon-ai-talent-data` | `RedisTalent` | Redis 长期记忆 |
| `solon-ai-talent-file` | `FileReadWriteTalent` / `ZipTalent` | 文件读写、压缩归档 |
| `solon-ai-talent-pdf` | `PdfTalent` | PDF 读取与排版生成 |
| `solon-ai-talent-generation` | `ImageGenerationTalent` / `VideoGenerationTalent` | 图片/视频生成 |
| `solon-ai-talent-mail` | `MailTalent` | 邮件发送 |
| `solon-ai-talent-social` | `DingTalkTalent` / `FeishuTalent` / `WeComTalent` | 钉钉/飞书/企业微信推送 |
| `solon-ai-talent-sys` | `NodejsTalent` / `PythonTalent` / `ShellTalent` / `SystemClockTalent` | 脚本与系统运维 |
| `solon-ai-talent-code` | 代码工程规范才能 | 4.0.3+，从 harness 拆出 |
| `solon-ai-talent-diff` | `ApplyDiffTalent` / `ApplyPatchTalent` | 差异/补丁应用（4.0.4+） |
| `solon-ai-talent-lucene` | `LuceneTalent` | Lucene 全文索引才能（4.0.4+） |
| `solon-ai-talent-lsp` | `LspTalent` | LSP 代码导航才能（4.0.4+） |
| `solon-ai-talent-mount` | `MountManager` / `MountDir` | 挂载点管理（4.0.4+） |
| `solon-ai-talent-memory` | `MemoryTalent` | 长期记忆管理（4.0.4+） |

## Loop — 循环执行引擎（4.0.3+）

Dependency: `solon-ai-loop`

```java
// 默认引擎（内存状态）
LoopEngine engine = LoopAutoConfiguration.createDefaultEngine();

// 或磁盘状态 + 监控
LoopEngine engine = new LoopAutoConfiguration()
        .useDiskState("/path/to/project")
        .enableMonitoring(true)
        .build();

RalphLoopStrategy strategy = RalphLoopStrategy.builder()
        .verificationRequired(false)
        .maxIterations(5)
        .build();

LoopConfig config = LoopConfig.builder()
        .taskDescription("Implement user login feature")
        .strategy(strategy)
        .maxIterations(5)
        .build();

LoopSession session = engine.start(config);
session.waitForCompletion(java.time.Duration.ofSeconds(30));
LoopResult result = session.getResult();
```

> 详细策略（Ralph / Team / UltraQA）、管线与状态目录见 `solon-ai-loop` 模块 README。

## Agent / Loop 依赖索引

| Artifact | Description |
|---|---|
| `solon-ai-agent` | Agent 框架（Simple/ReAct/Team，含 `TeamProtocols.A2A`） |
| `solon-ai-loop` | 循环执行引擎（4.0.3+） |
| `solon-ai-talent-cli` | CLI 才能 |
| `solon-ai-talent-code` | 代码工程规范才能（4.0.3+） |
| `solon-ai-talent-web` | Web 才能 |
| `solon-ai-talent-gateway` | 网关才能 |
| `solon-ai-talent-diff` | 差异/补丁应用才能（4.0.4+） |
| `solon-ai-talent-lucene` | Lucene 全文索引才能（4.0.4+） |
| `solon-ai-talent-lsp` | LSP 代码导航才能（4.0.4+） |
| `solon-ai-talent-mount` | 挂载点管理才能（4.0.4+） |
| `solon-ai-talent-memory` | 长期记忆管理才能（4.0.4+） |
| `solon-ai-talent-*` | 其它预置才能（见上文表格） |
| `solon-ai-harness` | 马具框架 → 见 `ai_harness.md` |
| `solon-ai-ui-aisdk` / `solon-ai-acp` | UI/协议 → 见 `ai_protocol_ui.md` |

## 4.0.3 AI 增量要点

| 能力 | 说明 |
|---|---|
| `solon-ai-loop` | 循环执行引擎（借鉴 oh-my-claudecode 设计；依赖 flow/expression/ai/harness） |
| `solon-ai-talent-code` | 代码工程规范才能（从 harness 拆出） |
| `GenerateTalent` | 原 harness 内 `GenerateTool` 更名为 `GenerateTalent`，便于动态启停 |
| `Talent.setEnabled` | 接口级开关 |
| A2A | 使用 `TeamProtocols.A2A`（`solon-ai-agent`），无独立 `solon-ai-a2a` 模块 |

## 4.0.4 AI 增量要点

| 能力 | 说明 |
|---|---|
| `ToolName` | 替代 `ToolPermission`（`@Deprecated`）；`TOOL_RESTAPI` 更名为 `TOOL_OPENAPI` |
| `AgentEvent` 体系 | 原 `AgentChunk` 更名为 `AgentEvent`，新增 15+ Event 子类（RunStart/End、ReasonStart/End、ActionStart/End、ToolCallStart/End、HITLPending/Decided、TeamStart/End 等） |
| ReActInterceptor 新事件 | `onActionStart/End`、`onToolCallStart/End`、`onReasonRetry`；旧 `onThought`/`onAction`/`onObservation` 标弃用 |
| `HITLStrategy` | 函数式接口，替代 `InterventionStrategy`（`@Deprecated`） |
| `reasoning_effort` / `thinking` | 统一模型选项 API（跨方言通用） |
| `compressionMaxContextRatio` | 替代 `compressionMaxTokens`，按上下文窗口比例触发压缩 |
| `getModelOrDefInstance` | 替代 `getModelOrMain`（`@Deprecated`） |
| `allowToolReset` / `disallowToolReset` | 工具权限全量重置（原子操作，自动重建 Agent） |
| `AgentDefinition.builder()` | 链式 Builder 构造方法 |
| `TaskWrapChuck` | 子代理流块包装，提高调用透明度 |
| Skills/Agents 局部刷新 | `refreshSkills()` / `refreshAgents()`，无需重启引擎 |
| `ChatRequestDesc` | 新增 `role()` / `instruction()` / `systemPrompt()` 链式方法 |
| `Prompt.copy()` | Prompt 复制方法 |
| 新增 Talent | `diff`（ApplyDiffTalent/ApplyPatchTalent）、`lucene`（LuceneTalent）、`lsp`（LspTalent）、`mount`（MountManager）、`memory`（MemoryTalent） |

## 4.0.5 AI 增量要点（待发布）

> 以下内容基于源码 UPDATE_LOG 预览，4.0.5 尚未正式发布。

| 能力 | 说明 |
|---|---|
| MCP SSE 兼容修复 | 修复 SSE 传输模式兼容性问题 |
| TaskTalent maxTasks | 修复子代理最大任务数限制 |
| Memory 引导词优化 | 优化记忆系统引导词 |
| 调试与稳定性 | 多项 bug 修复与性能优化 |

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-loop</artifactId>
</dependency>
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-talent-code</artifactId>
</dependency>
```
