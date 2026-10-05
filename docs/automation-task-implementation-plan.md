# 自动任务功能执行方案

## 一、目标定义

在现有“以 session 为中心的循环任务”之上，新增一个“以任务为中心的自动任务”能力：

> 用户创建一个长期存在的自动任务，任务绑定一个专属隐藏对话，按计划自动执行；用户可以从左侧“自动任务”菜单中查看任务状态、执行记录和隐藏对话内容。

需要同时满足：

1. 自动任务不依赖普通会话生命周期；
2. 每个自动任务拥有独立隐藏会话；
3. 支持创建、编辑、暂停、恢复、删除、立即执行；
4. 支持 cron 和固定间隔；
5. 支持查看最近执行结果；
6. 不破坏现有 session 级循环任务；
7. 自动任务默认不出现在普通最近对话列表中。

---

# 二、总体方案

## 2.1 资源关系

```text
AutomationTask
    ├── automationId
    ├── name
    ├── prompt
    ├── schedule
    ├── hiddenSessionId
    ├── enabled
    ├── status
    ├── lastRun
    └── nextRun
```

执行关系：

```text
自动任务触发
    ↓
读取 AutomationTask
    ↓
使用 hiddenSessionId 加载隐藏会话
    ↓
执行 prompt
    ↓
记录执行结果
    ↓
更新任务状态
```

与现有功能的边界：

```text
循环任务：当前 session 的辅助调度能力
自动任务：独立存在的长期自动化能力
```

现有循环任务继续保留，不进行一次性强迁移。

## 2.2 推荐架构

```text
AutomationController
        ↓
AutomationService
        ↓
AutomationStore
        ↓
AutomationScheduler
        ↓
现有 Loop 执行机制 / 通用 Agent 执行机制
```

职责划分：

### AutomationController

负责 Web API：

- 查询任务；
- 新建任务；
- 修改任务；
- 启停任务；
- 删除任务；
- 立即执行；
- 查询执行记录；
- 打开隐藏会话。

### AutomationService

负责业务逻辑：

- 校验任务配置；
- 创建隐藏会话；
- 绑定任务与会话；
- 调度注册；
- 执行任务；
- 写入执行记录；
- 处理删除和恢复。

### AutomationStore

负责任务和执行记录持久化：

```text
automations/
  <automationId>/
    automation.json
    runs.ndjson
```

### AutomationScheduler

负责：

- 启动时恢复所有启用任务；
- 注册 cron 或 interval；
- 执行到期任务；
- 处理暂停、删除和重新注册；
- 防止同一任务并发执行。

---

# 三、数据模型设计

## 3.1 自动任务配置

建议新增：

```java
class AutomationTask {
    String id;
    String name;
    String description;

    String prompt;

    String scheduleType;     // CRON / INTERVAL
    String cronExpression;
    Long intervalSeconds;
    String timezone;

    String hiddenSessionId;

    boolean enabled;
    String status;           // IDLE / RUNNING / SUCCESS / FAILED / PAUSED

    Instant createdAt;
    Instant updatedAt;
    Instant lastRunAt;
    Instant nextRunAt;

    String lastRunId;
    String lastResultSummary;
    String lastError;
}
```

第一版可暂不暴露所有高级字段，但后端模型建议预留。

## 3.2 执行记录

```java
class AutomationRun {
    String runId;
    String automationId;
    String hiddenSessionId;

    Instant scheduledAt;
    Instant startedAt;
    Instant finishedAt;

    String status;           // RUNNING / SUCCESS / FAILED / SKIPPED
    String summary;
    String error;

    long durationMs;
}
```

执行记录建议先保留最近 50 次，后续再增加清理策略。

## 3.3 隐藏会话标识

普通会话模型中增加明确标识：

```json
{
  "sessionType": "AUTOMATION",
  "ownerAutomationId": "auto-001",
  "visibleInRecentChats": false
}
```

不要只依赖前端隐藏。后端列举普通会话时，也必须排除自动任务会话。

---

# 四、持久化方案

## 4.1 推荐目录

```text
<workspace-data>/
  automations/
    auto-001/
      automation.json
      runs.ndjson
    auto-002/
      automation.json
      runs.ndjson
```

隐藏会话可以继续使用现有 session 存储结构：

```text
<workspace-data>/
  sessions/
    auto-session-auto-001/
```

但必须在 session 元数据中标记为自动任务会话。

这样做的好处：

- 自动任务配置与执行历史聚合；
- 自动任务不依赖普通会话列表；
- 删除普通会话不会影响自动任务；
- 后续可以扩展通知、执行统计和任务级权限。

