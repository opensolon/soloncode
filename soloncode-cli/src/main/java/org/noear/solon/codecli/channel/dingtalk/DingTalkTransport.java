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
package org.noear.solon.codecli.channel.dingtalk;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

import org.noear.java_websocket.client.SimpleWebSocketClient;
import org.noear.snack4.ONode;
import org.noear.solon.codecli.workspace.WorkspaceLogRouter;
import org.noear.solon.core.util.RunUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程级钉钉 Stream 传输层。
 *
 * <p>持有 {@code appKey -> WebSocket 连接} 的归属，负责协议解析、ACK/pong 应答与重连；
 * 不含任何绑定/路由逻辑——收到业务事件后统一回调 {@link Sink}，由进程级
 * {@code ImGateway} 完成「绑定归属 + 消息投递」。</p>
 *
 * <p>与旧实现的关键差异：连接不再随工作区 {@code WorkspaceContext} 关闭而断开，
 * 也不随工作区激活而重建。同一 appKey 在进程内至多一条连接，工作区生命周期
 * 与连接生命周期彻底解耦（LRU 释放工作区不会掉线）。</p>
 *
 * @author noear 2026/5/9 created
 */
public class DingTalkTransport {
    private static final Logger LOG = LoggerFactory.getLogger(DingTalkTransport.class);

    /**
     * 事件回调：由进程级网关实现，负责绑定与投递。
     */
    public interface Sink {
        /** 收到文本消息。 */
        void onDingTalkText(String appKey, String appSecret, String userId, String text, String messageId);

        /** 收到非文本消息（回执提示由实现方负责）。 */
        void onDingTalkNonText(String appKey, String appSecret, String userId);

        /** 某 appKey 连接建立/重连成功（用于清空该 appKey 的消息去重缓存）。 */
        void onDingTalkConnected(String appKey);
    }

    private final Sink sink;
    /** workspaceId -> 物理路径，仅用于把连接线程日志归属到工作区文件。 */
    private final Function<String, String> pathResolver;

    /**
     * appKey -> StreamConnection（每个 appKey 独立一条 WebSocket 连接）
     */
    private final Map<String, StreamConnection> connections = new ConcurrentHashMap<>();

    public DingTalkTransport(Sink sink, Function<String, String> pathResolver) {
        this.sink = sink;
        this.pathResolver = pathResolver;
    }

    // ==================== 状态查询 ====================

    /** 是否存在已建立连接的 appKey。 */
    public boolean isStarted() {
        return connections.values().stream().anyMatch(c -> c.streamStarted);
    }

    /** 指定 appKey 是否已在本进程持有连接。 */
    public boolean isAppInUse(String appKey) {
        return appKey != null && connections.containsKey(appKey);
    }

    // ==================== 连接生命周期 ====================

    /**
     * 启动（或复用）指定 appKey 的连接。
     *
     * @return 连接可用时返回 true
     */
    public synchronized boolean startStream(String appKey, String appSecret,
                                            String workspaceId, String sessionId) {
        if (appKey == null || appKey.isEmpty() || appSecret == null || appSecret.isEmpty()) {
            LOG.warn("[DingTalk] startStream: appKey or appSecret is empty");
            return false;
        }
        return getOrCreateConnection(appKey, appSecret, workspaceId, sessionId) != null;
    }

    /**
     * 获取或创建指定 appKey 的 Stream 连接（已存在则复用）。
     */
    private synchronized StreamConnection getOrCreateConnection(String appKey, String appSecret,
                                                                String workspaceId, String sessionId) {
        StreamConnection conn = connections.get(appKey);
        if (conn != null) {
            return conn;
        }

        StreamConnection created = new StreamConnection(appKey, appSecret, workspaceId, sessionId,
                resolvePath(workspaceId));
        StreamConnection existing = connections.putIfAbsent(appKey, created);
        if (existing != null) {
            return existing;
        }

        created.start();
        return created;
    }

    /**
     * 关闭某 appKey 的连接（断开 WS、停止线程）。
     */
    public void stopConnection(String appKey) {
        StreamConnection conn = connections.remove(appKey);
        if (conn != null) {
            conn.stop();
        }
    }

    /** 断开全部连接（进程退出时调用）。 */
    public void stopAll() {
        for (String appKey : new ArrayList<>(connections.keySet())) {
            stopConnection(appKey);
        }
    }

    // ==================== 提示发送（不依赖会话绑定） ====================

