///*
// * Copyright 2017-2026 noear.org and authors
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// * https://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//package agentTems;
//
//import org.junit.jupiter.api.*;
//import org.noear.solon.codecli.core.teams.memory.LongTermMemory;
//import org.noear.solon.codecli.core.teams.memory.Memory;
//import org.noear.solon.codecli.core.teams.memory.MemoryManager;
//import org.noear.solon.codecli.core.teams.memory.ShortTermMemory;
//import org.noear.solon.codecli.core.teams.message.AgentMessage;
//import org.noear.solon.codecli.core.teams.message.MessageChannel;
//import org.noear.solon.codecli.core.teams.message.MessageHandler;
//import org.noear.solon.codecli.core.teams.SharedTaskList;
//import org.noear.solon.codecli.core.teams.TeamTask;
//
//import java.nio.file.Paths;
//import java.util.*;
//import java.util.concurrent.CompletableFuture;
//import java.util.concurrent.TimeUnit;
//import java.util.concurrent.atomic.AtomicInteger;
//import java.util.function.Function;
//
//import static org.junit.jupiter.api.Assertions.*;
//
///**
// * Agent Teams 综合测试
// *
// * 测试覆盖：
// * 1. 路由模式
// * 2. 协作状态
// * 3. 不同 agents 之间的交互
// * 4. 死循环检测
// * 5. mainAgents 的使用
// *
// * @author bai
// * @since 3.9.5
// */
//@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
//public class AgentTeamsComprehensiveTest {
//
//    private static final String TEST_WORK_DIR = "./work/test-teams-comprehensive";
//
//    private MemoryManager memoryManager;
//    private EventBus eventBus;
//    private MessageChannel messageChannel;
//    private SharedTaskList taskList;
//
//    @BeforeEach
//    public void setUp() {
//        System.out.println("\n========================================");
//
//        // 初始化组件
//        memoryManager = new MemoryManager(
//                Paths.get(TEST_WORK_DIR, "memory"),
//                3600_000L,
//                7 * 24 * 3600_000L,
//                300_000L,
//                true,
//                1000,
//                500
//        );
//
//        eventBus = new EventBus(2, 100);
//        messageChannel = new MessageChannel(TEST_WORK_DIR, 2);
//        taskList = new SharedTaskList(eventBus, 50);
//
//        System.out.println("✓ 测试环境初始化完成");
//    }
//
//    @AfterEach
//    public void tearDown() {
//        System.out.println("\n清理测试环境...");
//
//        try {
//            if (memoryManager != null) {
//                memoryManager.shutdown();
//            }
//            if (eventBus != null) {
//                eventBus.shutdown();
//            }
//            if (messageChannel != null) {
//                messageChannel.shutdown();
//            }
//        } catch (Exception e) {
//            System.err.println("清理错误: " + e.getMessage());
//        }
//
//        System.out.println("✓ 测试环境已清理");
//        System.out.println("========================================");
//    }
//
//    // ==================== 1. 路由模式测试 ====================
//
//    @Test
//    @Order(1)
//    @DisplayName("测试任务路由模式 - 基于优先级")
//    public void testRoutingByPriority() throws Exception {
//        System.out.println("\n=== 测试任务路由模式 - 基于优先级 ===");
//
//        // 创建不同优先级的任务
//        TeamTask lowPriorityTask = TeamTask.builder()
//                .title("低优先级任务")
//                .priority(3)
//                .type(TeamTask.TaskType.DEVELOPMENT)
//                .build();
//
//        TeamTask highPriorityTask = TeamTask.builder()
//                .title("高优先级任务")
//                .priority(9)
//                .type(TeamTask.TaskType.DEVELOPMENT)
//                .build();
//
//        TeamTask mediumPriorityTask = TeamTask.builder()
//                .title("中优先级任务")
//                .priority(5)
//                .type(TeamTask.TaskType.DEVELOPMENT)
//                .build();
//
//        // 添加到任务列表
//        taskList.addTask(lowPriorityTask).join();
//        taskList.addTask(mediumPriorityTask).join();
//        taskList.addTask(highPriorityTask).join();
//
//        System.out.println("添加了 3 个任务（优先级: 3, 5, 9）");
//
//        // 获取可认领任务（应该按优先级排序）
//        List<TeamTask> claimableTasks = taskList.getClaimableTasks();
//
//        System.out.println("可认领任务数: " + claimableTasks.size());
//        for (TeamTask task : claimableTasks) {
//            System.out.println("  - " + task.getTitle() + " (优先级: " + task.getPriority() + ")");
//        }
//
//        // 验证高优先级任务排在前面
//        assertEquals(3, claimableTasks.size());
//        assertEquals(9, claimableTasks.get(0).getPriority());
//        assertEquals(5, claimableTasks.get(1).getPriority());
//        assertEquals(3, claimableTasks.get(2).getPriority());
//
//        System.out.println("✓ 任务按优先级正确排序");
//    }
//
//    @Test
//    @Order(2)
//    @DisplayName("测试任务路由模式 - 基于类型")
//    public void testRoutingByType() throws Exception {
//        System.out.println("\n=== 测试任务路由模式 - 基于类型 ===");
//
//        // 创建不同类型的任务
//        TeamTask exploreTask = TeamTask.builder()
//                .title("探索任务")
//                .type(TeamTask.TaskType.EXPLORATION)
//                .build();
//
//        TeamTask devTask = TeamTask.builder()
//                .title("开发任务")
//                .type(TeamTask.TaskType.DEVELOPMENT)
//                .build();
//
//        TeamTask testTask = TeamTask.builder()
//                .title("测试任务")
//                .type(TeamTask.TaskType.TESTING)
//                .build();
//
//        // 添加任务
//        taskList.addTask(exploreTask).join();
//        taskList.addTask(devTask).join();
//        taskList.addTask(testTask).join();
//
//        System.out.println("添加了 3 个不同类型的任务");
//
//        // 模拟不同类型的 agent 认领任务
//        String exploreAgent = "explore-agent";
//        String devAgent = "dev-agent";
//
//        // explore-agent 只认领探索任务
//        List<TeamTask> allTasks = taskList.getAllTasks();
//        for (TeamTask task : allTasks) {
//            if (task.getType() == TeamTask.TaskType.EXPLORATION) {
//                taskList.claimTask(task.getId(), exploreAgent).join();
//                System.out.println(exploreAgent + " 认领了: " + task.getTitle());
//            } else if (task.getType() == TeamTask.TaskType.DEVELOPMENT) {
//                taskList.claimTask(task.getId(), devAgent).join();
//                System.out.println(devAgent + " 认领了: " + task.getTitle());
//            }
//        }
//
//        // 验证认领结果
//        assertEquals(TeamTask.Status.IN_PROGRESS,
//                taskList.getTask(exploreTask.getId()).getStatus());
//        assertEquals(TeamTask.Status.IN_PROGRESS,
//                taskList.getTask(devTask.getId()).getStatus());
//        assertEquals(TeamTask.Status.PENDING,
//                taskList.getTask(testTask.getId()).getStatus());
//
//        System.out.println("✓ 任务按类型正确路由");
//    }
//
//    @Test
//    @Order(3)
//    @DisplayName("测试任务路由模式 - 基于负载均衡")
//    public void testRoutingByLoadBalancing() throws Exception {
//        System.out.println("\n=== 测试任务路由模式 - 基于负载均衡 ===");
//
//        // 创建多个任务
//        for (int i = 1; i <= 5; i++) {
//            TeamTask task = TeamTask.builder()
//                    .title("任务 " + i)
//                    .build();
//            taskList.addTask(task).join();
//        }
//
//        System.out.println("添加了 5 个任务");
//
//        // 模拟 3 个 agent 认领任务
//        String agent1 = "agent-1";
//        String agent2 = "agent-2";
//        String agent3 = "agent-3";
//
//        // Agent 1 认领 2 个任务
//        claimTasksForAgent(agent1, 2);
//        Thread.sleep(50);
//
//        // Agent 2 认领 1 个任务
//        claimTasksForAgent(agent2, 1);
//        Thread.sleep(50);
//
//        // Agent 3 认领 2 个任务
//        claimTasksForAgent(agent3, 2);
//
//        // 检查负载分布
//        Map<String, Integer> loads = taskList.getAllAgentLoads();
//        System.out.println("Agent 负载分布:");
//        loads.forEach((agent, load) -> System.out.println("  " + agent + ": " + load));
//
//        assertEquals(2, (int) loads.get(agent1));
//        assertEquals(1, (int) loads.get(agent2));
//        assertEquals(2, (int) loads.get(agent3));
//
//        // 验证负载最小的 agent
//        String leastLoadedAgent = loads.entrySet().stream()
//                .min(Map.Entry.comparingByValue())
//                .map(Map.Entry::getKey)
//                .orElse(null);
//        assertEquals(agent2, leastLoadedAgent);
//        System.out.println("✓ 负载均衡正确，最小负载 agent: " + leastLoadedAgent);
//    }
//
//    // ==================== 2. 协作状态测试 ====================
//
//    @Test
//    @Order(4)
//    @DisplayName("测试协作状态 - 任务依赖")
//    public void testCollaborationWithDependencies() throws Exception {
//        System.out.println("\n=== 测试协作状态 - 任务依赖 ===");
//
//        // 创建任务链: task1 -> task2 -> task3
//        TeamTask task1 = TeamTask.builder()
//                .title("基础任务")
//                .build();
//
//        TeamTask task2 = TeamTask.builder()
//                .title("依赖任务1")
//                .dependencies(Arrays.asList(task1.getId()))
//                .build();
//
//        TeamTask task3 = TeamTask.builder()
//                .title("依赖任务2")
//                .dependencies(Arrays.asList(task2.getId()))
//                .build();
//
//        taskList.addTask(task1).join();
//        taskList.addTask(task2).join();
//        taskList.addTask(task3).join();
//
//        System.out.println("创建了任务链: task1 -> task2 -> task3");
//
//        // 验证依赖关系
//        Function<String, TeamTask> taskLookup = id -> taskList.getTask(id);
//
//        // task1 没有依赖，应该返回 true
//        assertTrue(task1.areAllDependenciesCompleted(taskLookup),
//                "task1 无依赖，应该返回 true");
//
//        // task2 依赖 task1，task1 未完成，应该返回 false
//        assertFalse(task2.areAllDependenciesCompleted(taskLookup),
//                "task2 依赖未完成的 task1，应该返回 false");
//
//        // 完成 task1
//        task1.setStatus(TeamTask.Status.COMPLETED);
//
//        System.out.println("task1 已完成");
//
//        // 验证 task2 是否可以开始
//        List<TeamTask> pendingTasks = taskList.getPendingTasks();
//        boolean task2Pending = pendingTasks.stream()
//                .anyMatch(t -> t.getId().equals(task2.getId()));
//        assertTrue(task2Pending, "task2 应该在待认领列表中");
//
//        System.out.println("✓ 任务依赖协作正常");
//    }
//
//    @Test
//    @Order(5)
//    @DisplayName("测试协作状态 - 多 agent 并行工作")
//    public void testMultiAgentParallelWork() throws Exception {
//        System.out.println("\n=== 测试协作状态 - 多 agent 并行工作 ===");
//
//        // 创建多个独立任务
//        List<TeamTask> tasks = new ArrayList<>();
//        for (int i = 1; i <= 3; i++) {
//            TeamTask task = TeamTask.builder()
//                    .title("并行任务 " + i)
//                    .build();
//            taskList.addTask(task).join();
//            tasks.add(task);
//        }
//
//        System.out.println("创建了 3 个并行任务");
//
//        // 多个 agent 同时认领任务（带重试机制）
//        CompletableFuture<TeamTask> agent1 = CompletableFuture.supplyAsync(() -> {
//            try {
//                String agentId = "agent-1";
//                int maxRetries = 3;
//
//                for (int retry = 0; retry < maxRetries; retry++) {
//                    List<TeamTask> claimable = taskList.getClaimableTasks();
//                    if (claimable.isEmpty()) {
//                        System.out.println(agentId + " 没有可认领的任务（重试 " + retry + "/" + maxRetries + "）");
//                        Thread.sleep(50);
//                        continue;
//                    }
//
//                    // 尝试认领第一个任务
//                    TeamTask task = claimable.get(0);
//                    Boolean claimed = taskList.claimTask(task.getId(), agentId).join();
//
//                    if (claimed) {
//                        System.out.println(agentId + " 认领: " + task.getTitle());
//
//                        // 模拟工作
//                        Thread.sleep(100);
//                        task.setStatus(TeamTask.Status.COMPLETED);
//                        taskList.completeTask(task.getId(), "完成");
//
//                        System.out.println(agentId + " 完成: " + task.getTitle());
//                        return task;
//                    } else {
//                        System.out.println(agentId + " 认领失败，任务可能已被其他 agent 认领");
//                        Thread.sleep(50);  // 等待后重试
//                    }
//                }
//
//                System.out.println(agentId + " 经过 " + maxRetries + " 次重试后仍未找到可认领任务");
//                return null;
//
//            } catch (Exception e) {
//                e.printStackTrace();
//                return null;
//            }
//        });
//
//        CompletableFuture<TeamTask> agent2 = CompletableFuture.supplyAsync(() -> {
//            try {
//                String agentId = "agent-2";
//                int maxRetries = 3;
//
//                for (int retry = 0; retry < maxRetries; retry++) {
//                    List<TeamTask> claimable = taskList.getClaimableTasks();
//                    if (claimable.isEmpty()) {
//                        System.out.println(agentId + " 没有可认领的任务（重试 " + retry + "/" + maxRetries + "）");
//                        Thread.sleep(50);
//                        continue;
//                    }
//
//                    // 尝试认领第一个任务
//                    TeamTask task = claimable.get(0);
//                    Boolean claimed = taskList.claimTask(task.getId(), agentId).join();
//
//                    if (claimed) {
//                        System.out.println(agentId + " 认领: " + task.getTitle());
//
//                        // 模拟工作
//                        Thread.sleep(100);
//                        task.setStatus(TeamTask.Status.COMPLETED);
//                        taskList.completeTask(task.getId(), "完成");
//
//                        System.out.println(agentId + " 完成: " + task.getTitle());
//                        return task;
//                    } else {
//                        System.out.println(agentId + " 认领失败，任务可能已被其他 agent 认领");
//                        Thread.sleep(50);  // 等待后重试
//                    }
//                }
//
//                System.out.println(agentId + " 经过 " + maxRetries + " 次重试后仍未找到可认领任务");
//                return null;
//
//            } catch (Exception e) {
//                e.printStackTrace();
//                return null;
//            }
//        });
//
//        // 等待所有 agent 完成
//        CompletableFuture.allOf(agent1, agent2).get(5, TimeUnit.SECONDS);
//
//        // 验证结果
//        long completedCount = tasks.stream()
//                .filter(t -> taskList.getTask(t.getId()).getStatus() == TeamTask.Status.COMPLETED)
//                .count();
//
//        System.out.println("已完成任务数: " + completedCount);
//        assertTrue(completedCount >= 2, "至少应该完成 2 个任务");
//        System.out.println("✓ 多 agent 并行协作正常");
//    }
//
//    // ==================== 3. Agents 交互测试 ====================
//
//    @Test
//    @Order(6)
//    @DisplayName("测试 agents 交互 - 消息传递")
//    public void testAgentMessagePassing() throws Exception {
//        System.out.println("\n=== 测试 agents 交互 - 消息传递 ===");
//
//        // 注册消息处理器
//        AtomicInteger messageCount = new AtomicInteger(0);
//
//        messageChannel.registerHandler("receiver", new MessageHandler() {
//            @Override
//            public <T> CompletableFuture<Object> handle(AgentMessage<T> message) {
//                int count = messageCount.incrementAndGet();
//                System.out.println("收到消息 #" + count + ": " + message.getContent());
//
//                if ("query".equals(message.getType())) {
//                    return CompletableFuture.completedFuture("响应: " + message.getContent());
//                }
//                return CompletableFuture.completedFuture("ACK");
//            }
//        });
//
//        // 发送多条消息
//        for (int i = 1; i <= 3; i++) {
//            CompletableFuture<Object> response = messageChannel.request(
//                    "sender",
//                    "receiver",
//                    "query",
//                    "测试消息 " + i
//            );
//            Object result = response.get(200, TimeUnit.SECONDS);
//            System.out.println("收到响应: " + result);
//            assertNotNull(result);
//        }
//
//        assertEquals(3, messageCount.get());
//        System.out.println("✓ Agent 消息传递正常");
//    }
//
//    @Test
//    @Order(7)
//    @DisplayName("测试 agents 交互 - 事件广播")
//    public void testAgentEventBroadcast() throws Exception {
//        System.out.println("\n=== 测试 agents 交互 - 事件广播 ===");
//
//        // 订阅计数器（验证订阅者能收到事件）
//        AtomicInteger receiveCount = new AtomicInteger(0);
//        CompletableFuture<String> eventReceived = new CompletableFuture<>();
//
//        // 使用枚举类型订阅（与发布的事件类型匹配）
//        String subscriptionId = eventBus.subscribe(AgentEventType.TASK_PROGRESS, event -> {
//            try {
//                String agent = event.getMetadata().getSourceAgent();
//                String content = (String) event.getPayload();
//
//                System.out.println("✓ 订阅者收到事件: sourceAgent=" + agent + ", content=" + content);
//
//                // 完成 Future
//                if (receiveCount.incrementAndGet() > 0) {
//                    eventReceived.complete(content);
//                }
//
//                return CompletableFuture.completedFuture(EventHandler.Result.success());
//            } catch (Exception e) {
//                System.err.println("事件处理器异常: " + e.getMessage());
//                e.printStackTrace();
//                throw e;
//            }
//        });
//
//        System.out.println("订阅ID: " + subscriptionId);
//        System.out.println("订阅者数量: " + eventBus.getSubscriberCount(AgentEventType.TASK_PROGRESS));
//
//        // 等待订阅生效
//        Thread.sleep(200);
//
//        // 发布事件
//        System.out.println("发布事件...");
//        AgentEvent event = new AgentEvent(
//                AgentEventType.TASK_PROGRESS,
//                "任务已更新",
//                EventMetadata.builder()
//                        .sourceAgent("agent-1")
//                        .taskId("task-123")
//                        .build()
//        );
//
//        eventBus.publish(event);
//        System.out.println("事件已发布，等待响应...");
//
//        // 等待订阅者接收事件
//        String result = eventReceived.get(3, TimeUnit.SECONDS);
//        assertNotNull(result);
//        assertEquals("任务已更新", result);
//
//        System.out.println("✓ Agent 事件广播正常");
//    }
//
//    @Test
//    @Order(8)
//    @DisplayName("测试 agents 交互 - 共享内存")
//    public void testAgentSharedMemory() throws Exception {
//        System.out.println("\n=== 测试 agents 交互 - 共享内存 ===");
//
//        // Agent 1 写入记忆
//        ShortTermMemory memory1 = memoryManager.createShortTermMemory(
//                "agent-1",
//                "Agent 1 的发现",
//                "task-1"
//        );
//        memoryManager.store(memory1);
//        System.out.println("Agent 1 写入记忆");
//
//        // Agent 2 读取记忆
//        List<Memory> memories = memoryManager.retrieve(Memory.MemoryType.SHORT_TERM, 10);
//        assertFalse(memories.isEmpty());
//        System.out.println("Agent 2 读取到 " + memories.size() + " 条记忆");
//
//        // Agent 3 搜索记忆
//        List<Memory> searchResults = memoryManager.search("Agent 1", 5);
//        assertFalse(searchResults.isEmpty());
//        System.out.println("Agent 3 搜索到 " + searchResults.size() + " 条相关记忆");
//
//        // 验证记忆内容
//        Memory found = searchResults.get(0);
//        if (found instanceof ShortTermMemory) {
//            ShortTermMemory stm = (ShortTermMemory) found;
//            assertEquals("agent-1", stm.getAgentId());
//            assertEquals("Agent 1 的发现", stm.getContext());
//        }
//
//        System.out.println("✓ Agent 共享内存正常");
//    }
//
//    // ==================== 4. 死循环检测测试 ====================
//
//    @Test
//    @Order(9)
//    @DisplayName("测试死循环检测 - 任务循环依赖")
//    public void testCyclicDependencyDetection() {
//        System.out.println("\n=== 测试死循环检测 - 任务循环依赖 ===");
//
//        // 创建循环依赖: task1 -> task2 -> task3 -> task1
//        TeamTask task1 = TeamTask.builder().title("任务1").build();
//        TeamTask task2 = TeamTask.builder()
//                .title("任务2")
//                .dependencies(Arrays.asList(task1.getId()))
//                .build();
//        TeamTask task3 = TeamTask.builder()
//                .title("任务3")
//                .dependencies(Arrays.asList(task2.getId()))
//                .build();
//
//        // 闭合循环
//        task1.setDependencies(Arrays.asList(task3.getId()));
//
//        // 创建查找函数
//        java.util.function.Function<String, TeamTask> lookup = id -> {
//            if (id.equals(task1.getId())) return task1;
//            if (id.equals(task2.getId())) return task2;
//            if (id.equals(task3.getId())) return task3;
//            return null;
//        };
//
//        // 检测循环依赖
//        assertTrue(task1.hasCyclicDependency(lookup),
//                "应该检测到循环依赖");
//
//        // 尝试添加循环依赖任务（应该失败）
//        assertThrows(Exception.class, () -> {
//            taskList.addTask(task1).join();
//            taskList.addTask(task2).join();
//            taskList.addTask(task3).get(); // 这会抛出异常
//        });
//
//        System.out.println("✓ 循环依赖检测正常");
//    }
//
//    @Test
//    @Order(10)
//    @DisplayName("测试死循环检测 - 消息循环")
//    public void testMessageLoopPrevention() throws Exception {
//        System.out.println("\n=== 测试死循环检测 - 消息循环 ===");
//
//        AtomicInteger messageCount = new AtomicInteger(0);
//        final int MAX_MESSAGES = 5;
//
//        // 注册处理器（模拟可能的循环）
//        messageChannel.registerHandler("loop-test", new MessageHandler() {
//            private boolean shouldRespond = true;
//
//            @Override
//            public <T> CompletableFuture<Object> handle(AgentMessage<T> message) {
//                int count = messageCount.incrementAndGet();
//                System.out.println("收到消息 #" + count + ": " + message.getContent());
//
//                if (count > MAX_MESSAGES) {
//                    System.out.println("⚠️ 检测到可能的消息循环，停止响应");
//                    shouldRespond = false;
//                    return CompletableFuture.completedFuture("STOP");
//                }
//
//                if (shouldRespond && "ping".equals(message.getType())) {
//                    // 不再发送响应以避免循环
//                    return CompletableFuture.completedFuture("pong #" + count);
//                }
//
//                return CompletableFuture.completedFuture("ACK");
//            }
//        });
//
//        // 发送消息
//        CompletableFuture<Object> response = messageChannel.request(
//                "sender",
//                "loop-test",
//                "ping",
//                "测试消息"
//        );
//
//        Object result = response.get(2, TimeUnit.SECONDS);
//
//        assertNotNull(result);
//        assertTrue(messageCount.get() <= MAX_MESSAGES,
//                "消息数不应该超过限制 (实际: " + messageCount.get() + ")");
//
//        System.out.println("✓ 消息循环检测正常");
//    }
//
//    // ==================== 5. MainAgent 协调测试 ====================
//
//    @Test
//    @Order(11)
//    @DisplayName("测试 MainAgent 协调 - 任务分配")
//    public void testMainAgentTaskCoordination() throws Exception {
//        System.out.println("\n=== 测试 MainAgent 协调 - 任务分配 ===");
//
//        // 创建主任务
//        TeamTask mainTask = TeamTask.builder()
//                .title("主任务: 实现用户认证")
//                .description("需要探索、开发、测试三个阶段")
//                .type(TeamTask.TaskType.DEVELOPMENT)
//                .priority(8)
//                .build();
//
//        taskList.addTask(mainTask).join();
//        System.out.println("创建主任务: " + mainTask.getTitle());
//
//        // MainAgent 分解子任务
//        TeamTask exploreSubTask = TeamTask.builder()
//                .title("探索现有代码")
//                .dependencies(Arrays.asList(mainTask.getId()))
//                .type(TeamTask.TaskType.EXPLORATION)
//                .build();
//
//        TeamTask devSubTask = TeamTask.builder()
//                .title("开发认证功能")
//                .dependencies(Arrays.asList(mainTask.getId()))
//                .type(TeamTask.TaskType.DEVELOPMENT)
//                .build();
//
//        TeamTask testSubTask = TeamTask.builder()
//                .title("测试认证功能")
//                .dependencies(Arrays.asList(devSubTask.getId()))
//                .type(TeamTask.TaskType.TESTING)
//                .build();
//
//        taskList.addTask(exploreSubTask).join();
//        taskList.addTask(devSubTask).join();
//        taskList.addTask(testSubTask).join();
//
//        System.out.println("MainAgent 创建了 3 个子任务");
//
//        // 验证任务总数
//        assertEquals(4, taskList.getAllTasks().size());
//
//        // 验证主任务没有依赖
//        assertTrue(mainTask.getDependencies().isEmpty(), "主任务不应该有依赖");
//
//        // 验证 exploreSubTask 依赖于 mainTask
//        assertTrue(exploreSubTask.getDependencies().contains(mainTask.getId()),
//                "探索任务应该依赖于主任务");
//
//        // 验证 devSubTask 依赖于 mainTask
//        assertTrue(devSubTask.getDependencies().contains(mainTask.getId()),
//                "开发任务应该依赖于主任务");
//
//        // 验证 testSubTask 依赖于 devSubTask
//        assertTrue(testSubTask.getDependencies().contains(devSubTask.getId()),
//                "测试任务应该依赖于开发任务");
//
//        // 显示每个任务的依赖树
//        System.out.println("\n--- 任务依赖关系 ---");
//        for (TeamTask task : taskList.getAllTasks()) {
//            String tree = task.getDependencyTree(taskList::getTask);
//            System.out.println(tree);
//        }
//
//        System.out.println("✓ MainAgent 任务协调正常");
//    }
//
//    @Test
//    @Order(12)
//    @DisplayName("测试 MainAgent 协调 - 结果汇总")
//    public void testMainAgentResultAggregation() throws Exception {
//        System.out.println("\n=== 测试 MainAgent 协调 - 结果汇总 ===");
//
//        // 创建任务并分配给不同 agents
//        TeamTask task1 = TeamTask.builder()
//                .title("数据分析任务")
//                .type(TeamTask.TaskType.ANALYSIS)
//                .build();
//
//        taskList.addTask(task1).join();
//
//        // Agent 1 完成任务
//        String agent1 = "analysis-agent";
//        taskList.claimTask(task1.getId(), agent1).join();
//
//        // 模拟工作完成
//        Thread.sleep(100);
//
//        task1.setStatus(TeamTask.Status.COMPLETED);
//        task1.setResult("分析结果: 发现 3 个性能问题");
//        taskList.completeTask(task1.getId(), task1.getResult());
//
//        System.out.println(agent1 + " 完成任务，结果: " + task1.getResult());
//
//        // MainAgent 查询结果
//        List<TeamTask> completedTasks = taskList.getTasksByStatus(TeamTask.Status.COMPLETED);
//        assertFalse(completedTasks.isEmpty());
//
//        // 汇总结果
//        StringBuilder summary = new StringBuilder("任务汇总:\n");
//        for (TeamTask task : completedTasks) {
//            summary.append("  - ")
//                    .append(task.getTitle())
//                    .append(": ")
//                    .append(task.getResult())
//                    .append("\n");
//        }
//
//        System.out.println(summary);
//        assertTrue(summary.toString().contains("性能问题"));
//        System.out.println("✓ MainAgent 结果汇总正常");
//    }
//
//    // ==================== 6. 综合场景测试 ====================
//
//    @Test
//    @Order(13)
//    @DisplayName("综合场景 - 完整的协作流程")
//    public void testCompleteCollaborationScenario() throws Exception {
//        System.out.println("\n=== 综合场景 - 完整的协作流程 ===");
//
//        // 场景：实现一个新功能，需要多个 agent 协作
//        // 1. Explore agent 探索代码库
//        // 2. Plan agent 制定计划
//        // 3. Dev agents 开发功能
//        // 4. Test agent 测试功能
//        // 5. MainAgent 汇总结果
//
//        System.out.println("步骤 1: Explore agent 探索代码库");
//        TeamTask exploreTask = TeamTask.builder()
//                .title("探索用户管理模块")
//                .type(TeamTask.TaskType.EXPLORATION)
//                .build();
//        taskList.addTask(exploreTask).join();
//
//        // Explore agent 认领并完成
//        taskList.claimTask(exploreTask.getId(), "explore-agent").join();
//        Thread.sleep(50);
//        exploreTask.setStatus(TeamTask.Status.COMPLETED);
//        exploreTask.setResult("找到 3 个相关文件，发现需要重构");
//        System.out.println("✓ 探索完成");
//
//        // 存储到共享记忆
//        LongTermMemory plan = new LongTermMemory(
//                "用户管理模块重构计划",
//                "explore-agent",
//                Arrays.asList("refactor", "user-management")
//        );
//        plan.setImportance(0.9);
//        memoryManager.store(plan);
//        System.out.println("✓ 计划已存储到共享记忆");
//
//        System.out.println("步骤 2: Plan agent 制定详细计划");
//        // 发布事件通知
//        eventBus.publish(new AgentEvent(
//                AgentEventType.TASK_COMPLETED,
//                "探索完成",
//                EventMetadata.builder()
//                        .sourceAgent("explore-agent")
//                        .taskId(exploreTask.getId())
//                        .build()
//        ));
//
//        System.out.println("步骤 3: 开发和测试任务");
//        TeamTask devTask1 = TeamTask.builder()
//                .title("重构用户服务")
//                .dependencies(Arrays.asList(exploreTask.getId()))
//                .type(TeamTask.TaskType.DEVELOPMENT)
//                .build();
//
//        TeamTask devTask2 = TeamTask.builder()
//                .title("更新数据库模型")
//                .dependencies(Arrays.asList(exploreTask.getId()))
//                .type(TeamTask.TaskType.DEVELOPMENT)
//                .build();
//
//        TeamTask testTask = TeamTask.builder()
//                .title("编写单元测试")
//                .dependencies(Arrays.asList(devTask1.getId(), devTask2.getId()))
//                .type(TeamTask.TaskType.TESTING)
//                .build();
//
//        taskList.addTask(devTask1).join();
//        taskList.addTask(devTask2).join();
//        taskList.addTask(testTask).join();
//
//        System.out.println("✓ 创建了开发任务和测试任务");
//
//        // 验证任务状态
//        List<TeamTask> allTasks = taskList.getAllTasks();
//        System.out.println("总任务数: " + allTasks.size());
//
//        // 验证依赖关系
//        java.util.function.Function<String, TeamTask> lookup = taskList::getTask;
//        assertFalse(testTask.areAllDependenciesCompleted(lookup),
//                "测试任务的依赖未完成");
//
//        // 完成开发任务
//        devTask1.setStatus(TeamTask.Status.COMPLETED);
//        taskList.completeTask(devTask1.getId(), "开发完成");
//        devTask2.setStatus(TeamTask.Status.COMPLETED);
//        taskList.completeTask(devTask2.getId(), "开发完成");
//
//        System.out.println("✓ 开发任务完成");
//
//        // 发布完成事件
//        eventBus.publish(new AgentEvent(
//                AgentEventType.TASK_COMPLETED,
//                "开发完成",
//                EventMetadata.builder()
//                        .sourceAgent("dev-agent")
//                        .taskId(devTask1.getId())
//                        .build()
//        ));
//
//        System.out.println("步骤 4: 测试任务可认领");
//        List<TeamTask> pending = taskList.getPendingTasks();
//        boolean testTaskPending = pending.stream()
//                .anyMatch(t -> t.getId().equals(testTask.getId()));
//        assertTrue(testTaskPending, "测试任务应该可认领");
//
//        System.out.println("\n协作流程统计:");
//        System.out.println("  总任务数: " + allTasks.size());
//        System.out.println("  已完成: " + taskList.getTasksByStatus(TeamTask.Status.COMPLETED).size());
//        System.out.println("  进行中: " + taskList.getTasksByStatus(TeamTask.Status.IN_PROGRESS).size());
//        System.out.println("  待认领: " + taskList.getPendingTasks().size());
//        System.out.println("  Agent 负载: " + taskList.getAllAgentLoads());
//
//        System.out.println("✓ 综合协作场景测试通过");
//    }
//
//    // ==================== 辅助方法 ====================
//
//    private void claimTasksForAgent(String agentId, int taskCount) {
//        List<TeamTask> claimable = taskList.getClaimableTasks();
//        int claimed = 0;
//
//        for (TeamTask task : claimable) {
//            if (claimed >= taskCount) break;
//
//            try {
//                taskList.claimTask(task.getId(), agentId).join();
//                System.out.println(agentId + " 认领: " + task.getTitle());
//                claimed++;
//            } catch (Exception e) {
//                System.err.println("认领失败: " + e.getMessage());
//            }
//        }
//    }
//}