现有会话数据边界可参考：

```text
soloncode-cli/src/main/java/org/noear/solon/codecli/workspace/WorkspaceDataUtil.java
```

现有 Loop 任务以 session 为存储维度，相关实现可参考：

```text
soloncode-cli/src/main/java/org/noear/solon/codecli/command/builtin/LoopScheduler.java
soloncode-cli/src/main/java/org/noear/solon/codecli/command/builtin/LoopTask.java
```

---

# 五、后端执行阶段

## 阶段一：建立领域模型和存储

### 任务清单

1. 新增 `AutomationTask`；
2. 新增 `AutomationRun`；
3. 新增 `AutomationStore`；
4. 实现 JSON 或 NDJSON 读写；
5. 实现任务创建、查询、更新、删除；
6. 实现最近执行记录查询；
7. 增加数据格式版本字段。

建议配置文件包含：

```json
{
  "version": 1,
  "id": "auto-001",
  "name": "每日构建检查",
  "scheduleType": "CRON",
  "cronExpression": "0 0 9 * * ? *",
  "prompt": "检查项目最近一次构建和测试结果",
  "hiddenSessionId": "auto-session-auto-001",
  "enabled": true
}
```

### 验证标准

- 创建任务后重启程序，任务仍然存在；
- 修改任务后配置能够正确覆盖；
- 删除任务后配置和调度均被清理；
- 损坏的单个任务文件不会导致整个程序启动失败；
- 旧的 session Loop 文件可以继续读取。

---

## 阶段二：实现隐藏会话生命周期

### 任务清单

1. 创建自动任务时自动生成隐藏会话；
2. 为隐藏会话写入 `sessionType=AUTOMATION`；
3. 普通会话列表排除自动任务会话；
4. 自动任务删除时清理或归档对应隐藏会话；
5. 防止用户删除普通会话时误删自动任务；
6. 支持通过 `automationId` 定位隐藏会话。

### 推荐策略

默认一个自动任务绑定一个长期隐藏会话：

```text
auto-001 → auto-session-auto-001
```

每次执行都继续使用这个会话，保持上下文连续性。

### 验证标准

- 新建任务后能够看到对应隐藏会话文件；
- 普通“最近对话”中不出现隐藏会话；
- 自动任务页面可以打开该隐藏会话；
- 任务执行两次后，第二次能够读取第一次产生的上下文；
- 删除任务后不会误删其他普通会话。

---

## 阶段三：接入调度器

### 任务清单

1. 新增 `AutomationScheduler`；
2. 程序启动时恢复启用任务；
3. 支持 cron；
4. 支持 interval；
5. 支持暂停和恢复；
6. 支持立即执行；
7. 支持任务执行锁；
8. 支持程序重启后的任务恢复；
9. 支持执行失败记录；
10. 支持任务执行超时或异常状态更新。

现有调度能力可以参考：

```text
LoopScheduler.schedule()
LoopScheduler.remove()
LoopScheduler.toggle()
LoopScheduler.trigger()
LoopScheduler.restoreAll()
```

来源：

```text
soloncode-cli/src/main/java/org/noear/solon/codecli/command/builtin/LoopScheduler.java
```

### 并发规则

同一个自动任务不能并发运行：

```text
RUNNING 状态下再次触发
    → 标记为 SKIPPED
    或排队等待
```

第一版建议采用 `SKIPPED`，避免任务堆积。

不同自动任务之间是否允许并发，需要复用现有资源控制机制。建议第一版默认允许不同任务并发，但仍受全局 Agent 和模型资源限制。

### 验证标准

- 程序重启后启用任务能恢复；
- 暂停任务不会继续触发；
- 立即执行可以绕过下一次计划时间；
- 同一任务不会同时执行两次；
- 执行成功和失败均能写入 `runs.ndjson`；
- cron 和 interval 的下一次执行时间计算正确；
- 任务失败不会导致调度线程退出。

---

## 阶段四：提供 Web API

建议新增接口：

```text
GET    /web/automation/list
GET    /web/automation/get?id=<id>
POST   /web/automation/create
POST   /web/automation/update
POST   /web/automation/delete
POST   /web/automation/toggle
POST   /web/automation/trigger
GET    /web/automation/runs?id=<id>
GET    /web/automation/session?id=<id>
```

创建请求示例：

```json
{
  "name": "每日构建检查",
  "prompt": "检查项目最近一次构建和测试结果",
  "scheduleType": "CRON",
  "cronExpression": "0 0 9 * * ? *",
  "timezone": "Asia/Shanghai",
  "enabled": true
}
```

