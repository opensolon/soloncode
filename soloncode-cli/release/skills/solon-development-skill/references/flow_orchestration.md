# Flow Orchestration — 流程编排

> 适用场景：计算编排、业务规则引擎、可中断/可恢复流程（快照持久化）、AI Agent 系统。

Dependency: `solon-flow`

## 1. YAML 定义

### 应用配置

```yaml
solon.flow:
  - "classpath:flow/*.yml"
```

### 流程配置（完整模式）

支持 `yml` 或 `json` 格式，文件放置于 `flow/` 目录下，例如 `flow/score.yml`：

```yaml
id: "score_rule"
title: "积分规则引擎"
layout:
  - { id: "n1", type: "start", link: "n2" }
  - { id: "n2", type: "activity", link: "n3", task: "score = 100;", when: "amount > 100 && amount <= 500" }
  - { id: "n2b", type: "activity", link: "n3", task: "score = 500;", when: "amount > 500" }
  - { id: "n2c", type: "activity", link: "n3", task: "score = 0;", when: "amount <= 100" }
  - { id: "n3", type: "activity", task: 'context.put("score", score);', link: "n4" }
  - { id: "n4", type: "end" }
```

### 流程配置（简化模式）

简化规则：
- 无 `id` 时按顺序自动生成（格式 `"n-1"`）
- 无 `link` 时自动连接后一个节点
- 无 `type` 时缺省为 `activity`
- 无 `type=start` 节点时，第一个节点为开始节点
- 无 `type=end` 节点时，不影响执行

```yaml
id: "c1"
layout:
  - { task: 'System.out.println("hello world!");' }
```

上例等价于一个 start 节点连接一个 activity 节点，activity 执行后流程自然结束。

## 2. Java 执行

### 注解模式

```java
@Component
public class DemoCom implements LifecycleBean {
    @Inject
    private FlowEngine flowEngine;

    @Override
    public void start() throws Throwable {
        flowEngine.eval("c1");
    }
}
```

### 原生 Java 模式

```java
FlowEngine engine = FlowEngine.newInstance();
engine.load("classpath:flow/*.yml");

FlowContext ctx = FlowContext.of();
ctx.put("amount", 600);
engine.eval("score_rule", ctx);
System.out.println(ctx.get("score")); // 500
```

## 3. Java 硬编码构建 Graph

### 创建图

```java
Graph graph = Graph.create("demo1", spec -> {
    spec.addStart("start").linkAdd("n1");
    spec.addActivity("n1").task((ctx, n) -> {
        ctx.put("validated", true);
    }).linkAdd("end");
    spec.addEnd("end");
});

engine.eval(graph, FlowContext.of());
```

### 复制并修改图

```java
Graph graphNew = Graph.copy(graph, spec -> {
    spec.removeNode("n3");
    spec.getNode("n2").linkRemove("n3").linkAdd("end");
});

engine.eval(graphNew);
```

## 4. 核心 API 参考

### FlowEngine（流程引擎）

| 方法 | 返回类型 | 描述 |
|---|---|---|
| `newInstance()` | `FlowEngine` | 实例化引擎 |
| `newInstance(driver)` | `FlowEngine` | 实例化引擎（指定默认驱动器） |
| `load(graphUri)` | | 加载图（支持 `*` 号批量加载） |
| `load(graph)` | | 加载图 |
| `unload(graphId)` | | 卸载图 |
| `getGraphs()` | `Collection<Graph>` | 获取所有图 |
| `getGraph(graphId)` | `Graph` | 获取图 |
| `getGraphOrThrow(graphId)` | `Graph` | 获取图，没有则异常 |
| `eval(graphId)` | | 执行图 |
| `eval(graphId, context)` | | 执行图（带上下文） |
| `eval(graphId, steps, context)` | | 执行图（指定步数） |
| `eval(graphId, steps, context, options)` | | 执行图（指定步数和选项） |
| `eval(graph)` | | 执行图 |
| `eval(graph, context)` | | 执行图（带上下文） |
| `eval(graph, steps, context)` | | 执行图（指定步数） |
| `eval(graph, steps, context, options)` | | 执行图（指定步数和选项） |
| `addInterceptor(interceptor)` | | 添加拦截器 |
| `register(name, driver)` | | 注册驱动器 |
| `register(driver)` | | 注册默认驱动器 |

### FlowContext（流程上下文）

| 方法 | 返回类型 | 描述                    |
|---|---|-----------------------|
| `of()` | `FlowContext` | 获取上下文实例               |
| `of(instanceId)` | `FlowContext` | 获取上下文实例（指定实例ID）       |
| `fromJson(json)` | `FlowContext` | 从 JSON 加载上下文（v3.8.1+） |
| `toJson()` | `String` | 序列化为 JSON（v3.8.1+）    |
| `getInstanceId()` | `String` | 获取实例 ID               |
| `vars()` | `Map<String, Object>` | 上下文变量                 |
| `put(key, value)` | `FlowContext` | 推入参数                  |
| `getAs(key)` | `T` | 获取参数（泛型）              |
| `getOrDefault(key, def)` | `T` | 获取参数或默认               |
| `remove(key)` | `void` | 移除参数                  |
| `containsKey(key)` | `boolean` | 是否包含参数                |
| `interrupt()` | | 中断当前分支（其他分支仍执行）       |
| `stop()` | | 停止执行（整个流不再前进）         |
| `isStopped()` | `boolean` | 是否已停止                 |
| `trace()` | `FlowTrace` | 执行跟踪（v3.8.1+）         |
| `lastRecord()` | `NodeRecord` | 最后执行的节点记录             |
| `lastNodeId()` | `String` | 最后执行的节点 ID            |
| `eventBus()` | `DamiBus` | 当前实例事件总线              |

