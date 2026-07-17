# AI Agent / Talent / Loop — Solon AI 智能体

> 适用场景：Agent（Simple/ReAct/Team）、Talent 才能、Loop。
>
> 目标版本：4.0.3。Harness 见 `ai_harness.md`；ChatModel / RAG / MCP 见 `ai_chat_rag_mcp.md`；AI UI / ACP / A2A 见 `ai_protocol_ui.md`。

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
| `solon-ai-talent-cli` | `TerminalTalent` / `SkillTalent` / `TodoTalent` | 终端命令、技能管理、任务进度 |
| `solon-ai-talent-web` | `WebsearchTalent` / `WebfetchTalent` / `CodeSearchTalent` | 网络搜索、网页抓取、代码搜索 |
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