返回数据至少包括：

```json
{
  "id": "auto-001",
  "name": "每日构建检查",
  "enabled": true,
  "status": "SUCCESS",
  "scheduleText": "每天 09:00",
  "nextRunAt": "2026-...",
  "lastRunAt": "2026-...",
  "lastResultSummary": "检查完成，发现 1 个测试失败",
  "hiddenSessionId": "auto-session-auto-001"
}
```

### 验证标准

- 所有接口返回统一 JSON 结构；
- 参数错误返回明确错误信息；
- 不存在的任务返回 404 或等价错误；
- 删除、暂停、立即执行等操作具有幂等性；
- API 不需要前端刷新页面即可反映状态变化。

---

# 六、前端执行阶段

## 阶段五：增加左侧菜单

修改：

```text
soloncode-cli/src/main/resources/static/web.html
```

在“心智记忆”下增加：

```html
<button class="side-menu-item" data-action="automation">
    自动任务
</button>
```

建议保持“自动任务”与“心智记忆”平级，而不是嵌套在心智记忆内部。

### 验证标准

- 左侧能够看到“自动任务”；
- 点击后打开自动任务 Viewer；
- 当前聊天内容不会被清空；
- 菜单选中状态正确；
- 刷新页面后仍能正常进入自动任务。

---

## 阶段六：实现自动任务面板

新增：

```text
soloncode-cli/src/main/resources/static/js/app-automation.js
```

建议结构：

```text
自动任务面板
├── 顶部工具栏
│   ├── 新建自动任务
│   └── 刷新
├── 任务列表
│   └── 任务卡片
└── 详情区
    ├── 基本信息
    ├── 调度设置
    ├── 最近执行
    └── 查看隐藏对话
```

任务卡片显示：

- 名称；
- 调度描述；
- 当前状态；
- 最近一次执行；
- 下一次执行；
- 最近结果摘要；
- 启用/暂停；
- 立即执行；
- 查看对话；
- 编辑；
- 删除。

现有循环任务前端可以作为交互参考：

```text
soloncode-cli/src/main/resources/static/js/app-loop.js
```

但不要直接复制其“绑定当前 session”的逻辑。

---

## 阶段七：新建和编辑弹窗

第一版字段：

```text
任务名称
任务说明
执行提示词
调度方式：Cron / 固定间隔
Cron 表达式或间隔时间
时区
是否启用
```

建议增加“高级设置”折叠区域，暂时隐藏：

```text
模型
Agent
最大预算
超时时间
失败重试次数
通知方式
```

### 表单校验

前端校验：

- 任务名称不能为空；
- prompt 不能为空；
- cron 表达式格式基本合法；
- interval 必须大于 0；
- 任务名称长度限制；
- 不允许重复名称，或允许重复但必须显示唯一 ID。

后端必须再次校验，不能依赖前端。

---

## 阶段八：查看隐藏对话

点击“查看对话”时，不建议直接把隐藏会话设为当前普通聊天会话。推荐打开只读或半只读查看器：

```text
自动任务详情
    ├── 执行记录
    └── 隐藏对话内容
```

如果后续允许用户继续向隐藏会话发送消息，必须明确标示：

```text
这是自动任务「每日构建检查」的专属对话
```

避免用户误以为是普通聊天。

现有记忆 Viewer 的展示和面板机制可参考：

```text
soloncode-cli/src/main/resources/static/js/app-memory.js
```

### 验证标准

- 能查看隐藏对话历史；
- 能从某次执行记录定位到对应内容；
- 对话内容不会出现在普通会话列表；
- 返回自动任务列表后，任务状态不丢失。

---

# 七、兼容和迁移策略

## 7.1 旧 Loop 任务不立即迁移

第一版保持两个入口：

```text
当前对话输入框中的循环任务
自动任务菜单中的独立自动任务
```

这样可以降低风险，避免改变现有用户行为。

## 7.2 旧接口继续保留

现有接口：

```text
/web/chat/loop/*
```

继续工作。

新接口使用：

```text
/web/automation/*
```

不要直接修改旧接口语义，否则可能影响现有前端和 CLI 命令。

## 7.3 后续迁移入口

后续可以在现有循环任务卡片中提供：

```text
转换为自动任务
```

转换过程：

```text
旧 session LoopTask
    ↓
创建 AutomationTask
    ↓
绑定新隐藏会话或复用原 session
    ↓
旧 LoopTask 标记为已迁移
```

第一版不建议自动迁移，避免重复执行。

---

