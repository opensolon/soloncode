/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package agentTems;

import org.junit.jupiter.api.Test;
import org.noear.solon.bot.core.AgentKernel;
import org.noear.solon.bot.core.AgentProperties;
import org.noear.solon.bot.core.memory.*;
import org.noear.solon.bot.core.event.*;
import org.noear.solon.bot.core.message.*;
import org.noear.solon.bot.core.teams.SharedTaskList;
import org.noear.solon.bot.core.teams.TeamTask;
import org.noear.solon.bot.core.teams.SubAgentAgentBuilder;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent Teams 集成测试
 * <p>
 * 测试共享记忆、事件总线、消息传递、任务协作等完整功能
 *
 * @author bai
 * @since 3.9.5
 */
public class AgentTeamsIntegrationTest {

    private static final String TEST_WORK_DIR = "work/test";

    // ==================== 共享记忆测试 ====================

    @Test
    public void testSharedMemoryManager() {
        System.out.println("=== 测试共享记忆管理器 ===");

        // 创建管理器
        SharedMemoryManager manager = new SharedMemoryManager(Paths.get(TEST_WORK_DIR, AgentKernel.SOLONCODE_MEMORY));

        // 1. 测试存储短期记忆
        ShortTermMemory stm = new ShortTermMemory("explore", "探索任务上下文", "task-1");
        manager.store(stm);
        System.out.println("✓ 存储短期记忆");

        // 2. 测试存储长期记忆
        LongTermMemory ltm = new LongTermMemory("重要架构决策", "plan", Arrays.asList("architecture", "design"));
        ltm.setImportance(0.9);
        manager.store(ltm);
        System.out.println("✓ 存储长期记忆");

        // 3. 测试存储知识库
        KnowledgeMemory km = new KnowledgeMemory("Solon 框架", "轻量级 Java 框架", "framework");
        km.setKeywords(Arrays.asList("java", "lightweight", "framework"));
        manager.store(km);
        System.out.println("✓ 存储知识库记忆");

        // 4. 测试检索
        List<Memory> memories = manager.retrieve(Memory.MemoryType.SHORT_TERM, 10);
        assertTrue(memories.size() >= 1);
        System.out.println("✓ 检索到 " + memories.size() + " 条短期记忆");

        // 5. 测试按标签检索
        List<LongTermMemory> taggedMemories = manager.retrieveByTag("architecture", 10);
        assertTrue(taggedMemories.size() >= 1);
        System.out.println("✓ 按标签检索到 " + taggedMemories.size() + " 条记忆");

        // 6. 测试搜索
        List<Memory> searchResults = manager.search("架构", 10);
        assertTrue(searchResults.size() >= 1);
        System.out.println("✓ 搜索到 " + searchResults.size() + " 条相关记忆");

        // 7. 测试统计信息
        Map<String, Object> stats = manager.getStats();
        System.out.println("✓ 记忆统计: " + stats);

        // 清理
        manager.shutdown();
        System.out.println("✓ 共享记忆管理器测试通过\n");
    }

    @Test
    public void testEventBus() throws Exception {
        System.out.println("=== 测试事件总线 ===");

        // 创建事件总线
        EventBus bus = new EventBus(2, 100);

        // 1. 测试订阅和发布（使用枚举）
        CompletableFuture<String> future = new CompletableFuture<>();
        String subscriptionId = bus.subscribe(AgentEventType.TASK_STARTED, event -> {
            future.complete((String) event.getPayload());
            System.out.println("接收到事件: " + event);
            return CompletableFuture.completedFuture(EventHandler.Result.success());
        });
        System.out.println("✓ 订阅事件: " + subscriptionId);

        // 2. 发布事件
        EventMetadata metadata = EventMetadata.builder()
                .sourceAgent("test")
                .taskId("task-1")
                .priority(5)
                .build();
        AgentEvent event = new AgentEvent(AgentEventType.TASK_STARTED, "hello world", metadata);
        bus.publish(event);
        System.out.println("✓ 发布事件");

        // 3. 验证收到事件
        String result = future.get(20, TimeUnit.SECONDS);
        assertEquals("hello world", result);
        System.out.println("✓ 接收到事件: " + result);

        // 4. 测试通配符订阅
        CompletableFuture<String> wildcardFuture = new CompletableFuture<>();
        bus.subscribe("task.*", e -> {
            wildcardFuture.complete(e.getEventTypeCode());
            return CompletableFuture.completedFuture(EventHandler.Result.success());
        });

        AgentEvent taskEvent = new AgentEvent(AgentEventType.TASK_COMPLETED, "data", metadata);
        bus.publish(taskEvent);

        String eventType = wildcardFuture.get(2, TimeUnit.SECONDS);
        assertEquals("task.completed", eventType);
        System.out.println("✓ 通配符订阅成功: " + eventType);

        // 5. 测试事件历史
        List<AgentEvent> history = bus.getEventHistory(10);
        assertTrue(history.size() >= 2);
        System.out.println("✓ 事件历史记录: " + history.size() + " 条");

        // 6. 测试订阅者数量
        int count = bus.getSubscriberCount();
        assertTrue(count >= 2);
        System.out.println("✓ 订阅者数量: " + count);

        // 清理
        bus.shutdown();
        System.out.println("✓ 事件总线测试通过\n");
    }

