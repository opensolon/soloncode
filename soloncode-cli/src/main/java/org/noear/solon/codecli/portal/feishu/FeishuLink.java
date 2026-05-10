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
package org.noear.solon.codecli.portal.feishu;

import java.net.SocketException;
import java.net.URI;
import org.noear.snack4.ONode;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.config.AgentProperties;
import org.noear.solon.codecli.portal.IMLink;
import org.noear.solon.codecli.portal.WebGate;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 飞书 Bot 通道（基于飞书 WebSocket 长连接 + Protobuf 协议）
 *
 * <p>飞书 WebSocket 使用 Pbbp2 Protobuf 二进制协议：</p>
 * <ul>
 *   <li>method=0 → CONTROL（ping/pong、disconnect）</li>
 *   <li>method=1 → DATA（业务事件推送）</li>
 *   <li>method=2 → ACK（确认回复）</li>
 * </ul>
 *
 * <p>绑定流程：
 * <ol>
 *   <li>前端提交 App ID + App secret → 调用 {@link #startStream}，WebSocket 连接启动</li>
 *   <li>进入 pending 状态（等待用户在飞书端发消息给机器人）</li>
 *   <li>机器人收到消息 → 自动提取 sender.openId → 绑定到 pending 会话</li>
 *   <li>前端轮询 {@link #getStreamStatus()} 获取绑定结果</li>
 * </ol>
 * </p>
 *
 * @author noear 2026/5/9 created
 */
public class FeishuLink implements IMLink, Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuLink.class);

    private final HarnessEngine engine;
    private final WebGate webGate;
    private final FeishuCredentialStore credentialStore;

    /**
     * 当前已配置的 appId / appSecret（可能从配置文件预加载，也可能由前端动态提交）
     */
    private volatile String appId;
    private volatile String appSecret;

    /**
     * 纯手工 WebSocket 客户端实例
     */
    private volatile RawWsClient wsClient;

    /**
     * WebSocket 连接是否已启动
     */
    private volatile boolean streamStarted = false;

    /**
     * 待绑定的会话 ID（用户在前端点击绑定后设置，等待飞书端发消息来自动完成绑定）
     */
    private volatile String pendingSessionId;

    /**
     * sessionId -> FeishuBinding
     */
    private final Map<String, FeishuBinding> bindings = new ConcurrentHashMap<>();

    /**
     * openId -> sessionId（反向映射，用于从飞书消息路由到会话）
     */
    private final Map<String, String> openIdToSession = new ConcurrentHashMap<>();

    /**
     * 消息处理调度器（单线程顺序处理）
     */
    private final ExecutorService messageExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "feishu-message");
        t.setDaemon(true);
        return t;
    });

    /**
     * WebSocket 连接线程
     */
    private volatile Thread streamThread;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 重连锁（防止并发重连）
     */
    private final Object reconnectLock = new Object();

    /**
     * 飞书返回的心跳间隔（毫秒）
     */
    private volatile long pingIntervalMs = 20_000;

    /**
     * 从 WS URL 提取的 service_id（SDK 用这个值构建 ping 帧）
     */
    private volatile int serviceId = 1;

    /**
     * 从 WS URL 提取的 device_id（连接标识）
     */
    private volatile String connId;

    public FeishuLink(HarnessEngine engine, WebGate webGate) {
        this.engine = engine;
        this.webGate = webGate;
        AgentProperties agentProps = (AgentProperties) engine.getProps();
        this.credentialStore = new FeishuCredentialStore(agentProps);

        // 目前没有预配置项，全部由前端动态提交
        this.appId = null;
        this.appSecret = null;

        webGate.getStreamBuilder().bind(this);

        // 尝试恢复已保存的绑定（含 appId/appSecret）
        loadBindings();
    }

    // ==================== IMLink 接口实现 ====================

    @Override
    public String getChannelName() {
        return "feishu";
    }

    @Override
    public boolean isBound(String sessionId) {
        return bindings.containsKey(sessionId);
    }

    @Override
    public void sendReply(String sessionId, String reply) {
        FeishuBinding binding = bindings.get(sessionId);
        if (binding == null) {
            return;
        }

        if (Assert.isEmpty(reply)) {
            return;
        }

        if (appId == null || appSecret == null) {
            LOG.warn("[Feishu] Cannot send reply: credentials not initialized");
            return;
        }

        messageExecutor.execute(() -> {
            try {
                sendReplyDo(binding, reply);
            } catch (Exception e) {
                LOG.error("[Feishu] Reply error: {}", e.getMessage(), e);
            }
        });
    }

    // ==================== 生命周期 ====================

    @Override
    public void run() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        if (Assert.isEmpty(appId) || Assert.isEmpty(appSecret)) {
            LOG.info("[Feishu] No appId/appSecret configured, waiting for web bind...");
            // 空跑保持线程存活（startStream 会另起新线程）
            while (!Thread.currentThread().isInterrupted() && running.get()) {
                try {
                    Thread.sleep(60000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } else {
            // 预配置了凭据，直接启动 Stream
            doStartStream();
        }
    }

    /**
     * 动态启动 Stream 连接（由前端绑定操作触发）
     */
    public synchronized boolean startStream(String appId, String appSecret, String sessionId) {
        if (appId == null || appId.isEmpty() || appSecret == null || appSecret.isEmpty()) {
            LOG.warn("[Feishu] startStream: appId or appSecret is empty");
            return false;
        }

        // 如果已用相同凭据启动，只需设置 pending
        if (streamStarted && appId.equals(this.appId) && appSecret.equals(this.appSecret)) {
            LOG.info("[Feishu] Stream already started with same credentials, setting pending session: {}", sessionId);
            this.pendingSessionId = sessionId;
            return true;
        }

        // 如果已有连接但凭据不同，先关闭旧连接
        if (streamStarted) {
            stopStream();
        }

        this.appId = appId;
        this.appSecret = appSecret;
        this.pendingSessionId = sessionId;

        // 在新线程中启动 WebSocket
        streamThread = new Thread(() -> doStartStream(), "feishu-stream");
        streamThread.setDaemon(true);
        streamThread.start();

        return true;
    }

    /**
     * 内部方法：建立飞书 WebSocket 长连接
     *
     * <p>第一步：HTTP POST 获取 WebSocket 端点 URL</p>
     * <p>第二步：建立 WebSocket 连接，接收二进制 Protobuf 消息</p>
     */
    private void doStartStream() {
        LOG.info("[Feishu] Starting WebSocket connection, appId={}",
                appId != null ? appId.substring(0, Math.min(8, appId.length())) + "..." : "null");

        try {
            // 第一步：获取 WebSocket 端点
            ONode endpointData = FeishuClient.getWsEndpoint(appId, appSecret);
            if (endpointData == null) {
                throw new RuntimeException("Failed to get WS endpoint");
            }

            // 注意：飞书返回的字段名是大写的（URL / ClientConfig / PingInterval）
            String wssUrl = endpointData.get("URL").getString();
            if (wssUrl == null || wssUrl.isEmpty()) {
                throw new RuntimeException("WS endpoint URL is empty");
            }

            long pingInterval = 20_000; // 默认20秒
            ONode clientConfig = endpointData.get("ClientConfig");
            if (clientConfig != null && !clientConfig.isNull()) {
                try {
                    // PingInterval 以秒为单位
                    Long interval = clientConfig.get("PingInterval").getLong();
                    if (interval != null && interval > 0) {
                        pingInterval = interval * 1000L;
                    }
                } catch (Exception ignored) {
                }
            }

            pingIntervalMs = pingInterval;
            LOG.info("[Feishu] Got WS endpoint, pingInterval={}ms", pingInterval);

            // 第二步：从 URL 提取 service_id 和 device_id（SDK 源码关键步骤）
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

            // 第三步：建立纯手工 WebSocket 连接（完全自主控制帧解析与解压缩）
            wsClient = new RawWsClient(URI.create(wssUrl), new RawWsClient.Listener() {
                @Override
                public void onBinary(ByteBuffer data) {
                    try {
                        FeishuPbCodec.Frame frame = FeishuPbCodec.decode(data);
                        handlePbFrame(frame);
                    } catch (Exception e) {
                        LOG.error("[Feishu] Failed to parse binary frame: {}", e.getMessage(), e);
                    }
                }

                @Override
                public void onOpen() {
                    LOG.info("[Feishu] Raw WS connection opened");
                    startHeartbeat();
                }

                @Override
                public void onClose(int code, String reason) {
                    LOG.info("[Feishu] Raw WS closed: code={}, reason={}", code, reason);
                    stopHeartbeat();
                    if (running.get() && streamStarted) {
                        streamStarted = false;
                        scheduleReconnect();
                    }
                }

                @Override
                public void onError(Exception ex) {
                    LOG.error("[Feishu] Raw WS error: {}", ex.getMessage(), ex);
                }
            });

            boolean connected = wsClient.connect();
            if (!connected) {
                throw new RuntimeException("Raw WebSocket connect returned false");
            }
            streamStarted = true;

            LOG.info("[Feishu] Raw WebSocket connected successfully");

            // 保持线程存活
            while (!Thread.currentThread().isInterrupted() && running.get()) {
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
            // 自动重连
            scheduleReconnect();
        }
    }

    // ==================== 飞书 WebSocket 客户端（Protobuf 二进制协议） ====================

    /**
     * 心跳调度器
     */
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile ScheduledExecutorService heartbeatScheduler;

    // ==================== Protobuf 帧处理 ====================

    /**
     * 处理解析后的 Protobuf 帧
     *
     * <p>帧类型：</p>
     * <ul>
     *   <li>method=0 (CONTROL)：ping/pong、disconnect</li>
     *   <li>method=1 (DATA)：业务事件推送（消息接收等）</li>
     * </ul>
     */
    private void handlePbFrame(FeishuPbCodec.Frame frame) {
        LOG.debug("[Feishu] PB frame: method={}, service={}, seqId={}, headers={}",
                frame.method, frame.service, frame.seqId, frame.headers.size());

        switch (frame.method) {
            case 0: // CONTROL
                handleControl(frame);
                break;
            case 1: // DATA
                handleData(frame);
                break;
            case 2: // ACK
                LOG.debug("[Feishu] Received ACK for seqId={}", frame.seqId);
                break;
            default:
                LOG.debug("[Feishu] Unknown frame method: {}", frame.method);
                break;
        }
    }

    /**
     * 处理 CONTROL 帧（与 SDK handleControlFrame 一致）
     *
     * <p>SDK 行为：</p>
     * <ul>
     *   <li>ping: 直接 return（客户端无需回复服务端 ping，SDK 不回复）</li>
     *   <li>pong: 如果有 payload 则解析 ClientConfig 更新配置</li>
     * </ul>
     */
    private void handleControl(FeishuPbCodec.Frame frame) {
        String type = frame.getHeader("type");
        if (type == null) {
            LOG.debug("[Feishu] CONTROL frame without type header");
            return;
        }

        switch (type) {
            case "ping": {
                // SDK 收到服务端 ping 时直接 return，不回复 pong
                LOG.debug("[Feishu] Received server ping, seqId={}", frame.seqId);
                return;
            }
            case "pong": {
                LOG.debug("[Feishu] Received pong, seqId={}", frame.seqId);
                // SDK：如果有 payload 则解析 ClientConfig 更新配置
                String configJson = frame.getPayloadAsString();
                if (configJson != null && !configJson.isEmpty()) {
                    try {
                        ONode conf = ONode.ofJson(configJson);
                        Integer interval = conf.get("PingInterval").getInt();
                        if (interval != null && interval > 0) {
                            pingIntervalMs = interval * 1000L;
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
                streamStarted = false;
                scheduleReconnect();
                break;
            }
            default: {
                LOG.debug("[Feishu] Unknown CONTROL type: {}", type);
                break;
            }
        }
    }

    /**
     * 处理 DATA 帧（与 SDK handleDataFrame 一致）
     *
     * <p>SDK 行为（关键！）：</p>
     * <ol>
     *   <li>从 headers 提取 message_id, trace_id, sum, seq, type</li>
     *   <li>处理合包（sum > 1 时合并分片）</li>
     *   <li>根据 type（event/card）处理业务</li>
     *   <li>回复一个 **DATA 帧**（method=1），payload 为 {"code":200,...}，附加 biz_rt header</li>
     * </ol>
     * <p>注意：SDK 的 FrameType 只有 CONTROL(0) 和 DATA(1)，没有 ACK(2)。</p>
     */
    private void handleData(FeishuPbCodec.Frame frame) {
        long startMs = System.currentTimeMillis();

        // 从 headers 提取元数据
        String msgId = frame.getHeader("message_id");
        String traceId = frame.getHeader("trace_id");
        String type = frame.getHeader("type");

        LOG.debug("[Feishu] DATA frame: type={}, msgId={}, traceId={}", type, msgId, traceId);

        // 回复 DATA 响应帧（与 SDK 一致：不是 ACK，是 DATA 帧带 {"code":200}）
        int code = 200;
        byte[] respPayload;
        try {
            // 处理业务
            String payloadJson = frame.getPayloadAsString();
            if (payloadJson != null && !payloadJson.isEmpty()) {
                LOG.debug("[Feishu] DATA payload: {}", payloadJson.substring(0, Math.min(payloadJson.length(), 300)));
                ONode eventNode = ONode.ofJson(payloadJson);
                onWsEvent(eventNode);
            }
            respPayload = "{\"code\":200}".getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.error("[Feishu] Failed to handle DATA: {}", e.getMessage(), e);
            code = 500;
            respPayload = "{\"code\":500}".getBytes(StandardCharsets.UTF_8);
        }

        long elapsedMs = System.currentTimeMillis() - startMs;

        // 发送 DATA 响应帧（与 SDK 一致：preserve payloadEncoding/payloadType, 添加 biz_rt header）
        byte[] respBytes = FeishuPbCodec.buildDataResponse(frame, respPayload, elapsedMs);
        if (wsClient != null && wsClient.isRunning()) {
            wsClient.send(respBytes);
            LOG.debug("[Feishu] Sent DATA response code={}, elapsed={}ms", code, elapsedMs);
        }
    }

    /**
     * 处理解析后的事件 JSON
     *
     * <p>飞书事件格式：</p>
     * <pre>
     * {
     *   "schema": "2.0",
     *   "header": { "event_type": "im.message.receive_v1", ... },
     *   "event": { "sender": {...}, "message": {...} }
     * }
     * </pre>
     */
    private void onWsEvent(ONode eventNode) {
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
            onImMessageReceive(eventNode);
        }
        // 其他事件类型忽略
    }

    /**
     * 处理飞书 im.message.receive_v1 事件
     */
    private void onImMessageReceive(ONode msg) {
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
                    // content 格式: {"text":"xxx"}
                    ONode contentNode = ONode.ofJson(contentJson);
                    text = contentNode.get("text").getString();
                } catch (Exception e) {
                    // 降级：直接用 content
                    text = contentJson;
                }
            }
        }

        if (text == null || text.isEmpty()) {
            LOG.debug("[Feishu] Ignored non-text message from {}", openId);
            return;
        }

        LOG.info("[Feishu] Received from {}: {}", openId, text.substring(0, Math.min(text.length(), 50)));

        // 如果有 pending 会话，自动绑定
        if (pendingSessionId != null) {
            bindSession(pendingSessionId, openId);
        }

        // 路由到已绑定的会话
        String sessionId = openIdToSession.get(openId);
        if (sessionId == null) {
            LOG.warn("[Feishu] Received message from unbound user: openId={}", openId);
            return;
        }

        FeishuBinding binding = bindings.get(sessionId);
        if (binding == null) return;

        // 防重复处理
        if (msgId != null && msgId.equals(binding.lastMessageId)) {
            return;
        }
        binding.lastMessageId = msgId;

        final String finalSessionId = sessionId;
        final String finalText = text;
        messageExecutor.execute(() -> {
            try {
                webGate.onFeishuMessage(finalSessionId, finalText);
            } catch (Exception e) {
                LOG.error("[Feishu] Message processing error: {}", e.getMessage(), e);
            }
        });
    }

    // ==================== 重连机制 ====================

    /**
     * 延迟重连（避免频繁重连）
     */
    private void scheduleReconnect() {
        synchronized (reconnectLock) {
            if (wsClient != null) {
                try {
                    wsClient.disconnect();
                } catch (Exception ignored) {
                }
                wsClient = null;
            }

            LOG.info("[Feishu] Reconnecting in 5 seconds...");
            Thread reconnectThread = new Thread(() -> {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                if (!streamStarted && appId != null && appSecret != null && running.get()) {
                    doStartStream();
                }
            }, "feishu-reconnect");
            reconnectThread.setDaemon(true);
            reconnectThread.start();
        }
    }

    /**
     * 停止 Stream 连接
     */
    // ==================== 心跳管理 ====================

    private void startHeartbeat() {
        stopHeartbeat();
        if (pingIntervalMs > 0) {
            final int sid = serviceId;
            heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "feishu-heartbeat");
                t.setDaemon(true);
                return t;
            });
            heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(() -> {
                try {
                    if (wsClient != null && wsClient.isRunning()) {
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
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }
    }

    private void stopStream() {
        streamStarted = false;
        stopHeartbeat();
        if (wsClient != null) {
            try {
                wsClient.disconnect();
            } catch (Exception ignored) {
            }
            wsClient = null;
        }
        if (streamThread != null) {
            streamThread.interrupt();
            streamThread = null;
        }
        LOG.info("[Feishu] Stream stopped");
    }

    /**
     * 停止所有资源
     */
    public void stop() {
        running.set(false);
        stopStream();
        messageExecutor.shutdownNow();
        LOG.info("[Feishu] Link stopped");
    }

    // ==================== Stream 状态查询 ====================

    /**
     * 获取当前 Stream 状态（供前端轮询）
     */
    public Map<String, Object> getStreamStatus(String sessionId) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("streamStarted", streamStarted);
        status.put("pending", pendingSessionId != null && pendingSessionId.equals(sessionId));
        status.put("bound", bindings.containsKey(sessionId));
        return status;
    }

    // ==================== 绑定管理 ====================

    /**
     * 绑定飞书用户到指定会话
     */
    public void bindSession(String sessionId, String openId) {
        FeishuBinding binding = new FeishuBinding();
        binding.openId = openId;
        binding.lastMessageId = "";
        binding.appId = this.appId;
        binding.appSecret = this.appSecret;

        // 一个 openId 只能绑定一个 session（与微信行为一致）
        Set<String> unbindSessionIds = new HashSet<>();
        bindings.forEach((k, v) -> {
            if (v.openId.equals(openId)) {
                unbindSessionIds.add(k);
            }
        });
        for (String unbindSessionId : unbindSessionIds) {
            unbindSession(unbindSessionId);
        }

        bindings.put(sessionId, binding);
        openIdToSession.put(openId, sessionId);

        // 清除 pending
        if (sessionId.equals(pendingSessionId)) {
            pendingSessionId = null;
        }

        // 清除当前 wsClient 的 pending 状态（如果有 SocketException）
        credentialStore.save(bindings);
        LOG.info("[Feishu] Session {} bound to Feishu user {}", sessionId, openId);
    }

    /**
     * 解绑飞书
     */
    public void unbindSession(String sessionId) {
        FeishuBinding binding = bindings.remove(sessionId);
        if (binding != null) {
            openIdToSession.remove(binding.openId);
        }
        credentialStore.save(bindings);
        LOG.info("[Feishu] Session {} unbound", sessionId);
    }

    /**
     * 从持久化存储恢复所有已绑定的会话
     */
    public void loadBindings() {
        Map<String, FeishuBinding> restored = credentialStore.load();
        if (restored.isEmpty()) return;

        LOG.info("[Feishu] Restoring {} saved binding(s)", restored.size());
        for (Map.Entry<String, FeishuBinding> entry : restored.entrySet()) {
            String sessionId = entry.getKey();
            FeishuBinding binding = entry.getValue();
            bindings.put(sessionId, binding);
            openIdToSession.put(binding.openId, sessionId);
            LOG.info("[Feishu] Restored session {} -> openId {}", sessionId, binding.openId);

            // 从第一个绑定记录中恢复凭据
            if (this.appId == null && binding.appId != null && !binding.appId.isEmpty()) {
                this.appId = binding.appId;
                this.appSecret = binding.appSecret;
                LOG.info("[Feishu] Restored credentials, appId={}", appId.substring(0, Math.min(8, appId.length())) + "...");
            }
        }
    }

    /**
     * 获取所有已绑定会话 ID
     */
    public Set<String> getBoundSessionIds() {
        return Collections.unmodifiableSet(bindings.keySet());
    }

    // ==================== 消息发送 ====================

    private void sendReplyDo(FeishuBinding binding, String reply) {
        try {
            // 获取 tenant_access_token
            String token = FeishuClient.getTenantAccessToken(appId, appSecret);
            if (token == null) {
                LOG.error("[Feishu] Cannot send reply: failed to get access token");
                return;
            }

            // 清理 markdown 标记（飞书文本消息不渲染 markdown）
            String cleanReply = cleanMarkdown(reply);
            if (cleanReply.isEmpty()) {
                cleanReply = reply;
            }

            // 飞书消息长度限制约 4000 字符
            int maxLen = 4000;
            if (cleanReply.length() <= maxLen) {
                FeishuClient.sendMessage(token, "open_id", binding.openId, cleanReply);
            } else {
                int pos = 0;
                int part = 1;
                while (pos < cleanReply.length()) {
                    int end = Math.min(pos + maxLen, cleanReply.length());
                    String chunk = cleanReply.substring(pos, end);
                    if (part > 1) {
                        chunk = "(" + part + ") " + chunk;
                    }
                    FeishuClient.sendMessage(token, "open_id", binding.openId, chunk);
                    pos = end;
                    part++;
                }
            }
        } catch (Exception e) {
            LOG.error("[Feishu] sendReplyDo error: {}", e.getMessage(), e);
        }
    }

    /**
     * 清理 Markdown 格式为纯文本
     */
    private String cleanMarkdown(String text) {
        return text
                .replaceAll("`{3}[\\s\\S]*?`{3}", "")       // 去掉代码块
                .replaceAll("`([^`]+)`", "$1")                // 去掉行内代码
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")      // 去掉加粗
                .replaceAll("\\*([^*]+)\\*", "$1")             // 去掉斜体
                .trim();
    }

    // ==================== 内部数据类 ====================

    public static class FeishuBinding {
        public String openId;         // 飞书用户 open_id（唯一标识）
        public String lastMessageId;  // 最后处理的消息 ID（防重复）
        public String appId;          // 飞书应用 App ID（凭据，随绑定一起持久化）
        public String appSecret;      // 飞书应用 App Secret（凭据，随绑定一起持久化）
    }
}
