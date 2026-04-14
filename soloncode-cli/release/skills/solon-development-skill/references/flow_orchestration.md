# Flow Orchestration — 流程编排

> 适用场景：规则引擎、工作流、计算编排、可中断/可恢复流程。

Dependency: `solon-flow`

## YAML 定义 (`flow/score.yml`)

```yaml
id: "score_rule"
title: "积分规则引擎"
layout:
  - { type: "start" }
  - { when: "amount > 100 && amount <= 500", task: "score = 100;" }
  - { when: "amount > 500", task: "score = 500;" }
  - { when: "amount <= 100", task: "score = 0;" }
  - { task: 'context.put("score", score);' }
  - { type: "end" }
```

## Java 执行

```java
FlowEngine engine = FlowEngine.newInstance();
engine.load("classpath:flow/*.yml");

FlowContext ctx = FlowContext.of();
ctx.put("amount", 600);
engine.eval("score_rule", ctx);
System.out.println(ctx.get("score")); // 500
```

## Java 硬编码构建 Graph

```java
Graph graph = Graph.create("myFlow", spec -> {
    spec.addStart("start").linkAdd("validate");
    spec.addActivity("validate").task((ctx, node) -> {
        ctx.put("validated", true);
    }).linkAdd("end");
    spec.addEnd("end");
});
engine.eval(graph, FlowContext.of());
```

Flow supports: computation orchestration, business rule engines, interruptible/resumable workflows (snapshot persistence), and AI agent systems.

## 核心 API 参考

| Interface/Class | Description |
|---|---|
| `FlowEngine` | 流程引擎，加载/执行流程图 |
| `FlowContext` | 流程上下文，传递数据/控制中断 |
| `Graph` / `GraphSpec` | 流程图定义 |
| `Node` / `NodeSpec` | 流程节点 |
| `Link` / `LinkSpec` | 流程连接 |
| `FlowDriver` | 驱动器接口 |
| `SimpleFlowDriver` | 简单驱动器实现 |
| `MapContainer` | Map 组件容器 |
| `TaskComponent` | 任务组件接口 |
| `Evaluation` | 脚本执行器接口 |

## Flow YAML 节点类型

| type | Description |
|---|---|
| `start` | 开始节点 |
| `end` | 结束节点 |
| `activity` | 活动节点（执行任务） |
| `exclusive` | 排他网关（条件分支） |
| `inclusive` | 包容网关 |

## Flow YAML 属性

| Property | Description |
|---|---|
| `id` | 节点 ID |
| `type` | 节点类型 |
| `link` | 连接到下一节点 ID |
| `when` | 连接条件表达式 |
| `task` | 节点任务（脚本或 @组件名） |
| `meta` | 节点元数据 |

## Flow YAML Format (完整示例)

```yaml
id: "flow1"
layout:
  - { id: "n1", type: "start", link: "n2" }
  - { id: "n2", type: "activity", link: "n3", task: "handler.process()" }
  - { id: "n3", type: "exclusive", link: "n4" }
  - { id: "n4", type: "activity", link: "n5", task: "@step2", when: "ctx.data.status == 'ok'" }
  - { id: "n5", type: "end" }
```