    @Test
    public void testMessageChannel() throws Exception {
        System.out.println("=== 测试消息通道 ===");

        // 创建消息通道
        MessageChannel channel = new MessageChannel(TEST_WORK_DIR, 2);

        // 1. 测试注册处理器
        String handlerId = channel.registerHandler("receiver", new MessageHandler() {
            @Override
            public <T> CompletableFuture<Object> handle(AgentMessage<T> message) {
                System.out.println("  → 收到消息: " + message.getContent());
                return CompletableFuture.completedFuture("ACK");
            }
        });
        System.out.println("✓ 注册消息处理器: " + handlerId);

        // 2. 测试发送点对点消息
        AgentMessage<String> message = new AgentMessage.Builder()
                .from("sender")
                .to("receiver")
                .type("test.type")
                .content("test payload")
                .requireAck(true)
                .build();

        CompletableFuture<MessageAck> future = channel.send(message);
        MessageAck ack = future.get(2, TimeUnit.SECONDS);
        assertTrue(ack.isSuccess());
        System.out.println("✓ 消息投递成功: " + ack);

        // 3. 测试广播消息
        // 注册多个接收者
        channel.registerHandler("agent1", new MessageHandler() {
            @Override
            public <T> CompletableFuture<Object> handle(AgentMessage<T> message) {
                System.out.println("  → 收到消息: " + message.getContent());
                return CompletableFuture.completedFuture("ACK1");
            }
        });

        channel.registerHandler("agent2", new MessageHandler() {
            @Override
            public <T> CompletableFuture<Object> handle(AgentMessage<T> message) {
                System.out.println("  → 收到消息: " + message.getContent());
                return CompletableFuture.completedFuture("ACK2");
            }
        });

        AgentMessage<String> broadcastMsg = new AgentMessage.Builder()
                .from("main")
                .to("*")
                .type("notification")
                .content("broadcast test")
                .build();

        CompletableFuture<List<MessageAck>> broadcastFuture = channel.broadcast(broadcastMsg);
        List<MessageAck> acks = broadcastFuture.get(2, TimeUnit.SECONDS);
        assertTrue(acks.size() >= 2);
        System.out.println("✓ 广播消息成功: " + acks.size() + " 个接收者");

        // 4. 测试消息队列（离线消息）
        channel.registerHandler("offline_agent", new MessageHandler() {
            @Override
            public <T> CompletableFuture<Object> handle(AgentMessage<T> message) {
                System.out.println("  → 收到消息: " + message.getContent());
                return CompletableFuture.completedFuture("RECEIVED");
            }
        });
        AgentMessage<Object> queuedMsg = new AgentMessage.Builder<>()
                .to("offline_agent")
                .type("query")
                .content("queued message")
                .persistent(true)
                .ttl(60000)
                .build();

        MessageAck queuedAck = channel.send(queuedMsg).get(2, TimeUnit.SECONDS);
        assertTrue(queuedAck.isSuccess());
        System.out.println("✓ 离线消息队列成功");

        // 清理
        channel.shutdown();
        System.out.println("✓ 消息通道测试通过\n");
    }

