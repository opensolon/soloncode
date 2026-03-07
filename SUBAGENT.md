

# Subagent 子代理系统使用指南

Subagent 系统提供了类似 Claude Code 的子代理功能，允许主 Agent 调用专门的子代理来处理特定任务，提高任务处理效率。

## 功能概述

Subagent 系统包含以下类型的子代理：

| 类型 | 代码 | 描述 |
|------|------|------|
| 探索代理 | `explore` | 快速探索代码库，查找文件、理解代码结构 |
| 计划代理 | `plan` | 软件架构师，设计实现方案和执行计划 |
| 命令代理 | `bash` | 命令执行专家，处理 git、构建等终端任务 |
| 通用代理 | `general-purpose` | 处理复杂的多步骤任务 |
| Solon指南代理 | `solon-guide` | Solon Code、Agent SDK 和 API 专家，可从官网读取文档 |

## 启用 Subagent

在创建 AgentKernel 时启用子代理功能：

```java
AgentProperties properties = new AgentProperties();
properties.setSubagentEnabled(true);

AgentKernel mainAgent = new AgentKernel(chatModel, ..., properties);
```

## 使用方式

### 方式一：主 Agent 通过工具调用

主 Agent 可以使用 `subagent` 工具调用子代理：

```
用户：帮我探索一下项目的核心类

主 Agent 会：
1. 识别任务类型为"探索代码库"
2. 调用 subagent 工具，type="explore"
3. 将任务委托给探索代理处理
4. 返回探索结果给用户
```

### 方式二：直接调用 Subagent

通过 SubagentManager 直接调用：

```java

// 获取 SubagentManager
SubagentManager manager = mainAgent.getSubagentManager();

// 使用探索代理
String prompt = "请探索这个项目的代码结构，找出所有的 Java 源文件";
AgentResponse response = manager.getAgent(SubagentType.EXPLORE)
        .execute(sessionId, Prompt.of(prompt));
```

## 各类型子代理详解

### 1. ExploreSubagent - 探索代理

**适用场景：**
- 查找特定文件或模式
- 理解代码库结构
- 搜索类、函数、变量定义
- 分析模块依赖关系

**示例：**
```java
import org.noear.solon.ai.chat.prompt.Prompt;

// 查找所有配置文件
manager.getAgent(SubagentType.EXPLORE)
    .execute(sessionId, Prompt.of("找出项目中所有的 .yml 和 .properties 配置文件"));

// 理解某个模块的结构
manager.getAgent(SubagentType.EXPLORE)
    .execute(sessionId, Prompt.of("分析 soloncodecli/core 包的代码结构"));
```

**特点：**
- 优先使用 Glob 工具进行文件查找
- 使用 Grep 工具搜索代码内容
- 只读操作，不会修改代码
- 较少的步数限制（15步）

### 2. PlanSubagent - 计划代理

**适用场景：**
- 设计新功能的实现方案
- 规划重构策略
- 制定迁移计划
- 架构设计

**示例：**
```java
import org.noear.solon.ai.chat.prompt.Prompt;

manager.getAgent(SubagentType.PLAN)
    .execute(sessionId, Prompt.of("为添加用户认证功能设计实现方案"));

manager.getAgent(SubagentType.PLAN)
    .execute(sessionId, Prompt.of("规划将代码迁移到新架构的步骤"));
```

**输出格式：**
1. 概述：实现思路
2. 关键文件：需要修改的文件
3. 执行步骤：具体的实现步骤
4. 注意事项：潜在风险
5. 验证方案：如何验证实现

**特点：**
- 专注于规划和设计
- 不执行代码修改
- 考虑架构权衡
- 提供清晰的执行计划

### 3. BashSubagent - 命令代理

**适用场景：**
- Git 操作（commit, push, pull, branch）
- 项目构建（mvn, gradle, npm）
- 测试执行
- 依赖安装

**示例：**
```java
import org.noear.solon.ai.chat.prompt.Prompt;

manager.getAgent(SubagentType.BASH)
    .execute(sessionId, Prompt.of("运行所有单元测试"));

manager.getAgent(SubagentType.BASH)
    .execute(sessionId, Prompt.of("创建一个新的 git 分支 feature/auth"));

manager.getAgent(SubagentType.BASH)
    .execute(sessionId, Prompt.of("使用 Maven 构建项目"));
```

**特点：**
- 只包含 Bash 工具
- 专注于命令执行
- 较少的步数限制（10步）
- 小的会话窗口（3）

### 4. GeneralPurposeSubagent - 通用代理

**适用场景：**
- 复杂的多步骤任务
- 需要多种工具协作的任务
- 跨模块的任务协调
- 涉及网络检索的研究任务

**示例：**
```java
import org.noear.solon.ai.chat.prompt.Prompt;

manager.getAgent(SubagentType.GENERAL_PURPOSE)
    .execute(sessionId, Prompt.of("重构登录模块，添加多因素认证功能"));

manager.getAgent(SubagentType.GENERAL_PURPOSE)
    .execute(sessionId, Prompt.of("研究并实现一个新的 API 端点"));
```

**特点：**
- 包含完整的工具集
- 最多步数限制（25步）
- 最大的会话窗口（10）
- 功能最全面

### 5. SolonCodeGuideSubagent - Solon 指南代理

**适用场景：**
- 查询 Solon Code 相关问题
- 学习 Solon Agent SDK 使用方法
- 了解 Solon API 接口和用法
- 从 Solon 官网获取最新文档

