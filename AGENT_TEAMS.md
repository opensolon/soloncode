# Agent Teams 模式

## 概述

Agent Teams 是一种多代理协作模式，通过 **MainAgent（协调器）** 和 **SubAgents（执行者）** 的分工协作，实现复杂任务的自动化分解和执行。

---

## 架构设计

```
┌─────────────────────────────────────────────────────────────────┐
│                         用户请求                                   │
│                    "实现用户登录功能"                               │
└─────────────────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                      MainAgent (协调器)                           │
│                    - 分析任务需求                                  │
│                    - 创建子任务                                    │
│                    - 协调 SubAgents                               │
│                    - 汇总执行结果                                  │
└─────────────────────────────────────────────────────────────────┘
                         │
         ┌───────────────┼───────────────┐
         │               │               │
         ▼               ▼               ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│ ExploreSub   │ │  PlanSub     │ │  BashSub     │
│  探索代码库   │ │  设计方案     │ │  执行命令     │
└──────────────┘ └──────────────┘ └──────────────┘
         │               │               │
         └───────────────┴───────────────┘
                         │
                         ▼
              ┌─────────────────────┐
              │   SharedTaskList    │
              │   (共享任务队列)      │
              └─────────────────────┘
```

---

## 核心组件

### 1. MainAgent（主代理）

**职责**: 任务协调和决策

**功能**:
- 接收用户请求，分析任务复杂度
- 将复杂任务分解为多个子任务
- 协调 SubAgents 认领和执行任务
- 汇总结果并生成最终回复

### 2. SubAgents（子代理）

**内置类型**:

| 子代理 | 代码 | 专长 | 最大步数 |
|--------|------|------|----------|
| 探索代理 | `explore` | 快速定位文件、理解代码结构 | 15 |
| 规划代理 | `plan` | 任务分解、实现方案设计 | 20 |
| 命令代理 | `bash` | Git 操作、构建、测试 | 10 |
| 通用代理 | `general-purpose` | 复杂多步骤任务 | 25 |

### 3. SharedTaskList（共享任务队列）

**功能**:
- 存储所有团队任务
- 支持任务依赖关系
- 自动分配可认领任务
- 追踪任务状态和进度

---

## Teammate 管理（团队成员）

类似 Claude Code 的 `/teammate` 功能，支持动态创建和管理团队成员。

### 创建团队成员

```java
// 通过 AgentTeamsSkill 创建新成员
teammate(
    name="security-expert",
    role="安全专家",
    description="专注于安全审计、漏洞检测和合规性检查",
    expertise="security,auth,encryption",
    model="gpt-4"
)
```

**输出格式**（表格）:
```
✅ 团队成员创建成功

## 成员信息

| 属性 | 值 |
|------|------|
| **名称** | `security-expert` |
| **角色** | 安全专家 |
| **描述** | 专注于安全审计、漏洞检测和合规性检查 |
| **专业领域** | security,auth,encryption |
| **模型** | gpt-4 |
| **文件** | `.soloncode/agents/security-expert.md` |
| **状态** | 🟢 已激活 |
```

### 查看所有成员

```java
// 列出所有团队成员（表格格式）
teammates()
```

**输出格式**:
```
## 团队成员

| 名称 | 角色 | 描述 | 状态 | 模型 |
|------|------|------|------|------|
| `explore` | Explore | 代码探索专家 | 🟢 活跃 | 默认 |
| `plan` | Plan | 方案设计专家 | 🟢 活跃 | 默认 |
| `security-expert` | 安全专家 | 安全审计和漏洞检测 | 🟢 活跃 | gpt-4 |

**总计**: 3 位活跃成员
```

### 移除团队成员

```java
// 禁用指定成员
remove_teammate("security-expert")
```

---

## 简单代码实现

### 基础初始化

```java
// 1. 创建 SubAgentAgentBuilder
SubAgentAgentBuilder builder = SubAgentAgentBuilder.of(chatModel)
    .workDir("./work")
    .sessionProvider(sessionProvider)
    .poolManager(poolManager)
    .addAllFrom(subagentManager);

// 2. 构建 MainAgent
MainAgent mainAgent = builder.build();

// 3. 执行团队协作任务
AgentResponse response = mainAgent.execute(
    Prompt.of("实现用户登录功能，包括探索代码、设计方案、开发实现、编写测试")
);

// 4. 获取任务统计
SharedTaskList.TaskStatistics stats = mainAgent.getTaskList().getStatistics();
System.out.println("总任务: " + stats.totalTasks);
System.out.println("已完成: " + stats.completedTasks);
```

### 创建和管理任务

```java
// 获取共享任务列表
SharedTaskList taskList = mainAgent.getTaskList();

// 创建新任务
TeamTask exploreTask = new TeamTask();
exploreTask.setTitle("探索现有认证代码");
exploreTask.setDescription("分析项目中的认证相关代码结构");
exploreTask.setType(TeamTask.TaskType.EXPLORATION);
exploreTask.setPriority(8);

// 添加到任务队列
CompletableFuture<TeamTask> future = taskList.addTask(exploreTask);
TeamTask addedTask = future.join();

// 创建带依赖的任务
TeamTask implTask = new TeamTask();
implTask.setTitle("实现登录功能");
implTask.setDependencies(Arrays.asList(addedTask.getId())); // 依赖 exploreTask

taskList.addTask(implTask);
```

### SubAgent 认领任务

