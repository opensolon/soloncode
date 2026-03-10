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
import org.noear.solon.bot.core.message.AgentMessage;
import org.noear.solon.bot.core.message.MessageChannel;
import org.noear.solon.bot.core.message.MessageHandler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 消息传递调试测试
 *
 * @author bai
 * @since 3.9.5
 */
public class MessagePassingDebugTest {

    @Test
    public void testBasicMessageSending() throws Exception {
        System.out.println("=== 基础消息发送测试 ===");

        MessageChannel messageChannel = new MessageChannel("./work/test-message");

        // 注册处理器
        AtomicInteger receiveCount = new AtomicInteger(0);
        messageChannel.registerHandler("receiver", new MessageHandler() {
            @Override
            public <T> CompletableFuture<Object> handle(AgentMessage<T> message) {
                int count = receiveCount.incrementAndGet();
                System.out.println("Handler 被调用 #" + count);
                System.out.println("  From: " + message.getFrom());
                System.out.println("  To: " + message.getTo());
                System.out.println("  Type: " + message.getType());
                System.out.println("  Content: " + message.getContent());

                return CompletableFuture.completedFuture("OK: " + message.getContent());
            }
        });
        System.out.println("✓ 处理器已注册");

        // 等待处理器就绪
        Thread.sleep(100);

        // 发送消息
        System.out.println("发送消息...");
        CompletableFuture<Object> response = messageChannel.request(
                "sender",
                "receiver",
                "test",
                "Hello"
        );

        // 等待响应
        System.out.println("等待响应...");
        Object result = response.get(5, TimeUnit.SECONDS);
        System.out.println("收到响应: " + result);

        assertNotNull(result, "响应不应该为 null");
        assertEquals(1, receiveCount.get(), "处理器应该被调用一次");

        messageChannel.shutdown();
        System.out.println("✓ 测试通过");
    }

    @Test
    public void testMultipleMessages() throws Exception {
        System.out.println("\n=== 多条消息测试 ===");

        MessageChannel messageChannel = new MessageChannel("./work/test-message");

        AtomicInteger receiveCount = new AtomicInteger(0);

        messageChannel.registerHandler("receiver", new MessageHandler() {
            @Override
            public <T> CompletableFuture<Object> handle(AgentMessage<T> message) {
                int count = receiveCount.incrementAndGet();
                System.out.println("收到消息 #" + count + ": " + message.getContent());

                // 模拟处理时间
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                return CompletableFuture.completedFuture("响应 #" + count);
            }
        });

        Thread.sleep(100);

        // 发送3条消息
        for (int i = 1; i <= 3; i++) {
            System.out.println("发送消息 " + i);
            CompletableFuture<Object> response = messageChannel.request(
                    "sender",
                    "receiver",
                    "query",
                    "消息 " + i
            );

            Object result = response.get(5, TimeUnit.SECONDS);
            System.out.println("收到响应: " + result);
            assertNotNull(result);
        }

        assertEquals(3, receiveCount.get(), "应该收到3条消息");

        messageChannel.shutdown();
        System.out.println("✓ 测试通过");
    }
}
