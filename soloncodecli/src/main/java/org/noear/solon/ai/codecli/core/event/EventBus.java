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
package org.noear.solon.ai.codecli.core.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 事件总线
 *
 * 负责事件的发布、订阅、路由和分发
 *
 * @author bai
 * @since 3.9.5
 */
public class EventBus {
    private static final Logger LOG = LoggerFactory.getLogger(EventBus.class);

    // 订阅者注册表：eventType -> handlers
    private final Map<AgentEventType, List<EventHandlerWrapper>> subscribers = new ConcurrentHashMap<>();

    // 异步执行器
    private final ExecutorService executor;

    // 事件过滤器
    private final List<EventFilter> filters = new CopyOnWriteArrayList<>();

    // 事件历史（用于调试，可配置）
    private final Queue<AgentEvent> eventHistory;
    private final int maxHistorySize;

    /**
     * 构造函数（使用默认配置）
     */
    public EventBus() {
        this(Runtime.getRuntime().availableProcessors(), 1000);
    }

    /**
     * 完整构造函数
     *
     * @param asyncThreads 异步处理线程数
     * @param maxHistorySize 事件历史最大数量
     */
    public EventBus(int asyncThreads, int maxHistorySize) {
        this.executor = Executors.newFixedThreadPool(asyncThreads, r -> {
            Thread t = new Thread(r, "EventBus-Thread");
            t.setDaemon(true);
            return t;
        });
        this.maxHistorySize = maxHistorySize;
        this.eventHistory = new LinkedList<>();

        LOG.info("事件总线初始化: asyncThreads={}, maxHistorySize={}", asyncThreads, maxHistorySize);
    }

    /**
     * 订阅事件
     *
     * @param eventType 事件类型（支持通配符 *）
     * @param handler 事件处理器
     * @return 订阅ID（用于取消订阅）
     */
    public String subscribe(AgentEventType eventType, EventHandler handler) {
        String subscriptionId = UUID.randomUUID().toString();

        EventHandlerWrapper wrapper = new EventHandlerWrapper(subscriptionId, eventType, handler);

        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                   .add(wrapper);

        LOG.debug("新订阅: eventType={}, subscriptionId={}", eventType, subscriptionId);
        return subscriptionId;
    }

    /**
     * 取消订阅
     *
     * @param subscriptionId 订阅ID
     */
    public void unsubscribe(String subscriptionId) {
        subscribers.values().forEach(handlers ->
            handlers.removeIf(wrapper -> wrapper.getSubscriptionId().equals(subscriptionId))
        );

        LOG.debug("取消订阅: subscriptionId={}", subscriptionId);
    }

    /**
     * 发布事件（异步）
     *
     * @param event 事件对象
     * @return CompletableFuture
     */
    public CompletableFuture<Void> publishAsync(AgentEvent event) {
        return CompletableFuture.runAsync(() -> publish(event), executor);
    }

    /**
     * 发布事件（同步）
     *
     * @param event 事件对象
     */
    public void publish(AgentEvent event) {
        try {
            // 1. 记录历史
            recordHistory(event);

            // 2. 应用过滤器
            for (EventFilter filter : filters) {
                if (!filter.test(event)) {
                    LOG.debug("事件被过滤器拦截: eventType={}", event.getEventType());
                    return;
                }
            }

            // 3. 查找匹配的订阅者
            List<EventHandlerWrapper> matchedHandlers = findHandlers(event.getEventType());

            if (matchedHandlers.isEmpty()) {
                LOG.debug("无订阅者处理事件: eventType={}", event.getEventType());
                return;
            }

            // 4. 分发事件（异步）

            // 5. 等待所有处理完成（带超时）
            CompletableFuture.allOf(matchedHandlers.stream()
                            .map(wrapper -> wrapper.handle(event)).toArray(CompletableFuture[]::new))
                .whenComplete((v, ex) -> {
                    if (ex != null) {
                        LOG.warn("事件处理超时或失败: eventType={}, error={}",
                                event.getEventType(), ex.getMessage());
                    }
                });

            LOG.debug("事件已发布: eventType={}, handlers={}",
                     event.getEventType(), matchedHandlers.size());

        } catch (Exception e) {
            LOG.error("事件发布失败: eventType={}, error={}",
                     event.getEventType(), e.getMessage(), e);
        }
    }

