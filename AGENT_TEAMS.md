# Agent Teams 模式

## 概述

Agent Teams 是一种多代理协作模式，通过 **Team Lead（团队领导）** 协调 **SubAgents（执行者）** 的分工协作，实现复杂任务的自动化分解和执行。

---

## 架构设计

### 整体架构

```
┌─────────────────────────────────────────────────────────┐
│                    主 ReActAgent                          │
│                  (用户交互入口)                            │
│                                                           │
│  ┌─────────────────────────────────────────────────┐    │
│  │          Team Lead 系统提示词                     │    │
│  │   - 任务分解                                       │    │
│  │   - 团队协作                                       │    │
│  │   - 结果汇总                                       │    │
│  └─────────────────────────────────────────────────┘    │
│                                                           │
│  技能集:                                                   │
│  ├── AgentTeamsSkill (团队协作工具)                       │
│  ├── TaskSkill (子代理调用)                               │
│  └── ... (其他技能)                                       │
└─────────────────────────────────────────────────────────┘
                         │
                         │ 调用 team_task()
                         ▼
┌─────────────────────────────────────────────────────────┐
│                      MainAgent                            │
│                   (团队协调器)                             │
│                                                           │
│  基础设施:                                                 │
│  ├── SharedTaskList (任务池)                              │
│  ├── SharedMemoryManager (记忆管理)                       │
│  ├── EventBus (事件总线)                                  │
│  └── MessageChannel (消息通道)                            │
└─────────────────────────────────────────────────────────┘
                         │
                         │ 调用子代理
                         ▼
┌─────────────────────────────────────────────────────────┐
│                       SubAgent                           │
│                      (执行者)                             │
│  ├── ExploreSubagent (代码探索)                          │
│  ├── PlanSubagent (方案设计)                             │
│  ├── BashSubagent (命令执行)                             │
│  ├── GeneralPurposeSubagent (通用任务)                   │
│  └── ... (自定义 teammates)                               │
└─────────────────────────────────────────────────────────┘
```

### 工作流程

```
用户请求 → Team Lead 分析 → 创建主任务 → 分解子任务
                                    ↓
                    ┌───────────────┴───────────────┐
                    │                               │
                    ▼                               ▼
              简单任务                         复杂任务
                    │                               │
                    ▼                               ▼
              直接回答                      team_task()
                                                    ↓
                    ┌────────────────────────────────┐
                    │     SubAgent 协作执行           │
                    │  - explore: 探索代码           │
                    │  - plan: 设计方案              │
                    │  - bash: 执行命令              │
                    │  - general-purpose: 通用任务   │
                    └────────────────────────────────┘
                                                    ↓
                    ┌────────────────────────────────┐
                    │     汇总结果并返回             │
                    └────────────────────────────────┘
```

---

## 核心组件

### 1. Team Lead（团队领导）

**角色**: 协调决策者

**核心职责**:
- 分析任务复杂度，决定执行方式
- 简单任务直接回答
- 复杂任务启动团队协作
- 协调 SubAgents 执行
- 汇总结果

### 2. MainAgent（协调器）

**职责**: 提供协作基础设施

**功能**:
- SharedTaskList: 任务池管理
- SharedMemoryManager: 记忆共享
- EventBus: 事件通知
- MessageChannel: 消息传递

### 3. SubAgents（执行者）

**内置类型**:

| 子代理 | 类型 | 专长 | 场景 |
|--------|------|------|------|
| 探索代理 | `explore` | 快速定位文件、理解代码结构 | 代码探索 |
| 规划代理 | `plan` | 任务分解、实现方案设计 | 方案设计 |
| 命令代理 | `bash` | Git 操作、构建、测试 | 命令执行 |
| 通用代理 | `general-purpose` | 复杂多步骤任务 | 通用场景 |

---

## 团队成员管理

### 快速创建（推荐）

```bash
# 只需 name + role，其他智能默认
teammate_quick(name="security-expert", role="安全专家")

# 输出：
# [OK] 团队成员创建成功
#
# ## 成员信息
#
# | 属性 | 值 |
# |------|------|
# | **名称** | `security-expert` |
# | **角色** | 安全专家 |
# | **描述** | 安全专家 |
# | **所属团队** | security-team (智能生成) |
# | **状态** | 🟢 已激活 |
```


### 完整自定义创建

```bash
# 需要完整自定义时使用
teammate(
    name="my-expert",
    role="专家",
    description="详细描述",
    expertise="skill1,skill2",
    tools="read,write,edit",
    skills="skill1,skill2"
)
```

### 查看团队成员

```bash
# 列出所有成员
teammates()

# 按团队筛选
teammates(teamName="security-team")
```

### 移除成员

```bash
remove_teammate("security-expert")
```

---

## 任务管理

### 快速添加任务（推荐）

```bash
# 只需标题
task_add(title="实现登录功能")

# 带描述
task_add(title="实现登录", description="包括用户认证和权限验证")
```

### 批量添加任务

```bash
# 逗号分隔
tasks_add(titles="任务1,任务2,任务3")
```

### 完整任务配置

```bash
# 需要完整配置时使用
create_task(
    title="实现登录",
    description="用户认证和权限验证",
    type="DEVELOPMENT",
    priority=8,
    dependencies="task1,task2"
)
```

### 查看任务状态

```bash
# 查看团队任务状态
team_status()

# 查看任务统计
get_task_statistics()

# 查看所有任务
list_all_tasks()
```

