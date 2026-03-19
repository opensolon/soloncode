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
package org.noear.solon.bot.core.message;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 消息通道
 *
 * 负责代理之间的点对点和广播通信
 *
 * @author bai
 * @since 3.9.5
 */
public class MessageChannel {
    private static final Logger LOG = LoggerFactory.getLogger(MessageChannel.class);

    // 消息处理器注册表：agentId -> handlers
    private final Map<String, List<MessageHandlerWrapper>> handlers = new ConcurrentHashMap<>();

    // 消息队列（用于持久化消息）- 泛型支持
    private final Map<String, Queue<AgentMessage<?>>> pendingMessages = new ConcurrentHashMap<>();

    // 确认队列（用于需要确认的消息）
    private final Map<String, CompletableFuture<MessageAck>> ackFutures = new ConcurrentHashMap<>();

    // 异步执行器
    private final ScheduledExecutorService executor;

    // 消息持久化路径
    private final String messageStorePath;

    // 性能优化：消息队列大小限制
    private static final int MAX_PENDING_MESSAGES = 1000;  // 每个 Agent 最多 1000 条待处理消息

    /**
     * 构造函数（使用默认配置）
     */
    public MessageChannel(String workDir) {
        this(workDir, 4);
    }

    /**
     * 完整构造函数
     *
     * @param workDir 工作目录（应该是完整的消息存储路径，例如：workDir/.soloncode/memory）
     * @param asyncThreads 异步处理线程数
     */
    public MessageChannel(String workDir, int asyncThreads) {
        // workDir 应该已经是完整的路径（例如：workDir/.soloncode/memory）
        // 不再重复拼接 ".soloncode"
        this.messageStorePath = workDir + File.separator + "messages" + File.separator;
        this.executor = Executors.newScheduledThreadPool(asyncThreads, r -> {
            Thread t = new Thread(r, "MessageChannel-Thread");
            t.setDaemon(true);
            return t;
        });

        // 初始化存储目录
        initStorage();

        // 启动定期清理任务
        this.executor.scheduleAtFixedRate(
            this::cleanupExpiredMessages,
            1, 1, TimeUnit.MINUTES
        );

        LOG.info("消息通道初始化完成");
    }

    /**
     * 注册消息处理器
     *
     * @param agentId 代理ID
     * @param handler 消息处理器
     * @return 处理器ID
     */
    public String registerHandler(String agentId, MessageHandler handler) {
        String handlerId = UUID.randomUUID().toString();

        MessageHandlerWrapper wrapper = new MessageHandlerWrapper(handlerId, agentId, handler);
        handlers.computeIfAbsent(agentId, k -> new CopyOnWriteArrayList<>())
                .add(wrapper);

        LOG.debug("消息处理器已注册: agentId={}, handlerId={}", agentId, handlerId);

        // 处理待处理消息
        processPendingMessages(agentId);

        return handlerId;
    }

    /**
     * 注销消息处理器
     *
     * @param agentId 代理ID
     * @param handlerId 处理器ID
     */
    public void unregisterHandler(String agentId, String handlerId) {
        List<MessageHandlerWrapper> agentHandlers = handlers.get(agentId);
        if (agentHandlers != null) {
            agentHandlers.removeIf(wrapper -> wrapper.getHandlerId().equals(handlerId));
            LOG.debug("消息处理器已注销: agentId={}, handlerId={}", agentId, handlerId);
        }
    }

    /**
     * 发送点对点消息（类型安全）
     *
     * @param message 泛型消息对象
     * @param <T> 消息内容类型
     * @return 异步结果（如果需要确认，返回确认对象）
     */
    public <T> CompletableFuture<MessageAck> send(AgentMessage<T> message) {
        String to = message.getTo();

        // 查找接收者的处理器
        List<MessageHandlerWrapper> receivers = handlers.get(to);

        if (receivers == null || receivers.isEmpty()) {
            // 没有注册的处理器
            String persistent = message.getMetadata("persistent", "false");
            if ("true".equals(persistent)) {
                // 持久化消息
                return queueMessage(message);
            } else {
                LOG.warn("无接收者: to={}", to);
                return CompletableFuture.completedFuture(
                    new MessageAck(message.getId(), to, false, "No receiver", null)
                );
            }
        }

        // 处理消息
        return deliverMessage(message, receivers);
    }