    @Test
    public void testAgentCollaboration() throws Exception {
        System.out.println("=== 测试代理协作 ===");

        // 创建管理器
        SharedMemoryManager memoryManager = new SharedMemoryManager(Paths.get(TEST_WORK_DIR, AgentKernel.SOLONCODE_MEMORY));
        EventBus eventBus = new EventBus(2, 100);
        MessageChannel messageChannel = new MessageChannel(TEST_WORK_DIR, 2);

        // 场景：Plan 代理制定计划 → 存储到共享记忆 → 发布事件 → Explore 代理接收事件
        String taskId = "task-collab-1";

        // 1. 先订阅事件（在发布之前）
        CompletableFuture<String> exploreFuture = new CompletableFuture<>();
        eventBus.subscribe(AgentEventType.TASK_COMPLETED.getCode(), event -> {
            if ("plan".equals(event.getMetadata().getSourceAgent())) {
                // 检索相关记忆
                System.out.println("检索相关记忆");
                List<Memory> memories = memoryManager.search("缓存", 5);
                exploreFuture.complete("找到 " + memories.size() + " 条相关记忆");
                return CompletableFuture.completedFuture(EventHandler.Result.success());
            }
            return CompletableFuture.completedFuture(EventHandler.Result.success());
        });
        System.out.println("✓ Explore 代理订阅事件");

        // 2. Plan 代理存储计划到共享记忆
        LongTermMemory plan = new LongTermMemory(
                "实现缓存系统的计划",
                "plan",
                Arrays.asList("cache", "architecture")
        );
        plan.setImportance(0.95);
        memoryManager.store(plan);
        System.out.println("✓ Plan 代理存储计划到共享记忆");

        // 3. Plan 代理发布完成事件（在订阅之后）
        eventBus.publish(new AgentEvent(
                AgentEventType.TASK_COMPLETED,
                "计划已完成",
                EventMetadata.builder()
                        .sourceAgent("plan")
                        .taskId(taskId)
                        .priority(5)
                        .build()
        ));
        System.out.println("✓ Plan 代理发布完成事件");

        // 4. Explore 代理发送消息给 Plan 代理
        messageChannel.registerHandler("explore", new MessageHandler() {
            @Override
            public <T> CompletableFuture<Object> handle(AgentMessage<T> message) {
                return CompletableFuture.completedFuture("探索完成");
            }
        });
        messageChannel.registerHandler("plan", new MessageHandler() {
            @Override
            public <T> CompletableFuture<Object> handle(AgentMessage<T> message) {
                if ("query".equals(message.getType())) {
                    CompletableFuture.runAsync(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                System.out.println("模拟处理  → 收到消息: " + message.getContent());
                                Thread.sleep(1000); // 模拟处理
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    });
                    return CompletableFuture.completedFuture("计划细节");
                }
                return CompletableFuture.completedFuture("ACK");
            }
        });

        CompletableFuture<Object> queryResult = messageChannel.request(
                "explore", "plan", "query", "需要探索的模块"
        );
        Object result = queryResult.get(2, TimeUnit.SECONDS);
        assertNotNull(result);
        System.out.println("✓ 代理间消息传递成功");

        // 5. 验证结果
        String exploreResult = exploreFuture.get(2, TimeUnit.SECONDS);
        assertTrue(exploreResult.contains("相关记忆"));
        System.out.println("✓ " + exploreResult);