> **脚本中的变量规则**：FlowContext 实例在脚本里的变量名为 `context`；vars 中所有变量在脚本中可直接作为变量使用。

### Graph / GraphSpec

| 方法 | 描述 |
|---|---|
| `Graph.create(id, spec->{})` | 创建图 |
| `Graph.copy(graph, spec->{})` | 复制图并修改定义 |
| `Graph.fromUri(url)` | 通过配置文件加载图 |
| `Graph.fromText(text)` | 通过配置文本加载图 |
| `graph.toYaml()` | 转为 YAML |
| `graph.toJson()` | 转为 JSON |
| `graph.toPlantuml()` | 转为 PlantUML 状态图文本（v3.9.5+） |
| `spec.addStart(id)` | 添加开始节点 |
| `spec.addEnd(id)` | 添加结束节点 |
| `spec.addActivity(id)` | 添加活动节点 |
| `spec.addInclusive(id)` | 添加包容网关节点 |
| `spec.addExclusive(id)` | 添加排他网关节点 |
| `spec.addParallel(id)` | 添加并行网关节点 |
| `spec.addLoop(id)` | 添加循环网关节点 |
| `spec.removeNode(id)` | 移除节点 |
| `spec.getNode(id)` | 获取节点定义 |

### FlowDriver（流程驱动器）

| 方法 | 返回类型 | 描述 |
|---|---|---|
| `onNodeStart(exchanger, node)` | | 节点开始时回调 |
| `onNodeEnd(exchanger, node)` | | 节点结束时回调 |
| `handleCondition(exchanger, condition)` | `boolean` | 处理条件检测 |
| `handleTask(exchanger, task)` | | 处理执行任务 |
| `postHandleTask(exchanger, task)` | | 提交处理任务（二次控制） |

主要实现：`SimpleFlowDriver`

## 5. 节点类型（NodeType）

| type | 描述 | 执行任务 | 连接条件 | 多线程 | 可流入连接数 | 可流出连接数 | 备注 |
|---|---|---|---|---|---|---|---|
| `start` | 开始 | / | / | / | `0` | `1` | 必须有且只有一个 |
| `activity` | 活动节点（缺省类型） | 支持 | / | / | `1...n` | `1...n` | |
| `inclusive` | 包容网关（类似多选） | 支持 | 支持 | / | `1...n` | `1...n` | 需成对使用 |
| `exclusive` | 排他网关（类似单选） | 支持 | 支持 | / | `1...n` | `1...n` | |
| `parallel` | 并行网关（类似全选） | 支持 | / | 支持 | `1...n` | `1...n` | 需成对使用 |
| `loop` | 循环网关 | 支持 | / | / | `1` | `1` | 需成对使用 |
| `end` | 结束 | / | / | / | `1...n` | `0` | |

### 节点类型示例

**exclusive（排他网关，单选）**

```yaml
id: demo1
layout:
  - type: start
  - { type: exclusive, link: [n1, { nextId: n2, when: "a>1" }] }
  - { type: activity, task: "@Task1", id: n1, link: g_end }
  - { type: activity, task: "@Task2", id: n2, link: g_end }
  - { type: exclusive, id: g_end }
  - type: end
```

**inclusive（包容网关，多选）**

```yaml
id: demo1
layout:
  - type: start
  - { type: inclusive, link: [{ nextId: n1, when: "b>1" }, { nextId: n2, when: "a>1" }] }
  - { type: activity, task: "@Task1", id: n1, link: g_end }
  - { type: activity, task: "@Task2", id: n2, link: g_end }
  - { type: inclusive, id: g_end }
  - type: end
```

**parallel（并行网关，全选）**

```yaml
id: demo1
layout:
  - type: start
  - { type: parallel, link: [n1, n2] }
  - { type: activity, task: "@Task1", id: n1, link: g_end }
  - { type: activity, task: "@Task2", id: n2, link: g_end }
  - { type: parallel, id: g_end }
  - type: end
```

**loop（循环网关）**

```yaml
id: demo1
layout:
  - type: start
  - { type: loop, meta: { "$for": "id", "$in": "idList" } }
  - { type: activity, task: "@Job" }
  - type: loop
  - type: end
```

> loop 流出元数据：`$for` 为遍历变量名（后续节点可直接使用），`$in` 为集合变量名（从上下文中取值）。

## 6. 配置属性参考

### Graph 属性

