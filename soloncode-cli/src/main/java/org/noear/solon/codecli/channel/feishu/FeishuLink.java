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
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.noear.java_websocket.client.SimpleWebSocketClient;
import org.noear.snack4.ONode;
import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.channel.ChunkedSender;
import org.noear.solon.codecli.channel.ImMessages;
import org.noear.solon.codecli.channel.ImStatus;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceLogRouter;
import org.noear.solon.codecli.workspace.WorkspaceMessageGateway;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RunUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书 Bot 通道（基于飞书 WebSocket 长连接 + Protobuf 协议）
 *
 * <p>支持多 appId 多连接：每个 appId 独立一条 WebSocket 连接，
 * 不同 session 可以绑定不同的飞书机器人（不同 appId/appSecret）。</p>
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
public class FeishuLink implements Channel, Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuLink.class);

    private final WorkspaceContext wsContext;
    private final FeishuCredentialStore credentialStore;
    /** 本工作区 ID；绑定归属与连接归属都以它为准。 */
    private final String workspaceId;

    /**
     * appId -> StreamConnection（每个 appId 独立一条 WebSocket 连接）
     */
    private final Map<String, StreamConnection> connections = new ConcurrentHashMap<>();

    /**
     * sessionId -> FeishuBinding
     */
    private final Map<String, FeishuBinding> bindings = new ConcurrentHashMap<>();

    /**
     * openId -> sessionId（反向映射，用于从飞书消息路由到会话）
     */
    private final Map<String, String> openIdToSession = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);

    public FeishuLink(WorkspaceContext wsContext) {
        this.wsContext = wsContext;
        this.workspaceId = wsContext.getMeta().getId();
        this.credentialStore = new FeishuCredentialStore(wsContext.getEngine(), workspaceId);

        // 尝试恢复已保存的绑定（含 appId/appSecret）
        loadBindings();
    }

    // ==================== Channel 接口实现 ====================

    @Override
    public String getChannelName() {
        return "feishu";
    }

    @Override
    public boolean isBound(String sessionId) {
        return bindings.containsKey(sessionId);
    }

    @Override
    public void sendReply(String sessionId, String reply, boolean isFinal) {
        sendReply(sessionId, reply, isFinal, null, null, null);
    }

    @Override
    public void sendReply(String sessionId, String reply, boolean isFinal,
                          String sourceUserId, String replyTarget, String messageId) {
        FeishuBinding binding = replyBinding(bindings.get(sessionId), sourceUserId, replyTarget);
        if (binding == null || Assert.isEmpty(reply)) {
            return;
        }

        if (binding.appId == null || binding.appSecret == null) {
            LOG.warn("[Feishu] Cannot send reply: binding credentials not initialized");
            return;
        }

        RunUtil.async(() -> {
            try {
                sendReplyDo(binding, reply);
            } catch (Exception e) {
                LOG.error("[Feishu] Reply error: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * 交互状态信号：只下发非流式提示文本，不走流式分片。
     */
    @Override
    public void sendStatus(String sessionId, ImStatus status, String detail,
                           String sourceUserId, String replyTarget, String messageId) {
        FeishuBinding binding = replyBinding(bindings.get(sessionId), sourceUserId, replyTarget);
        if (binding == null || binding.appId == null || binding.appSecret == null) {
            return;
        }
        String text = ImMessages.textOf(status, detail);
        if (text != null) {
            sendHint(binding.appId, binding.appSecret, binding.openId, text);
        }
    }

    // 发送前固定收件人和凭据，避免异步任务读取后来变更的绑定。
    static FeishuBinding replyBinding(FeishuBinding binding, String sourceUserId, String replyTarget) {
        if (binding == null) return null;
        FeishuBinding snapshot = new FeishuBinding();
        snapshot.openId = !Assert.isEmpty(replyTarget) ? replyTarget
                : !Assert.isEmpty(sourceUserId) ? sourceUserId : binding.openId;
        snapshot.appId = binding.appId;
        snapshot.appSecret = binding.appSecret;
        return snapshot;
    }

    // ==================== 生命周期 ====================

    @Override
    public void run() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        if (bindings.isEmpty()) {
            LOG.info("[Feishu] No bindings...");
            return;
        }

        // 按 appId 分组，为每个 appId 启动独立的 WebSocket 连接
        Map<String, FeishuBinding> byAppId = new LinkedHashMap<>();
        for (FeishuBinding b : bindings.values()) {
            if (b.appId != null && b.appSecret != null) {
                byAppId.putIfAbsent(b.appId, b);
            }
        }

        for (Map.Entry<String, FeishuBinding> entry : byAppId.entrySet()) {
            getOrCreateConnection(entry.getValue().appId, entry.getValue().appSecret);
        }
    }

    /**
     * 获取或创建指定 appId 的 Stream 连接（已存在则复用）
     */
    private StreamConnection getOrCreateConnection(String appId, String appSecret) {
        StreamConnection conn = connections.get(appId);
        if (conn != null) {
            return conn;
        }

        conn = new StreamConnection(appId, appSecret);
        StreamConnection existing = connections.putIfAbsent(appId, conn);
        if (existing != null) {
            return existing;
        }

        // 新连接，启动
        conn.start();
        return conn;
    }

    /**
     * 动态启动 Stream 连接（由前端绑定操作触发）
     */
    public boolean startStream(String appId, String appSecret, String sessionId) {
        if (appId == null || appId.isEmpty() || appSecret == null || appSecret.isEmpty()) {
            LOG.warn("[Feishu] startStream: appId or appSecret is empty");
            return false;
        }

        StreamConnection conn = getOrCreateConnection(appId, appSecret);
        // 扫码轮询成功时已经完成绑定；此时不能重新标记为 pending。
        // 否则首条消息到达后会再次 bindSession，同一会话可能先被解绑，导致连接被关闭。
        FeishuBinding binding = bindings.get(sessionId);
        if (binding != null && appId.equals(binding.appId)) {
            conn.pendingSessionId = null;
            LOG.info("[Feishu] startStream: appId={}, session already bound={}",
                    appId.substring(0, Math.min(8, appId.length())) + "...", sessionId);
        } else {
            conn.pendingSessionId = sessionId;
            LOG.info("[Feishu] startStream: appId={}, pendingSession={}",
                    appId.substring(0, Math.min(8, appId.length())) + "...", sessionId);
        }

        return true;
    }

    /**
     * 停止所有资源
     */
    public void stop() {
        running.set(false);
        for (StreamConnection conn : connections.values()) {
            conn.stop();
        }
        connections.clear();
        LOG.info("[Feishu] Link stopped");
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
        status.put("bound", bindings.containsKey(sessionId));
        return status;
    }

    // ==================== 绑定管理 ====================

    /**
     * 绑定飞书用户到指定会话
     */
    public synchronized void bindSession(String sessionId, String openId, String appId, String appSecret) {
        FeishuBinding current = bindings.get(sessionId);
        if (current != null && Objects.equals(current.openId, openId)
                && Objects.equals(current.appId, appId)) {
            // 扫码轮询与首条消息可能同时完成绑定；同一绑定必须幂等，
            // 尤其不能先 unbind 自己，否则 unbindSession 会关闭唯一的 Stream 连接。
            current.appSecret = appSecret;
            current.workspaceId = workspaceId;
            StreamConnection conn = connections.get(appId);
            if (conn != null && sessionId.equals(conn.pendingSessionId)) {
                conn.pendingSessionId = null;
            }
            credentialStore.save(bindings);
            LOG.debug("[Feishu] Session {} is already bound to Feishu user {}", sessionId, openId);
            return;
        }
        
        FeishuBinding binding = new FeishuBinding();
        binding.openId = openId;
        binding.lastMessageId = "";
        binding.appId = appId;
        binding.appSecret = appSecret;
        binding.workspaceId = workspaceId;

        // 一个 openId 只能绑定一个 session（与微信行为一致）
        Set<String> unbindSessionIds = new HashSet<>();
        bindings.forEach((k, v) -> {
            if (Objects.equals(v.openId, openId) && !sessionId.equals(k)) {
                unbindSessionIds.add(k);
            }
        });
        for (String unbindSessionId : unbindSessionIds) {
            unbindSession(unbindSessionId);
        }

        bindings.put(sessionId, binding);
        openIdToSession.put(openId, sessionId);

        // 清除连接的 pending 状态
        StreamConnection conn = connections.get(appId);
        if (conn != null && sessionId.equals(conn.pendingSessionId)) {
            conn.pendingSessionId = null;
        }

        credentialStore.save(bindings);
        LOG.info("[Feishu] Session {} bound to Feishu user {}", sessionId, openId);
    }

    /**
     * 解绑飞书
     */
    public synchronized void unbindSession(String sessionId) {
        FeishuBinding binding = bindings.remove(sessionId);
        if (binding == null) return;

        openIdToSession.remove(binding.openId);
        credentialStore.save(bindings);

        // 检查是否还有绑定使用此 appId，如果没有则关闭连接
        if (binding.appId != null) {
            boolean stillUsed = bindings.values().stream()
                    .anyMatch(b -> binding.appId.equals(b.appId));
            if (!stillUsed) {
                StreamConnection conn = connections.remove(binding.appId);
                if (conn != null) {
                    conn.stop();
                }
            }
        }

        LOG.info("[Feishu] Session {} unbound", sessionId);
    }

    /**
     * 从持久化存储恢复所有已绑定的会话。
     *
     * <p>绑定按 workspaceId 归属过滤：只装载归属本工作区的条目。历史遗留条目（无归属字段）
     * 按会话目录探测认领——sessions/&lt;sessionId&gt; 目录存在于本工作区即视为本工作区资产，
     * 认领后回写归属并持久化，其它工作区不再可见（杜绝多工作区同 appId 双连接随机路由）。</p>
     */
    public void loadBindings() {
        Map<String, FeishuBinding> restored = credentialStore.load();
        if (restored.isEmpty()) return;

        LOG.info("[Feishu] Restoring {} saved binding(s)", restored.size());
        boolean migrated = false;
        for (Map.Entry<String, FeishuBinding> entry : restored.entrySet()) {
            String sessionId = entry.getKey();
            FeishuBinding binding = entry.getValue();

            if (!ownsBinding(sessionId, binding)) {
                LOG.info("[Feishu] Skip binding of other workspace: session {} (owner={})", sessionId, binding.workspaceId);
                continue;
            }
            if (binding.workspaceId == null) {
                // 遗留条目认领：打上本工作区标记，后续保存会把归属写回文件
                binding.workspaceId = workspaceId;
                migrated = true;
            }
            bindings.put(sessionId, binding);
            openIdToSession.put(binding.openId, sessionId);
            LOG.info("[Feishu] Restored session {} -> openId {}", sessionId, binding.openId);
        }
        if (migrated) {
            credentialStore.save(bindings);
        }
    }

    /** 绑定是否归属本工作区：显式归属以 workspaceId 为准；遗留无归属条目按会话目录探测认领。 */
    boolean ownsBinding(String sessionId, FeishuBinding binding) {
        if (binding.workspaceId != null) {
            return workspaceId.equals(binding.workspaceId);
        }
        // 历史数据兼容：本工作区 sessions 目录下存在该会话目录则认领
        return Files.isDirectory(wsContext.getSessionsRoot().resolve(sessionId));
    }

    /**
     * 获取所有已绑定会话 ID
     */
    public Set<String> getBoundSessionIds() {
        return Collections.unmodifiableSet(bindings.keySet());
    }

    /**
     * 指定 appId 是否已存在活跃连接（供跨工作区绑定冲突检查）。
     * 同一 appId 若被多个工作区各自建立 Stream 连接，飞书服务端会随机路由消息，必须避免。
     */
    public boolean isAppInUse(String appId) {
        return appId != null && connections.containsKey(appId);
    }

    // ==================== WS 消息处理（由 StreamConnection 回调） ====================

    /**
     * 处理 StreamConnection 收到的二进制消息
     */
    private void onWsBinaryMessage(ByteBuffer bytes, StreamConnection conn) {
        try {
            FeishuPbCodec.Frame frame = FeishuPbCodec.decode(bytes);
            handlePbFrame(frame, conn);
        } catch (Exception e) {
            LOG.error("[Feishu] Failed to parse binary frame: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理解析后的 Protobuf 帧
     */
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

    /**
     * 处理 CONTROL 帧（ping/pong/disconnect）
     */
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

    /**
     * 处理 DATA 帧（业务事件推送）
     */
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

    /**
     * 处理解析后的事件 JSON
     */
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

    /**
     * 处理飞书 im.message.receive_v1 事件
     */
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
            // 统一回执：web 支持附件，IM 暂不支持时不能让用户以为消息已发出
            sendHint(conn.appId, conn.appSecret, openId, ImMessages.HINT_NON_TEXT());
            return;
        }

        LOG.info("[Feishu] Received from {}: {}", openId, text.substring(0, Math.min(text.length(), 50)));

        // 如果有 pending 会话，自动绑定
        if (conn.pendingSessionId != null) {
            bindSession(conn.pendingSessionId, openId, conn.appId, conn.appSecret);
        }

        // 路由到已绑定的会话
        String sessionId = openIdToSession.get(openId);
        if (sessionId == null) {
            LOG.warn("[Feishu] Received message from unbound user: openId={}", openId);
            // 统一引导：静默丢弃会让用户完全不知道自己没绑上
            sendHint(conn.appId, conn.appSecret, openId, ImMessages.HINT_UNBOUND());
            return;
        }

        FeishuBinding binding = bindings.get(sessionId);
        if (binding == null) return;

        // 防重复处理（仅限同连接生命周期内）
        if (msgId != null && msgId.equals(binding.lastMessageId)) {
            return;
        }

        final String finalSessionId = sessionId;
        final String finalText = text;
        final String finalOpenId = openId;
        final FeishuBinding finalBinding = binding;
        final String finalMsgId = msgId;
        RunUtil.async(() -> {
            try {
                // WebGate 仅在 web 模式下初始化；acp/cli 等模式恢复的绑定不应持有长连接
                //（由 ChannelHub.start 的调用方保证），此处判空防 NPE 静默吞消息。
                WorkspaceMessageGateway gateway = wsContext.getMessageGateway();
                if (gateway == null) {
                    LOG.warn("[Feishu] message gateway not ready, drop message from {}", finalOpenId);
                    return;
                }
                boolean accepted = gateway.acceptInput(wsContext, finalSessionId, finalText, "Feishu", finalOpenId, null, finalMsgId);
                if (accepted) {
                    // 消息被接受进入处理流程后才记录 lastMessageId，
                    // 避免 WS 断连重试时因 lastMessageId 已设置而跳过未处理的消息
                    if (finalMsgId != null) {
                        finalBinding.lastMessageId = finalMsgId;
                    }
                }
                // 未受理（如队列已满）已由 safeChatInput 统一回执 ImStatus.REJECTED，此处不再重复提示。
            } catch (Exception e) {
                LOG.error("[Feishu] Message processing error: {}", e.getMessage(), e);
            }
        });
    }

    // ==================== 消息发送 ====================

    private static final int MAX_SEND_RETRIES = 3;

    private void sendReplyDo(FeishuBinding binding, String reply) {
        // 外层重试：应对 token 获取失败或偶发网络抖动
        for (int attempt = 0; attempt < MAX_SEND_RETRIES; attempt++) {
            try {
                // 获取 tenant_access_token（使用绑定自己的凭据）
                String token = FeishuClient.getTenantAccessToken(binding.appId, binding.appSecret);
                if (token == null) {
                    if (attempt < MAX_SEND_RETRIES - 1) {
                        long delay = 1000L * (1L << attempt); // 1s, 2s
                        LOG.warn("[Feishu] Token fetch failed, retry in {}ms (attempt {}/{})",
                                delay, attempt + 1, MAX_SEND_RETRIES);
                        Thread.sleep(delay);
                        continue;
                    }
                    LOG.error("[Feishu] Cannot send reply: failed to get access token after {} attempts", MAX_SEND_RETRIES);
                    return;
                }

                final String fToken = token;
                final String fOpenId = binding.openId;

                // ★ 不再 cleanMarkdown：直接以 Markdown 格式发送（飞书 post + md tag）
                ChunkedSender.SendResult result = ChunkedSender.sendChunked(reply,
                        ChunkedSender.Config.feishu(),
                        (chunk, part) -> {
                            String title = part > 1 ? "(" + part + ")" : "";
                            String msgId = FeishuClient.sendMdPostMessage(fToken, "open_id", fOpenId, title, chunk);
                            return msgId != null;
                        });

                // 部分发送失败告警（非全部失败，不需要降级，但需记录）
                if (result.getFailedParts() > 0 && result.getFailedParts() < result.getTotalParts()) {
                    LOG.warn("[Feishu] Partial send failure: {}/{} parts failed", result.getFailedParts(), result.getTotalParts());
                }

                // 如果全部失败，降级为纯文本发送
                if (result.getFailedParts() == result.getTotalParts() && result.getTotalParts() > 0) {
                    LOG.warn("[Feishu] MD post failed, degrading to plain text for {} part(s)", result.getTotalParts());
                    String cleanReply = cleanMarkdown(reply);
                    if (cleanReply.isEmpty()) {
                        cleanReply = reply;
                    }
                    ChunkedSender.SendResult textResult = ChunkedSender.sendChunked(cleanReply,
                            ChunkedSender.Config.feishu(),
                            (chunk, part) -> {
                                String msgId = FeishuClient.sendMessage(fToken, "open_id", fOpenId, chunk);
                                return msgId != null;
                            });

                    // 降级也失败，走外层重试
                    if (textResult.getFailedParts() == textResult.getTotalParts() && textResult.getTotalParts() > 0) {
                        if (attempt < MAX_SEND_RETRIES - 1) {
                            long delay = 1000L * (1L << attempt); // 1s, 2s
                            LOG.warn("[Feishu] Plain text fallback also failed, retry in {}ms (attempt {}/{})",
                                    delay, attempt + 1, MAX_SEND_RETRIES);
                            Thread.sleep(delay);
                            continue;
                        }
                        LOG.error("[Feishu] All send attempts failed for {} part(s)", result.getTotalParts());
                    } else if (textResult.getFailedParts() > 0) {
                        // 降级后部分失败告警
                        LOG.warn("[Feishu] Partial plain text failure: {}/{} parts failed",
                                textResult.getFailedParts(), textResult.getTotalParts());
                    }
                }

                // 发送成功（MD 成功或降级成功），退出重试
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("[Feishu] sendReplyDo interrupted");
                return;
            } catch (Exception e) {
                LOG.error("[Feishu] sendReplyDo error (attempt {}/{}): {}",
                        attempt + 1, MAX_SEND_RETRIES, e.getMessage());
                if (attempt < MAX_SEND_RETRIES - 1) {
                    try {
                        long delay = 1000L * (1L << attempt);
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    /**
     * 直接向指定用户发送一条轻量提示（不依赖会话绑定）。
     *
     * <p>用于未绑定用户引导与非文本消息回执：这些场景还没有 session，走不了 sendReply 的绑定路径。</p>
     */
    private void sendHint(String appId, String appSecret, String openId, String text) {
        if (appId == null || appSecret == null || openId == null) {
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

    // ==================== 内部连接类（每个 appId 独立一条连接） ====================

    /**
     * 单个 appId 的 WebSocket 长连接。
     * 封装了连接生命周期、心跳、重连等所有连接级状态。
     */
    private class StreamConnection {
        final String appId;
        final String appSecret;

        volatile SimpleWebSocketClient wsClient;
        volatile boolean streamStarted = false;
        volatile Thread streamThread;
        volatile Thread reconnectThread;
        final Object reconnectLock = new Object();

        /**
         * 待绑定的会话 ID（此连接上等待飞书用户发消息来自动完成绑定）
         */
        volatile String pendingSessionId;

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

        StreamConnection(String appId, String appSecret) {
            this.appId = appId;
            this.appSecret = appSecret;
        }

        /**
         * 启动连接（在新线程中执行）
         */
        void start() {
            String threadName = "feishu-stream-" + appId.substring(0, Math.min(6, appId.length()));
            streamThread = new Thread(WorkspaceLogRouter.withWorkspaceLogKey(wsContext.getMeta().getPath(), this::doStart), threadName);
            streamThread.setDaemon(true);
            streamThread.start();
        }

        /**
         * 停止连接（释放所有资源）
         */
        void stop() {
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
            LOG.info("[Feishu] Starting WebSocket connection, appId={}",
                    appId.substring(0, Math.min(8, appId.length())) + "...");

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
                        FeishuLink.this.onWsBinaryMessage(bytes, StreamConnection.this);
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        LOG.info("[Feishu] WS closed: code={}, reason={}, remote={}", code, reason, remote);
                        stopHeartbeat();
                        if (running.get() && streamStarted) {
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

                LOG.info("[Feishu] WebSocket connected successfully, appId={}",
                        appId.substring(0, Math.min(8, appId.length())) + "...");

                // 重连成功后清除此 appId 相关的去重缓存，
                // 确保 Feishu 在断连期间缓存的事件重推后能被正常处理
                for (FeishuBinding b : FeishuLink.this.bindings.values()) {
                    if (appId.equals(b.appId)) {
                        b.lastMessageId = "";
                    }
                }

                // 保持线程存活（便于 stop() 通过 interrupt 终止）
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

                LOG.info("[Feishu] Reconnecting in 5 seconds, appId={}",
                        appId.substring(0, Math.min(8, appId.length())) + "...");
                reconnectThread = new Thread(WorkspaceLogRouter.withWorkspaceLogKey(wsContext.getMeta().getPath(), () -> {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!streamStarted && running.get()) {
                        doStart();
                    }
                }), "feishu-reconnect");
                reconnectThread.setDaemon(true);
                reconnectThread.start();
            }
        }
    }

    // ==================== 内部数据类 ====================

    public static class FeishuBinding {
        public String openId;         // 飞书用户 open_id（唯一标识）
        public volatile String lastMessageId;  // 最后处理的消息 ID（防重复，volatile 保证多线程可见性）
        public String appId;          // 飞书应用 App ID（凭据，随绑定一起持久化）
        public String appSecret;      // 飞书应用 App Secret（凭据，随绑定一起持久化）
        public String workspaceId;    // 绑定归属的工作区 ID（多工作区同 appId 会双连接随机路由，必须隔离）
    }
}