    /**
     * 广播消息（类型安全）
     *
     * @param message 泛型消息对象
     * @param <T> 消息内容类型
     * @return 所有接收者的确认列表
     */
    public <T> CompletableFuture<List<MessageAck>> broadcast(AgentMessage<T> message) {
        List<CompletableFuture<MessageAck>> futures = new ArrayList<>();

        // 向所有注册的代理（除了发送者）发送消息
        handlers.keySet().stream()
            .filter(agentId -> !agentId.equals(message.getFrom()))
            .forEach(agentId -> {
                // 为每个接收者创建副本（修改 to 字段）
                AgentMessage<T> copy = message.toBuilder()
                        .to(agentId)
                        .build();
                futures.add(send(copy));
            });

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList())
            );
    }

    /**
     * 请求-响应模式（类型安全）
     *
     * @param from 发送者
     * @param to 接收者
     * @param type 消息类型
     * @param payload 消息内容
     * @param <T> 消息内容类型
     * @return 响应结果
     */
    public <T> CompletableFuture<Object> request(String from, String to, String type, T payload) {
        AgentMessage<T> message = AgentMessage.<T>of(payload)
                .from(from)
                .to(to)
                .type(type)
                .metadata("requireAck", "true")
                .build();

        return send(message)
            .thenCompose(ack -> {
                if (!ack.isSuccess()) {
                    // Java 8 兼容
                    CompletableFuture<Object> failedFuture = new CompletableFuture<>();
                    failedFuture.completeExceptionally(
                        new RuntimeException("Message delivery failed: " + ack.getMessage())
                    );
                    return failedFuture;
                }
                // 返回 handler 的处理结果
                return CompletableFuture.completedFuture(ack.getResponse());
            });
    }

    /**
     * 获取待处理消息数量
     *
     * @param agentId 代理ID
     * @return 待处理消息数量
     */
    public int getPendingMessageCount(String agentId) {
        Queue<AgentMessage<?>> queue = pendingMessages.get(agentId);
        return queue != null ? queue.size() : 0;
    }

    /**
     * 获取所有待处理消息数量
     *
     * @return 待处理消息总数
     */
    public int getTotalPendingMessageCount() {
        return pendingMessages.values().stream()
            .mapToInt(Queue::size)
            .sum();
    }

    /**
     * 关闭消息通道
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            LOG.info("消息通道已关闭");
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ========== 私有方法 ==========

    /**
     * 投递消息（支持泛型）
     */
    private <T> CompletableFuture<MessageAck> deliverMessage(AgentMessage<T> message, List<MessageHandlerWrapper> receivers) {

        // 处理重试逻辑
        int retryTimes = message.getIntMetadata("retryTimes", 0);
        long retryDelay = message.getIntMetadata("retryDelay", 1000);

        return CompletableFuture.anyOf(receivers.stream()
                        .map(wrapper -> wrapper.handle(message)).toArray(CompletableFuture[]::new))
            .thenApply(result -> {
                // 将 handler 的处理结果存储到 MessageAck 中
                MessageAck ack = new MessageAck(message.getId(), message.getTo(), true, null, result);

                // 如果需要确认，记录确认
                boolean requireAck = message.getBooleanMetadata("requireAck", false);
                if (requireAck) {
                    CompletableFuture<MessageAck> future = ackFutures.remove(message.getId());
                    if (future != null) {
                        future.complete(ack);
                    }
                }

                // 持久化已发送的消息
                boolean persistent = message.getBooleanMetadata("persistent", false);
                if (persistent) {
                    persistMessage(message);
                }

                return ack;
            })
            .exceptionally(ex -> {
                LOG.warn("消息投递失败: messageId={}, to={}, error={}",
                         message.getId(), message.getTo(), ex.getMessage());

                // 重试
                if (retryTimes > 0) {
                    LOG.info("重试发送消息: messageId={}, retryTimes={}",
                            message.getId(), retryTimes);

                    CompletableFuture<MessageAck> retryFuture = new CompletableFuture<>();
                    executor.schedule(() -> {
                        // 创建修改了 retryTimes 的消息副本
                        AgentMessage<T> retryMessage = message.toBuilder()
                                .metadata("retryTimes", String.valueOf(retryTimes - 1))
                                .build();
                        deliverMessage(retryMessage, receivers).whenComplete((ack, throwable) -> {
                            if (throwable != null) {
                                retryFuture.completeExceptionally(throwable);
                            } else {
                                retryFuture.complete(ack);
                            }
                        });
                    }, retryDelay, TimeUnit.MILLISECONDS);

                    try {
                        return retryFuture.get();
                    } catch (InterruptedException | ExecutionException e) {
                        Thread.currentThread().interrupt();
                        return new MessageAck(message.getId(), message.getTo(), false, e.getMessage(), null);
                    }
                }

                return new MessageAck(message.getId(), message.getTo(), false, ex.getMessage(), null);
            });
    }

    /**
     * 将消息放入待处理队列（支持泛型）
     * 性能优化：添加队列大小限制
     */
    private <T> CompletableFuture<MessageAck> queueMessage(AgentMessage<T> message) {
        String to = message.getTo();
        Queue<AgentMessage<?>> queue = pendingMessages.computeIfAbsent(to, k -> new LinkedList<>());

        // 检查队列大小限制
        if (queue.size() >= MAX_PENDING_MESSAGES) {
            LOG.warn("消息队列已满: to={}, maxSize={}, messageId={}",
                    to, MAX_PENDING_MESSAGES, message.getId());
            CompletableFuture<MessageAck> failedFuture = new CompletableFuture<>();
            failedFuture.complete(new MessageAck(
                    message.getId(),
                    to,
                    false,
                    "Message queue full (max: " + MAX_PENDING_MESSAGES + ")",
                    null
            ));
            return failedFuture;
        }

        queue.offer(message);

        // 持久化消息
        persistMessage(message);

        LOG.debug("消息已加入待处理队列: to={}, messageId={}, queueSize={}",
                to, message.getId(), queue.size());

        CompletableFuture<MessageAck> future = new CompletableFuture<>();
        boolean requireAck = message.getBooleanMetadata("requireAck", false);
        if (requireAck) {
            ackFutures.put(message.getId(), future);
        } else {
            future.complete(new MessageAck(message.getId(), to, true, "Queued", null));
        }

        return future;
    }

    /**
     * 处理待处理消息（支持泛型）
     */
    private void processPendingMessages(String agentId) {
        Queue<AgentMessage<?>> queue = pendingMessages.get(agentId);
        if (queue == null || queue.isEmpty()) {
            return;
        }

        List<MessageHandlerWrapper> receivers = handlers.get(agentId);
        if (receivers == null || receivers.isEmpty()) {
            return;
        }

        LOG.info("处理待处理消息: agentId={}, pendingCount={}", agentId, queue.size());

        AgentMessage<?> message;
        while ((message = queue.poll()) != null) {
            deliverMessage(message, receivers);
        }
    }

    /**
     * 持久化消息（支持泛型）
     */
    private <T> void persistMessage(AgentMessage<T> message) {
        CompletableFuture.runAsync(() -> {
            try {
                File dir = new File(messageStorePath);
                if (!dir.exists()) {
                    dir.mkdirs();
                }

                String filePath = messageStorePath + message.getId() + ".json";
                String json = toJson(message);
                Files.write(Paths.get(filePath), json.getBytes(StandardCharsets.UTF_8));

            } catch (Throwable e) {
                LOG.warn("消息持久化失败: messageId={}, error={}", message.getId(), e.getMessage());
            }
        }, executor);
    }

    /**
     * 简单的JSON序列化（支持泛型）
     */
    private <T> String toJson(AgentMessage<T> message) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"id\":\"").append(escapeJson(message.getId())).append("\",");
        sb.append("\"from\":\"").append(escapeJson(message.getFrom())).append("\",");
        sb.append("\"to\":\"").append(escapeJson(message.getTo())).append("\",");
        sb.append("\"type\":\"").append(escapeJson(message.getType())).append("\",");
        sb.append("\"content\":\"").append(escapeJson(String.valueOf(message.getContent()))).append("\",");
        sb.append("\"timestamp\":").append(message.getTimestamp()).append(",");
        sb.append("\"persistent\":").append(message.getBooleanMetadata("persistent", false));

        // 添加元数据
        if (!message.getMetadata().isEmpty()) {
            sb.append(",\"metadata\":{");
            boolean first = true;
            for (Map.Entry<String, String> entry : message.getMetadata().entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
                sb.append("\"").append(escapeJson(entry.getValue())).append("\"");
                first = false;
            }
            sb.append("}");
        }

        sb.append("}");
        return sb.toString();
    }

    /**
     * 清理过期消息（新 API - 支持泛型）
     */
    private void cleanupExpiredMessages() {
        long now = System.currentTimeMillis();
        int removed = 0;

        // 清理待处理队列中的过期消息
        for (Map.Entry<String, Queue<AgentMessage<?>>> entry : pendingMessages.entrySet()) {
            Queue<AgentMessage<?>> queue = entry.getValue();
            while (!queue.isEmpty()) {
                AgentMessage<?> message = queue.peek();
                int ttl = message.getIntMetadata("ttl", 60000);
                if (now - message.getTimestamp() > ttl) {
                    queue.poll();
                    removed++;
                } else {
                    break;
                }
            }
        }

        // 清理持久化的过期消息文件
        try {
            File dir = new File(messageStorePath);
            if (dir.exists()) {
                File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
                if (files != null) {
                    for (File file : files) {
                        // 简化实现：删除超过1小时的文件
                        if (now - file.lastModified() > 3600_000) {
                            file.delete();
                            removed++;
                        }
                    }
                }
            }
        } catch (Throwable e) {
            LOG.warn("清理过期消息文件失败: error={}", e.getMessage());
        }

        if (removed > 0) {
            LOG.info("清理了 {} 条过期消息", removed);
        }
    }

    /**
     * 初始化存储
     */
    private void initStorage() {
        try {
            File dir = new File(messageStorePath);
            if (!dir.exists()) {
                dir.mkdirs();
            }
        } catch (Throwable e) {
            LOG.warn("消息存储初始化失败: error={}", e.getMessage());
        }
    }

    /**
     * 转义JSON字符串
     */
    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 消息处理器包装器（新 API - 支持泛型）
     */
    @Getter
    private static class MessageHandlerWrapper {
        private final String handlerId;
        private final String agentId;
        private final MessageHandler handler;

        public MessageHandlerWrapper(String handlerId, String agentId, MessageHandler handler) {
            this.handlerId = handlerId;
            this.agentId = agentId;
            this.handler = handler;
        }

        public <T> CompletableFuture<Object> handle(AgentMessage<T> message) {
            try {
                return handler.handle(message);
            } catch (Throwable e) {
                LOG.error("消息处理器异常: handlerId={}, error={}",
                         handlerId, e.getMessage(), e);
                // Java 8 兼容：手动创建失败的 Future
                CompletableFuture<Object> failedFuture = new CompletableFuture<>();
                failedFuture.completeExceptionally(e);
                return failedFuture;
            }
        }

    }
}
