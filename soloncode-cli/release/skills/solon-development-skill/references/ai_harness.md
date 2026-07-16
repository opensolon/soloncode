# AI Harness — 智能体马具框架

> 适用场景：HarnessEngine、工具权限、子代理、拦截器、命令系统。
>
> 目标版本：4.0.3。Agent / Talent / Loop 见 `ai_agent.md`；AI UI / ACP / A2A 见 `ai_protocol_ui.md`。

Dependency: `solon-ai-harness`

v4.0.0 起完善。通过 `solon-ai-talent-*` 插件组合并定制而成的高性能智能体执行框架。理论上可嵌入到任意 Java 项目中。

综合示例项目：
- [SolonCode（基于 Java 8 实现的 "Claude Code" 或 "OpenCode"）](https://gitee.com/opensolon/soloncode)
- [SolonClaw（基于 Java 8 实现的 "OpenClaw" 或 "Moltbot"）](https://gitee.com/opensolon/solonclaw)

## 核心职责

- **工具使用 (Tool Steering)**: 动态挂载、MCP 协议、安全拦截
- **记忆与会话 (Memory & Session)**: 持久化、短期/长期记忆、快照恢复
- **上下文工程 (Context Engineering)**: 窗口滑窗、语义压缩、意图聚焦
- **环境隔离 (Sandbox)**: 影子空间、自愈循环

## 依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-harness</artifactId>
</dependency>
```

## Helloworld

```java
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.permission.ToolPermission;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DemoApp {
    public static void main(String[] arg) throws Throwable {
        AgentSessionProvider sessionProvider = new AgentSessionProvider() {
            private Map<String, AgentSession> sessionMap = new ConcurrentHashMap<>();

            @Override
            public AgentSession getSession(String instanceId) {
                return sessionMap.computeIfAbsent(instanceId, k -> InMemoryAgentSession.of(k));
            }
        };

        //--- 1. 初始化（v4：流式构建，不再使用 HarnessProperties）
        HarnessEngine engine = HarnessEngine.of("work", ".soloncode/") // 工作区、马具主目录
                .systemPrompt("xxx")                  // Harness 侧：systemPrompt(String)
                .modelAdd(new ChatConfig())           // Builder：modelAdd（可多个，第一个为默认）
                .toolsAdd(ToolPermission.TOOL_WEBSEARCH) // 设定工具权限
                .sessionProvider(sessionProvider)
                .build();
        // 构建后运行时动态加模型用：engine.addModel(new ChatConfig());

        //--- 用主代理执行
        case1(engine, "hello");

        //--- 动态创建子代理执行（可以动态创建不同的工具权限）
        case2(engine, "hello");
    }

    private static void case1(HarnessEngine engine, String prompt) throws Throwable {
        AgentSession session = engine.getSession("default");

        engine.prompt(prompt)
                .session(session) //没有，则为临时会话
                .options(o -> {
                    //按需，动态指定工作区（没有，则为默认工作区）
                    o.toolContextPut(HarnessEngine.ATTR_CWD, "xxx");
                })
                .call();
    }

    private static void case2(HarnessEngine engine, String prompt) throws Throwable {
        AgentSession session = engine.getSession("default");

        AgentDefinition definition = new AgentDefinition();
        definition.setSystemPrompt("xxx");
        definition.getMetadata().addTools(ToolPermission.TOOL_BASH);

        ReActAgent subagent = engine.createSubagent(definition).build();
        subagent.prompt(prompt)
                .session(session)
                .options(o -> {
                    o.toolContextPut(HarnessEngine.ATTR_CWD, "xxx");
                })
                .call();
    }
}
```

## 核心配置项（v4 流式构建）

```java
HarnessEngine engine = HarnessEngine.of("work", ".soloncode/")
        .systemPrompt("你是一个 AI 助手")
        .sessionWindowSize(8)
        .compressionThreshold(30, 30_000) // 消息条数阈值、token 阈值
        .maxTurns(30)
        .autoRethink(true)
        .toolsAdd(ToolPermission.TOOL_ALL_FULL)
        .sessionProvider(sessionProvider)
        .build();
```

构建完成后仍可在运行时动态调整（变更后自动重建主代理立即生效）：

```java
engine.allowTool("websearch");
engine.disallowTool("bash");
engine.setMaxTurns(30);
engine.setCompressionThreshold(30, 30_000);
engine.setSandboxEnabled(true);
engine.addModel(new ChatConfig());
engine.setDefaultModel("deepseek-v4-flash");
```

### 核心配置

> v4 字段更名：`maxSteps`→`maxTurns`、`maxStepsAutoExtensible`→`autoRethink`、`summaryWindowSize`→`compressionMaxMessages`、`summaryWindowToken`→`compressionMaxTokens`、`summaryModel`→`compressionModel`、`sandboxMode`→`sandboxEnabled`、`mountPools`→`mounts`。`models` 由 `List` 改为 `Map`。

| 配置项 | 类型 | 默认值 | 描述 |
|---|---|---|---|
| `workspace` | `String` | `work` | 工作区 |
| `harnessHome` | `String` | `.solon/` | 马具主目录（例：`.soloncode`） |
| `systemPrompt` | `String` | / | 系统提示词 |
| `tools` | `Set<String>` | / | 工具权限（`**`=所有工具；`*`=仅公域工具） |
| `disallowedTools` | `Set<String>` | / | 禁用工具（具体工具名） |
| `defaultModel` | `String` | / | 默认模型名（不指定则取 models 中第一个） |
| `models` | `Map<String, ChatConfig>` | / | 大模型配置 |
| `maxTurns` | int | `20` | 根代理最大循环步数 |
| `autoRethink` | bool | `true` | 最大步数自动续航（由 LLM 反思控制） |
| `sessionWindowSize` | int | `8` | 会话历史窗口大小 |
| `compressionMaxMessages` | int | `30` | 触发上下文压缩的消息条数阈值 |
| `compressionMaxTokens` | int | `30000` | 触发上下文压缩的内容长度阈值 |
| `compressionModel` | `String` | / | 压缩用大模型（不指定则使用主模型） |

### 安全与行为配置

| 配置项 | 类型 | 默认值 | 描述 |
|---|---|---|---|
| `sandboxEnabled` | bool | `true` | 沙盒模式，启用时禁止访问绝对路径 |
| `sandboxAllowUserHome` | bool | `true` | 沙盒下允许访问用户主目录 |
| `sandboxSystemRestrict` | bool | `true` | 沙盒系统级限制 |
| `hitlEnabled` | bool | `false` | 人工审核（危险操作需确认） |
| `subagentEnabled` | bool | `true` | 子代理模式 |
| `bashAsyncEnabled` | bool | `false` | Bash 异步执行 |
| `memoryEnabled` | bool | `true` | 心智记忆 |
| `userAgent` | `String` | / | 用户代理标识（自动传播给所有模型） |
| `apiRetries` / `mcpRetries` / `modelRetries` | int | `3` | 重试次数 |

### 扩展配置

| 配置项 | 类型 | 描述 |
|---|---|---|
| `mounts` | `MountDir` | 挂载配置（alias 须以 `@` 开头） |
| `mcpServers` | `Map<String, McpServerParameters>` | MCP 服务 |
| `apiServers` | `Map<String, ApiSource>` | Web API 服务 |
| `lspServers` | `Map<String, LspServerParameters>` | LSP 服务 |
| `extensions` | `List<HarnessExtension>` | 扩展接口 |

## 工具权限配置 (ToolPermission)

常用枚举（`toolsAdd(...)` / `allowTool`）：

| 类别 | 工具名 / 枚举 | 说明 |
|---|---|---|
| 全集 | `**`=`TOOL_ALL_FULL`；`*`=`TOOL_ALL_PUBLIC` | 全量 / 仅公域 |
| 聚合 | `pi`=`TOOL_PI` | read+write+edit+bash |
| 私域 | `hitl` / `generate` / `restapi` / `mcp` | 审核、生成子代理、API/MCP 接入 |
| 公域检索 | `websearch` / `webfetch` / `codesearch` / `lsp` / `code` | 搜索与代码理解 |
| 公域工程 | `todo` / `skill` / `task` / `bash` / `ls` / `grep` / `glob` / `edit` / `read` / `write` | 任务与文件操作 |

枚举常量命名：工具名大写加 `TOOL_` 前缀（如 `TOOL_WEBSEARCH`）。

## 调用与流式请求

`engine.prompt(...)` 返回 `ReActRequest` 接口（与 `ReActAgent::prompt` 一致）。

```java
engine.prompt("hello").call();    // 同步
engine.prompt("hello").stream();  // 流式

engine.getMainAgent().prompt("hello")
        .session(session)
        .call();
```

## 扩展定制

### 动态添加 Web API

```java
HarnessEngine engine = HarnessEngine.of("work", ".soloncode/")
        .sessionProvider(sessionProvider)
        .build();

// v4：在 engine 上动态注册（原 harnessProps.addApiSource 已移除）
engine.addApiServer(new ApiSource().then(s -> {
            s.setDocUrl("http://xx.xx.xx/doc");
            s.setApiBaseUrl("http://xx.xx.xx/");
        }));
```

### 注册自定义业务工具

```java
HarnessEngine engine = HarnessEngine.of("work", ".soloncode/")
        .sessionProvider(sessionProvider)
        .extensionAdd((agentName, agentBuilder) -> {
            agentBuilder.defaultToolAdd(new BizTool());
        })
        .build();
```

### 动态配置系统提示词

```java
HarnessEngine engine = HarnessEngine.of("work", ".soloncode/")
        .sessionProvider(sessionProvider)
        .extensionAdd((agentName, agentBuilder) -> {
            if ("main".equals(agentName)) {
                // Agent 侧 systemPrompt 接受 AgentSystemPrompt（trace -> String）
                agentBuilder.systemPrompt(trace -> "你是一个专业的业务助手...");
                // 或更常用：agentBuilder.instruction("你是一个专业的业务助手...");
            }
        })
        .build();
```

### 子代理定制

- 被主代理调度时，不可定制，只能通过 `{workspace}/{harnessHome}/agents/xxx.md` 定义
- 使用代码调度时可以进一步定制

```java
AgentDefinition definition = new AgentDefinition();
definition.setSystemPrompt("xxx");
definition.getMetadata().addTools(ToolPermission.TOOL_BASH);

ReActAgent subagent = engine.createSubagent(definition)
        .defaultToolAdd(new OrderTool())
        .build();

subagent.prompt(prompt)
        .session(session)
        .options(o -> {
            o.toolContextPut(HarnessEngine.ATTR_CWD, "xxx");
        })
        .call();
```

## 内置拦截器

- `compressionInterceptor` — 上下文压缩处理
- `hitlInterceptor` — 人工介入处理（含七层安全审计策略）

### 修改内置拦截器

```java
HarnessEngine engine = HarnessEngine.of("work", ".soloncode/")
        .sessionProvider(sessionProvider)
        .compressionInterceptor(new ContextCompressionInterceptor()) // v4：原 SummarizationInterceptor
        .hitlInterceptor(new HITLInterceptor())
        .build();
```

### 添加新的拦截器

```java
HarnessEngine engine = HarnessEngine.of("work", ".soloncode/")
        .sessionProvider(sessionProvider)
        .extensionAdd((agentName, agentBuilder) -> {
            agentBuilder.defaultInterceptorAdd(new ReActInterceptor() {
                @Override
                public void onAgentStart(ReActTrace trace) {
                    ReActInterceptor.super.onAgentStart(trace);
                }
            });
        })
        .build();
```

## 模型运行时切换

```java
engine.setDefaultModel("model-name"); // v4：原 switchMainModel
engine.addModel(new ChatConfig());
engine.removeModel("model-name");
```

## 命令系统

支持基于 Markdown 模板的命令加载（兼容 Claude Code Custom Commands），支持 `$ARGUMENTS` 和 `$1`/`$2` 位置变量。

```java
import java.nio.file.Paths;

CommandRegistry registry = engine.getCommandRegistry();
registry.load(Paths.get(".solon/commands"));  // JDK8：用 Paths.get，不要 Path.of
registry.register(myCommand);

Command cmd = registry.find("/compact");
CommandResult result = cmd.execute(ctx);
```

| CommandType | 描述 |
|---|---|
| `SYSTEM` | 系统级：`/exit`, `/clear` |
| `CONFIG` | 配置级：`/model` |
| `AGENT` | Agent 级：`/resume`, `/compact` |

## 内置代理

AgentManager 内置代理：`bash`, `explore`, `plan`, `general`（及 `git-summary` 等）。自定义代理用 `addAgent`；挂载目录代理由 `MountManager` 解析，**无** `agentPool(...)` API。

```java
AgentManager agentManager = engine.getAgentManager();
agentManager.addAgent(myAgentDefinition);
// 挂载点代理：通过 Harness 工作区 / MountManager 加载 agents/*.md，按名称 getAgent("xxx")
```

## 典型示例：按工具权限组合

```java
// Pi 风格：仅 read/write/edit/bash
HarnessEngine pi = HarnessEngine.of("work", ".soloncode/")
        .toolsAdd(ToolPermission.TOOL_PI)
        .modelAdd(new ChatConfig())
        .sessionProvider(sessionProvider)
        .build();

// 知识问答：检索类公域工具
HarnessEngine qa = HarnessEngine.of("work", ".soloncode/")
        .toolsAdd(ToolPermission.TOOL_CODESEARCH,
                ToolPermission.TOOL_WEBSEARCH, ToolPermission.TOOL_WEBFETCH)
        .modelAdd(new ChatConfig())
        .sessionProvider(sessionProvider)
        .build();
```

## 依赖索引

| Artifact | Description |
|---|---|
| `solon-ai-harness` | 智能体马具框架 |
| `solon-ai-agent` | Agent 框架（Simple/ReAct/Team） |
| `solon-ai-talent-*` | 预置才能插件（见 `ai_agent.md`） |