# 八、分阶段里程碑

## 里程碑一：数据层完成

交付内容：

- `AutomationTask`；
- `AutomationRun`；
- `AutomationStore`；
- 自动任务目录；
- 创建、查询、更新、删除逻辑。

验收：

- 单元测试覆盖任务序列化；
- 重启后任务可恢复；
- 损坏任务文件不会影响其他任务；
- 旧 Loop 功能无回归。

## 里程碑二：执行层完成

交付内容：

- `AutomationScheduler`；
- cron 和 interval；
- 启停；
- 立即执行；
- 执行记录；
- 并发保护。

验收：

- 定时触发准确；
- 暂停后不再触发；
- 同一任务不重复并发；
- 执行失败可恢复；
- 重启后调度正常。

## 里程碑三：Web API 完成

交付内容：

- 任务 CRUD API；
- 执行 API；
- 执行记录 API；
- 隐藏会话访问 API。

验收：

- 使用接口可以完整管理任务；
- 错误信息明确；
- 接口幂等；
- API 测试覆盖正常、错误和边界场景。

## 里程碑四：前端页面完成

交付内容：

- 左侧“自动任务”菜单；
- 自动任务列表；
- 新建、编辑弹窗；
- 任务详情；
- 执行记录；
- 查看隐藏对话。

验收：

- 用户无需打开当前聊天即可管理任务；
- 刷新后页面状态正确；
- 空列表、加载失败、执行中、执行失败均有明确 UI；
- 任务操作不影响普通聊天。

## 里程碑五：稳定性和兼容性验证

交付内容：

- 重启恢复；
- 异常恢复；
- 长时间运行测试；
- 旧 Loop 回归测试；
- 数据清理策略；
- 文档和使用说明。

验收：

- 连续运行一段时间无调度线程泄漏；
- 任务执行异常不会使服务退出；
- 旧 session 任务行为不变；
- 删除和暂停操作不会产生幽灵任务；
- 自动任务隐藏会话不会混入普通会话列表。

---

# 九、风险与应对

## 风险一：新增第二套调度逻辑

如果自动任务完全复制 LoopScheduler，后续会出现两套 cron、重试、状态和恢复逻辑。

应对：

- 抽取通用调度能力；
- 或由 AutomationScheduler 负责领域管理，复用现有执行能力；
- 不复制核心执行流程。

## 风险二：任务与隐藏会话状态不一致

例如任务存在但会话不存在，或者会话存在但任务已删除。

应对：

启动恢复时执行一致性检查：

```text
任务存在、会话不存在
    → 自动重建隐藏会话

会话存在、任务不存在
    → 标记为孤儿会话，延迟清理

任务正在运行但进程已退出
    → 恢复为 FAILED 或 INTERRUPTED
```

## 风险三：自动任务无限积累上下文

长期隐藏会话可能越来越大，影响模型成本和上下文窗口。

应对：

第一版先记录风险；后续增加：

- 自动摘要；
- 上下文裁剪；
- 按周期创建新执行会话；
- 保留主任务上下文和最近若干轮执行内容。

## 风险四：普通会话列表出现隐藏会话

应对：

- 后端排除；
- 前端再次过滤；
- 为隐藏会话增加明确类型字段；
- 增加回归测试。

## 风险五：任务执行结果缺少可追溯性

仅保存 `lastResult` 不够。

应对：

- 引入 `AutomationRun`；
- 每次运行拥有唯一 `runId`；
- 执行记录关联 `hiddenSessionId`；
- UI 支持从任务到执行记录再到对话跳转。

---

# 十、推荐开发顺序

```text
1. 设计并实现 AutomationTask / AutomationRun
2. 实现 AutomationStore
3. 实现隐藏会话标识和生命周期
4. 实现 AutomationScheduler
5. 实现 Web API
6. 增加左侧菜单
7. 实现任务列表和任务详情
8. 实现新建/编辑弹窗
9. 实现执行记录和隐藏对话查看
10. 做重启、异常、兼容性测试
```

不要先做前端页面再倒推接口，也不要先复制现有 Loop 页面。核心风险在任务生命周期、隐藏会话和调度恢复，而不是菜单展示。

## 最终方案结论

采用以下正式定义：

> “自动任务”是独立于普通会话的长期调度资源；每个自动任务拥有一个专属隐藏会话，调度器负责触发，执行记录负责追踪，左侧菜单负责管理和查看。

第一版只做“独立任务、专属隐藏会话、cron/interval、执行历史和手动触发”，暂不加入复杂通知、条件触发和任务迁移。这样可以在不破坏现有 Loop 功能的前提下，建立未来自动化能力的正确基础。

