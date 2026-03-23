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
//import lombok.extern.slf4j.Slf4j;
//import org.noear.solon.codecli.core.AgentRuntime;
//import org.noear.solon.codecli.core.AgentProperties;
//import org.noear.solon.codecli.core.teams.event.AgentEvent;
//import org.noear.solon.codecli.core.teams.event.EventBus;
//import org.noear.solon.codecli.core.teams.event.EventHandler;
//import org.noear.solon.codecli.core.teams.event.EventMetadata;
//import org.noear.solon.codecli.core.teams.message.AgentMessage;
//import org.noear.solon.codecli.core.teams.message.MessageAck;
//import org.noear.solon.codecli.core.teams.message.MessageChannel;
//import org.noear.solon.codecli.core.teams.message.MessageHandler;
//
//import java.nio.file.Paths;
//import java.util.Arrays;
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.CompletableFuture;
//import java.util.concurrent.TimeUnit;
//
///**
// * Agent Teams 功能调试程序
// *
// * 用于测试共享记忆、事件总线、消息传递三大功能
// *
// * @author bai
// * @since 3.9.5
// */
//@Slf4j
//public class AgentTeamsDebugger {
//
//    private static final String TEST_WORK_DIR = "work/debug";
//
//    public static void main(String[] args) {
//        System.out.println("========================================");
//        System.out.println("  Agent Teams 功能调试程序");
//        System.out.println("========================================\n");
//
//        try {
//            // 测试共享记忆
//            testSharedMemory();
//
//            // 测试事件总线
//            testEventBus();
//
//            // 测试消息通道
//            testMessageChannel();
//
//            // 测试配置
//            testConfiguration();
//
//            System.out.println("\n========================================");
//            System.out.println("  所有测试通过！✅");
//            System.out.println("========================================");
//
//        } catch (Exception e) {
//            System.err.println("\n========================================");
//            System.err.println("  测试失败！❌");
//            System.err.println("========================================");
//            e.printStackTrace();
//        }
//    }
//
//    /**
//     * 测试1: 共享记忆管理器
//     */
//    private static void testSharedMemory() throws Exception {
//        System.out.println("【测试1】共享记忆管理器");
//        System.out.println("-----------------------------------");
//
//        // 创建管理器
//        MemoryManager manager = new MemoryManager(Paths.get(TEST_WORK_DIR, AgentRuntime.SOLONCODE_MEMORY));
//
//        // 1.1 存储短期记忆
//        System.out.print("1. 存储短期记忆... ");
//        ShortTermMemory stm = new ShortTermMemory("explore", "探索用户认证模块", "task-001");
//        manager.store(stm);
//        System.out.println("✅");
//
//        // 1.2 存储长期记忆
//        System.out.print("2. 存储长期记忆... ");
//        LongTermMemory ltm = new LongTermMemory(
//            "系统采用 JWT + Redis 实现分布式会话",
//            "plan",
//            Arrays.asList("architecture", "auth", "jwt", "redis")
//        );
//        ltm.setImportance(0.95);
//        manager.store(ltm);
//        System.out.println("✅");
//
//        // 1.3 存储知识库
//        System.out.print("3. 存储知识库... ");
//        KnowledgeMemory km = new KnowledgeMemory(
//            "Spring Security",
//            "Spring 生态的安全框架，支持认证和授权",
//            "framework"
//        );
//        km.setKeywords(Arrays.asList("spring", "security", "auth"));
//        manager.store(km);
//        System.out.println("✅");
//
//        // 1.4 检索短期记忆
//        System.out.print("4. 检索短期记忆... ");
//        List<Memory> shortTerm = manager.retrieve(Memory.MemoryType.SHORT_TERM, 10);
//        assertTrue(shortTerm.size() >= 1, "应该至少有1条短期记忆");
//        System.out.println("✅ (找到 " + shortTerm.size() + " 条)");
//
//        // 1.5 按标签检索
//        System.out.print("5. 按标签检索... ");
//        List<LongTermMemory> tagged = manager.retrieveByTag("architecture", 10);
//        assertTrue(tagged.size() >= 1, "应该找到包含 'architecture' 标签的记忆");
//        System.out.println("✅ (找到 " + tagged.size() + " 条)");
//
//        // 1.6 全文搜索
//        System.out.print("6. 全文搜索... ");
//        List<Memory> searchResults = manager.search("JWT", 10);
//        assertTrue(searchResults.size() >= 1, "应该找到包含 'JWT' 的记忆");
//        System.out.println("✅ (找到 " + searchResults.size() + " 条)");
//
//        // 1.7 获取统计信息
//        System.out.print("7. 获取统计信息... ");
//        Map<String, Object> stats = manager.getStats();
//        System.out.println("✅");
//        System.out.println("   统计: " + stats);
//
//        manager.shutdown();
//        System.out.println("-----------------------------------");
//        System.out.println();
//    }
//
//    /**
//     * 测试2: 事件总线
//     */
//    private static void testEventBus() throws Exception {
//        System.out.println("【测试2】事件总线");
//        System.out.println("-----------------------------------");
//
//        // 创建事件总线
//        EventBus bus = new EventBus(2, 100);
//
//        // 2.1 订阅和发布精确匹配事件
//        System.out.print("1. 测试精确匹配... ");
//        CompletableFuture<String> future1 = new CompletableFuture<>();
//        bus.subscribe("test.event", event -> {
//            future1.complete((String) event.getPayload());
//            System.out.println("接收到事件: " + event);
//            return CompletableFuture.completedFuture(EventHandler.Result.success());
//        });
//
//        EventMetadata metadata = EventMetadata.builder()
//                .sourceAgent("test-agent")
//                .taskId("test-task")
//                .priority(5)
//                .build();
//
//        bus.publish(new AgentEvent("test.event", "hello", metadata));
//        String result1 = future1.get(2, TimeUnit.SECONDS);
//        assertEquals("hello", result1, "事件内容应该匹配");
//        System.out.println("✅");
//
//        // 2.2 测试通配符订阅
//        System.out.print("2. 测试通配符匹配... ");
//        CompletableFuture<String> future2 = new CompletableFuture<>();
//        bus.subscribe("task.*", event -> {
//            future2.complete(event.getEventTypeCode());
//            System.out.println("接收到事件: " + event);
//            return CompletableFuture.completedFuture(EventHandler.Result.success());
//        });
//
//        bus.publish(new AgentEvent("task.completed", "data", metadata));
//        String result2 = future2.get(2, TimeUnit.SECONDS);
//        assertEquals("task.completed", result2, "事件类型代码应该匹配");
//        System.out.println("✅");
//
//        // 2.3 测试事件历史
//        System.out.print("3. 测试事件历史... ");
//        List<AgentEvent> history = bus.getEventHistory(10);
//        assertTrue(history.size() >= 2, "应该有至少2条历史记录");
//        System.out.println("✅ (历史记录: " + history.size() + " 条)");
//
//        // 2.4 测试订阅者数量
//        System.out.print("4. 测试订阅者统计... ");
//        int count = bus.getSubscriberCount();
//        assertTrue(count >= 2, "应该有至少2个订阅者");
//        System.out.println("✅ (订阅者: " + count + " 个)");
//
//        // 2.5 测试取消订阅
//        System.out.print("5. 测试取消订阅... ");
//        String subId = bus.subscribe("temp.event", e ->
//            CompletableFuture.completedFuture(EventHandler.Result.success())
//        );
//        bus.unsubscribe(subId);
//        System.out.println("✅");
//
//        bus.shutdown();
//        System.out.println("-----------------------------------");
//        System.out.println();
//    }
//
//    /**
//     * 测试3: 消息通道
//     */
//    private static void testMessageChannel() throws Exception {
//        System.out.println("【测试3】消息通道");
//        System.out.println("-----------------------------------");
//
//        // 创建消息通道
//        MessageChannel channel = new MessageChannel(TEST_WORK_DIR, 2);
//
//        // 3.1 注册处理器并发送消息
//        System.out.print("1. 测试点对点消息... ");
//        String handlerId = channel.registerHandler("receiver", new MessageHandler() {
//            @Override
//            public <T> CompletableFuture<Object> handle(AgentMessage<T> message) {
//                System.out.println("收到消息: " + message);
//                return CompletableFuture.completedFuture("ACK");
//            }
//        });
//
//        AgentMessage<String> p2pMsg = new AgentMessage.Builder<String>()
//            .from("sender")
//            .to("receiver")
//            .type("test.type")
//            .content("p2p test")
//            .requireAck(true)
//            .build();
//
//        MessageAck ack1 = channel.send(p2pMsg).get(2, TimeUnit.SECONDS);
//        assertTrue(ack1.isSuccess(), "消息应该投递成功");
//        System.out.println("✅");
//
//        // 3.2 测试广播消息
//        System.out.print("2. 测试广播消息... ");
//        channel.registerHandler("agent1", new MessageHandler() {
//            @Override
//            public <T> CompletableFuture<Object> handle(AgentMessage<T> message) {
//                return CompletableFuture.completedFuture("ACK1");
//            }
//        });
//        channel.registerHandler("agent2", new MessageHandler() {
//            @Override
//            public <T> CompletableFuture<Object> handle(AgentMessage<T> message) {
//                return CompletableFuture.completedFuture("ACK2");
//            }
//        });
//
//        AgentMessage<String> broadcastMsg = new AgentMessage.Builder<String>()
//            .from("main")
//            .to("*")
//            .type("notification")
//            .content("broadcast test")
//            .build();
//
//        List<MessageAck> acks = channel.broadcast(broadcastMsg).get(2, TimeUnit.SECONDS);
//        assertTrue(acks.size() >= 2, "应该至少有2个接收者");
//        System.out.println("✅ (接收者: " + acks.size() + " 个)");
//
//        // 3.3 测试消息队列
//        System.out.print("3. 测试离线消息队列... ");
//        channel.registerHandler("offline_agent", new MessageHandler() {
//            @Override
//            public <T> CompletableFuture<Object> handle(AgentMessage<T> message) {
//                return CompletableFuture.completedFuture("RECEIVED");
//            }
//        });
//
//        // 先发送消息（此时处理器已注册）
//        AgentMessage<String> queuedMsg = new AgentMessage.Builder<String>()
//            .from("main")
//            .to("offline_agent")
//            .type("query")
//            .content("queued message")
//            .persistent(true)
//            .ttl(60000)
//            .build();
//
//        MessageAck queuedAck = channel.send(queuedMsg).get(2, TimeUnit.SECONDS);
//        assertTrue(queuedAck.isSuccess(), "离线消息应该投递成功");
//        System.out.println("✅");
//
//        // 3.4 测试待处理消息数量
//        System.out.print("4. 测试统计信息... ");
//        int pendingCount = channel.getTotalPendingMessageCount();
//        System.out.println("✅ (待处理消息: " + pendingCount + " 条)");
//
//        channel.shutdown();
//        System.out.println("-----------------------------------");
//        System.out.println();
//    }
//
//    /**
//     * 测试4: 配置属性
//     */
//    private static void testConfiguration() {
//        System.out.println("【测试4】配置属性");
//        System.out.println("-----------------------------------");
//
//        AgentProperties props =
//            new AgentProperties();
//
//        // 4.1 验证默认值
//        System.out.print("1. 验证默认配置... ");
//        assertFalse(props.sharedMemoryEnabled, "共享记忆默认应该未启用");
//        assertFalse(props.eventBusEnabled, "事件总线默认应该未启用");
//        assertFalse(props.messageChannelEnabled, "消息通道默认应该未启用");
//        System.out.println("✅");
//
//        // 4.2 验证共享记忆配置
//        System.out.print("2. 验证共享记忆配置... ");
//        assertNotNull(props.sharedMemory, "共享记忆配置对象不应为null");
//        assertEquals(3600_000L, props.sharedMemory.shortTermTtl, "短期记忆TTL应该匹配");
//        assertEquals(7 * 24 * 3600_000L, props.sharedMemory.longTermTtl, "长期记忆TTL应该匹配");
//        assertTrue(props.sharedMemory.persistOnWrite, "写入时应该持久化");
//        assertEquals(1000, props.sharedMemory.maxShortTermCount, "短期记忆最大数量应该匹配");
//        System.out.println("✅");
//
//        // 4.3 验证事件总线配置
//        System.out.print("3. 验证事件总线配置... ");
//        assertNotNull(props.eventBus, "事件总线配置对象不应为null");
//        assertEquals(1000, props.eventBus.maxHistorySize, "事件历史最大数量应该匹配");
//        assertEquals(5, props.eventBus.defaultPriority, "默认优先级应该匹配");
//        assertEquals(30, props.eventBus.timeoutSeconds, "超时时间应该匹配");
//        System.out.println("✅");
//
//        // 4.4 验证消息通道配置
//        System.out.print("4. 验证消息通道配置... ");
//        assertNotNull(props.messageChannel, "消息通道配置对象不应为null");
//        assertEquals(60_000L, props.messageChannel.defaultTtl, "默认TTL应该匹配");
//        assertEquals(1000, props.messageChannel.maxQueueSize, "最大队列长度应该匹配");
//        assertTrue(props.messageChannel.persistMessages, "消息应该持久化");
//        System.out.println("✅");
//
//        System.out.println("-----------------------------------");
//        System.out.println();
//    }
//
//    // ========== 辅助方法 ==========
//
//    private static void assertTrue(boolean condition, String message) {
//        if (!condition) {
//            throw new AssertionError("断言失败: " + message);
//        }
//    }
//
//    private static void assertFalse(boolean condition, String message) {
//        if (condition) {
//            throw new AssertionError("断言失败: " + message);
//        }
//    }
//
//    private static void assertEquals(Object expected, Object actual, String message) {
//        if (expected == null && actual == null) {
//            return;
//        }
//        if (expected != null && expected.equals(actual)) {
//            return;
//        }
//        throw new AssertionError("断言失败: " + message +
//                               " (期望: " + expected + ", 实际: " + actual + ")");
//    }
//
//    private static void assertNotNull(Object obj, String message) {
//        if (obj == null) {
//            throw new AssertionError("断言失败: " + message);
//        }
//    }
//}
