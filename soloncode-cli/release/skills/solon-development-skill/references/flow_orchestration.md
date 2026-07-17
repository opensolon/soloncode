# Flow Orchestration — 流程编排

> 适用场景：计算编排、业务规则引擎、AI Agent 系统中的流程定义与执行。
>
> 目标版本：4.0.3。中断恢复 / Workflow / 拦截器 / 事件总线见 `flow_workflow.md`。

Dependency: `solon-flow`

## 1. YAML 定义

### 应用配置

```yaml
solon.flow:
  - "classpath:flow/*.yml"
```

### 流程配置（完整模式）

支持 `yml` 或 `json`，文件放于 `flow/`，例如 `flow/score.yml`：

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

- 无 `id` → 按序自动生成（`"n-1"`…）
- 无 `link` → 自动连后一节点
- 无 `type` → 缺省 `activity`
- 无 `start` → 首节点为开始；无 `end` 不影响执行

```yaml
id: "c1"
layout:
  - { task: 'System.out.println("hello world!");' }
```

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

```java
Graph graph = Graph.create("demo1", spec -> {
    spec.addStart("start").linkAdd("n1");
    spec.addActivity("n1").task((ctx, n) -> {
        ctx.put("validated", true);
    }).linkAdd("end");
    spec.addEnd("end");
});
engine.eval(graph, FlowContext.of());

// 复制并修改
Graph graphNew = Graph.copy(graph, spec -> {
    spec.removeNode("n3");
    spec.getNode("n2").linkRemove("n3").linkAdd("end");
});
engine.eval(graphNew);
```

## 4. 核心 API

### FlowEngine

| 方法 | 描述 |
|---|---|
| `newInstance()` / `newInstance(driver)` | 实例化引擎 |
| `load(graphUri)` / `load(graph)` | 加载图（URI 支持 `*` 批量） |
| `unload(graphId)` | 卸载图 |
| `getGraphs()` / `getGraph(id)` / `getGraphOrThrow(id)` | 查询图 |
| `eval(graphId\|graph [, steps] [, context] [, options])` | 执行图 |
| `addInterceptor(interceptor)` | 添加拦截器 |
| `register(name, driver)` / `register(driver)` | 注册驱动器 |

### FlowContext

| 方法 | 描述 |
|---|---|
| `of()` / `of(instanceId)` | 创建上下文 |
| `fromJson(json)` / `toJson()` | 快照恢复/序列化（v3.8.1+） |
| `vars()` / `put` / `getAs` / `getOrDefault` / `remove` / `containsKey` | 变量读写 |
| `interrupt()` | 中断当前分支 |
| `stop()` / `isStopped()` | 停止整条流 |
| `trace()` / `lastRecord()` / `lastNodeId()` | 执行跟踪 |
| `eventBus()` | 当前实例 DamiBus |

> 脚本中：`context` 为 FlowContext；vars 中变量可直接当脚本变量用。

### Graph / GraphSpec

| 方法 | 描述 |
|---|---|
| `Graph.create` / `copy` / `fromUri` / `fromText` | 创建/加载图 |
| `toYaml` / `toJson` / `toPlantuml` | 导出（PlantUML v3.9.5+） |
| `spec.addStart/End/Activity/Inclusive/Exclusive/Parallel/Loop` | 添加节点 |
| `spec.removeNode` / `getNode` | 修改节点 |

### FlowDriver

| 方法 | 描述 |
|---|---|
| `onNodeStart` / `onNodeEnd` | 节点生命周期 |
| `handleCondition` / `handleTask` / `postHandleTask` | 条件与任务 |

主要实现：`SimpleFlowDriver`。

## 5. 节点类型（NodeType）

| type | 描述 | 任务 | 条件 | 多线程 | 备注 |
|---|---|---|---|---|---|
| `start` | 开始 | / | / | / | 必须唯一 |
| `activity` | 活动（缺省） | 支持 | / | / | |
| `inclusive` | 包容网关 | 支持 | 支持 | / | 需成对 |
| `exclusive` | 排他网关 | 支持 | 支持 | / | |
| `parallel` | 并行网关 | 支持 | / | 支持 | 需成对 |
| `loop` | 循环网关 | 支持 | / | / | 需成对；meta: `$for`/`$in` |
| `end` | 结束 | / | / | / | |

### 网关示例

**exclusive（单选）**

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

**inclusive（多选）**

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

**parallel（全选）**

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

**loop**

```yaml
id: demo1
layout:
  - type: start
  - { type: loop, meta: { "$for": "id", "$in": "idList" } }
  - { type: activity, task: "@Job" }
  - type: loop
  - type: end
```

> `$for`：遍历变量名；`$in`：上下文中的集合变量名。

## 6. 配置属性

### Graph

| 属性 | 需求 | 描述 |
|---|---|---|
| `id` | 必填 | 图 ID（全局唯一） |
| `title` / `driver` / `meta` | | 标题 / 驱动器 / 元数据 |
| `layout` | | 节点编排 |

### Node

| 属性 | 描述 |
|---|---|
| `id` / `type` / `title` / `meta` | 标识与类型（type 缺省 activity） |
| `link` | `String` / `Link` / 数组；缺省连后一节点 |
| `task` / `when` | 任务描述 / 执行条件 |

### Link

| 属性 | 需求 | 描述 |
|---|---|---|
| `nextId` | 必填 | 目标节点 ID |
| `title` / `meta` / `when` | | 标题 / 元数据 / 分支条件 |

## 进阶

中断恢复、Workflow 审批、拦截器、流程内事件总线 → 见 `flow_workflow.md`。