---

# 十一、关于“循环任务”和“自动任务”统一建模的修订方案

## 11.1 核心判断

“循环任务”和“自动任务”底层都是定时心跳，因此应该共享同一套调度和执行内核；但它们的归属关系不同，不能把两者简单当成同一种业务资源：

```text
循环任务：Session → LoopTask[]
自动任务：AutomationTask → 专用 Session
```

也就是说：

- 循环任务以 `sessionId` 为拥有者，一个 session 可以有多个 loop；
- 自动任务以任务本身为拥有者，一个任务绑定一个专用 session；
- 两者都可以有 cron、interval、enabled、状态和执行记录；
- 两者的删除、展示、恢复和生命周期不同。

因此推荐采用：

> 统一任务协议、统一调度内核、统一执行记录格式；保留两种任务的领域归属和兼容性存储。

不建议第一版直接把所有历史数据重写成一个全新的文件格式。

## 11.2 统一接口是可行的

可以提供一套以任务为中心的通用接口，并通过类型和归属参数区分两种任务：

```text
GET    /web/tasks/list
GET    /web/tasks/get
POST   /web/tasks/create
POST   /web/tasks/update
POST   /web/tasks/delete
POST   /web/tasks/toggle
POST   /web/tasks/trigger
GET    /web/tasks/runs
```

请求中增加明确的任务类型：

```json
{
  "type": "SESSION_LOOP",
  "sessionId": "session-001",
  "taskId": "loop-001"
}
```

或者：

```json
{
  "type": "AUTOMATION",
  "sessionId": "auto-session-001",
  "taskId": "auto-001"
}
```

推荐类型枚举使用：

```text
SESSION_LOOP
AUTOMATION
```

不建议使用含义模糊的 `LOOP` 和 `TASK`，因为自动任务本身也是循环调度任务。

## 11.3 统一接口的数据契约

建议统一接口使用一个公共任务信封：

```java
class ScheduledTaskDTO {
    String taskId;
    String type;              // SESSION_LOOP / AUTOMATION

    String sessionId;         // 两种类型都保留
    String ownerId;           // SESSION_LOOP 为 sessionId；AUTOMATION 为 automationId

    String name;
    String prompt;

    String scheduleType;      // CRON / INTERVAL
    String cronExpression;
    Long intervalSeconds;
    String timezone;

    boolean enabled;
    String status;
    String scope;

    Instant createdAt;
    Instant updatedAt;
    Instant lastRunAt;
    Instant nextRunAt;
}
```

这里的关键设计是：

- `type` 表示任务领域类型；
- `taskId` 表示任务自身 ID；
- `sessionId` 表示实际执行上下文；
- `ownerId` 表示任务的业务拥有者；
- `scope` 可选，用于区分 workspace、session 或 automation。

### sessionId 是否应该统一保留

应该保留，而且两种任务都应该有关联的 `sessionId`，但含义不同：

```text
SESSION_LOOP：sessionId 是任务的拥有者，同时也是执行会话
AUTOMATION：sessionId 是任务专用的执行会话，任务本身才是拥有者
```

因此 `sessionId` 是共同的执行上下文字段，但不能反过来成为统一的主归属字段。自动任务不能重新退化成“session 下的任务”。

自动任务的关联关系应当是：

```text
automationId → hiddenSessionId
```

循环任务的关联关系仍然是：

```text
sessionId → loopTaskId[]
```

## 11.4 接口实现方式

建议新增一个统一的 `TaskService` 门面，但内部按类型分派：

```text
TaskController
    ↓
TaskService
    ├── SessionLoopTaskAdapter
    │       └── 现有 LoopScheduler / 旧 loop 存储
    └── AutomationTaskAdapter
            └── AutomationScheduler / 新自动任务存储
```

示例逻辑：

```text
TaskService.update(type=SESSION_LOOP, taskId=xxx)
    → SessionLoopTaskAdapter.update(...)

TaskService.update(type=AUTOMATION, taskId=xxx)
    → AutomationTaskAdapter.update(...)
```

这样可以统一 Web 层契约，同时避免把两种任务的生命周期强行揉在一个类里。

现有接口继续保留作为兼容接口：

```text
/web/chat/loop/*
```

新增统一接口作为新前端和后续客户端使用的接口：

```text
/web/tasks/*
```

旧接口可以内部转调 `TaskService`，但必须默认补齐：

```text
 type = SESSION_LOOP
 sessionId = 当前 sessionId
```