    /**
     * 直接向指定用户发送一条轻量提示（不依赖会话绑定，走 OpenAPI）。
     */
    public static void sendHint(String appKey, String appSecret, String userId, String text) {
        if (appKey == null || appSecret == null || userId == null || text == null) {
            return;
        }
        RunUtil.async(() -> {
            try {
                String token = DingTalkClient.getAccessToken(appKey, appSecret);
                if (token != null) {
                    DingTalkClient.sendSingleMarkdownMessage(token, appKey, userId, "提示", text);
                }
            } catch (Exception e) {
                LOG.warn("[DingTalk] Hint send error: {}", e.getMessage());
            }
        });
    }

    private String resolvePath(String workspaceId) {
        try {
            return pathResolver == null ? null : pathResolver.apply(workspaceId);
        } catch (Exception e) {
            return null;
        }
    }

    private static String appKeyForLog(String appKey) {
        return appKey == null ? "null" : appKey.substring(0, Math.min(8, appKey.length())) + "...";
    }

    private Runnable withLogKey(String workspacePath, Runnable runnable) {
        if (workspacePath == null || workspacePath.isEmpty()) {
            return runnable;
        }
        return WorkspaceLogRouter.withWorkspaceLogKey(workspacePath, runnable);
    }

    // ==================== WS 消息处理 ====================

    /**
     * 处理 StreamConnection 收到的 WS 消息
     */
    private void onWsMessage(String message, StreamConnection conn) {
        try {
            ONode msg = ONode.ofJson(message);
            String type = msg.get("type").getString();

            if ("SYSTEM".equals(type)) {
                ONode headers = msg.get("headers");
                String topic = headers != null ? headers.get("topic").getString() : null;
                String messageId = headers != null ? headers.get("messageId").getString() : null;

                if ("ping".equals(topic)) {
                    ONode pong = new ONode();
                    pong.set("code", 200);
                    ONode pongHeaders = pong.getOrNew("headers");
                    pongHeaders.set("messageId", messageId);
                    pongHeaders.set("contentType", "application/json");
                    pong.set("data", "{}");
                    if (conn.wsClient != null) {
                        conn.wsClient.send(pong.toJson());
                    }
                    LOG.debug("[DingTalk] Replied pong");
                } else if ("disconnect".equals(topic)) {
                    LOG.info("[DingTalk] Received disconnect command, will reconnect...");
                    conn.streamStarted = false;
                    conn.scheduleReconnect();
                }
                return;
            }

            if ("CALLBACK".equals(type)) {
                ONode headers = msg.get("headers");
                String messageId = headers != null ? headers.get("messageId").getString() : null;

                // 回复 ACK（仅确认收到，后续 AI 回复通过 OpenAPI 发送）
                ONode ack = new ONode();
                ack.set("code", 200);
                ONode ackHeaders = ack.getOrNew("headers");
                ackHeaders.set("messageId", messageId);
                ackHeaders.set("contentType", "application/json");
                ack.set("data", "{}");
                if (conn.wsClient != null) {
                    conn.wsClient.send(ack.toJson());
                }

                // 解析业务数据
                String data = msg.get("data").getString();
                if (data != null && !data.isEmpty()) {
                    onBotMessageParsed(ONode.ofJson(data), conn);
                }
            }
        } catch (Exception e) {
            LOG.error("[DingTalk] onWsMessage error: {}", e.getMessage(), e);
        }
    }

    /**
     * 解析并处理钉钉机器人消息（只做协议解析，绑定与投递交给进程级网关）。
     */
    private void onBotMessageParsed(ONode botMsg, StreamConnection conn) {
        String userId = botMsg.get("senderStaffId").getString();
        if (userId == null || userId.isEmpty()) {
            userId = botMsg.get("senderId").getString();
        }
        if (userId == null || userId.isEmpty()) {
            return;
        }

        String text = null;
        ONode textNode = botMsg.get("text");
        if (textNode != null && textNode.isNull() == false) {
            text = textNode.get("content").getString();
        }
        if (text == null || text.isEmpty()) {
            ONode contentNode = botMsg.get("content");
            if (contentNode != null && contentNode.isNull() == false) {
                text = contentNode.get("content").getString();
            }
        }

        String msgId = botMsg.get("msgId").getString();

        if (text == null || text.isEmpty()) {
            sink.onDingTalkNonText(conn.appKey, conn.appSecret, userId);
            return;
        }

        sink.onDingTalkText(conn.appKey, conn.appSecret, userId, text, msgId);
    }

    // ==================== 内部连接类（每个 appKey 独立一条连接） ====================

    /**
     * 单个 appKey 的 WebSocket 长连接。
     * 封装连接生命周期、心跳、重连等连接级状态；连接随进程存续。
     */
    private class StreamConnection {
        final String appKey;
        final String appSecret;
        /** 启动连接时登记的工作区/会话，仅用于日志与诊断。 */
        final String workspaceId;
        final String sessionId;
        /** 仅用于线程日志归属。 */
        final String workspacePath;

