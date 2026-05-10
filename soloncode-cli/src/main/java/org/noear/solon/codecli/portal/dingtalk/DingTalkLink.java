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
package org.noear.solon.codecli.portal.dingtalk;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.chatbot.BotReplier;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.config.AgentProperties;
import org.noear.solon.codecli.portal.IMLink;
import org.noear.solon.codecli.portal.WebGate;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * 钉钉 Bot 通道（基于钉钉 Stream SDK）
 *
 * <p>使用钉钉官方 Stream SDK（OpenClaw 机器人应用）建立长连接，
 * 通过 {@link OpenDingTalkStreamClientBuilder} 注册机器人消息回调，
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
public class DingTalkLink implements IMLink, Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(DingTalkLink.class);

    private final HarnessEngine engine;
    private final WebGate webGate;
    private final DingTalkCredentialStore credentialStore;

    /**
     * 当前已配置的 appKey（可能从 config.yml 预加载，也可能由前端动态提交）
     */
    private volatile String appKey;
    private volatile String appSecret;

    /**
     * 钉钉 Stream 客户端实例
     */
    private volatile OpenDingTalkClient streamClient;

    /**
     * Stream 是否已启动（连接成功）
     */
    private volatile boolean streamStarted = false;

    /**
     * 待绑定的会话 ID（用户在前端点击绑定后设置，等待钉钉端发消息来自动完成绑定）
     */
    private volatile String pendingSessionId;

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

    /**
     * Stream 连接线程
     */
    private volatile Thread streamThread;

    public DingTalkLink(HarnessEngine engine, WebGate webGate) {
        this.engine = engine;
        this.webGate = webGate;
        AgentProperties agentProps = (AgentProperties) engine.getProps();
        this.credentialStore = new DingTalkCredentialStore(agentProps);

        // 尝试从配置文件预加载 appKey/appSecret
        this.appKey = agentProps.getDingtalkAppKey();
        this.appSecret = agentProps.getDingtalkAppSecret();

        webGate.getStreamBuilder().bind(this);

        // 尝试恢复已保存的绑定（含 appKey/appSecret）
        loadBindings();
    }

    // ==================== IMLink 接口实现 ====================

    @Override
    public String getChannelName() {
        return "dingtalk";
    }

    @Override
    public boolean isBound(String sessionId) {
        return bindings.containsKey(sessionId);
    }

    @Override
    public void sendReply(String sessionId, String reply) {
        DingTalkBinding binding = bindings.get(sessionId);
        if (binding == null) {
            return;
        }

        if (Assert.isEmpty(reply)) {
            return;
        }

        // 优先使用缓存的 sessionWebhook 回复（BotReplier）
        String webhook = webhookCache.get(binding.userId);
        if (webhook != null && !webhook.isEmpty()) {
            try {
                BotReplier replier = BotReplier.fromWebhook(webhook);
                // 钉钉单条消息长度限制
                int maxLen = 5000;
                if (reply.length() <= maxLen) {
                    replier.replyText(reply);
                } else {
                    int pos = 0;
                    int part = 1;
                    while (pos < reply.length()) {
                        int end = Math.min(pos + maxLen, reply.length());
                        String chunk = reply.substring(pos, end);
                        if (part > 1) {
                            chunk = "(" + part + ") " + chunk;
                        }
                        replier.replyText(chunk);
                        pos = end;
                        part++;
                    }
                }
                return;
            } catch (Exception e) {
                LOG.warn("[DingTalk] BotReplier reply failed, falling back to API: {}", e.getMessage());
                // webhook 可能已过期，清除缓存，降级到 API 发送
                webhookCache.remove(binding.userId);
            }
        }

        // 降级：使用 API 方式发送
        sendReplyViaApi(binding, reply);
    }

    // ==================== 生命周期 ====================

    /**
     * Runnable 入口：如果预配置了 appKey/appSecret 则直接启动 Stream，
     * 否则空跑等待前端动态调用 startStream()。
     */
    @Override
    public void run() {
        if (Assert.isEmpty(appKey) || Assert.isEmpty(appSecret)) {
            LOG.info("[DingTalk] No appKey/appSecret configured, waiting for web bind...");
            // 空跑保持线程存活（startStream 会另起新线程）
            while (!Thread.currentThread().isInterrupted()) {
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
     * @param appKey    钉钉机器人应用 AppKey（Client ID）
     * @param appSecret 钉钉机器人应用 AppSecret（Client Secret）
     * @param sessionId 等待绑定的 Web 会话 ID
     * @return true=启动成功并进入 pending 等待状态
     */
    public synchronized boolean startStream(String appKey, String appSecret, String sessionId) {
        if (appKey == null || appKey.isEmpty() || appSecret == null || appSecret.isEmpty()) {
            LOG.warn("[DingTalk] startStream: appKey or appSecret is empty");
            return false;
        }

        // 如果已用相同凭据启动，只需设置 pending
        if (streamStarted && appKey.equals(this.appKey) && appSecret.equals(this.appSecret)) {
            LOG.info("[DingTalk] Stream already started with same credentials, setting pending session: {}", sessionId);
            this.pendingSessionId = sessionId;
            return true;
        }

        // 如果已有连接但凭据不同，先关闭旧连接
        if (streamClient != null) {
            stopStream();
        }

        this.appKey = appKey;
        this.appSecret = appSecret;
        this.pendingSessionId = sessionId;

        // 在新线程中启动 Stream
        streamThread = new Thread(() -> doStartStream(), "dingtalk-stream");
        streamThread.setDaemon(true);
        streamThread.start();

        return true;
    }

    /**
     * 内部方法：建立 Stream 长连接并阻塞
     */
    private void doStartStream() {
        LOG.info("[DingTalk] Starting stream connection, appKey={}",
                appKey != null ? appKey.substring(0, Math.min(8, appKey.length())) + "..." : "null");

        try {
            streamClient = OpenDingTalkStreamClientBuilder.custom()
                    .credential(new AuthClientCredential(appKey, appSecret))
                    .registerCallbackListener(
                            "/v1.0/im/bot/messages/get",
                            new OpenDingTalkCallbackListener<ChatbotMessage, Void>() {
                                @Override
                                public Void execute(ChatbotMessage message) {
                                    onBotMessage(message);
                                    return null;
                                }
                            }
                    )
                    .build();

            // start() 会阻塞直到连接建立成功（内部自动重连）
            streamClient.start();
            streamStarted = true;

            LOG.info("[DingTalk] Stream client started successfully");

            // 主线程保持存活
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(60000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Exception e) {
            LOG.error("[DingTalk] Stream client error: {}", e.getMessage(), e);
            streamStarted = false;
        }
    }

    /**
     * 停止 Stream 连接
     */
    private void stopStream() {
        streamStarted = false;
        streamClient = null;
        if (streamThread != null) {
            streamThread.interrupt();
            streamThread = null;
        }
        LOG.info("[DingTalk] Stream stopped");
    }

    /**
     * 获取当前 Stream 绑定状态（供前端轮询）
     *
     * @param sessionId 会话 ID
     * @return 状态信息：streamStarted、pending、bound
     */
    public Map<String, Object> getStreamStatus(String sessionId) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("streamStarted", streamStarted);
        status.put("pending", sessionId != null && sessionId.equals(pendingSessionId));
        status.put("bound", sessionId != null && bindings.containsKey(sessionId));
        return status;
    }

    /**
     * 停止所有资源
     */
    public void stop() {
        stopStream();
        messageExecutor.shutdownNow();
        LOG.info("[DingTalk] Link stopped");
    }

    // ==================== 绑定管理 ====================

    /**
     * 手动绑定钉钉用户到指定会话（兼容旧接口，一般不使用）
     */
    public void bindSession(String sessionId, String userId, String robotCode) {
        doBindSession(sessionId, userId, robotCode);
    }

    /**
     * 内部绑定实现
     */
    private void doBindSession(String sessionId, String userId, String robotCode) {
        DingTalkBinding binding = new DingTalkBinding();
        binding.userId = userId;
        binding.robotCode = robotCode != null ? robotCode : appKey;
        binding.appKey = this.appKey;
        binding.appSecret = this.appSecret;

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

        // 清除 pending
        if (sessionId.equals(pendingSessionId)) {
            pendingSessionId = null;
        }

        credentialStore.save(bindings);
        LOG.info("[DingTalk] Session {} bound to DingTalk user {}", sessionId, userId);
    }

    /**
     * 解绑钉钉
     */
    public void unbindSession(String sessionId) {
        doUnbindSession(sessionId);
    }

    private void doUnbindSession(String sessionId) {
        DingTalkBinding binding = bindings.remove(sessionId);
        if (binding != null) {
            userIdToSession.remove(binding.userId);
            webhookCache.remove(binding.userId);
        }
        credentialStore.save(bindings);
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

            // 如果绑定中有保存的 appKey/appSecret 且当前没有配置，则恢复
            if ((appKey == null || appKey.isEmpty()) && binding.appKey != null) {
                this.appKey = binding.appKey;
                this.appSecret = binding.appSecret;
            }
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
     * 处理从钉钉 Stream 收到的机器人消息
     *
     * <p>由 SDK 回调触发。从 ChatbotMessage 中提取发送者 userId、
     * 消息文本、sessionWebhook 等信息，路由到已绑定的 Web 会话。</p>
     *
     * <p>如果存在 pending 会话（等待绑定的会话），会自动将该用户绑定到 pending 会话。</p>
     */
    private void onBotMessage(ChatbotMessage message) {
        String userId = message.getSenderStaffId();
        if (userId == null || userId.isEmpty()) {
            userId = message.getSenderId();
        }

        String text = null;
        if (message.getText() != null) {
            text = message.getText().getContent();
        }
        // 兼容新版消息格式
        if ((text == null || text.isEmpty()) && message.getContent() != null) {
            text = message.getContent().getContent();
        }

        String msgId = message.getMsgId();
        String webhook = message.getSessionWebhook();
        String conversationType = message.getConversationType();

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
            if (pendingSessionId != null) {
                LOG.info("[DingTalk] Auto-binding user {} to pending session {}", userId, pendingSessionId);
                // OpenClaw 应用的 robotCode 通常就是 appKey
                String robotCode = appKey;
                doBindSession(pendingSessionId, userId, robotCode);
                sessionId = pendingSessionId;
                // 绑定成功后发一条欢迎提示
                if (webhook != null && !webhook.isEmpty()) {
                    try {
                        BotReplier.fromWebhook(webhook).replyText("绑定成功！已连接到 SolonCode Web 会话。");
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

        // 用 final 变量捕获，供 lambda 使用
        final String finalSessionId = sessionId;
        final String finalText = text;

        // 提交到消息处理线程
        messageExecutor.execute(() -> {
            try {
                webGate.onDingTalkMessage(finalSessionId, finalText);
            } catch (Exception e) {
                LOG.error("[DingTalk] Message processing error: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * 降级方案：通过 API 发送回复（当 sessionWebhook 不可用时）
     */
    private void sendReplyViaApi(DingTalkBinding binding, String reply) {
        String token = DingTalkClient.getAccessToken(appKey, appSecret);
        if (token == null) {
            LOG.error("[DingTalk] Cannot send reply: no access token");
            return;
        }

        String robotCode = binding.robotCode != null && !binding.robotCode.isEmpty()
                ? binding.robotCode : this.appKey; // OpenClaw 应用的 robotCode 通常就是 appKey

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

    // ==================== 内部数据类 ====================

    public static class DingTalkBinding {
        public String userId;          // 钉钉用户 staffId / userId
        public String robotCode;       // 机器人编码（发送消息用）
        public String lastMessageId;   // 最后处理的消息 ID（防重复）
        public String appKey;          // 保存的 AppKey（用于重启后恢复 Stream 连接）
        public String appSecret;       // 保存的 AppSecret
    }
}