这样不会破坏现有 CLI 和旧版前端。

## 11.5 哪些操作可以统一，哪些操作必须区分

### 可以完全统一的操作

- 查询任务详情；
- 编辑名称、prompt、调度表达式；
- 启用和暂停；
- 立即触发；
- 计算下一次执行时间；
- 查询最近执行状态；
- 查询执行记录；
- 校验 cron 和 interval；
- 执行锁和运行状态；
- 任务执行失败处理。

### 必须按类型处理的操作

#### 创建

```text
SESSION_LOOP：要求已有 sessionId，在该 session 下新增 loop
AUTOMATION：创建 taskId，同时创建专用 sessionId
```

#### 删除

```text
SESSION_LOOP：只删除 loop，不删除 session
AUTOMATION：删除任务时需要处理专用 session 的归档或清理
```

#### 列表

```text
SESSION_LOOP：按 sessionId 查询
AUTOMATION：按 workspace 查询
```

#### 打开执行上下文

```text
SESSION_LOOP：打开已有普通 session
AUTOMATION：打开隐藏的专用 session
```

#### 恢复

```text
SESSION_LOOP：从现有 session 目录恢复
AUTOMATION：从自动任务目录恢复，并校验专用 session 是否存在
```

## 11.6 数据是否统一保存：推荐分层统一，而不是立即合并文件

推荐结论：

> 逻辑模型统一，物理存储先分开；循环任务保持原有存储，自动任务使用新位置。

即：

```text
循环任务：保持现有 session 相关 loop 文件
自动任务：保存到 automations/<automationId>/automation.json
```

可以进一步把两者都映射成统一的内存对象或 DTO，但不要求它们读取同一个文件。

### 保持分开保存的原因

1. **兼容性**：现有循环任务的数据格式和恢复路径不能轻易改变；
2. **生命周期不同**：循环任务依附 session，自动任务独立于普通 session；
3. **删除语义不同**：删除 loop 不应删除 session，删除自动任务可能需要处理专用 session；
4. **恢复方式不同**：循环任务从 session 恢复，自动任务从 workspace 自动任务索引恢复；
5. **降低迁移风险**：不需要一次性扫描和重写已有用户数据；
6. **便于回滚**：自动任务功能出现问题时，不影响现有循环任务文件。

### 不推荐现在统一到一个 tasks.json

如果把两种任务都写入：

```text
workspace/tasks.json
```

短期看结构统一，长期会产生几个问题：

- session 删除和任务删除耦合；
- 多个 session 同时修改一个文件时容易产生并发写入问题；
- 现有 LoopScheduler 的恢复逻辑需要大幅修改；
- 自动任务的 workspace 生命周期被旧 session 结构牵制；
- 旧版本无法识别新文件格式；
- 出现损坏时影响所有类型任务。

## 11.7 推荐的统一存储抽象

虽然物理文件分开，但可以定义统一存储接口：

```java
interface ScheduledTaskRepository {
    ScheduledTaskDTO get(String type, String taskId);
    List<ScheduledTaskDTO> list(TaskQuery query);
    void save(ScheduledTaskDTO task);
    void delete(String type, String taskId);
    List<AutomationRun> listRuns(String type, String taskId);
}
```

具体实现：

```text
SessionLoopTaskRepository
    → 读取和写入现有 session loop 文件

AutomationTaskRepository
    → 读取和写入 automations/<automationId>/automation.json
```

如果未来确实需要统一文件，可以通过 Repository 层迁移，而不影响 Controller 和前端。

## 11.8 执行记录是否统一

执行记录比任务配置更适合统一。

建议抽取统一的运行记录模型：

```java
class ScheduledTaskRun {
    String runId;
    String taskType;          // SESSION_LOOP / AUTOMATION
    String taskId;
    String sessionId;

    Instant scheduledAt;
    Instant startedAt;
    Instant finishedAt;

    String status;             // RUNNING / SUCCESS / FAILED / SKIPPED
    String summary;
    String error;
}
```

但是物理存储仍可分开：

```text
循环任务：现有 session 运行记录或兼容字段
自动任务：automations/<automationId>/runs.ndjson
```

如果现有循环任务暂时没有完整运行历史，不要为了统一接口而强行补写全部旧记录。统一接口可以对旧任务返回当前已有的状态，并从新版本开始记录完整 run。

## 11.9 调度器的最终分层

建议不要创建完全独立的两套心跳线程，而是抽出公共调度内核：

