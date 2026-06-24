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
package org.noear.solon.codecli.channel.wechat;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.portal.web.WebChunk;
import org.noear.solon.codecli.portal.web.WebGate;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RunUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微信 iLink Bot 通道
 *
 * <p>每个绑定了微信的会话对应一个独立的长轮询线程，
 * 从微信收取消息后通过 HarnessEngine 调用 AI，再将回复发回微信。</p>
 *
 * @author noear 2026/5/5 created
 */
public class WeChatLink implements Channel, Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(WeChatLink.class);

    private final HarnessEngine engine;
    private final WebGate webGate;
    private final WeChatCredentialStore credentialStore;

    /**
     * sessionId -> WeChatBinding
     */
    private final Map<String, WeChatBinding> bindings = new ConcurrentHashMap<>();

    /**
     * sessionId -> PollWorker
     */
    private final Map<String, Future<?>> pollWorkers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "wechat-poll");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);

    public WeChatLink(HarnessEngine engine, WebGate webGate) {
        this.engine = engine;
        this.webGate = webGate;
        this.credentialStore = new WeChatCredentialStore(engine);

        webGate.getStreamBuilder().bind(this);
    }

    /**
     * 绑定微信到指定会话
     */
    public void bindSession(String sessionId, String botToken, String ilinkBotId, String ilinkUserId) {
        WeChatBinding binding = new WeChatBinding();
        binding.botToken = botToken;
        binding.ilinkBotId = ilinkBotId;
        binding.ilinkUserId = ilinkUserId;
        binding.cursor = "";

        //一个 ilinkUserId 只能补一个 session 绑定
        Set<String> unbindSessionIds = new HashSet<>();
        bindings.forEach((k, v) -> {
            if (v.ilinkUserId.equals(ilinkUserId)) {
                unbindSessionIds.add(k);
            }
        });
        for (String unbindSessionId : unbindSessionIds) {
            RunUtil.runAndTry(() -> {
                unbindSession(unbindSessionId);
            });
        }

        bindings.put(sessionId, binding);

        // 持久化凭据
        credentialStore.save(bindings);

        // 启动该会话的长轮询
        startPolling(sessionId);

        LOG.info("[WeChat] Session {} bound to WeChat user {}", sessionId, ilinkUserId);
    }

    /**
     * 解绑微信
     */
    public void unbindSession(String sessionId) {
        bindings.remove(sessionId);
        stopPolling(sessionId);
        // 持久化凭据（解绑后保存空的映射会删除文件）
        credentialStore.save(bindings);
        LOG.info("[WeChat] Session {} unbound", sessionId);
    }

    @Override
    public String getChannelName() {
        return "wechat";
    }

    /**
     * 查询会话是否已绑定微信
     */
    @Override
    public boolean isBound(String sessionId) {
        return bindings.containsKey(sessionId);
    }

    /**
     * 获取所有已绑定会话 ID
     */
    public Set<String> getBoundSessionIds() {
        return Collections.unmodifiableSet(bindings.keySet());
    }

    /**
     * 从持久化存储恢复所有已绑定的会话
     */
    public void loadBindings() {
        Map<String, WeChatBinding> saved = credentialStore.load();
        if (saved.isEmpty()) return;

        LOG.info("[WeChat] Restoring {} saved binding(s)", saved.size());
        for (Map.Entry<String, WeChatBinding> entry : saved.entrySet()) {
            String sessionId = entry.getKey();
            WeChatBinding binding = entry.getValue();
            bindings.put(sessionId, binding);
            startPolling(sessionId);
            LOG.info("[WeChat] Restored session {}", sessionId);
        }
    }

    @Override
    public void run() {
        if (!running.compareAndSet(false, true)) {
            return; // 已在运行
        }
        LOG.info("[WeChat] Link started");
        // 恢复已保存的绑定
        loadBindings();
        // 主线程保持存活，等待关闭信号
    }

    /**
     * 停止所有轮询并关闭
     */
    public void stop() {
        running.set(false);
        for (String sid : new ArrayList<>(pollWorkers.keySet())) {
            stopPolling(sid);
        }
        scheduler.shutdownNow();
        LOG.info("[WeChat] Link stopped");
    }

    private void startPolling(String sessionId) {
        stopPolling(sessionId); // 防止重复

        Future<?> future = scheduler.scheduleWithFixedDelay(() -> {
            if (!running.get()) return;
            try {
                pollOnce(sessionId);
            } catch (Exception e) {
                LOG.error("[WeChat] Poll error for session {}: {}", sessionId, e.getMessage());
            }
        }, 0, 2, TimeUnit.SECONDS);

        pollWorkers.put(sessionId, future);
    }

    private void stopPolling(String sessionId) {
        Future<?> future = pollWorkers.remove(sessionId);
        if (future != null) {
            future.cancel(true);
        }
    }

    /**
     * 单次轮询：获取消息 -> 调用 AI -> 回复微信
     */
    private void pollOnce(String sessionId) {
        WeChatBinding binding = bindings.get(sessionId);
        if (binding == null) {
            stopPolling(sessionId);
            return;
        }

        Map<String, Object> result = WeChatClient.getUpdates(binding.botToken, binding.cursor);
        if (result == null) return;

        // token 过期，自动解绑
        if (Boolean.TRUE.equals(result.get("expired"))) {
            LOG.warn("[WeChat] Token expired for session {}, auto-unbinding", sessionId);
            unbindSession(sessionId);
            // 通知前端
            webGate.emitToClient(sessionId, WebChunk.ofError("微信连接已过期，请重新扫码绑定"));
            webGate.emitToClient(sessionId, WebChunk.ofDone());
            return;
        }

        // 更新游标
        String newCursor = (String) result.get("cursor");
        if (newCursor != null && !newCursor.isEmpty()) {
            binding.cursor = newCursor;
        }

        // 处理消息
        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) result.get("messages");
        if (Assert.isEmpty(messages)) {
            return;
        }

        for (Map<String, String> msg : messages) {
            String text = msg.get("text");
            String fromUserId = msg.get("from_user_id");
            String contextToken = msg.get("context_token");

            if (text == null || text.isEmpty()) continue;

            LOG.info("[WeChat] Received from {}: {}", fromUserId, text.substring(0, Math.min(text.length(), 50)));

            // 缓存 context_token（回复时必须携带）
            binding.lastContextToken = contextToken;
            binding.lastFromUserId = fromUserId;

            // 获取 typing_ticket 并发送"正在输入"状态
            if (binding.typingTicket == null && fromUserId != null && contextToken != null) {
                binding.typingTicket = WeChatClient.getConfig(binding.botToken, fromUserId, contextToken);
            }
            if (binding.typingTicket != null) {
                WeChatClient.sendTyping(binding.botToken, fromUserId, binding.typingTicket, 1);
            }

            // 调用 AI 并回复
            webGate.safeChatInput(sessionId, text, "WeChat");

            // 停止输入状态
            if (binding.typingTicket != null) {
                WeChatClient.sendTyping(binding.botToken, fromUserId, binding.typingTicket, 2);
            }
        }
    }

    @Override
    public void sendReply(String sessionId, String reply, boolean isFinal) {
        if(isFinal == false){
            return;
        }

        WeChatBinding binding = bindings.get(sessionId);
        if (binding == null) {
            return;
        }

        if (Assert.isEmpty(reply) || binding.lastContextToken == null) {
            return;
        }

        RunUtil.runAndTry(() -> {
            sendReplyDo(binding, reply);
        });
    }

    private void sendReplyDo(WeChatBinding binding, String reply) {
        // 清理 markdown 标记，微信不渲染 markdown
        String cleanReply = reply
                .replaceAll("`{3}[\\s\\S]*?`{3}", "") // 去掉代码块
                .replaceAll("`([^`]+)`", "$1")          // 去掉行内代码
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1") // 去掉加粗
                .replaceAll("\\*([^*]+)\\*", "$1")       // 去掉斜体
                .trim();

        if (cleanReply.isEmpty()) {
            cleanReply = reply; // fallback 到原文
        }

        // 微信消息长度限制，分段发送（每段最多 2000 字符）
        int maxLen = 2000;
        if (cleanReply.length() <= maxLen) {
            WeChatClient.sendMessage(binding.botToken, binding.lastFromUserId, binding.lastContextToken, cleanReply);
        } else {
            // 分段发送
            int pos = 0;
            int part = 1;
            while (pos < cleanReply.length()) {
                int end = Math.min(pos + maxLen, cleanReply.length());
                String chunk = cleanReply.substring(pos, end);
                if (part > 1) {
                    chunk = "(" + part + ") " + chunk;
                }
                WeChatClient.sendMessage(binding.botToken, binding.lastFromUserId, binding.lastContextToken, chunk);
                pos = end;
                part++;
            }
        }
    }

    // ==================== 内部数据类 ====================

    public static class WeChatBinding {
        public String botToken;
        public String ilinkBotId;
        public String ilinkUserId;
        public String cursor;
        public String lastContextToken;
        public String lastFromUserId;
        /**
         * typing_ticket 缓存，从 getconfig 获取，有效期约 24 小时
         */
        public String typingTicket;
    }
}