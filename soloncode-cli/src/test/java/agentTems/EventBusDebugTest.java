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
//import org.junit.jupiter.api.Test;
//import org.noear.solon.codecli.core.teams.event.AgentEvent;
//import org.noear.solon.codecli.core.teams.event.AgentEventType;
//import org.noear.solon.codecli.core.teams.event.EventHandler;
//import org.noear.solon.codecli.core.teams.event.EventBus;
//import org.noear.solon.codecli.core.teams.event.EventMetadata;
//
//import java.util.concurrent.CompletableFuture;
//import java.util.concurrent.TimeUnit;
//import java.util.concurrent.atomic.AtomicInteger;
//
//import static org.junit.jupiter.api.Assertions.*;
//
///**
// * EventBus 调试测试
// *
// * @author bai
// * @since 3.9.5
// */
//public class EventBusDebugTest {
//
//    @Test
//    public void testBasicEventPublish() throws Exception {
//        System.out.println("=== 基础事件发布测试 ===");
//
//        EventBus eventBus = new EventBus();
//
//        AtomicInteger handlerCallCount = new AtomicInteger(0);
//        CompletableFuture<String> eventReceived = new CompletableFuture<>();
//
//        // 订阅事件
//        String subscriptionId = eventBus.subscribe(AgentEventType.TASK_CREATED, event -> {
//            int count = handlerCallCount.incrementAndGet();
//            System.out.println("Handler 被调用 #" + count);
//            System.out.println("  Event Type: " + event.getEventType());
//            System.out.println("  Payload: " + event.getPayload());
//            System.out.println("  Source Agent: " + event.getMetadata().getSourceAgent());
//
//            eventReceived.complete((String) event.getPayload());
//
//            return CompletableFuture.completedFuture(EventHandler.Result.success());
//        });
//
//        System.out.println("订阅ID: " + subscriptionId);
//        System.out.println("订阅者数量: " + eventBus.getSubscriberCount(AgentEventType.TASK_CREATED));
//
//        // 等待订阅生效
//        Thread.sleep(200);
//
//        // 发布事件
//        System.out.println("发布事件...");
//        AgentEvent event = new AgentEvent(
//                AgentEventType.TASK_CREATED,
//                "测试任务",
//                EventMetadata.builder()
//                        .sourceAgent("test-agent")
//                        .taskId("task-1")
//                        .build()
//        );
//
//        eventBus.publish(event);
//        System.out.println("事件已发布");
//
//        // 等待处理器接收
//        String result = eventReceived.get(3, TimeUnit.SECONDS);
//        System.out.println("收到结果: " + result);
//
//        assertNotNull(result);
//        assertEquals("测试任务", result);
//        assertEquals(1, handlerCallCount.get());
//
//        eventBus.shutdown();
//        System.out.println("✓ 测试通过");
//    }
//
//    @Test
//    public void testMultipleSubscribers() throws Exception {
//        System.out.println("\n=== 多订阅者测试 ===");
//
//        EventBus eventBus = new EventBus();
//
//        AtomicInteger handler1Count = new AtomicInteger(0);
//        AtomicInteger handler2Count = new AtomicInteger(0);
//
//        // 订阅者1
//        eventBus.subscribe(AgentEventType.TASK_PROGRESS, event -> {
//            handler1Count.incrementAndGet();
//            System.out.println("订阅者1 收到事件");
//            return CompletableFuture.completedFuture(EventHandler.Result.success());
//        });
//
//        // 订阅者2
//        eventBus.subscribe(AgentEventType.TASK_PROGRESS, event -> {
//            handler2Count.incrementAndGet();
//            System.out.println("订阅者2 收到事件");
//            return CompletableFuture.completedFuture(EventHandler.Result.success());
//        });
//
//        System.out.println("订阅者数量: " + eventBus.getSubscriberCount(AgentEventType.TASK_PROGRESS));
//        Thread.sleep(200);
//
//        // 发布事件
//        AgentEvent event = new AgentEvent(
//                AgentEventType.TASK_PROGRESS,
//                "任务进度更新",
//                EventMetadata.builder()
//                        .sourceAgent("main-agent")
//                        .taskId("task-2")
//                        .build()
//        );
//
//        eventBus.publish(event);
//        System.out.println("事件已发布");
//
//        // 等待处理器执行
//        Thread.sleep(500);
//
//        assertEquals(1, handler1Count.get(), "订阅者1应该被调用1次");
//        assertEquals(1, handler2Count.get(), "订阅者2应该被调用1次");
//
//        eventBus.shutdown();
//        System.out.println("✓ 测试通过");
//    }
//
//    @Test
//    public void testAsyncPublish() throws Exception {
//        System.out.println("\n=== 异步发布测试 ===");
//
//        EventBus eventBus = new EventBus();
//
//        CompletableFuture<String> eventReceived = new CompletableFuture<>();
//
//        eventBus.subscribe(AgentEventType.TASK_COMPLETED, event -> {
//            System.out.println("收到完成事件: " + event.getPayload());
//            eventReceived.complete((String) event.getPayload());
//            return CompletableFuture.completedFuture(EventHandler.Result.success());
//        });
//
//        Thread.sleep(200);
//
//        // 异步发布
//        AgentEvent event = new AgentEvent(
//                AgentEventType.TASK_COMPLETED,
//                "任务已完成",
//                EventMetadata.builder()
//                        .sourceAgent("worker-agent")
//                        .taskId("task-3")
//                        .build()
//        );
//
//        CompletableFuture<Void> publishFuture = eventBus.publishAsync(event);
//        System.out.println("异步发布已启动");
//
//        // 等待发布完成
//        publishFuture.get(2, TimeUnit.SECONDS);
//        System.out.println("发布完成");
//
//        // 等待接收
//        String result = eventReceived.get(2, TimeUnit.SECONDS);
//        assertEquals("任务已完成", result);
//
//        eventBus.shutdown();
//        System.out.println("✓ 测试通过");
//    }
//}
