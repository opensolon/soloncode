# SubAgent 子代理系统完整指南

## 目录
- [架构设计](#架构设计)
- [核心组件](#核心组件)
- [实现细节](#实现细节)
- [使用示例](#使用示例)
- [自定义子代理](#自定义子代理)
- [最佳实践](#最佳实践)

---

## 架构设计

### 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                      主 Agent (AgentKernel)                         │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                    TaskSkill / AgentTeamsSkill                 │  │
│  │  ┌────────────────┐  ┌──────────────┐  ┌─────────────────┐   │  │
│  │  │   task()       │  │ team_task()  │  │ team_status()  │   │  │
│  │  │   create_agent()│  │ create_task() │  │ subagent()     │   │  │
│  │  └────────────────┘  └──────────────┘  └─────────────────┘   │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                   SubagentManager (子代理管理器)                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              AgentPool 扫描和发现                          │  │
│  │  - @soloncode_agents/  - .soloncode/agents/                   │  │
│  │  - @opencode_agents/    - .opencode/agents/                  │  │
│  │  - @claude_agents/      - .claude/agents/                     │  │
│  └──────────────────────────────────────────────────────────┘  │
│                              │                               │
│  │  ┌──────────────┐  ┌──────────────┐  ┌───────────────┐  │  │
│  │  │   Explore    │  │     Plan     │  │     Bash      │  │  │
│  │  │   Agent      │  │   Agent      │  │   Agent      │  │  │
│  │  └──────────────┘  └──────────────┘  └───────────────┘  │  │
│  │  ┌──────────────────────────────────────────────────────┐   │  │
│  │  │            GeneralPurpose Agent                      │   │  │
│  │  └──────────────────────────────────────────────────────┘   │  │
│  │  ┌──────────────────────────────────────────────────────┐   │  │
│  │  │         SolonCodeGuide Agent                         │   │  │
│  │  └──────────────────────────────────────────────────────┘   │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                      ┌────────────────────────┐
                      │  会话管理 (Sessions)   │
                      │  - 独立的会话空间        │
                      │  - 会话历史缓存          │
                      └────────────────────────┘
```

---

## 核心组件

### 1. Subagent 接口

**位置**: `org.noear.solon.bot.core.subagent.Subagent`

```java
public interface Subagent {
    /**
     * 获取子代理类型
     */
    String getType();

    /**
     * 获取描述
     */
    String getDescription();

    /**
     * 获取系统提示词
     */
    String getSystemPrompt();

    /**
     * 执行任务（同步）
     *
     * @param __cwd 工作目录
     * @param sessionId 会话ID
     * @param prompt 任务提示
     * @return 执行结果
     */
    AgentResponse call(String __cwd, String sessionId, Prompt prompt) throws Throwable;

    /**
     * 执行任务（流式）
     *
     * @param __cwd 工作目录
     * @param sessionId 会话ID
     * @param prompt 任务提示
     * @return 流式结果
     */
    Flux<AgentChunk> stream(String __cwd, String sessionId, Prompt prompt);
}
```

### 2. SubagentManager

**位置**: `org.noear.solon.bot.core.subagent.SubagentManager`

**核心功能**:
- 管理子代理的创建和缓存
- 从 AgentPool 扫描和发现子代理
- 根据类型获取子代理实例

**API**:
```java
public class SubagentManager {
    // 注册 AgentPool
    void agentPool(String alias, String path);

    // 获取子代理
    Subagent getAgent(String type);

    // 获取所有子代理
    List<Subagent> getAgents();

    // 添加子代理
    void addSubagent(Subagent subagent);
}
```

### 3. AbsSubagent 抽象类

**位置**: `org.noear.solon.bot.core.subagent.AbsSubagent`

**功能**:
- 提供子代理的基础实现
- 管理 ReActAgent 的创建和缓存
- 提供默认的工具集和配置

**继承层次**:
```
AbsSubagent (抽象基类)
    ├── ExploreSubagent
    ├── PlanSubagent
    ├── BashSubagent
    ├── GeneralPurposeSubagent
    └── SolonGuideSubagent
```

---

## 实现细节

### 1. 子代理创建流程

```java
// SubagentManager.java

public Subagent getAgent(String type) {
    // 1. 从缓存获取
    Subagent agent = agents.get(type);
    if (agent != null) {
        return agent;
    }

    // 2. 从 AgentPool 扫描
    for (AgentPool pool : agentPools) {
        agent = pool.loadAgent(type);
        if (agent != null) {
            // 缓存并返回
            agents.put(type, agent);
            return agent;
        }
    }

    // 3. 未找到
    return null;
}
```

### 2. AgentPool 扫描机制

```java
// AgentPool.java

public Subagent loadAgent(String type) {
    // 1. 查找提示词文件
    Optional<Path> promptFile = findPromptFile(type);

    if (!promptFile.isPresent()) {
        return null;
    }

    // 2. 解析 YAML frontmatter 和 Markdown 内容
    SubAgentMetadata metadata = parseMetadata(promptFile.get());
    String systemPrompt = parseSystemPrompt(promptFile.get());

    // 3. 创建子代理实例
    AbsSubagent agent = createAgentInstance(metadata, systemPrompt);

    return agent;
}

private Optional<Path> findPromptFile(String type) {
    // 搜索 type.md 文件
    // 搜索顺序：soloncode_agents > opencode_agents > claude_agents
    Path[] searchPaths = {
        paths.get("soloncode_agents", ".soloncode/agents"),
        paths.get("opencode_agents", ".opencode/agents"),
        paths.get("claude_agents", ".claude/agents")
    };

    for (Path path : searchPaths) {
        Path file = path.resolve(type + ".md");
        if (Files.exists(file)) {
            return Optional.of(file);
        }
    }

    return Optional.empty();
}
```

### 3. 会话隔离

每个子代理调用都有独立的会话ID：

```java
// 生成唯一会话ID
String sessionId = "subagent_" + type + "_" + System.currentTimeMillis();

// 执行任务
AgentResponse response = agent.call(
    __cwd,      // 工作目录
    sessionId,  // 会话ID
    Prompt.of(prompt)
);

// 会话ID 格式：subagent_explore_1234567890
```

**好处**:
- ✅ 子代理之间不会相互干扰
- ✅ 可以通过 `taskId` 继续之前的任务
- ✅ 便于追踪和调试

### 4. Token 统计

子代理的 Token 使用会累计到主 Agent：

```java
// TaskSkill.handle() / AgentTeamsSkill.subagent()

AgentSession __parentSession = kernel.getSession(__sessionId);
ReActTrace __parentTrace = ReActTrace.getCurrent(__parentSession.getSnapshot());

// 执行子代理
AgentResponse response = agent.call(...);

// 累计 Token
__parentTrace.getMetrics().addMetrics(response.getMetrics());
```

---

## 使用示例

### 示例 1: 基础调用

```java
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.bot.core.subagent.SubagentManager;

// 初始化
AgentKernel kernel = new AgentKernel(chatModel, properties);
SubagentManager manager = kernel.getSubagentManager();

// 调用探索代理
String sessionId = "session_" + System.currentTimeMillis();

AgentResponse response = manager.getAgent("explore")
    .call("./work", sessionId, Prompt.of("探索项目的核心类"));

// 处理结果
System.out.println(response.getContent());
```

### 示例 2: 通过工具调用（推荐）

```java
// 1. 将 TaskSkill 或 AgentTeamsSkill 添加到 AgentKernel
AgentKernel kernel = new AgentKernel(chatModel, properties);
TaskSkill taskSkill = new TaskSkill(kernel, subagentManager);

kernel.getReActAgent()
    .defaultSkillAdd(taskSkill);

// 2. LLM 自动调用
// 用户："帮我探索项目的认证模块"

// 3. 主 Agent 的内部推理
/*
识别任务：代码探索
选择工具：subagent
参数：
  - type="explore"
  - prompt="探索项目中的认证相关模块，找出所有涉及的类、接口和配置"

执行：调用 explore 子代理
返回：探索结果
*/
```

### 示例 3: 继续之前的任务

```java
// 第一次调用
AgentResponse response1 = manager.getAgent("explore")
    .call("./work", "session_1", Prompt.of("探索认证模块"));

String taskId = response.getMetadata().get("taskId");
// taskId: "subagent_explore_1234567890"

// 第二次调用（继续同一个任务）
AgentResponse response2 = manager.getAgent("explore")
    .call("./work", "session_1", Prompt.of("继续深入分析认证流程"));

// 或者使用 taskId
AgentResponse response2 = manager.getAgent("explore")
    .call("./work", taskId, Prompt.of("继续深入分析认证流程"));
```

### 示例 4: 团队协作场景

```java
// 任务：实现用户登录功能

// 1. 探索现有代码
AgentResponse exploreResult = manager.getAgent("explore")
    .call(sessionId, Prompt.of("探索项目中的认证相关代码"));

// 2. 设计实现方案
AgentResponse planResult = manager.getAgent("plan")
    .call(sessionId, Prompt.of(
        "基于探索结果，设计用户认证功能的实现方案。\n" +
        "现有代码：" + exploreResult.getContent()
    ));

// 3. 执行实现
AgentResponse implResult = manager.getAgent("general-purpose")
    .call(sessionId, Prompt.of(
        "按照设计方案实现用户认证功能：\n" +
        "1. 创建 UserService 接口\n" +
        "2. 实现 AuthenticationController\n" +
        "3. 添加登录表单"
    ));

// 4. 运行测试
AgentResponse testResult = manager.getAgent("bash")
    .call(sessionId, Prompt.of("运行项目的单元测试"));
```

---

## 自定义子代理

### 方式1: 通过 YAML 配置文件

**文件位置**: `.soloncode/agents/my-custom-agent.md`

```markdown
---
code: my-custom-agent
name: 我的自定义代理
description: 专门处理数据库相关任务
model: gpt-4
enabled: true
tools:
  - read
  - write
  - grep
skills:
  - expert
  - lucene
max_turns: 20
---

# 系统提示词

你是数据库专家，擅长：

1. **SQL 查询优化**
   - 分析查询性能
   - 优化索引
   - 重写复杂查询

2. **数据建模**
   - 设计合理的表结构
   - 定义关系
   - 规范化数据

3. **数据库迁移**
   - 编写迁移脚本
   - 处理版本升级

请在处理任务时：
- 优先考虑查询性能
- 确保数据一致性
- 遵循数据库设计范式
```

**自动发现**: 重启应用后自动加载

### 方式2: 通过代码创建

```java
import org.noear.solon.bot.core.subagent.AbsSubagent;
import org.noear.solon.bot.core.AgentKernel;

public class DatabaseExpertAgent extends AbsSubagent {

    public DatabaseExpertAgent(AgentKernel mainAgent) {
        super(mainAgent);
    }

    @Override
    public String getType() {
        return "database-expert";
    }

    @Override
    protected String getDefaultDescription() {
        return "数据库专家，擅长 SQL 优化和数据建模";
    }

    @Override
    protected String getDefaultSystemPrompt() {
        return """
            ## 数据库专家

            你是数据库专家，专注于：

            ### 核心能力
            - SQL 查询优化
            - 数据库设计
            - 性能调优

            ### 工具使用策略
            1. **read**: 读取 schema 文件
            2. **grep**: 搜索 SQL 语句
            3. **write**: 修改数据库脚本

            ### 最佳实践
            - 优先分析索引使用情况
            - 识别慢查询
            - 建议合理的索引
            """;
    }

    @Override
    protected void customize(ReActAgent.Builder builder) {
        // 添加工具
        builder.defaultSkillAdd(mainAgent.getCliSkills());
        builder.defaultSkillAdd(LuceneSkill.getInstance());

        // 设置配置
        builder.maxSteps(20);
        builder.sessionWindowSize(8);
    }
}
```

**注册子代理**:
```java
DatabaseExpertAgent agent = new DatabaseExpertAgent(kernel);
manager.addSubagent(agent);
```

### 方式3: 动态创建（通过工具）

```java
// 通过 TaskSkill 或 AgentTeamsSkill 的 create_agent() 工具

AgentResponse response = kernel.prompt(Prompt.of(
    "创建一个数据库专家代理" +
    "code: database-expert" +
    "name: 数据库专家" +
    "description: 专门处理SQL优化和数据建模" +
    "systemPrompt: 你是SQL专家..." +
    "tools: read,write,grep" +
    "skills: expert,lucene"
)).call();

// 代理定义保存到 .soloncode/agents/database-expert.md
// 下次启动时自动加载
```

---

## 内置子代理详解

### 1. ExploreSubagent - 探索代理

**代码**: `org.noear.solon.bot.core.subagent.ExploreSubagent`

**系统提示词**:
```
你是代码探索专家，擅长快速定位文件和理解代码结构。

### 工具使用策略
1. **优先使用 Glob**: 定位文件路径（如 **/*.java）
2. **使用 Grep**: 搜索代码内容
3. **最后才用 Read**: 读取具体文件

### 工作流程
1. 根据 task 描述，选择合适的搜索策略
2. 使用 Glob 找到相关文件
3. 使用 Grep 搜索关键代码
4. 必要时读取文件了解细节
```

**配置**:
```java
maxSteps: 15              // 最大步数
sessionWindowSize: 5      // 会话窗口
tools: glob, grep, read   // 只读工具
```

**适用任务**:
- 查找特定文件
- 理解代码结构
- 搜索类、函数定义
- 分析依赖关系

---

### 2. PlanSubagent - 计划代理

**代码**: `org.noear.solon.bot.core.subagent.PlanSubagent`

**系统提示词**:
```
## 软件架构师

你是软件架构师，负责设计和规划。

### 工作流程
1. **理解需求**: 仔细阅读 task 描述
2. **分析现状**: 查看现有代码和架构
3. **设计方案**: 提供清晰的实现方案
4. **拆解任务**: 将大任务分解为小步骤

### 输出格式
1. **概述**: 实现思路和技术选型
2. **关键文件**: 需要创建/修改的文件列表
3. **执行步骤**: 详细的实现步骤
4. **注意事项**: 潜在风险和注意事项
```

**配置**:
```java
maxSteps: 20              // 最大步数
sessionWindowSize: 8      // 会话窗口
tools: glob, grep, read   // 只读工具
```

**适用任务**:
- 设计新功能
- 规划重构策略
- 制定迁移计划
- 架构设计

---

### 3. BashSubagent - 命令代理

**代码**: `org.noear.solon.bot.core.subagent.BashSubagent`

**系统提示词**:
```
## 命令执行专家

你是命令执行专家，擅长处理终端任务。

### 支持的命令类型
- **Git 操作**: commit, push, pull, branch, merge
- **项目构建**: mvn, gradle, npm, make
- **测试执行**: pytest, npm test, go test
- **依赖管理**: npm install, pip install, go mod

### 注意事项
- 每次只执行一个命令
- 先检查命令是否成功
- 失败时提供错误信息和建议解决方案
```

**配置**:
```java
maxSteps: 10              // 最大步数
sessionWindowSize: 3      // 会话窗口
tools: bash             // 仅 Bash 工具
```

**适用任务**:
- Git 操作
- 项目构建
- 测试执行
- 依赖安装

---

### 4. GeneralPurposeSubagent - 通用代理

**代码**: `org.noear.solon.bot.core.subagent.GeneralPurposeSubagent`

**系统提示词**:
```
## 通用任务代理

你是全能型执行专家，负责处理复杂、多步骤且需要综合能力的开发任务。

### 工具使用策略
1. **本地搜索** (内部): 定位项目内代码、符号或文件时
   - Lucene: 全文搜索
   - Glob: 文件查找
   - Grep: 代码搜索

2. **全网调研** (外部): 遇到新技术、查阅第三方文档时
   - WebSearch: 搜索互联网信息
   - WebFetch: 获取文档内容

3. **闭环执行**: 拥有写权限，可以直接修改和验证

### 工作原则
1. **理解优先**: 动笔修改前，必须充分理解现有逻辑
2. **分步验证**: 每完成一个关键步骤，建议运行测试验证
3. **系统性思考**: 修改代码时需考虑对周边模块的影响
```

**配置**:
```java
maxSteps: 25              // 最大步数
sessionWindowSize: 10     // 会话窗口
tools: ALL              // 所有工具
```

**适用任务**:
- 复杂的多步骤任务
- 跨模块任务协调
- 涉及网络检索
- 需要多种工具协作

---

### 5. SolonGuideSubagent - Solon 指南代理

**代码**: `org.noear.solon.bot.core.subagent.SolonGuideSubagent`

**系统提示词**:
```
## Solon 技术文档专家

你是 Solon 技术专家，专注于 Solon 框架和 Agent SDK。

### 专属工具
- `read_solon_doc`: 读取 Solon 官网文档（支持本地缓存）
- `list_solon_docs`: 列出所有可用的官方文档
- `clear_solon_doc_cache`: 清除文档缓存

### 可用文档
- learn-start: Solon 快速入门
- agent-quick-start: Agent SDK 快速入门
- agent-tools: Agent 工具开发
- agent-skill: Agent 技能开发
- ...

### 使用流程
1. 检查本地缓存
2. 从官网获取最新文档
3. 分析并解答用户问题
```

**配置**:
```java
maxSteps: 15              // 最大步数
sessionWindowSize: 5      // 会话窗口
tools: read_solon_doc, list_solon_docs, lucene, grep
```

**适用任务**:
- 查询 Solon 文档
- 学习 Solon API
- 开发 Solon Agent 技能

---

## 最佳实践

### 1. 选择合适的子代理

```python
# 决策树
if 任务类型 == "代码探索" or "文件搜索":
    子代理 = "explore"
elif 任务类型 == "方案设计" or "架构规划":
    子代理 = "plan"
elif 任务类型 == "命令执行" or "Git操作":
    子代理 = "bash"
elif 任务复杂度 == "高" or 需要多种工具:
    子代理 = "general-purpose"
```

### 2. 任务分解策略

```python
# 复杂任务分解为多个子任务

任务：实现用户认证功能

# 步骤1: 探索现有代码
subagent(type="explore", prompt="探索认证相关代码")

# 步骤2: 设计方案
subagent(type="plan", prompt="基于探索结果设计认证方案")

# 步骤3: 实现
subagent(type="general-purpose", prompt="按照设计实现认证功能")

# 步骤4: 测试
subagent(type="bash", prompt="运行测试")
```

### 3. 继续之前的任务

```python
# 第一次调用
response1 = subagent(
    type="explore",
    prompt="探索认证模块",
    description="认证探索"
)
# 返回: task_id = "subagent_explore_1234567890"

# 第二次调用（继续）
response2 = subagent(
    type="explore",
    prompt="继续深入分析",
    taskId="subagent_explore_1234567890"  # 使用 task_id 继续会话
)
```

### 4. 错误处理

```python
# 子代理调用失败时的处理

try:
    response = subagent(type="explore", prompt="...")
except SubagentNotFoundException as e:
    # 子代理不存在
    print(f"错误: 未知的子代理类型: {e.type}")
    print(f"可用的子代理: {e.available_agents}")

except AgentExecutionException as e:
    # 子代理执行失败
    print(f"错误: 子代理执行失败: {e.message}")
    print(f"堆栈跟踪: {e.stack_trace}")
```

---

## 配置选项

### 1. 全局配置

**config.yml**:
```yaml
solon:
  code:
    cli:
      # 启用子代理
      subAgentEnabled: true

      # 子代理模型配置（可选）
      subAgentModels:
        explore: gpt-4
        plan: gpt-4
        bash: gpt-3.5
        general-purpose: gpt-4
```

### 2. 自定义子代理配置

**Java 代码**:
```java
// 创建子代理时自定义配置
SubagentMetadata metadata = new SubagentMetadata();
metadata.setCode("my-agent");
metadata.setModel("gpt-4");
metadata.setMaxTurns(30);
metadata.setTools(Arrays.asList("read", "write", "grep"));
```

**YAML 文件**:
```yaml
---
code: my-agent
model: gpt-4
max_turns: 30
tools:
  - read
  - write
  - grep
```

---

## 文件结构

```
.soloncode/
├── agents/                    # 子代理定义文件
│   ├── explore.md
│   ├── plan.md
│   ├── bash.md
│   ├── general-purpose.md
│   ├── solon-guide.md
│   └── *.md                   # 用户自定义代理
│
├── sessions/                  # 会话历史
│   ├── subagent_explore_123/
│   └── subagent_plan_456/
│
├── skills/                    # 技能文件
└── ...
```

---

## 测试和调试

### 运行测试

```bash
# 测试所有子代理
mvn test -Dtest=SubagentTest

# 测试特定子代理
mvn test -Dtest=SubagentTest#testExploreSubagent

# 测试子代理调用
mvn test -Dtest=AgentTeamsComprehensiveTest
```

### 调试技巧

**1. 查看子代理日志**
```java
// 设置日志级别
LoggerContext.getLogger(SubagentManager.class.getName()).setLevel(Level.DEBUG);

// 日志输出
DEBUG: 分派任务 -> 类型: explore, 会话: subagent_explore_123
INFO: 子代理任务完成: subagent_explore_123
```

**2. 检查会话历史**
```bash
# 查看特定会话的历史
ls .soloncode/sessions/subagent_explore_123/
```

**3. 验证子代理注册**
```java
// 列出所有可用的子代理
List<Subagent> agents = manager.getAgents();
for (Subagent agent : agents) {
    System.out.println(STR."- `\{agent.getType()}\`: \{agent.getDescription()}");
}
```

---

## 高级特性

### 1. 任务链（Task Chaining）

```java
// 前一个子代理的结果传递给下一个
String exploreResult = manager.getAgent("explore")
    .call(sessionId, Prompt.of("探索认证模块"))
    .getContent();

String planResult = manager.getAgent("plan")
    .call(sessionId, Prompt.of(
        "基于探索结果设计认证方案\n\n" +
        "现有代码:\n" + exploreResult
    ))
    .getContent();
```

### 2. 流式执行

```java
// 流式获取子代理输出
Flux<AgentChunk> chunks = manager.getAgent("explore")
    .stream(__cwd, sessionId, Prompt.of("探索代码"));

chunks.subscribe(
    chunk -> System.out.println(chunk.getContent()),
    error -> error.printStackTrace(),
    () -> System.out.println("完成")
);
```

### 3. 性能优化

```java
// 使用对象池减少创建开销
SubagentManager manager = new SubagentManager(10); // 缓存大小

// 预加载常用子代理
manager.getAgent("explore");  // 预加载
manager.getAgent("plan");     // 预加载
```

---

## 常见问题

### Q1: 子代理调用失败

**问题**: `ERROR: 未知的子代理类型 'xxx'`

**解决**:
1. 检查子代理类型名称是否正确
2. 确认 `.soloncode/agents/` 下是否有对应的 `.md` 文件
3. 使用 `subagent_list()` 查看所有可用子代理

### Q2: 子代理执行超时

**问题**: 调用子代理后长时间无响应

**解决**:
1. 检查子代理的 `maxSteps` 是否足够
2. 简化任务提示，减少上下文
3. 使用更快的模型
4. 检查网络连接（如果涉及网络工具）

### Q3: 子代理无法访问工具

**问题**: 子代理报告找不到工具

**解决**:
1. 确认工具已正确注册到 `AgentKernel`
2. 检查子代理的 `customize()` 方法
3. 查看子代理的系统提示词中是否说明了工具使用策略

---

## 总结

SubAgent 系统通过专门的子代理提供强大的任务委派能力：

| 特性 | 好处 |
|------|------|
| **类型专门化** | 每个子代理针对特定任务优化 |
| **独立会话** | 子代理有独立的会话空间，不会污染主对话 |
| **Token 节省** | 复杂任务委派给子代理，节省主对话上下文 |
| **可扩展** | 可以轻松添加新的子代理类型 |
| **兼容性** | 兼容 Claude Code 的子代理模式 |

---

**作者**: bai
**版本**: 2.0
**状态**: ✅ 完整
**更新日期**: 2026-03-09