    /**
     * 添加事件过滤器
     *
     * @param filter 事件过滤器
     */
    public void addFilter(EventFilter filter) {
        filters.add(filter);
        LOG.info("事件过滤器已添加: {}", filter.getClass().getSimpleName());
    }

    /**
     * 移除事件过滤器
     *
     * @param filter 事件过滤器
     */
    public void removeFilter(EventFilter filter) {
        filters.remove(filter);
        LOG.info("事件过滤器已移除: {}", filter.getClass().getSimpleName());
    }

    /**
     * 获取事件历史
     *
     * @param limit 最大数量
     * @return 事件列表
     */
    public List<AgentEvent> getEventHistory(int limit) {
        synchronized (eventHistory) {
            return eventHistory.stream()
                .limit(limit)
                .collect(Collectors.toList());
        }
    }

    /**
     * 获取订阅者数量
     *
     * @return 订阅者数量
     */
    public int getSubscriberCount() {
        return subscribers.values().stream()
            .mapToInt(List::size)
            .sum();
    }

    /**
     * 获取指定事件类型的订阅者数量
     *
     * @param eventType 事件类型
     * @return 订阅者数量
     */
    public int getSubscriberCount(AgentEventType eventType) {
        List<EventHandlerWrapper> handlers = subscribers.get(eventType);
        return handlers != null ? handlers.size() : 0;
    }

    /**
     * 清空所有订阅
     */
    public void clearSubscribers() {
        subscribers.clear();
        LOG.info("所有订阅已清空");
    }

    /**
     * 关闭事件总线
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            LOG.info("事件总线已关闭");
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ========== 私有方法 ==========

    /**
     * 查找匹配的处理器
     */
    private List<EventHandlerWrapper> findHandlers(AgentEventType eventType) {

        // 精确匹配
        List<EventHandlerWrapper> handlers = new ArrayList<>(subscribers.getOrDefault(eventType, Collections.emptyList()));

        // 通配符匹配（例如: "task.*" 匹配 "task.started"）
        subscribers.entrySet().stream()
            .filter(entry -> isWildcardMatch(entry.getKey().getCode(), eventType))
            .forEach(entry -> handlers.addAll(entry.getValue()));

        return handlers;
    }

    /**
     * 通配符匹配
     */
    private boolean isWildcardMatch(String pattern, AgentEventType eventType) {
        if (pattern.equals("*")) {
            return true;
        }

        if (pattern.endsWith(".*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return eventType.getCode().startsWith(prefix + ".");
        }

        return false;
    }

    /**
     * 记录事件历史
     */
    private void recordHistory(AgentEvent event) {
        synchronized (eventHistory) {
            eventHistory.offer(event);
            while (eventHistory.size() > maxHistorySize) {
                eventHistory.poll();
            }
        }
    }

    /**
     * 事件处理器包装器
     */
    private static class EventHandlerWrapper {
        private final String subscriptionId;
        private final AgentEventType eventType;
        private final EventHandler handler;

        public EventHandlerWrapper(String subscriptionId, AgentEventType eventType, EventHandler handler) {
            this.subscriptionId = subscriptionId;
            this.eventType = eventType;
            this.handler = handler;
        }

        public CompletableFuture<EventHandler.Result> handle(AgentEvent event) {
            try {
                return handler.handle(event);
            } catch (Exception e) {
                LOG.error("事件处理器异常: subscriptionId={}, error={}",
                         subscriptionId, e.getMessage(), e);
                return CompletableFuture.completedFuture(
                    EventHandler.Result.failure(e.getMessage())
                );
            }
        }

        public String getSubscriptionId() {
            return subscriptionId;
        }

        public AgentEventType getEventType() {
            return eventType;
        }

        public EventHandler getHandler() {
            return handler;
        }
    }
}
