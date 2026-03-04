# SubAgent 子代理系统使用指南

SubAgent 系统提供了类似 Claude Code 的子代理功能，允许主 Agent 调用专门的子代理来处理特定任务，提高任务处理效率。

## 功能概述

SubAgent 系统包含以下类型的子代理：

| 类型 | 代码 | 描述 |
|------|------|------|
| 探索代理 | `explore` | 快速探索代码库，查找文件、理解代码结构 |
| 计划代理 | `plan` | 软件架构师，设计实现方案和执行计划 |
| 命令代理 | `bash` | 命令执行专家，处理 git、构建等终端任务 |
| 通用代理 | `general-purpose` | 处理复杂的多步骤任务 |

## 启用 SubAgent

在创建 CodeAgent 时启用子代理功能：

```java
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.codecli.core.CodeAgent;

CodeAgent codeAgent = new CodeAgent(chatModel)
        .workDir("./work")
        .enableSubAgent(true)  // 启用子代理功能
        .prepare();
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

### 方式二：直接调用 SubAgent

通过 SubAgentManager 直接调用：

```java
import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.chat.prompt.Prompt;

// 获取 SubAgentManager
SubAgentManager manager = codeAgent.getSubAgentManager();

// 使用探索代理
String prompt = "请探索这个项目的代码结构，找出所有的 Java 源文件";
AgentResponse response = manager.getAgent(SubAgentType.EXPLORE)
        .execute(Prompt.of(prompt));
```

## 各类型子代理详解

### 1. ExploreSubAgent - 探索代理

**适用场景：**
- 查找特定文件或模式
- 理解代码库结构
- 搜索类、函数、变量定义
- 分析模块依赖关系

**示例：**
```java
import org.noear.solon.ai.chat.prompt.Prompt;

// 查找所有配置文件
manager.getAgent(SubAgentType.EXPLORE)
    .execute(Prompt.of("找出项目中所有的 .yml 和 .properties 配置文件"));

// 理解某个模块的结构
manager.getAgent(SubAgentType.EXPLORE)
    .execute(Prompt.of("分析 soloncodecli/core 包的代码结构"));
```

**特点：**
- 优先使用 Glob 工具进行文件查找
- 使用 Grep 工具搜索代码内容
- 只读操作，不会修改代码
- 较少的步数限制（15步）

### 2. PlanSubAgent - 计划代理

**适用场景：**
- 设计新功能的实现方案
- 规划重构策略
- 制定迁移计划
- 架构设计

**示例：**
```java
import org.noear.solon.ai.chat.prompt.Prompt;

manager.getAgent(SubAgentType.PLAN)
    .execute(Prompt.of("为添加用户认证功能设计实现方案"));

manager.getAgent(SubAgentType.PLAN)
    .execute(Prompt.of("规划将代码迁移到新架构的步骤"));
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

### 3. BashSubAgent - 命令代理

**适用场景：**
- Git 操作（commit, push, pull, branch）
- 项目构建（mvn, gradle, npm）
- 测试执行
- 依赖安装

**示例：**
```java
import org.noear.solon.ai.chat.prompt.Prompt;

manager.getAgent(SubAgentType.BASH)
    .execute(Prompt.of("运行所有单元测试"));

manager.getAgent(SubAgentType.BASH)
    .execute(Prompt.of("创建一个新的 git 分支 feature/auth"));

manager.getAgent(SubAgentType.BASH)
    .execute(Prompt.of("使用 Maven 构建项目"));
```

**特点：**
- 只包含 Bash 工具
- 专注于命令执行
- 较少的步数限制（10步）
- 小的会话窗口（3）

### 4. GeneralPurposeSubAgent - 通用代理

**适用场景：**
- 复杂的多步骤任务
- 需要多种工具协作的任务
- 跨模块的任务协调
- 涉及网络检索的研究任务

**示例：**
```java
import org.noear.solon.ai.chat.prompt.Prompt;

manager.getAgent(SubAgentType.GENERAL_PURPOSE)
    .execute(Prompt.of("重构登录模块，添加多因素认证功能"));

manager.getAgent(SubAgentType.GENERAL_PURPOSE)
    .execute(Prompt.of("研究并实现一个新的 API 端点"));
```

**特点：**
- 包含完整的工具集
- 最多步数限制（25步）
- 最大的会话窗口（10）
- 功能最全面

## 最佳实践

### 1. 选择合适的子代理类型

```
简单文件查找      -> explore
设计实现方案      -> plan
执行命令          -> bash
复杂的多步骤任务  -> general-purpose
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

### 禁用 SubAgent

如果不希望使用子代理功能：

```java
CodeAgent codeAgent = new CodeAgent(chatModel)
        .enableSubAgent(false);  // 禁用子代理
```

### 自定义子代理配置

可以通过 SubAgentConfig 自定义每个子代理的行为：

```java
import org.noear.solon.ai.codecli.core.subagent.SubAgentConfig;
import org.noear.solon.ai.codecli.core.subagent.SubAgentType;

SubAgentConfig config = new SubAgentConfig(SubAgentType.EXPLORE);
config.setMaxSteps(20);  // 设置最大步数
config.setDescription("自定义描述");
```

## 工具列表

SubAgent 系统提供以下工具：

| 工具名 | 描述 |
|--------|------|
| `subagent` | 启动指定类型的子代理执行任务 |
| `subagent_list` | 列出所有可用的子代理及其描述 |

## 与 Claude Code 的兼容性

本 SubAgent 系统设计上兼容 Claude Code 的子代理模式：

- 支持相同的子代理类型概念
- 提供类似的工具调用接口
- 可以通过 AGENTS.md 自定义子代理行为

## 测试示例

参考 `SubAgentTest.java` 查看完整的使用示例：

```bash
# 运行子代理测试
mvn test -Dtest=SubAgentTest
```