```java
// SubAgent 主动认领任务
Subagent exploreAgent = subagentManager.getAgent("explore");

// 查看可认领任务
List<TeamTask> claimableTasks = taskList.getClaimableTasks();

// 认领并执行
for (TeamTask task : claimableTasks) {
    if (task.getType() == TeamTask.TaskType.EXPLORATION) {
        exploreAgent.claimAndExecute(task);
        break;
    }
}
```

### 监听任务事件

```java
// 订阅任务事件
EventBus eventBus = mainAgent.getEventBus();

// 监听任务创建
eventBus.subscribe(AgentEventType.TASK_CREATED, event -> {
    TeamTask task = (TeamTask) event.getData();
    System.out.println("新任务创建: " + task.getTitle());
});

// 监听任务完成
eventBus.subscribe(AgentEventType.TASK_COMPLETED, event -> {
    TeamTask task = (TeamTask) event.getData();
    System.out.println("任务完成: " + task.getTitle());
});

// 监听任务失败
eventBus.subscribe(AgentEventType.TASK_FAILED, event -> {
    TeamTask task = (TeamTask) event.getData();
    System.out.println("任务失败: " + task.getErrorMessage());
});
```

---

## 工作流程

```
1. 用户请求
   ↓
2. MainAgent 分析任务
   ↓
3. 创建主任务并添加到 SharedTaskList
   ↓
4. MainAgent 分解任务为子任务
   ↓
5. 子任务添加到 SharedTaskList（带依赖关系）
   ↓
6. SubAgents 扫描可认领任务
   ↓
7. SubAgent 认领并执行任务
   ↓
8. 更新任务状态，触发事件
   ↓
9. 重复 6-8 直到所有任务完成
   ↓
10. MainAgent 汇总结果并返回
```

---

## 典型使用场景

### 场景 1: 功能开发

```
用户: "实现用户登录功能"

MainAgent:
  ├─ 创建任务: "实现用户登录"
  ├─ 分解:
  │   ├─ Task 1: 探索现有认证代码 (explore)
  │   ├─ Task 2: 设计登录方案 (plan)
  │   ├─ Task 3: 实现登录逻辑 (general-purpose)
  │   └─ Task 4: 编写测试 (general-purpose)
  └─ 协调 SubAgents 执行
```

### 场景 2: 代码重构

```
用户: "重构 UserService 类，使其符合 SOLID 原则"

MainAgent:
  ├─ 创建任务: "重构 UserService"
  ├─ 分解:
  │   ├─ Task 1: 分析 UserService 依赖 (explore)
  │   ├─ Task 2: 设计重构方案 (plan)
  │   ├─ Task 3: 执行重构 (general-purpose)
  │   └─ Task 4: 运行测试验证 (bash)
  └─ 协调 SubAgents 执行
```

### 场景 3: 问题诊断

```
用户: "登录时偶尔超时，帮我排查问题"

MainAgent:
  ├─ 创建任务: "诊断登录超时问题"
  ├─ 分解:
  │   ├─ Task 1: 搜索日志文件 (bash)
  │   ├─ Task 2: 分析认证代码 (explore)
  │   ├─ Task 3: 检查数据库连接 (bash)
  │   └─ Task 4: 生成诊断报告 (general-purpose)
  └─ 协调 SubAgents 执行
```

---

## 配置示例

**config.yml**:
```yaml
solon:
  code:
    cli:
      workDir: ./work
      chatModel:
        apiUrl: https://api.openai.com/v1
        apiKey: ${OPENAI_API_KEY}
        model: gpt-4
        defaultOptions:
          temperature: 0.7

      # 启用子代理系统
      subAgentEnabled: true

      # 子代理模型配置（可选）
      subAgentModels:
        explore: gpt-4o-mini
        plan: gpt-4
        bash: gpt-4o-mini
        general-purpose: gpt-4
```

---

## 关键特性

| 特性 | 说明 |
|------|------|
| **任务分解** | MainAgent 自动将复杂任务分解为可执行的子任务 |
| **依赖管理** | 支持任务间依赖关系，确保执行顺序正确 |
| **并行执行** | 多个 SubAgent 可并行执行独立任务 |
| **状态跟踪** | 实时追踪任务状态、进度和结果 |
| **事件驱动** | 基于 EventBus 的异步事件通知 |
| **容错机制** | 任务失败自动重试或标记错误 |

---

## 总结

Agent Teams 模式通过 **MainAgent 协调 + SubAgents 执行** 的分工协作，实现了复杂任务的自动化处理：

1. **MainAgent** 负责任务分解和协调决策
2. **SubAgents** 专注于特定领域的任务执行
3. **SharedTaskList** 提供统一的任务管理
4. **EventBus** 实现松耦合的事件通信
5. **Teammate 管理** 支持动态创建和管理团队成员（类似 Claude Code）

### 核心工具

| 工具 | 功能 | 输出格式 |
|------|------|----------|
| `team_task()` | 启动团队协作任务 | 统计 + 结果 |
| `team_status()` | 查看任务状态 | 表格 + 统计 |
| `create_task()` | 创建新任务 | 任务信息 |
| `teammate()` | 创建团队成员 | 表格 |
| `teammates()` | 列出所有成员 | 表格 |
| `remove_teammate()` | 移除团队成员 | 状态信息 |
| `subagent()` | 调用子代理 | task_id + 结果 |

这种模式特别适合需要多个步骤、涉及多个文件或需要专业领域知识的复杂任务。

---

**作者**: bai
**日期**: 2026-03-09
**版本**: 1.0