        volatile SimpleWebSocketClient wsClient;
        volatile boolean streamStarted = false;
        volatile Thread streamThread;
        volatile Thread reconnectThread;
        final Object reconnectLock = new Object();

        StreamConnection(String appKey, String appSecret, String workspaceId, String sessionId,
                         String workspacePath) {
            this.appKey = appKey;
            this.appSecret = appSecret;
            this.workspaceId = workspaceId;
            this.sessionId = sessionId;
            this.workspacePath = workspacePath;
        }

        /**
         * 启动连接（在新线程中执行）
         */
        void start() {
            String threadName = "dingtalk-stream-" + appKey.substring(0, Math.min(6, appKey.length()));
            streamThread = new Thread(withLogKey(workspacePath, this::doStart), threadName);
            streamThread.setDaemon(true);
            streamThread.start();
        }

        /**
         * 停止连接（释放所有资源）
         */
        void stop() {
            streamStarted = false;
            if (wsClient != null) {
                try {
                    wsClient.release();
                } catch (Exception ignored) {
                }
                wsClient = null;
            }
            if (reconnectThread != null) {
                reconnectThread.interrupt();
                reconnectThread = null;
            }
            if (streamThread != null) {
                streamThread.interrupt();
                streamThread = null;
            }
        }

        /**
         * 建立 Stream 长连接
         */
        private void doStart() {
            LOG.info("[DingTalk] Starting stream connection, appKey={}", appKeyForLog(appKey));

            try {
                // 第一步：HTTP POST 获取 endpoint + ticket
                ONode reqBody = new ONode();
                reqBody.set("clientId", appKey);
                reqBody.set("clientSecret", appSecret);

                ONode subs = reqBody.getOrNew("subscriptions").asArray();
                ONode sub = new ONode();
                sub.set("topic", "/v1.0/im/bot/messages/get");
                sub.set("type", "CALLBACK");
                subs.add(sub);
                reqBody.set("ua", "soloncode/1.0");

                String resp = DingTalkClient.httpPost(
                        "https://api.dingtalk.com/v1.0/gateway/connections/open",
                        reqBody.toJson(), null);

                if (resp == null || resp.isEmpty()) {
                    throw new RuntimeException("Failed to get stream endpoint: empty response");
                }

                ONode respNode = ONode.ofJson(resp);
                String endpoint = respNode.get("endpoint").getString();
                String ticket = respNode.get("ticket").getString();

                if (endpoint == null || ticket == null) {
                    throw new RuntimeException("Failed to get stream endpoint: " + resp);
                }

                LOG.info("[DingTalk] Got stream endpoint: {}", endpoint);

                // 第二步：建立 WebSocket 连接
                String wsUrl = endpoint + "?ticket=" + ticket;
                wsClient = new SimpleWebSocketClient(wsUrl) {
                    @Override
                    public void onMessage(String message) {
                        DingTalkTransport.this.onWsMessage(message, StreamConnection.this);
                    }
                };

                wsClient.connectBlocking(30, TimeUnit.SECONDS);
                wsClient.heartbeat(25_000, false);
                streamStarted = true;

                LOG.info("[DingTalk] Stream WebSocket connected successfully, appKey={}", appKeyForLog(appKey));

                // 重连成功后清除此 appKey 相关的去重缓存，
                // 确保钉钉在断连期间缓存的事件重推后能被正常处理
                sink.onDingTalkConnected(appKey);

                // 保持线程存活（便于 stop() 通过 interrupt 终止）
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(60000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                LOG.error("[DingTalk] Stream connection error: {}", e.getMessage(), e);
                streamStarted = false;
                scheduleReconnect();
            }
        }

        // ==================== 重连机制 ====================

        void scheduleReconnect() {
            synchronized (reconnectLock) {
                if (wsClient != null) {
                    try {
                        wsClient.release();
                    } catch (Exception ignored) {
                    }
                    wsClient = null;
                }

                if (reconnectThread != null) {
                    reconnectThread.interrupt();
                    reconnectThread = null;
                }

                LOG.info("[DingTalk] Reconnecting in 5 seconds, appKey={}", appKeyForLog(appKey));
                reconnectThread = new Thread(withLogKey(workspacePath, () -> {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!streamStarted) {
                        doStart();
                    }
                }), "dingtalk-reconnect");
                reconnectThread.setDaemon(true);
                reconnectThread.start();
            }
        }
    }
}
