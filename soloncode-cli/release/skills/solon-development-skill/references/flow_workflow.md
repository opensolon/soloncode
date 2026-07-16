# Flow Workflow — 中断恢复 / Workflow / 拦截器

> 适用场景：可中断可恢复流程、工作流审批、拦截器与流程内事件总线。
>
> 目标版本：4.0.3。基础 YAML/Graph/节点类型见 `flow_orchestration.md`。

## 1. 中断、持久化与恢复

solon-flow 通过 FlowContext 的执行跟踪和快照序列化，实现流程的「原地休眠」与「唤醒执行」。

### 核心机制

- **中断控制**：任务中调用 `context.stop()`，引擎停止向下流转
- **状态持久化**：`context.toJson()` 序列化当前进度与变量
- **状态恢复**：`FlowContext.fromJson(json)` 重建上下文后 `flowEngine.eval()`，自动从中断节点继续

### 示例

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
engine.eval(graph, context); // 自动从上次停止的节点继续
```

相关 API：`context.interrupt()`（仅中断当前分支）、`context.stop()` / `isStopped()`、`toJson()` / `fromJson()`、`trace()` / `lastNodeId()`。

## 2. Workflow 扩展（solon-flow-workflow）

基于 solon-flow 的上层封装，面向审批、任务认领等业务场景。

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

## 3. 拦截器（FlowInterceptor）

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

## 4. 流程内事件总线

基于 DamiBus，支持流程执行中的异步广播或同步调用：

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