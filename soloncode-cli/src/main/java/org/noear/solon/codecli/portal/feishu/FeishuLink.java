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

import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.config.AgentProperties;
import org.noear.solon.codecli.portal.IMLink;
import org.noear.solon.codecli.portal.WebGate;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 飞书 Bot 通道（基于飞书官方 SDK WebSocket 长连接）
 *
 * <p>使用飞书官方 SDK（com.larksuite.oapi:oapi-sdk）的 WebSocket 长连接接收消息，
 * 通过会话绑定将飞书用户与 Web 前端会话关联。</p>
 *
 * <p>绑定流程：
 * <ol>
 *   <li>前端提交 App ID + App Secret → 调用 {@link #startStream}，WebSocket 连接启动</li>
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
     * 飞书 API Client（SDK 管理 token 刷新）
     */
    private volatile Client apiClient;

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

        if (apiClient == null) {
            LOG.warn("[Feishu] Cannot send reply: API client not initialized");
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
     *
     * @param appId     飞书应用 App ID
     * @param appSecret 飞书应用 App Secret
     * @param sessionId 等待绑定的 Web 会话 ID
     * @return true=启动成功并进入 pending 等待状态
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

        // 初始化飞书 API Client（SDK 自动管理 token）
        this.apiClient = Client.newBuilder(appId, appSecret).build();

        // 在新线程中启动 WebSocket
        streamThread = new java.lang.Thread(() -> doStartStream(), "feishu-stream");
        streamThread.setDaemon(true);
        streamThread.start();

        return true;
    }

    /**
     * 内部方法：建立飞书 WebSocket 长连接并阻塞
     */
    private void doStartStream() {
        LOG.info("[Feishu] Starting WebSocket connection, appId={}",
                appId != null ? appId.substring(0, Math.min(8, appId.length())) + "..." : "null");

        try {
            // 构建事件处理器
            EventDispatcher eventHandler = EventDispatcher.newBuilder("", "")
                    .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                        @Override
                        public void handle(P2MessageReceiveV1 event) throws Exception {
                            onFeishuEvent(event);
                        }
                    })
                    .build();

            // 构建 WebSocket 客户端（使用全限定名避免与 com.lark.oapi.Client 冲突）
            com.lark.oapi.ws.Client wsClient = new com.lark.oapi.ws.Client.Builder(appId, appSecret)
                    .eventHandler(eventHandler)
                    .build();

            // start() 会阻塞直到连接建立成功（内部自动重连）
            wsClient.start();
            streamStarted = true;

            LOG.info("[Feishu] WebSocket client started successfully");

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
            LOG.error("[Feishu] WebSocket client error: {}", e.getMessage(), e);
            streamStarted = false;
        }
    }

    /**
     * 停止 Stream 连接
     */
    private void stopStream() {
        streamStarted = false;
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

        // 保存 appId/appSecret 到持久化
        credentialStore.saveWithCredentials(bindings, appId, appSecret);
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
        credentialStore.saveWithCredentials(bindings, appId, appSecret);
        LOG.info("[Feishu] Session {} unbound", sessionId);
    }

    /**
     * 从持久化存储恢复所有已绑定的会话
     */
    public void loadBindings() {
        FeishuCredentialStore.RestoreData restored = credentialStore.loadWithCredentials();
        if (restored.bindings.isEmpty() && restored.appId == null) return;

        // 恢复 appId/appSecret（如果有保存）
        if (restored.appId != null && !restored.appId.isEmpty()) {
            this.appId = restored.appId;
            this.appSecret = restored.appSecret;
            this.apiClient = Client.newBuilder(appId, appSecret).build();
            LOG.info("[Feishu] Restored credentials, appId={}", appId.substring(0, Math.min(8, appId.length())) + "...");
        }

        LOG.info("[Feishu] Restoring {} saved binding(s)", restored.bindings.size());
        for (Map.Entry<String, FeishuBinding> entry : restored.bindings.entrySet()) {
            String sessionId = entry.getKey();
            FeishuBinding binding = entry.getValue();
            bindings.put(sessionId, binding);
            openIdToSession.put(binding.openId, sessionId);
            LOG.info("[Feishu] Restored session {} -> openId {}", sessionId, binding.openId);
        }
    }

    /**
     * 获取所有已绑定会话 ID
     */
    public Set<String> getBoundSessionIds() {
        return Collections.unmodifiableSet(bindings.keySet());
    }

    // ==================== 消息处理 ====================

    /**
     * 处理从飞书 SDK 收到的事件
     */
    private void onFeishuEvent(P2MessageReceiveV1 event) {
        try {
            P2MessageReceiveV1Data eventData = event.getEvent();
            if (eventData == null) return;

            EventSender sender = eventData.getSender();
            if (sender == null) return;

            String openId = sender.getSenderId().getOpenId();
            EventMessage message = eventData.getMessage();
            if (message == null) return;

            String msgId = message.getMessageId();
            String msgType = message.getMessageType();

            // 只处理文本消息
            String text = null;
            if ("text".equals(msgType)) {
                String contentJson = message.getContent();
                if (contentJson != null && !contentJson.isEmpty()) {
                    try {
                        // content 格式: {"text":"xxx"}
                        java.util.Map<String, Object> contentObj = Jsons.DEFAULT.fromJson(contentJson, java.util.Map.class);
                        if (contentObj != null && contentObj.get("text") != null) {
                            text = contentObj.get("text").toString();
                        }
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

        } catch (Exception e) {
            LOG.error("[Feishu] Event handling error: {}", e.getMessage(), e);
        }
    }

    private void sendReplyDo(FeishuBinding binding, String reply) {
        if (apiClient == null) {
            LOG.error("[Feishu] Cannot send reply: API client not initialized");
            return;
        }

        try {
            // 清理 markdown 标记（飞书文本消息不渲染 markdown）
            String cleanReply = cleanMarkdown(reply);
            if (cleanReply.isEmpty()) {
                cleanReply = reply;
            }

            // 飞书消息长度限制约 4000 字符
            int maxLen = 4000;
            if (cleanReply.length() <= maxLen) {
                sendTextMessage(binding.openId, cleanReply);
            } else {
                int pos = 0;
                int part = 1;
                while (pos < cleanReply.length()) {
                    int end = Math.min(pos + maxLen, cleanReply.length());
                    String chunk = cleanReply.substring(pos, end);
                    if (part > 1) {
                        chunk = "(" + part + ") " + chunk;
                    }
                    sendTextMessage(binding.openId, chunk);
                    pos = end;
                    part++;
                }
            }
        } catch (Exception e) {
            LOG.error("[Feishu] sendReplyDo error: {}", e.getMessage(), e);
        }
    }

    /**
     * 通过飞书 SDK 发送文本消息
     */
    private void sendTextMessage(String openId, String text) throws Exception {
        CreateMessageReq req = CreateMessageReq.newBuilder()
                .receiveIdType("open_id")
                .createMessageReqBody(CreateMessageReqBody.newBuilder()
                        .receiveId(openId)
                        .msgType("text")
                        .content("{\"text\":\"" + escapeJson(text) + "\"}")
                        .build())
                .build();

        CreateMessageResp resp = apiClient.im().message().create(req);
        if (resp == null || resp.getData() == null) {
            LOG.warn("[Feishu] sendMessage response is empty");
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

    /**
     * JSON 字符串转义
     */
    private String escapeJson(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ==================== 内部数据类 ====================

    public static class FeishuBinding {
        public String openId;         // 飞书用户 open_id（唯一标识）
        public String lastMessageId;  // 最后处理的消息 ID（防重复）
    }
}