```text
ScheduledTaskEngine
    ├── 注册 cron / interval
    ├── 计算 nextRunAt
    ├── 触发任务
    ├── 执行锁
    ├── 忙碌检查
    ├── 失败处理
    └── 状态更新

TaskExecutor
    ├── SessionLoopExecutor
    └── AutomationExecutor
```

执行上下文不同：

```text
SessionLoopExecutor
    → 使用当前 session 的 loop 配置执行

AutomationExecutor
    → 使用 automationId 找到专用 session 后执行
```

可采用渐进式重构：

1. 先抽取 cron、interval、状态和执行锁等公共接口；
2. 让现有 `LoopScheduler` 通过适配器接入；
3. 新增自动任务适配器；
4. 最后再考虑重命名或合并调度类。

第一阶段不建议直接重写 `LoopScheduler`，避免破坏当前循环任务。

## 11.10 修订后的推荐接口

### 统一接口

```text
GET    /web/tasks/list?type=SESSION_LOOP&sessionId=xxx
GET    /web/tasks/list?type=AUTOMATION
GET    /web/tasks/get?type=...&taskId=...
POST   /web/tasks/create
POST   /web/tasks/update
POST   /web/tasks/delete
POST   /web/tasks/toggle
POST   /web/tasks/trigger
GET    /web/tasks/runs?type=...&taskId=...
GET    /web/tasks/session?type=...&taskId=...
```

### 创建请求示例：循环任务

```json
{
  "type": "SESSION_LOOP",
  "sessionId": "session-001",
  "prompt": "继续关注这个问题",
  "scheduleType": "INTERVAL",
  "intervalSeconds": 3600,
  "enabled": true
}
```

### 创建请求示例：自动任务

```json
{
  "type": "AUTOMATION",
  "name": "每日构建检查",
  "prompt": "检查项目最近一次构建和测试结果",
  "scheduleType": "CRON",
  "cronExpression": "0 0 9 * * ? *",
  "timezone": "Asia/Shanghai",
  "enabled": true
}
```

自动任务创建请求不要求前端提交 `sessionId`；由后端创建专用 session 并在响应中返回。更新和执行时必须使用服务端保存的 `sessionId`，不能允许客户端任意更换专用会话。

## 11.11 修订后的最终决策

最终采用以下方案：

```text
统一：
- 任务 DTO 契约
- type 类型字段
- CRUD / toggle / trigger / runs 接口
- 调度计算
- 执行锁
- 运行状态
- 执行记录格式
- 任务调度内核

分开：
- 两种任务的业务拥有者
- 创建和删除生命周期
- 列表查询范围
- 执行上下文获取方式
- 物理配置文件
- 旧接口兼容逻辑
```

数据关系最终定义为：

```text
SESSION_LOOP
    sessionId → loopTaskId[]

AUTOMATION
    automationId → dedicatedSessionId
```

所以用户提出的“任务数据上都要有关联的 sessionId”是正确方向，但需要补充一个重要约束：

> `sessionId` 是所有定时任务的执行上下文，不是所有定时任务的业务拥有者。

循环任务的业务拥有者仍然是 session；自动任务的业务拥有者是任务自身，session 只是它的专用执行上下文。

## 11.12 修订后的实施顺序

1. 抽取统一 `ScheduledTaskDTO` 和 `ScheduledTaskRun`；
2. 定义 `SESSION_LOOP` 与 `AUTOMATION` 类型契约；
3. 为现有 Loop 增加兼容适配器，不改变原有存储；
4. 新增 AutomationRepository，保存到 `automations/`；
5. 抽取公共调度内核和运行状态逻辑；
6. 为两种任务提供统一 `/web/tasks/*` 接口；
7. 保留 `/web/chat/loop/*`，内部转到统一 Service；
8. 新增“自动任务”前端菜单和任务页面；
9. 补充两种任务的类型化 API 测试、恢复测试和并发测试；
10. 后续再评估是否需要统一物理存储，不在第一版做迁移。

这一修订比“完全新做 AutomationScheduler + 完全新做 Automation API”更稳妥：既能复用现有循环任务能力，又不会让自动任务重新受 session 生命周期限制。

---

# 十二、实现审查结论（2026-10-01）

## 12.1 当前已经实现并验证

- 自动任务以工作区为范围保存，配置位于 `automations/`；
- 每个自动任务绑定独立的 `auto-*` session；
- 自动任务复用现有 `LoopScheduler`，没有复制第二套心跳执行器；
- 支持创建、编辑、启停、立即触发、删除和恢复；
- 已提供 `/web/tasks/*` 统一门面，并保留旧 `/web/chat/loop/*`、`/web/automation/*` 接口；
- 统一门面已使用 `type=AUTOMATION` 与默认的 `SESSION_LOOP` 分流；
- 自动任务执行事件已写入运行记录；
- 左侧“自动任务”入口和基础管理页面已完成；
- `mvn -pl soloncode-cli -am -DskipTests compile` 已通过。

