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
package org.noear.solon.codecli.channel.feishu;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
 * 进程级飞书 Stream 传输层。
 *
 * <p>持有 {@code appId -> WebSocket 连接} 的归属，负责协议编解码、心跳与重连；
 * 不含任何绑定/路由逻辑——收到业务事件后统一回调 {@link Sink}，由进程级
 * {@code ImGateway} 完成「绑定归属 + 消息投递」。</p>
 *
 * <p>与旧实现的关键差异：连接不再随工作区 {@code WorkspaceContext} 关闭而断开，
 * 也不随工作区激活而重建。同一 appId 在进程内至多一条连接，工作区生命周期
 * 与连接生命周期彻底解耦（LRU 释放工作区不会掉线）。</p>
 *
 * @author noear
 */
public class FeishuTransport {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuTransport.class);

    /**
     * 事件回调：由进程级网关实现，负责绑定与投递。
     */
    public interface Sink {
        /** 收到文本消息。 */
        void onFeishuText(String appId, String appSecret, String openId, String text, String messageId);

        /** 收到非文本消息（回执提示由实现方负责）。 */
        void onFeishuNonText(String appId, String appSecret, String openId);

        /** 某 appId 连接建立/重连成功（用于清空该 appId 的消息去重缓存）。 */
        void onFeishuConnected(String appId);
    }

    private final Sink sink;
    private final FeishuAppLeaseRegistry leases;
    /** workspaceId -> 物理路径，仅用于把连接线程日志归属到工作区文件。 */
    private final Function<String, String> pathResolver;

    /**
     * appId -> StreamConnection（每个 appId 独立一条 WebSocket 连接）
     */
    private final Map<String, StreamConnection> connections = new ConcurrentHashMap<>();

    public FeishuTransport(Sink sink, FeishuAppLeaseRegistry leases, Function<String, String> pathResolver) {
        this.sink = sink;
        this.leases = leases;
        this.pathResolver = pathResolver;
    }

    // ==================== 状态查询 ====================

    /** 是否存在已建立连接的 appId。 */
    public boolean isStarted() {
        return connections.values().stream().anyMatch(c -> c.streamStarted);
    }

    /** 指定 appId 是否已在本进程持有连接。 */
    public boolean isAppInUse(String appId) {
        return appId != null && connections.containsKey(appId);
    }

    // ==================== 连接生命周期 ====================

    /**
     * 启动（或复用）指定 appId 的连接。
     *
     * @return 连接可用时返回 true；租约被其它工作区持有时返回 false
     */
    public synchronized boolean startStream(String appId, String appSecret, String workspaceId,
                                            String sessionId, boolean force) {
        if (appId == null || appId.isEmpty() || appSecret == null || appSecret.isEmpty()) {
            LOG.warn("[Feishu] startStream: appId or appSecret is empty");
            return false;
        }
        return getOrCreateConnection(appId, appSecret, workspaceId, sessionId, force) != null;
    }

    /**
     * 获取或创建指定 appId 的 Stream 连接（已存在则复用）。
     */
    private synchronized StreamConnection getOrCreateConnection(String appId, String appSecret,
                                                                String workspaceId, String sessionId,
                                                                boolean force) {
        StreamConnection conn = connections.get(appId);
        if (conn != null) {
            return conn;
        }

        FeishuAppLeaseRegistry.Result lease = leases.acquire(appId, appSecret, workspaceId, sessionId, force);
        if (!lease.isAcquired()) {
            FeishuAppLeaseRegistry.Lease owner = lease.getConflict();
            LOG.warn("[Feishu] App lease conflict, appId={}, ownerWorkspace={}, ownerSession={}",
                    appIdForLog(appId), owner.getWorkspaceId(), owner.getSessionId());
            return null;
        }

        StreamConnection created = new StreamConnection(appId, appSecret, workspaceId, sessionId,
                resolvePath(workspaceId));
        StreamConnection existing = connections.putIfAbsent(appId, created);
        if (existing != null) {
            leases.release(appId, workspaceId, sessionId);
            return existing;
        }

        created.start();
        return created;
    }

    /**
     * 关闭某 appId 的连接（释放租约、断开 WS、停止线程）。
     */
    public void stopConnection(String appId) {
        StreamConnection conn = connections.remove(appId);
        if (conn != null) {
            conn.stop();
        }
    }

    /** 断开全部连接（进程退出时调用）。 */
    public void stopAll() {
        for (String appId : new ArrayList<>(connections.keySet())) {
            stopConnection(appId);
        }
    }

    // ==================== 提示发送（不依赖会话绑定） ====================

    /**
     * 直接向指定用户发送一条轻量提示（不依赖会话绑定）。
     */
    public static void sendHint(String appId, String appSecret, String openId, String text) {
        if (appId == null || appSecret == null || openId == null || text == null) {
            return;
        }
        RunUtil.async(() -> {
            try {
                String token = FeishuClient.getTenantAccessToken(appId, appSecret);
                if (token != null) {
                    FeishuClient.sendMessage(token, "open_id", openId, text);
                }
            } catch (Exception e) {
                LOG.warn("[Feishu] Hint send error: {}", e.getMessage());
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

    private static String appIdForLog(String appId) {
        return appId == null ? "null" : appId.substring(0, Math.min(8, appId.length())) + "...";
    }

    private Runnable withLogKey(String workspacePath, Runnable runnable) {
        if (workspacePath == null || workspacePath.isEmpty()) {
            return runnable;
        }
        return WorkspaceLogRouter.withWorkspaceLogKey(workspacePath, runnable);
    }

    // ==================== WS 消息处理 ====================

    private void onWsBinaryMessage(ByteBuffer bytes, StreamConnection conn) {
        try {
            FeishuPbCodec.Frame frame = FeishuPbCodec.decode(bytes);
            handlePbFrame(frame, conn);
        } catch (Exception e) {
            LOG.error("[Feishu] Failed to parse binary frame: {}", e.getMessage(), e);
        }
    }

    private void handlePbFrame(FeishuPbCodec.Frame frame, StreamConnection conn) {
        LOG.debug("[Feishu] PB frame: method={}, service={}, seqId={}, headers={}",
                frame.method, frame.service, frame.seqId, frame.headers.size());

        switch (frame.method) {
            case 0: // CONTROL
                handleControl(frame, conn);
                break;
            case 1: // DATA
                handleData(frame, conn);
                break;
            case 2: // ACK
                LOG.debug("[Feishu] Received ACK for seqId={}", frame.seqId);
                break;
            default:
                LOG.debug("[Feishu] Unknown frame method: {}", frame.method);
                break;
        }
    }

    private void handleControl(FeishuPbCodec.Frame frame, StreamConnection conn) {
        String type = frame.getHeader("type");
        if (type == null) {
            LOG.debug("[Feishu] CONTROL frame without type header");
            return;
        }

        switch (type) {
            case "ping": {
                LOG.debug("[Feishu] Received server ping, seqId={}", frame.seqId);
                return;
            }
            case "pong": {
                LOG.debug("[Feishu] Received pong, seqId={}", frame.seqId);
                String configJson = frame.getPayloadAsString();
                if (configJson != null && !configJson.isEmpty()) {
                    try {
                        ONode conf = ONode.ofJson(configJson);
                        Integer interval = conf.get("PingInterval").getInt();
                        if (interval != null && interval > 0) {
                            conn.pingIntervalMs = interval * 1000L;
                            LOG.info("[Feishu] Updated pingInterval={}s from pong", interval);
                        }
                    } catch (Exception e) {
                        LOG.debug("[Feishu] Failed to parse pong config: {}", e.getMessage());
                    }
                }
                break;
            }
            case "disconnect": {
                LOG.warn("[Feishu] Server sent disconnect");
                String reason = frame.getHeader("reason");
                LOG.warn("[Feishu] Disconnect reason: {}", reason);
                conn.streamStarted = false;
                conn.scheduleReconnect();
                break;
            }
            default: {
                LOG.debug("[Feishu] Unknown CONTROL type: {}", type);
                break;
            }
        }
    }

    private void handleData(FeishuPbCodec.Frame frame, StreamConnection conn) {
        long startMs = System.currentTimeMillis();

        String msgId = frame.getHeader("message_id");
        String traceId = frame.getHeader("trace_id");
        String type = frame.getHeader("type");

        LOG.debug("[Feishu] DATA frame: type={}, msgId={}, traceId={}", type, msgId, traceId);

        int code = 200;
        byte[] respPayload;
        try {
            String payloadJson = frame.getPayloadAsString();
            if (payloadJson != null && !payloadJson.isEmpty()) {
                LOG.debug("[Feishu] DATA payload: {}", payloadJson.substring(0, Math.min(payloadJson.length(), 300)));
                ONode eventNode = ONode.ofJson(payloadJson);
                onWsEvent(eventNode, conn);
            }
            respPayload = "{\"code\":200}".getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.error("[Feishu] Failed to handle DATA: {}", e.getMessage(), e);
            code = 500;
            respPayload = "{\"code\":500}".getBytes(StandardCharsets.UTF_8);
        }

        long elapsedMs = System.currentTimeMillis() - startMs;

        byte[] respBytes = FeishuPbCodec.buildDataResponse(frame, respPayload, elapsedMs);
        if (conn.wsClient != null && conn.wsClient.isOpen()) {
            conn.wsClient.send(respBytes);
            LOG.debug("[Feishu] Sent DATA response code={}, elapsed={}ms", code, elapsedMs);
        }
    }

    private void onWsEvent(ONode eventNode, StreamConnection conn) {
        ONode header = eventNode.get("header");
        if (header == null || header.isNull()) {
            LOG.debug("[Feishu] Ignored event without header");
            return;
        }

        String eventType = header.get("event_type").getString();
        if (eventType == null) {
            return;
        }

        if ("im.message.receive_v1".equals(eventType)) {
            onImMessageReceive(eventNode, conn);
        }
    }

    private void onImMessageReceive(ONode msg, StreamConnection conn) {
        ONode event = msg.get("event");
        if (event == null || event.isNull()) return;

        ONode sender = event.get("sender");
        if (sender == null || sender.isNull()) return;

        ONode senderId = sender.get("sender_id");
        if (senderId == null || senderId.isNull()) return;

        String openId = senderId.get("open_id").getString();
        if (openId == null || openId.isEmpty()) return;

        ONode messageNode = event.get("message");
        if (messageNode == null || messageNode.isNull()) return;

        String msgId = messageNode.get("message_id").getString();
        String msgType = messageNode.get("message_type").getString();

        // 只处理文本消息
        String text = null;
        if ("text".equals(msgType)) {
            String contentJson = messageNode.get("content").getString();
            if (contentJson != null && !contentJson.isEmpty()) {
                try {
                    ONode contentNode = ONode.ofJson(contentJson);
                    text = contentNode.get("text").getString();
                } catch (Exception e) {
                    text = contentJson;
                }
            }
        }

        if (text == null || text.isEmpty()) {
            LOG.debug("[Feishu] Ignored non-text message from {}", openId);
            sink.onFeishuNonText(conn.appId, conn.appSecret, openId);
            return;
        }

        LOG.info("[Feishu] Received from {}: {}", openId, text.substring(0, Math.min(text.length(), 50)));
        sink.onFeishuText(conn.appId, conn.appSecret, openId, text, msgId);
    }

    // ==================== 内部连接类（每个 appId 独立一条连接） ====================

    /**
     * 单个 appId 的 WebSocket 长连接。
     * 封装连接生命周期、心跳、重连等所有连接级状态；连接随进程存续。
     */
    private class StreamConnection {
        final String appId;
        final String appSecret;
        /** 获取租约时登记的工作区/会话，用于幂等释放。 */
        final String workspaceId;
        final String sessionId;
        /** 仅用于线程日志归属。 */
        final String workspacePath;

        volatile SimpleWebSocketClient wsClient;
        volatile boolean streamStarted = false;
        volatile boolean running = true;
        volatile Thread streamThread;
        volatile Thread reconnectThread;
        final Object reconnectLock = new Object();

        /**
         * 飞书返回的心跳间隔（毫秒）
         */
        volatile long pingIntervalMs = 20_000;

        /**
         * 从 WS URL 提取的 service_id
         */
        volatile int serviceId = 1;

        /**
         * 从 WS URL 提取的 device_id
         */
        volatile String connId;

        volatile ScheduledFuture<?> heartbeatFuture;

        StreamConnection(String appId, String appSecret, String workspaceId, String sessionId,
                         String workspacePath) {
            this.appId = appId;
            this.appSecret = appSecret;
            this.workspaceId = workspaceId;
            this.sessionId = sessionId;
            this.workspacePath = workspacePath;
        }

        /**
         * 启动连接（在新线程中执行）
         */
        void start() {
            String threadName = "feishu-stream-" + appId.substring(0, Math.min(6, appId.length()));
            streamThread = new Thread(withLogKey(workspacePath, this::doStart), threadName);
            streamThread.setDaemon(true);
            streamThread.start();
        }

        /**
         * 停止连接（释放所有资源）
         */
        void stop() {
            running = false;
            leases.release(appId, workspaceId, sessionId);
            streamStarted = false;
            stopHeartbeat();
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
         * 建立飞书 WebSocket 长连接
         */
        private void doStart() {
            LOG.info("[Feishu] Starting WebSocket connection, appId={}", appIdForLog(appId));

            try {
                // 第一步：获取 WebSocket 端点
                ONode endpointData = FeishuClient.getWsEndpoint(appId, appSecret);
                if (endpointData == null) {
                    throw new RuntimeException("Failed to get WS endpoint");
                }

                String wssUrl = endpointData.get("URL").getString();
                if (wssUrl == null || wssUrl.isEmpty()) {
                    throw new RuntimeException("WS endpoint URL is empty");
                }

                long pingInterval = 20_000;
                ONode clientConfig = endpointData.get("ClientConfig");
                if (clientConfig != null && !clientConfig.isNull()) {
                    try {
                        Long interval = clientConfig.get("PingInterval").getLong();
                        if (interval != null && interval > 0) {
                            pingInterval = interval * 1000L;
                        }
                    } catch (Exception ignored) {
                    }
                }

                pingIntervalMs = pingInterval;
                LOG.info("[Feishu] Got WS endpoint, pingInterval={}ms", pingInterval);

                // 第二步：从 URL 提取 service_id 和 device_id
                URI wsUri = URI.create(wssUrl);
                String query = wsUri.getQuery();
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] kv = param.split("=", 2);
                        if (kv.length == 2) {
                            if ("service_id".equals(kv[0])) {
                                serviceId = Integer.parseInt(kv[1]);
                            } else if ("device_id".equals(kv[0])) {
                                connId = kv[1];
                            }
                        }
                    }
                }
                LOG.info("[Feishu] WS params: serviceId={}, connId={}", serviceId,
                        connId != null ? connId.substring(0, Math.min(8, connId.length())) + "..." : "null");

                // 第三步：建立 WebSocket 连接
                final int sid = serviceId;
                wsClient = new SimpleWebSocketClient(wssUrl) {
                    @Override
                    public void onMessage(ByteBuffer bytes) {
                        FeishuTransport.this.onWsBinaryMessage(bytes, StreamConnection.this);
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        LOG.info("[Feishu] WS closed: code={}, reason={}, remote={}", code, reason, remote);
                        stopHeartbeat();
                        if (running && streamStarted) {
                            streamStarted = false;
                            scheduleReconnect();
                        }
                    }

                    @Override
                    public void onError(Exception ex) {
                        LOG.error("[Feishu] WS error: {}", ex.getMessage(), ex);
                    }
                };

                wsClient.connectBlocking(30, TimeUnit.SECONDS);
                streamStarted = true;

                // 启动自定义心跳（飞书协议要求发送 Protobuf 二进制 ping 帧）
                startHeartbeat();

                LOG.info("[Feishu] WebSocket connected successfully, appId={}", appIdForLog(appId));

                // 重连成功后清除此 appId 相关的去重缓存，
                // 确保飞书在断连期间缓存的事件重推后能被正常处理
                sink.onFeishuConnected(appId);

                // 保持线程存活（便于 stop() 通过 interrupt 终止）
                while (!Thread.currentThread().isInterrupted() && running) {
                    try {
                        Thread.sleep(60000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                LOG.error("[Feishu] WebSocket connection error: {}", e.getMessage(), e);
                streamStarted = false;
                scheduleReconnect();
            }
        }

        // ==================== 心跳管理 ====================

        private void startHeartbeat() {
            stopHeartbeat();
            if (pingIntervalMs > 0) {
                final int sid = serviceId;
                heartbeatFuture = RunUtil.timer().scheduleAtFixedRate(() -> {
                    try {
                        if (wsClient != null && wsClient.isOpen()) {
                            byte[] pingBytes = FeishuPbCodec.buildPing(sid);
                            wsClient.send(pingBytes);
                            LOG.debug("[Feishu] Sent ping, serviceId={}", sid);
                        }
                    } catch (Exception e) {
                        LOG.warn("[Feishu] Heartbeat error: {}", e.getMessage());
                    }
                }, pingIntervalMs, pingIntervalMs, TimeUnit.MILLISECONDS);
            }
        }

        private void stopHeartbeat() {
            if (heartbeatFuture != null) {
                heartbeatFuture.cancel(false);
                heartbeatFuture = null;
            }
        }

        // ==================== 重连机制 ====================

        void scheduleReconnect() {
            synchronized (reconnectLock) {
                stopHeartbeat();
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

                LOG.info("[Feishu] Reconnecting in 5 seconds, appId={}", appIdForLog(appId));
                reconnectThread = new Thread(withLogKey(workspacePath, () -> {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!streamStarted && running) {
                        doStart();
                    }
                }), "feishu-reconnect");
                reconnectThread.setDaemon(true);
                reconnectThread.start();
            }
        }
    }
}