---

## 记忆管理

### 存储记忆

```bash
# 自动分类存储
memory_store(content="用户登录使用 JWT Token")

# 带键名
memory_store(key="auth-method", content="JWT")
```

### 检索记忆

```bash
# 按关键词检索
memory_recall(query="登录")

# 限制返回数量
memory_recall(query="认证", limit=5)
```

### 查看统计

```bash
memory_stats()
```

---

## 启动团队协作

### team_task 工具

```bash
# 启动团队协作任务
team_task(prompt="实现用户登录功能")

# 输出：
# [OK] 团队任务执行完成
#
# **任务统计**:
# - 总任务数: 4
# - 已完成: 4
# - 失败: 0
# - 进行中: 0
# - 待认领: 0
#
# **主 Agent 回复**:
# ...
```

**何时使用**:
- ✅ 多步骤复杂任务
- ✅ 需要多种技能组合
- ✅ 需要多个专业领域协作
- ❌ 简单问答（直接回答即可）

---

## 核心工具速查

### 团队协作

| 工具 | 参数 | 说明 |
|------|------|------|
| `team_task(prompt)` | prompt | 启动团队协作 |
| `team_status()` | - | 查看任务状态 |

### 成员管理

| 工具 | 参数 | 说明 |
|------|------|------|
| `teammate_quick(name, role)` | 2个 | 快速创建 ⭐ |
| `teammate_template(template)` | 1个 | 模板创建 ⭐ |
| `teammate_templates()` | 0个 | 查看所有模板 |
| `teammates(teamName?)` | 0-1个 | 列出成员 |
| `remove_teammate(name)` | 1个 | 移除成员 |

### 任务管理

| 工具 | 参数 | 说明 |
|------|------|------|
| `task_add(title, desc?)` | 1-2个 | 快速添加 ⭐ |
| `tasks_add(titles)` | 1个 | 批量添加 ⭐ |
| `create_task(...)` | 5个 | 完整配置 |
| `list_all_tasks()` | - | 查看所有任务 |

### 记忆管理

| 工具 | 参数 | 说明 |
|------|------|------|
| `memory_store(content, key?)` | 1-2个 | 存储记忆 |
| `memory_recall(query?, limit?)` | 0-2个 | 检索记忆 |
| `memory_stats()` | - | 记忆统计 |

### 子代理调用

| 工具 | 参数 | 说明 |
|------|------|------|
| `task(type, prompt)` | 2个 | 调用子代理 |

---

## 配置示例

```yaml
solon:
  code:
    cli:
      workDir: ./work

      # 启用 Teams 模式
      agentTeamEnabled: true

      # Teams 配置
      teams:
        taskExecutorThreads: 20
        eventExecutorThreads: 1
        maxCompletedTasks: 100
        maxDependencyDepth: 100

      chatModel:
        apiUrl: https://api.openai.com/v1
        apiKey: ${OPENAI_API_KEY}
        model: gpt-4
```

---

## 典型使用场景

### 场景 1: 功能开发

```
用户: "实现用户登录功能"

Team Lead 分析: 多步骤任务 → 启动 team_task()

执行流程:
  ├─ Task 1: explore - 探索现有认证代码
  ├─ Task 2: plan - 设计登录方案
  ├─ Task 3: general-purpose - 实现登录逻辑
  └─ Task 4: bash - 运行测试验证

汇总: 返回完整的实现方案和代码
```

### 场景 2: 安全审计

```
用户: "检查代码中的安全问题"

Team Lead:
  ├─ 创建 security-expert: teammate_template(template="security")
  ├─ Task 1: analyze_tasks - 分解审计任务
  ├─ Task 2: security-expert - 执行安全审计
  └─ Task 3: general-purpose - 生成审计报告
```

### 场景 3: 性能优化

```
用户: "优化 API 响应时间"

Team Lead:
  ├─ 创建团队成员:
  │   ├─ performance-eng (teammate_template)
  │   └─ database-expert (teammate_template)
  ├─ 分析: explore - 查找 API 代码
  ├─ 分析: database-expert - 分析 SQL 查询
  ├─ 优化: general-purpose - 实施优化
  └─ 验证: bash - 性能测试
```

---

## 关键特性

| 特性 | 说明 |
|------|------|
| **智能决策** | Team Lead 自动判断任务复杂度 |
| **简化工具** | 80% 参数减少，降低 token 消耗 |
| **共享记忆** | 团队成员共享上下文信息 |
| **任务追踪** | 自动创建主任务，状态实时更新 |
| **事件驱动** | 异步事件通知，松耦合通信 |

---

## 总结

Agent Teams 模式通过 **Team Lead 协调 + SubAgents 执行** 的协作模式，实现复杂任务的高效处理：

1. **Team Lead** - 智能分析，决策执行方式
2. **MainAgent** - 提供协作基础设施
3. **SubAgents** - 专注领域任务执行
4. **简化工具** - 降低使用复杂度

### 工具选择指南

```
简单任务
  └─ 直接回答，无需调用工具

创建成员
  ├─ 快速创建 → teammate_quick(name, role)
  ├─ 模板创建 → teammate_template(template)
  └─ 完整配置 → teammate(...)

创建任务
  ├─ 快速添加 → task_add(title)
  ├─ 批量添加 → tasks_add(titles)
  └─ 完整配置 → create_task(...)

复杂任务
  └─ team_task(prompt) → 启动团队协作
```

---