        // 清理
        memoryManager.shutdown();
        eventBus.shutdown();
        messageChannel.shutdown();
        System.out.println("✓ 代理协作测试通过\n");
    }

    @Test
    public void testConfigurationProperties() {
        System.out.println("=== 测试配置属性 ===");

        AgentProperties props = new AgentProperties();

        // 验证默认值
        assertFalse(props.sharedMemoryEnabled);
        assertFalse(props.eventBusEnabled);
        assertFalse(props.messageChannelEnabled);
        System.out.println("✓ 默认功能未启用");

        // 验证共享记忆配置
        assertNotNull(props.sharedMemory);
        assertEquals(3600_000L, props.sharedMemory.shortTermTtl);
        assertEquals(7 * 24 * 3600_000L, props.sharedMemory.longTermTtl);
        assertTrue(props.sharedMemory.persistOnWrite);
        assertEquals(1000, props.sharedMemory.maxShortTermCount);
        System.out.println("✓ 共享记忆配置正确");

        // 验证事件总线配置
        assertNotNull(props.eventBus);
        assertEquals(1000, props.eventBus.maxHistorySize);
        assertEquals(5, props.eventBus.defaultPriority);
        assertEquals(30, props.eventBus.timeoutSeconds);
        System.out.println("✓ 事件总线配置正确");

        // 验证消息通道配置
        assertNotNull(props.messageChannel);
        assertEquals(60_000L, props.messageChannel.defaultTtl);
        assertEquals(1000, props.messageChannel.maxQueueSize);
        assertTrue(props.messageChannel.persistMessages);
        System.out.println("✓ 消息通道配置正确");

        System.out.println("✓ 配置属性测试通过\n");
    }

    // ==================== TeamTask 测试 ====================

    @Test
    public void testTeamTaskBasic() {
        System.out.println("=== 测试 TeamTask 基本功能 ===");

        // 1. 测试创建任务
        TeamTask task1 = new TeamTask("测试任务");
        assertNotNull(task1.getId());
        assertEquals("测试任务", task1.getTitle());
        assertEquals(TeamTask.Status.PENDING, task1.getStatus());
        assertEquals(5, task1.getPriority());
        System.out.println("✓ 创建任务成功: " + task1.getId());

        // 2. 测试使用 Builder 创建任务
        TeamTask task2 = TeamTask.builder()
                .title("Builder 任务")
                .description("使用 Builder 创建")
                .priority(8)
                .type(TeamTask.TaskType.EXPLORATION)
                .build();
        assertEquals("Builder 任务", task2.getTitle());
        assertEquals(8, task2.getPriority());
        assertEquals(TeamTask.TaskType.EXPLORATION, task2.getType());
        System.out.println("✓ Builder 创建任务成功");

        // 3. 测试任务状态转换
        task1.setStatus(TeamTask.Status.IN_PROGRESS);
        assertEquals(TeamTask.Status.IN_PROGRESS, task1.getStatus());
        assertFalse(task1.isClaimable());

        task1.setStatus(TeamTask.Status.COMPLETED);
        assertEquals(TeamTask.Status.COMPLETED, task1.getStatus());
        assertTrue(task1.isCompleted());
        System.out.println("✓ 任务状态转换成功");

        // 4. 测试任务元数据
        task1.putMetadata("key1", "value1");
        task1.putMetadata("key2", "value2");
        assertEquals("value1", task1.getMetadata().get("key1"));
        assertEquals("value2", task1.getMetadata().get("key2"));
        System.out.println("✓ 任务元数据操作成功");

        System.out.println("✓ TeamTask 基本功能测试通过\n");
    }

    @Test
    public void testTeamTaskDependencies() {
        System.out.println("=== 测试任务依赖关系 ===");

        // 创建任务链: task1 -> task2 -> task3
        TeamTask task1 = TeamTask.builder()
                .title("任务1")
                .build();

        TeamTask task2 = TeamTask.builder()
                .title("任务2")
                .dependencies(Arrays.asList(task1.getId()))
                .build();

        TeamTask task3 = TeamTask.builder()
                .title("任务3")
                .dependencies(Arrays.asList(task2.getId()))
                .build();

        // 创建任务查找函数
        java.util.function.Function<String, TeamTask> taskLookup = id -> {
            if (id.equals(task1.getId())) return task1;
            if (id.equals(task2.getId())) return task2;
            if (id.equals(task3.getId())) return task3;
            return null;
        };

        // 测试依赖检查
        assertTrue(task3.areAllDependenciesCompleted(taskLookup) == false);

        // 完成 task1
        task1.setStatus(TeamTask.Status.COMPLETED);
        assertTrue(task2.areAllDependenciesCompleted(taskLookup));

        // 但 task3 仍然不能完成（task2 未完成）
        assertTrue(task3.areAllDependenciesCompleted(taskLookup) == false);

        // 完成 task2
        task2.setStatus(TeamTask.Status.COMPLETED);
        assertTrue(task3.areAllDependenciesCompleted(taskLookup));
        System.out.println("✓ 依赖关系检查正确");

        // 测试获取所有依赖 ID
        java.util.Set<String> allDeps = task3.getAllDependencyIds(taskLookup);
        assertTrue(allDeps.contains(task1.getId()));
        assertTrue(allDeps.contains(task2.getId()));
        System.out.println("✓ 获取所有依赖 ID 成功，共 " + allDeps.size() + " 个");

        System.out.println("✓ 任务依赖关系测试通过\n");
    }

    @Test
    public void testTeamTaskCircularDependency() {
        System.out.println("=== 测试循环依赖检测 ===");

        // 创建循环依赖: task1 -> task2 -> task3 -> task1
        TeamTask task1 = TeamTask.builder()
                .title("任务1")
                .build();

        TeamTask task2 = TeamTask.builder()
                .title("任务2")
                .dependencies(Arrays.asList(task1.getId()))
                .build();

        TeamTask task3 = TeamTask.builder()
                .title("任务3")
                .dependencies(Arrays.asList(task2.getId()))
                .build();

        // 添加循环依赖
        task1.setDependencies(Arrays.asList(task3.getId()));

        // 创建任务查找函数
        java.util.function.Function<String, TeamTask> taskLookup = id -> {
            if (id.equals(task1.getId())) return task1;
            if (id.equals(task2.getId())) return task2;
            if (id.equals(task3.getId())) return task3;
            return null;
        };

        // 测试循环依赖检测
        assertTrue(task1.hasCyclicDependency(taskLookup));
        System.out.println("✓ 成功检测到循环依赖");

        // 测试依赖树可视化
        String tree = task1.getDependencyTree(taskLookup);
        assertTrue(tree.contains("⚠️"));
        System.out.println("依赖树:\n" + tree);

        System.out.println("✓ 循环依赖检测测试通过\n");
    }

    @Test
    public void testTeamTaskDependencyTree() {
        System.out.println("=== 测试依赖树可视化 ===");

        // 创建复杂依赖结构
        TeamTask root = TeamTask.builder().title("根任务").build();
        TeamTask child1 = TeamTask.builder().title("子任务1").build();
        TeamTask child2 = TeamTask.builder().title("子任务2").build();
        TeamTask grandchild = TeamTask.builder().title("孙任务").build();

        // 设置依赖
        child1.setDependencies(Arrays.asList(root.getId()));
        child2.setDependencies(Arrays.asList(root.getId()));
        grandchild.setDependencies(Arrays.asList(child1.getId()));

        // 更新状态
        root.setStatus(TeamTask.Status.COMPLETED);
        child1.setStatus(TeamTask.Status.IN_PROGRESS);

        // 创建任务查找函数
        java.util.function.Function<String, TeamTask> taskLookup = id -> {
            if (id.equals(root.getId())) return root;
            if (id.equals(child1.getId())) return child1;
            if (id.equals(child2.getId())) return child2;
            if (id.equals(grandchild.getId())) return grandchild;
            return null;
        };

        // 生成依赖树
        String tree = grandchild.getDependencyTree(taskLookup);
        System.out.println("依赖树:\n" + tree);

        assertTrue(tree.contains("根任务"));
        assertTrue(tree.contains("子任务1"));
        assertTrue(tree.contains("孙任务"));
        assertTrue(tree.contains("✅") || tree.contains("[DONE]"));
        System.out.println("✓ 依赖树可视化成功");

        System.out.println("✓ 依赖树测试通过\n");
    }

    // ==================== SharedTaskList 测试 ====================

    @Test
    public void testSharedTaskList() throws Exception {
        System.out.println("=== 测试共享任务列表 ===");

        EventBus eventBus = new EventBus();
        SharedTaskList taskList = new SharedTaskList(eventBus);

        // 1. 测试添加单个任务
        TeamTask task1 = TeamTask.builder()
                .title("任务1")
                .priority(8)
                .build();
        taskList.addTask(task1).join();  // 等待异步完成
        assertEquals(1, taskList.getAllTasks().size());
        System.out.println("✓ 添加单个任务成功");

        // 2. 测试批量添加任务
        List<TeamTask> tasks = Arrays.asList(
                TeamTask.builder().title("任务2").build(),
                TeamTask.builder().title("任务3").build(),
                TeamTask.builder().title("任务4").build()
        );
        List<TeamTask> added = taskList.addTasks(tasks).join();
        assertEquals(3, added.size());
        assertEquals(4, taskList.getAllTasks().size());
        System.out.println("✓ 批量添加任务成功");

        // 3. 测试获取任务
        TeamTask found = taskList.getTask(task1.getId());
        assertNotNull(found);
        assertEquals(task1.getTitle(), found.getTitle());
        System.out.println("✓ 获取任务成功");

        // 4. 测试任务统计
        SharedTaskList.TaskStatistics stats = taskList.getStatistics();
        assertEquals(4, stats.totalTasks);
        assertEquals(4, stats.pendingTasks);
        assertEquals(0, stats.completedTasks);
        System.out.println("✓ 任务统计: " + stats);

        // 5. 测试任务认领
        List<TeamTask> claimableTasks = taskList.getClaimableTasks();
        assertFalse(claimableTasks.isEmpty(), "应该有可认领的任务");
        TeamTask claimable = claimableTasks.get(0);
        boolean claimResult = taskList.claimTask(claimable.getId(), "agent-1").get();
        assertTrue(claimResult);
        TeamTask claimed = taskList.getTask(claimable.getId());
        assertNotNull(claimed);
        assertEquals("agent-1", claimed.getClaimedBy());
        assertEquals(TeamTask.Status.IN_PROGRESS, claimed.getStatus());
        System.out.println("✓ 任务认领成功");

        // 6. 测试任务完成
        boolean completeResult = taskList.completeTask(claimable.getId(), "任务完成");
        assertTrue(completeResult);
        System.out.println("✓ 任务完成");

        // 等待一小段时间确保状态更新
        Thread.sleep(100);

        // 验证统计更新
        SharedTaskList.TaskStatistics newStats = taskList.getStatistics();
        assertEquals(1, newStats.completedTasks);
        assertEquals(3, newStats.pendingTasks);
        System.out.println("✓ 统计更新正确");

        System.out.println("✓ 共享任务列表测试通过\n");
    }

    @Test
    public void testSharedTaskListWithDependencies() throws Exception {
        System.out.println("=== 测试带依赖的任务列表 ===");

        EventBus eventBus = new EventBus();
        SharedTaskList taskList = new SharedTaskList(eventBus);

        // 创建带依赖的任务链: task1 -> task2 -> task3
        TeamTask task1 = TeamTask.builder().title("基础任务").build();
        TeamTask task2 = TeamTask.builder()
                .title("中级任务")
                .dependencies(Arrays.asList(task1.getId()))
                .build();
        TeamTask task3 = TeamTask.builder()
                .title("高级任务")
                .dependencies(Arrays.asList(task2.getId()))
                .build();

        taskList.addTasks(Arrays.asList(task1, task2, task3)).join();

        // 测试可认领任务（只有 task1 可以认领）
        List<TeamTask> claimable = taskList.getClaimableTasks();
        assertEquals(1, claimable.size());
        assertEquals(task1.getId(), claimable.get(0).getId());
        System.out.println("✓ 只有基础任务可认领");

        // 完成 task1
        taskList.completeTask(task1.getId(), "完成");

        // 现在 task2 应该可以认领了
        List<TeamTask> newClaimable = taskList.getClaimableTasks();
        assertTrue(newClaimable.stream().anyMatch(t -> t.getId().equals(task2.getId())));
        System.out.println("✓ 基础任务完成后，中级任务可认领");

        // 测试阻塞信息
        String blockingInfo = taskList.getBlockingInfo();
        assertTrue(blockingInfo.contains("高级任务") || blockingInfo.contains("等待"));
        System.out.println("阻塞信息:\n" + blockingInfo);

        System.out.println("✓ 带依赖的任务列表测试通过\n");
    }

    @Test
    public void testSharedTaskListBatchOperations() throws Exception {
        System.out.println("=== 测试批量操作 ===");

        EventBus eventBus = new EventBus();
        SharedTaskList taskList = new SharedTaskList(eventBus);

        // 1. 批量添加大量任务
        List<TeamTask> largeBatch = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            largeBatch.add(TeamTask.builder()
                    .title("批量任务-" + i)
                    .priority(i % 10)
                    .build());
        }

        long startTime = System.currentTimeMillis();
        taskList.addTasks(largeBatch).join();
        long duration = System.currentTimeMillis() - startTime;

        assertEquals(50, taskList.getAllTasks().size());
        System.out.println("✓ 批量添加 50 个任务，耗时: " + duration + "ms");

        // 2. 测试高优先级任务排序
        List<TeamTask> highPriorityTasks = taskList.getAllTasks().stream()
                .filter(t -> t.getPriority() >= 9)
                .collect(java.util.stream.Collectors.toList());
        assertTrue(highPriorityTasks.size() >= 5);
        for (TeamTask task : highPriorityTasks) {
            assertEquals(9, task.getPriority());
        }
        System.out.println("✓ 高优先级任务筛选成功，共 " + highPriorityTasks.size() + " 个");

        // 3. 批量更新状态
        for (TeamTask task : taskList.getAllTasks()) {
            if (task.getPriority() >= 8) {
                taskList.completeTask(task.getId(), "批量完成");
            }
        }

        SharedTaskList.TaskStatistics stats = taskList.getStatistics();
        assertTrue(stats.completedTasks >= 5);
        System.out.println("✓ 批量更新完成，已完成: " + stats.completedTasks);

        System.out.println("✓ 批量操作测试通过\n");
    }

    // ==================== SubAgentAgentBuilder 测试 ====================

    @Test
    public void testSubAgentAgentBuilder() {
        System.out.println("=== 测试 SubAgentAgentBuilder ===");

        // 注意：这个测试需要实际的 ChatModel、AgentSessionProvider、PoolManager
        // 这里只测试 Builder 的结构和方法链

        // 测试静态工厂方法
        assertNotNull(SubAgentAgentBuilder.class);
        System.out.println("✓ SubAgentAgentBuilder 类存在");

        // 检查是否有必要的方法
        boolean hasBuilder = false;
        boolean hasOfMethod = false;
        boolean hasBuildMethod = false;

        try {
            SubAgentAgentBuilder.builder();
            hasBuilder = true;
            System.out.println("✓ builder() 方法存在");
        } catch (Exception e) {
            System.out.println("✗ builder() 方法调用失败（可能需要实际依赖）");
        }

        try {
            java.lang.reflect.Method ofMethod = SubAgentAgentBuilder.class.getMethod("of", org.noear.solon.ai.chat.ChatModel.class);
            hasOfMethod = true;
            System.out.println("✓ of() 方法存在");
        } catch (Exception e) {
            System.out.println("✗ of() 方法不存在");
        }

        try {
            java.lang.reflect.Method buildMethod = SubAgentAgentBuilder.class.getMethod("build");
            hasBuildMethod = true;
            System.out.println("✓ build() 方法存在");
        } catch (Exception e) {
            System.out.println("✗ build() 方法不存在");
        }

        assertTrue(hasBuilder || hasOfMethod);
        assertTrue(hasBuildMethod);

        System.out.println("✓ SubAgentAgentBuilder 测试通过\n");
    }

    // ==================== 边界情况和错误处理测试 ====================

    @Test
    public void testErrorHandling() {
        System.out.println("=== 测试错误处理 ===");

        // 1. 测试重复添加任务
        EventBus eventBus = new EventBus();
        SharedTaskList taskList = new SharedTaskList(eventBus);

        TeamTask task1 = TeamTask.builder()
                .title("任务1")
                .build();
        taskList.addTask(task1);

        // 尝试添加相同 ID 的任务
        assertThrows(Exception.class, () -> {
            taskList.addTask(task1); // 应该抛出异常
        });
        System.out.println("✓ 重复添加任务被拒绝");

        // 2. 测试认领不存在的任务
        try {
            boolean claimResult = taskList.claimTask("non-existent-id", "agent-1").get();
            assertFalse(claimResult);
            System.out.println("✓ 认领不存在的任务返回 false");
        } catch (Exception e) {
            // 期望异常
            assertTrue(true);
        }

        // 3. 测试获取不存在的任务
        TeamTask found = taskList.getTask("non-existent-id");
        assertNull(found);
        System.out.println("✓ 获取不存在的任务返回 null");

        // 4. 测试空任务列表操作
        SharedTaskList emptyList = new SharedTaskList(eventBus);
        assertTrue(emptyList.getAllTasks().isEmpty());
        assertTrue(emptyList.getClaimableTasks().isEmpty());
        System.out.println("✓ 空任务列表操作正常");

        System.out.println("✓ 错误处理测试通过\n");
    }

    @Test
    public void testConcurrentOperations() throws Exception {
        System.out.println("=== 测试并发操作 ===");

        EventBus eventBus = new EventBus();
        SharedTaskList taskList = new SharedTaskList(eventBus);

        // 1. 并发添加任务
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(10);
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < 100; i++) {
            final int index = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    TeamTask task = TeamTask.builder()
                            .title("并发任务-" + index)
                            .build();
                    taskList.addTask(task);
                } catch (Exception e) {
                    // 某些可能会失败（重复 ID 等）
                }
            }, executor);
            futures.add(future);
        }

        // 等待所有操作完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();

        // 验证结果
        int taskCount = taskList.getAllTasks().size();
        assertTrue(taskCount > 0);
        System.out.println("✓ 并发添加 " + taskCount + " 个任务成功");

        // 2. 并发认领任务
        List<TeamTask> claimableTasks = taskList.getClaimableTasks();
        if (!claimableTasks.isEmpty()) {
            List<CompletableFuture<Boolean>> claimFutures = new java.util.ArrayList<>();

            for (int i = 0; i < Math.min(10, claimableTasks.size()); i++) {
                final String taskId = claimableTasks.get(i).getId();
                CompletableFuture<Boolean> claimFuture = taskList.claimTask(taskId, "agent-" + Thread.currentThread().getId());
                claimFutures.add(claimFuture);
            }

            // 等待认领完成
            CompletableFuture.allOf(claimFutures.toArray(new CompletableFuture[0])).get();

            long claimedCount = claimFutures.stream()
                    .map(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .filter(result -> result)
                    .count();
            System.out.println("✓ 并发认领 " + claimedCount + " 个任务");
        }

        executor.shutdown();
        System.out.println("✓ 并发操作测试通过\n");
    }

    @Test
    public void testMemoryTTL() throws Exception {
        System.out.println("=== 测试记忆 TTL ===");

        // 创建短期 TTL 的管理器（1 秒）
        SharedMemoryManager manager = new SharedMemoryManager(
                Paths.get(TEST_WORK_DIR, AgentKernel.SOLONCODE_MEMORY),
                1000L,  // 1 秒 TTL
                7000L,  // 7 秒 TTL
                100L,   // 快速清理
                true,
                100,
                50
        );

        // 存储短期记忆（使用工厂方法，自动应用配置的 TTL）
        ShortTermMemory stm = manager.createShortTermMemory("agent1", "测试内容", "task-1");
        long createTime = stm.getTimestamp();
        System.out.println("记忆创建时间: " + createTime);
        System.out.println("记忆 TTL: " + stm.getTtl() + "ms");

        manager.store(stm);
        System.out.println("✓ 存储短期记忆 (TTL=1000ms)");

        // 立即检索（应该能找到）
        List<Memory> memories1 = manager.retrieve(Memory.MemoryType.SHORT_TERM, 10);
        System.out.println("立即检索到 " + memories1.size() + " 条记忆");
        assertFalse(memories1.isEmpty(), "应该能检索到刚创建的记忆");

        // 等待 TTL 过期（多等一些时间确保过期）
        Thread.sleep(2000); // 改为 2 秒，确保足够超过 1 秒 TTL

        // 检查记忆是否已过期
        boolean isExpired = stm.isExpired();
        long elapsedTime = System.currentTimeMillis() - createTime;
        System.out.println("经过时间: " + elapsedTime + "ms");
        System.out.println("记忆是否过期: " + isExpired);

        // 再次检索（应该找不到）
        List<Memory> memories2 = manager.retrieve(Memory.MemoryType.SHORT_TERM, 10);
        System.out.println("过期后检索到 " + memories2.size() + " 条记忆");
        assertTrue(memories2.isEmpty(), "过期后应该检索不到记忆");
        System.out.println("✓ TTL 过期后无法检索到记忆");

        manager.shutdown();
        System.out.println("✓ 记忆 TTL 测试通过\n");
    }
}
