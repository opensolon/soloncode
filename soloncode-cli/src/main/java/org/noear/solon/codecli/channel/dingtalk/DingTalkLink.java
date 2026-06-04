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

import org.noear.java_websocket.client.SimpleWebSocketClient;
import org.noear.snack4.ONode;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.config.AgentProperties;
import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.portal.web.WebGate;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * 钉钉 Bot 通道（基于钉钉 Stream 协议 + WebSocket 长连接）
 *
 * <p>支持多 appKey 多连接：每个 appKey 独立一条 WebSocket 连接，
 * 不同 session 可以绑定不同的钉钉机器人（不同 appKey/appSecret）。</p>
 *
 * <p>使用 java-websocket-ns 建立 WebSocket 长连接，通过钉钉 Stream 协议接收消息，
 * 实现消息的实时接收与回复。</p>
 *
 * <p>绑定流程：
 * <ol>
 *   <li>前端提交 AppKey + AppSecret → 调用 {@link #startStream}，Stream 连接启动</li>
 *   <li>进入 pending 状态（等待用户在钉钉端发消息给机器人）</li>
 *   <li>机器人收到消息 → 自动提取 senderStaffId → 绑定到 pending 会话</li>
 *   <li>前端轮询 {@link #getStreamStatus()} 获取绑定结果</li>
 * </ol>
 * </p>
 *
 * @author noear 2026/5/9 created
 */
public class DingTalkLink implements Channel, Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(DingTalkLink.class);

    private final HarnessEngine engine;
    private final WebGate webGate;
    private final DingTalkCredentialStore credentialStore;

    /**
     * appKey -> StreamConnection（每个 appKey 独立一条 WebSocket 连接）
     */
    private final Map<String, StreamConnection> connections = new ConcurrentHashMap<>();

    /**
     * sessionId -> DingTalkBinding
     */
    private final Map<String, DingTalkBinding> bindings = new ConcurrentHashMap<>();

    /**
     * userId -> sessionId（反向映射，用于消息路由）
     */
    private final Map<String, String> userIdToSession = new ConcurrentHashMap<>();

    /**
     * 最近收到消息的 webhook 缓存（userId -> sessionWebhook），用于回复
     */
    private final Map<String, String> webhookCache = new ConcurrentHashMap<>();

    /**
     * 消息处理调度器（单线程顺序处理）
     */
    private final ExecutorService messageExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dingtalk-message");
        t.setDaemon(true);
        return t;
    });

    public DingTalkLink(HarnessEngine engine, WebGate webGate) {
        this.engine = engine;
        this.webGate = webGate;
        this.credentialStore = new DingTalkCredentialStore(engine);

        webGate.getStreamBuilder().bind(this);

        // 尝试恢复已保存的绑定（含 appKey/appSecret）
        loadBindings();
    }

    // ==================== Channel 接口实现 ====================

    @Override
    public String getChannelName() {
        return "dingtalk";
    }

    @Override
    public boolean isBound(String sessionId) {
        return bindings.containsKey(sessionId);
    }

    @Override
    public void sendReply(String sessionId, String reply, boolean isFinal) {
        DingTalkBinding binding = bindings.get(sessionId);
        if (binding == null) {
            return;
        }

        if (Assert.isEmpty(reply)) {
            return;
        }

        // 优先使用缓存的 sessionWebhook 回复
        String webhook = webhookCache.get(binding.userId);
        if (webhook != null && !webhook.isEmpty()) {
            try {
                int maxLen = 5000;
                if (reply.length() <= maxLen) {
                    DingTalkClient.replyViaWebhook(webhook, reply);
                } else {
                    int pos = 0;
                    int part = 1;
                    while (pos < reply.length()) {
                        int end = Math.min(pos + maxLen, reply.length());
                        String chunk = reply.substring(pos, end);
                        if (part > 1) {
                            chunk = "(" + part + ") " + chunk;
                        }
                        DingTalkClient.replyViaWebhook(webhook, chunk);
                        pos = end;
                        part++;
                    }
                }
                return;
            } catch (Exception e) {
                LOG.warn("[DingTalk] Webhook reply failed, falling back to API: {}", e.getMessage());
                webhookCache.remove(binding.userId);
            }
        }

        // 降级：使用 API 方式发送
        sendReplyViaApi(binding, reply);
    }

    // ==================== 生命周期 ====================

    @Override
    public void run() {
        if (bindings.isEmpty()) {
            LOG.info("[DingTalk] No bindings, waiting for web bind...");
            return;
        }

        // 按 appKey 分组，为每个 appKey 启动独立的 WebSocket 连接
        Map<String, DingTalkBinding> byAppKey = new LinkedHashMap<>();
        for (DingTalkBinding b : bindings.values()) {
            if (b.appKey != null && b.appSecret != null) {
                byAppKey.putIfAbsent(b.appKey, b);
            }
        }

        for (Map.Entry<String, DingTalkBinding> entry : byAppKey.entrySet()) {
            getOrCreateConnection(entry.getValue().appKey, entry.getValue().appSecret);
        }
    }

    /**
     * 获取或创建指定 appKey 的 Stream 连接（已存在则复用）
     */
    private StreamConnection getOrCreateConnection(String appKey, String appSecret) {
        StreamConnection conn = connections.get(appKey);
        if (conn != null) {
            return conn;
        }

        conn = new StreamConnection(appKey, appSecret);
        StreamConnection existing = connections.putIfAbsent(appKey, conn);
        if (existing != null) {
            return existing;
        }

        conn.start();
        return conn;
    }

    /**
     * 动态启动 Stream 连接（由前端绑定操作触发）
     */
    public boolean startStream(String appKey, String appSecret, String sessionId) {
        if (appKey == null || appKey.isEmpty() || appSecret == null || appSecret.isEmpty()) {
            LOG.warn("[DingTalk] startStream: appKey or appSecret is empty");
            return false;
        }

        StreamConnection conn = getOrCreateConnection(appKey, appSecret);
        conn.pendingSessionId = sessionId;
        LOG.info("[DingTalk] startStream: appKey={}, pendingSession={}",
                appKey.substring(0, Math.min(8, appKey.length())) + "...", sessionId);

        return true;
    }

    /**
     * 停止所有资源
     */
    public void stop() {
        for (StreamConnection conn : connections.values()) {
            conn.stop();
        }
        connections.clear();
        messageExecutor.shutdownNow();
        LOG.info("[DingTalk] Link stopped");
    }

    // ==================== Stream 状态查询 ====================

    /**
     * 获取当前 Stream 状态（供前端轮询）
     */
    public Map<String, Object> getStreamStatus(String sessionId) {
        Map<String, Object> status = new LinkedHashMap<>();
        boolean anyStarted = connections.values().stream().anyMatch(c -> c.streamStarted);
        status.put("streamStarted", anyStarted);
        status.put("pending", connections.values().stream()
                .anyMatch(c -> sessionId.equals(c.pendingSessionId)));
        status.put("bound", sessionId != null && bindings.containsKey(sessionId));
        return status;
    }

    // ==================== 绑定管理 ====================

    /**
     * 手动绑定钉钉用户到指定会话（兼容旧接口）
     */
    public void bindSession(String sessionId, String userId, String robotCode) {
        doBindSession(sessionId, userId, robotCode);
    }

    /**
     * 内部绑定实现
     */
    private void doBindSession(String sessionId, String userId, String robotCode, String appKey, String appSecret) {
        DingTalkBinding binding = new DingTalkBinding();
        binding.userId = userId;
        binding.robotCode = robotCode != null ? robotCode : appKey;
        binding.appKey = appKey;
        binding.appSecret = appSecret;

        // 一个 userId 只能绑定一个 session（清理旧绑定）
        Set<String> unbindSessionIds = new HashSet<>();
        bindings.forEach((k, v) -> {
            if (v.userId.equals(userId)) {
                unbindSessionIds.add(k);
            }
        });
        for (String unbindSessionId : unbindSessionIds) {
            doUnbindSession(unbindSessionId);
        }

        bindings.put(sessionId, binding);
        userIdToSession.put(userId, sessionId);

        // 清除连接的 pending 状态
        StreamConnection conn = connections.get(appKey);
        if (conn != null && sessionId.equals(conn.pendingSessionId)) {
            conn.pendingSessionId = null;
        }

        credentialStore.save(bindings);
        LOG.info("[DingTalk] Session {} bound to DingTalk user {}", sessionId, userId);
    }

    /**
     * 兼容旧接口的绑定（不传 appKey/appSecret，从连接中取）
     */
    private void doBindSession(String sessionId, String userId, String robotCode) {
        // 旧接口兼容：找第一个有 pending 的连接
        for (StreamConnection conn : connections.values()) {
            if (conn.pendingSessionId != null && conn.pendingSessionId.equals(sessionId)) {
                doBindSession(sessionId, userId, robotCode, conn.appKey, conn.appSecret);
                return;
            }
        }
        // fallback：用第一个连接的凭据
        if (!connections.isEmpty()) {
            StreamConnection conn = connections.values().iterator().next();
            doBindSession(sessionId, userId, robotCode, conn.appKey, conn.appSecret);
        }
    }

    /**
     * 解绑钉钉
     */
    public void unbindSession(String sessionId) {
        doUnbindSession(sessionId);
    }

    private void doUnbindSession(String sessionId) {
        DingTalkBinding binding = bindings.remove(sessionId);
        if (binding == null) return;

        userIdToSession.remove(binding.userId);
        webhookCache.remove(binding.userId);
        credentialStore.save(bindings);

        // 检查是否还有绑定使用此 appKey，如果没有则关闭连接
        if (binding.appKey != null) {
            boolean stillUsed = bindings.values().stream()
                    .anyMatch(b -> binding.appKey.equals(b.appKey));
            if (!stillUsed) {
                StreamConnection conn = connections.remove(binding.appKey);
                if (conn != null) {
                    conn.stop();
                }
            }
        }

        LOG.info("[DingTalk] Session {} unbound", sessionId);
    }

    /**
     * 从持久化存储恢复所有已绑定的会话
     */
    private void loadBindings() {
        Map<String, DingTalkBinding> saved = credentialStore.load();
        if (saved.isEmpty()) return;

        LOG.info("[DingTalk] Restoring {} saved binding(s)", saved.size());
        for (Map.Entry<String, DingTalkBinding> entry : saved.entrySet()) {
            String sessionId = entry.getKey();
            DingTalkBinding binding = entry.getValue();
            bindings.put(sessionId, binding);
            userIdToSession.put(binding.userId, sessionId);
            LOG.info("[DingTalk] Restored session {} -> userId {}", sessionId, binding.userId);
        }
    }

    /**
     * 获取所有已绑定会话 ID
     */
    public Set<String> getBoundSessionIds() {
        return Collections.unmodifiableSet(bindings.keySet());
    }

    // ==================== WS 消息处理（由 StreamConnection 回调） ====================

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

                // 回复 ACK
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
                    ONode botMsg = ONode.ofJson(data);
                    onBotMessageParsed(botMsg, conn);
                }
            }
        } catch (Exception e) {
            LOG.error("[DingTalk] onWsMessage error: {}", e.getMessage(), e);
        }
    }

    /**
     * 解析并处理钉钉机器人消息
     */
    private void onBotMessageParsed(ONode botMsg, StreamConnection conn) {
        String userId = botMsg.get("senderStaffId").getString();
        if (userId == null || userId.isEmpty()) {
            userId = botMsg.get("senderId").getString();
        }

        String text = null;
        ONode textNode = botMsg.get("text");
        if (textNode != null && textNode.isNull() == false) {
            text = textNode.get("content").getString();
        }
        if ((text == null || text.isEmpty())) {
            ONode contentNode = botMsg.get("content");
            if (contentNode != null && contentNode.isNull() == false) {
                text = contentNode.get("content").getString();
            }
        }

        String msgId = botMsg.get("msgId").getString();
        String webhook = botMsg.get("sessionWebhook").getString();
        String conversationType = botMsg.get("conversationType").getString();

        LOG.info("[DingTalk] Received bot message: userId={}, convType={}, msgId={}, text={}",
                userId, conversationType, msgId,
                text != null ? text.substring(0, Math.min(text.length(), 50)) : "null");

        // 缓存 sessionWebhook（用于后续回复）
        if (webhook != null && !webhook.isEmpty()) {
            webhookCache.put(userId, webhook);
        }

        // 查找绑定的 session
        String sessionId = userIdToSession.get(userId);

        if (sessionId == null) {
            // 未绑定的用户 → 检查是否有 pending 会话在等待
            if (conn.pendingSessionId != null) {
                LOG.info("[DingTalk] Auto-binding user {} to pending session {}", userId, conn.pendingSessionId);
                String robotCode = conn.appKey;
                doBindSession(conn.pendingSessionId, userId, robotCode, conn.appKey, conn.appSecret);
                sessionId = conn.pendingSessionId;
                // 绑定成功后发一条欢迎提示
                if (webhook != null && !webhook.isEmpty()) {
                    try {
                        DingTalkClient.replyViaWebhook(webhook, "绑定成功！已连接到 SolonCode Web 会话。");
                    } catch (Exception e) {
                        LOG.warn("[DingTalk] Welcome message send failed: {}", e.getMessage());
                    }
                }
            } else {
                LOG.warn("[DingTalk] Received message from unbound user (no pending session): userId={}", userId);
                return;
            }
        }

        DingTalkBinding binding = bindings.get(sessionId);
        if (binding == null) return;

        // 防重复处理
        if (msgId != null && msgId.equals(binding.lastMessageId)) {
            return;
        }
        binding.lastMessageId = msgId;

        if (text == null || text.isEmpty()) {
            return;
        }

        final String finalSessionId = sessionId;
        final String finalText = text;

        messageExecutor.execute(() -> {
            try {
                webGate.safeChatInput(finalSessionId, finalText, "DingTalk");
            } catch (Exception e) {
                LOG.error("[DingTalk] Message processing error: {}", e.getMessage(), e);
            }
        });
    }

    // ==================== 消息发送 ====================

    /**
     * 降级方案：通过 API 发送回复（当 sessionWebhook 不可用时）
     */
    private void sendReplyViaApi(DingTalkBinding binding, String reply) {
        String token = DingTalkClient.getAccessToken(binding.appKey, binding.appSecret);
        if (token == null) {
            LOG.error("[DingTalk] Cannot send reply: no access token");
            return;
        }

        String robotCode = binding.robotCode != null && !binding.robotCode.isEmpty()
                ? binding.robotCode : binding.appKey;

        int maxLen = 5000;
        if (reply.length() <= maxLen) {
            DingTalkClient.sendSingleMessage(token, robotCode, binding.userId, reply);
        } else {
            int pos = 0;
            int part = 1;
            while (pos < reply.length()) {
                int end = Math.min(pos + maxLen, reply.length());
                String chunk = reply.substring(pos, end);
                if (part > 1) {
                    chunk = "(" + part + ") " + chunk;
                }
                DingTalkClient.sendSingleMessage(token, robotCode, binding.userId, chunk);
                pos = end;
                part++;
            }
        }
    }

    // ==================== 内部连接类（每个 appKey 独立一条连接） ====================

    /**
     * 单个 appKey 的 WebSocket 长连接。
     * 封装了连接生命周期、重连等所有连接级状态。
     */
    private class StreamConnection {
        final String appKey;
        final String appSecret;

        volatile SimpleWebSocketClient wsClient;
        volatile boolean streamStarted = false;
        volatile Thread streamThread;
        volatile Thread reconnectThread;
        final Object reconnectLock = new Object();

        /**
         * 待绑定的会话 ID（此连接上等待钉钉用户发消息来自动完成绑定）
         */
        volatile String pendingSessionId;

        StreamConnection(String appKey, String appSecret) {
            this.appKey = appKey;
            this.appSecret = appSecret;
        }

        /**
         * 启动连接（在新线程中执行）
         */
        void start() {
            String threadName = "dingtalk-stream-" + appKey.substring(0, Math.min(6, appKey.length()));
            streamThread = new Thread(this::doStart, threadName);
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
            LOG.info("[DingTalk] Starting stream connection, appKey={}",
                    appKey.substring(0, Math.min(8, appKey.length())) + "...");

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
                        DingTalkLink.this.onWsMessage(message, StreamConnection.this);
                    }
                };

                wsClient.connectBlocking(30, TimeUnit.SECONDS);
                wsClient.heartbeat(25_000, false);
                streamStarted = true;

                LOG.info("[DingTalk] Stream WebSocket connected successfully, appKey={}",
                        appKey.substring(0, Math.min(8, appKey.length())) + "...");

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

                LOG.info("[DingTalk] Reconnecting in 5 seconds, appKey={}",
                        appKey.substring(0, Math.min(8, appKey.length())) + "...");
                reconnectThread = new Thread(() -> {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!streamStarted) {
                        doStart();
                    }
                }, "dingtalk-reconnect");
                reconnectThread.setDaemon(true);
                reconnectThread.start();
            }
        }
    }

    // ==================== 内部数据类 ====================

    public static class DingTalkBinding {
        public String userId;          // 钉钉用户 staffId / userId
        public String robotCode;       // 机器人编码（发送消息用）
        public String lastMessageId;   // 最后处理的消息 ID（防重复）
        public String appKey;          // 保存的 AppKey（用于重启后恢复 Stream 连接）
        public String appSecret;       // 保存的 AppSecret
    }
}