| 属性 | 数据类型 | 需求 | 描述 |
|---|---|---|---|
| `id` | `String` | 必填 | 图 ID（全局唯一） |
| `title` | `String` | | 显示标题 |
| `driver` | `String` | | 驱动器（缺省为默认驱动器） |
| `meta` | `Map` | | 元数据（用于应用扩展） |
| `layout` | `Node[]` | | 编排（布局） |

### Node 属性

| 属性 | 数据类型 | 需求 | 描述 |
|---|---|---|---|
| `id` | `String` | | 节点 ID（图内唯一），不配置时自动生成 |
| `type` | `NodeType` | | 节点类型，缺省为 `activity` |
| `title` | `String` | | 显示标题 |
| `meta` | `Map` | | 元数据 |
| `link` | `String` / `Link` / `String[]` / `Link[]` | | 连接，不配置时自动连接后一个节点 |
| `task` | `String` | | 任务描述（触发驱动的 handleTask） |
| `when` | `String` | | 任务执行条件（触发驱动的 handleCondition） |

### Link 属性

| 属性 | 数据类型 | 需求 | 描述 |
|---|---|---|---|
| `nextId` | `String` | 必填 | 后面的节点 ID |
| `title` | `String` | | 显示标题 |
| `meta` | `Map` | | 元数据 |
| `when` | `String` | | 分支流出条件（触发驱动的 handleCondition） |

## 7. 中断、持久化与恢复

solon-flow 通过 FlowContext 的执行跟踪和快照序列化能力，实现流程的"原地休眠"与"唤醒执行"。

### 核心机制

- **中断控制**：在任务中调用 `context.stop()`，引擎停止向下流转
- **状态持久化**：使用 `context.toJson()` 序列化当前执行进度和变量数据
- **状态恢复**：通过 `FlowContext.fromJson(json)` 重建上下文，调用 `flowEngine.eval()` 引擎自动从中断节点继续执行

### 示例代码

```java
// --- 第一阶段：执行并因条件不满足而停止 ---
Graph graph = Graph.create("g1", spec -> {
    spec.addStart("n1").linkAdd("n2");
    spec.addActivity("n2").task((ctx, n) -> {
        System.out.println(n.getId());
    }).linkAdd("n3");
    spec.addActivity("n3").task((ctx, n) -> {
        if (ctx.getOrDefault("ready", false) == false) {
            ctx.stop();
        }
    }).linkAdd("n4");
    spec.addEnd("n4");
});

FlowEngine engine = FlowEngine.newInstance();
FlowContext context = FlowContext.of("inst-1");
engine.eval(graph, context);

if (context.isStopped()) {
    String snapshot = context.toJson(); // 序列化并存入数据库
}

// --- 第二阶段：从快照恢复并继续执行 ---
context = FlowContext.fromJson(snapshot);
context.put("ready", true);
engine.eval(graph, context); // 自动从上次停止的节点继续流转
```

## 8. Workflow 扩展（solon-flow-workflow）

Workflow 是基于 solon-flow 的上层封装，提供工作流审批、任务认领等业务场景支持。

Dependency: `solon-flow-workflow`

### 核心接口

| 接口/类 | 描述 |
|---|---|
| `WorkflowExecutor` | 工作流执行器 |
| `StateController` | 状态控制器（`NotBlockStateController`、`BlockStateController`、`ActorStateController`） |
| `StateRepository` | 状态持久化（`InMemoryStateRepository`、`RedisStateRepository`） |
| `Task` | 任务实体 |

### 使用示例

```java
// 构建工作流执行器
WorkflowExecutor workflow = WorkflowExecutor.of(engine,
    new NotBlockStateController(),
    new InMemoryStateRepository());

// 查询任务
Task task = workflow.findTask("c1", FlowContext.of("inst-1"));

// 认领任务（权限匹配 + 状态激活）
Task task = workflow.claimTask("c1", FlowContext.of("inst-1"));
```

### 注解模式

```java
@Configuration
public class WorkflowConfig {
    @Bean
    public WorkflowExecutor workflowOf(FlowEngine engine) {
        return WorkflowExecutor.of(engine,
            new NotBlockStateController(),
            new InMemoryStateRepository());
    }
}
```

## 9. 拦截器（FlowInterceptor）

可通过拦截器对节点执行进行前置/后置处理：

```java
engine.addInterceptor(new FlowInterceptor() {
    @Override
    public void onNodeStart(FlowContext context, Node node) {
        System.out.println("开始执行: " + node.getId());
    }

    @Override
    public void onNodeEnd(FlowContext context, Node node) {
        System.out.println("执行完成: " + node.getId());
    }
});
```

## 10. 事件总线

基于 DamiBus 实现，支持流程执行中的异步广播或同步调用：

```java
FlowContext context = FlowContext.of();
context.eventBus().<String, String>listen("demo.topic", event -> {
    System.out.println(event.getPayload());
});

engine.eval("c1", context);
```

YAML 中使用：

```yaml
id: event1
layout:
  - task: "@DemoCom"
  - task: 'context.eventBus().send("demo.topic", "hello");'
```