## 12.2 与完整方案仍有差距

以下事项不能标记为“全部完成”：

1. `/web/tasks/*` 目前是 Controller 层分流，不是独立 `TaskService`/Adapter；功能可用，但后续应抽取服务层，避免 Controller 继续膨胀。
2. 循环任务仍没有统一的运行记录接口；`/web/tasks/runs` 当前只支持 `AUTOMATION`，这是兼容优先下的阶段性取舍。
3. 自动任务配置目前是 `automations/<id>.json` 与 `<id>.runs.ndjson`，不是方案早期示例中的 `automations/<id>/automation.json`。当前扁平文件布局可接受，但文档和实现必须保持一致。
4. 运行记录采用 append-only 事件方式：一次执行会产生 `RUNNING` 和终态两条记录，尚未抽象为一个稳定 `runId` 的完整生命周期记录。
5. 自动任务页面尚未提供“查看专用隐藏对话”和执行历史 UI，虽然后端已经提供 session 和 runs 查询接口。
6. 自动任务专用 session 当前主要依靠 `auto-` 前缀隐藏，尚未写入显式 `sessionType=AUTOMATION` 元数据。
7. 自动任务删除时会删除任务配置和运行记录，但不会清理或归档专用 session，需要补充生命周期策略。
8. 自动任务 API 的参数校验和错误码还不完全统一，尤其是 interval、cron、limit 的边界值。

## 12.3 审查结论

当前实现可以作为 MVP 使用，但不应宣称“完整方案全部完成”。合理的短期收敛顺序是：

1. 先补自动任务页面的执行记录和专用对话入口；
2. 补任务参数校验、删除幂等性和显式 session 元数据；
3. 将统一 Controller 分流抽为 `TaskService`；
4. 再决定是否把循环任务运行记录纳入统一接口；
5. 最后补完整的自动任务、统一门面、重启恢复和并发测试。

在这些事项完成前，不建议继续扩大功能范围，也不建议迁移现有循环任务物理存储。

---

# 十三、本轮优化记录（2026-10-01）

本轮按审查结论完成了收敛性优化：

- 运行记录继续采用 append-only，但读取时按 `runId` 聚合；一次执行在接口和页面中只呈现一条记录；
- `AutomationManager` 在任务开始与结束事件之间复用同一个 `runId`，终态完成后释放活动运行映射；
- 增加自动任务参数边界校验：prompt、name、interval、cron 长度、maxTokens 和 maxDurationMs；
- 自动任务页面增加“执行记录”和“查看对话”入口，使用已有消息接口读取专用 session；
- 保持循环任务原有存储、旧接口和调度恢复逻辑不变；
- 未引入第二套调度器，也未迁移现有循环任务物理存储。

## 本轮验证

```text
mvn -pl soloncode-cli -am -DskipTests compile
BUILD SUCCESS

mvn -pl soloncode-cli -Dtest=LoopSchedulerCronValidationTest,LoopExecutionResultTest,WebControllerSessionIdTest test
Tests run: 11, Failures: 0, Errors: 0
```

## 当前仍保留的边界

- `TaskService`/Adapter 尚未从 `WebController` 完全抽离；当前统一门面已经可用，但实现仍是 Controller 分流；
- 循环任务尚未补充统一运行历史；
- 全量测试仍需单独处理项目中既有的 `AgentSettingsControllerTest` 和 `WebEventMapperChannelReplyTest` 失败。

## 十四、本轮轻量生命周期优化（2026-10-01）

本轮只处理专用 session 生命周期，不扩大到调度器重构或物理存储迁移：

- `SessionMeta` 增加 `sessionType`、`ownerAutomationId`、`archived`；
- 创建自动任务时写入 `sessionType=AUTOMATION` 和所属任务 ID；
- 删除自动任务时停止调度、删除任务配置与运行记录，但专用 session 改为归档，保留历史上下文；
- 普通会话删除接口拒绝直接删除 `AUTOMATION` session，必须通过自动任务生命周期管理；
- 仍保留 `auto-` 前缀兼容逻辑，元数据作为新的可靠判断依据；
- 增加 `SessionMeta` 自动任务元数据往返测试。

这样既补齐了会话生命周期边界，又不引入独立归档目录、迁移脚本或复杂 Repository 抽象。