**示例：**
```java
import org.noear.solon.ai.chat.prompt.Prompt;

// 查询 Solon 快速入门文档
manager.getAgent(SubagentType.SOLON_CODE_GUIDE)
    .execute(sessionId, Prompt.of("Solon 的快速入门方法是什么？"));

// 学习 Agent SDK
manager.getAgent(SubagentType.SOLON_CODE_GUIDE)
    .execute(sessionId, Prompt.of("如何在 Solon Agent SDK 中创建自定义工具？"));

// 了解 API 用法
manager.getAgent(SubagentType.SOLON_CODE_GUIDE)
    .execute(sessionId, Prompt.of("ChatModel 接口如何使用？"));
```

**专属工具：**
- `read_solon_doc`: 读取 Solon 官网文档（支持本地缓存）
  - 支持文档：learn-start, agent-quick-start, agent-tools, agent-skill 等
  - 自动缓存到 `.soloncode/cache/docs/` 目录
  - 提供内存缓存提升性能
- `list_solon_docs`: 列出所有可用的官方文档
- `clear_solon_doc_cache`: 清除文档缓存

**特点：**
- 专注于 Solon 技术栈
- 可直接从官网获取最新文档
- 支持文档缓存，减少网络请求
- 较少的步数限制（15步）
- 小的会话窗口（5）

## 最佳实践

### 1. 选择合适的子代理类型

```
简单文件查找      -> explore
设计实现方案      -> plan
执行命令          -> bash
复杂的多步骤任务   -> general-purpose
```

### 2. 组合使用子代理

```
任务：添加新功能

1. 使用 explore 代理查找相关文件
2. 使用 plan 代理设计实现方案
3. 使用 general-purpose 代理执行实现
4. 使用 bash 代理运行测试验证
```

### 3. 子代理工具调用

主 Agent 可以智能地选择合适的子代理：

```
用户：帮我理解这个项目的认证模块

主 Agent 的思考过程：
1. 这是一个代码理解任务
2. 应该使用 explore 子代理
3. 调用 subagent(type="explore", prompt="...")
4. 返回探索结果
```

## 配置选项

### 禁用 Subagent

如果不希望使用子代理功能：

```java
CodeAgent codeAgent = new CodeAgent(chatModel)
        .enableSubagent(false);  // 禁用子代理
```

### 自定义子代理配置

可以通过 SubagentConfig 自定义每个子代理的行为：

```java
import org.noear.solon.ai.codecli.core.subagent.SubagentConfig;
import org.noear.solon.ai.codecli.core.subagent.SubagentType;

SubagentConfig config = new SubagentConfig(SubagentType.EXPLORE);
config.setMaxSteps(20);  // 设置最大步数
config.setDescription("自定义描述");
```

### 注册自定义 AgentPool

可以注册自定义的 agentPool，让系统从多个目录发现子代理：

```java
// 获取 SubagentManager
SubagentManager manager = codeAgent.getSubagentManager();

// 注册自定义 agent 池
manager.agentPool("@my_agents", "/path/to/my/agents");
manager.agentPool("@shared_agents", "./shared/agents");

// 系统默认已注册以下池：
// - @soloncode_agents -> .soloncode/agents/
// - @opencode_agents  -> .opencode/agents/
// - @claude_agents     -> .claude/agents/
```

**AgentPool 搜索优先级：**
1. 自定义注册的 agentPool（按注册顺序）
2. `.opencode/agents/`
3. `.claude/agents/`
4. `.soloncode/agents/`（默认池）

当创建子代理时，系统会按优先级搜索提示词文件。

## 工具列表

Subagent 系统提供以下工具：

| 工具名 | 描述 |
|--------|------|
| `subagent` | 启动指定类型的子代理执行任务 |
| `subagent_list` | 列出所有可用的子代理（包括预定义和自定义代理） |

### subagent_list 工具详解

`subagent_list` 工具会扫描所有已注册的 agentPools，动态发现并列出所有可用的子代理：

**输出格式：**
```
可用的子代理：

【预定义子代理】
- **explore** (EXPLORE): 快速探索代码库...
- **plan** (PLAN): 设计实现方案...
- **bash** (BASH): 执行命令...
- **general-purpose** (GENERAL_PURPOSE): 通用代理...

【自定义子代理】
- **performance-tester** (来自 @soloncode_agents): 性能测试专家...
- **my-custom-agent** (来自 @opencode_agents): 我的自定义代理...

提示：可以通过 .soloncode/agents/ 目录添加自定义子代理
```

**特性：**
- 自动去重：如果自定义代理与预定义代理同名，只显示预定义版本
- 显示来源：标注每个自定义代理来自哪个 agentPool
- 描述提取：从 MD 文件的第一行提取描述信息
- 支持多池：扫描所有已注册的 agentPools 目录

## 配置文件位置

Subagent 的提示词文件存储在项目根目录的 `.soloncode/agents/` 目录下：

```
项目根目录/
├── .soloncode/              ← SolonCode 系统目录
│   ├── agents/               ← Subagent 提示词文件
│   │   ├── explore.md        # 探索代理
│   │   ├── plan.md           # 计划代理
│   │   ├── bash.md           # Bash代理
│   │   ├── general-purpose.md # 通用代理
│   │   └── *.md              # 用户自定义代理
│   ├── sessions/             ← 会话历史
│   └── skills/               ← 技能文件
├── work/                     ← 工作目录
├── AGENTS.md                 ← 主 Agent 配置
└── config.yml                ← 全局配置
```

用户可以编辑 `.soloncode/agents/` 下的 MD 文件来自定义 Subagent 的行为，修改后重启即可生效。

## 与 Claude Code 的兼容性

本 Subagent 系统设计上兼容 Claude Code 的子代理模式：

- 支持相同的子代理类型概念
- 提供类似的工具调用接口
- 可以通过 AGENTS.md 自定义子代理行为

## 测试示例

参考 `SubagentTest.java` 查看完整的使用示例：

```bash
# 运行子代理测试
mvn test -Dtest=SubagentTest
```
