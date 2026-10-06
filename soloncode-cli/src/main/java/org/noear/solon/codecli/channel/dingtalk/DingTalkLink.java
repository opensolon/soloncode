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

import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.channel.ChunkedSender;
import org.noear.solon.codecli.channel.ImBindingRegistry;
import org.noear.solon.codecli.channel.ImGateway;
import org.noear.solon.codecli.channel.ImMessages;
import org.noear.solon.codecli.channel.ImStatus;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RunUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.*;

/**
 * 钉钉 Bot 通道（工作区侧薄适配层）。
 *
 * <p>本类不再持有任何 WebSocket 连接、协议处理或绑定路由状态：</p>
 * <ul>
 *   <li>连接与协议由进程级 {@link DingTalkTransport} 持有（随进程存续，不随工作区关闭断开）；</li>
 *   <li>绑定归属与消息投递由进程级 {@link ImGateway} 裁决（userId 为主键，全局唯一）；</li>
 *   <li>本类只负责面向 Web 层的会话维度读写：启动/解绑、状态查询，以及出站回复发送。</li>
 * </ul>
 *
 * <p>绑定流程（与旧实现一致）：提交 AppKey + AppSecret → 启动 Stream 连接并登记
 * 待绑定挂点 → 用户在钉钉端发消息 → 网关按 userId 完成绑定 → 前端轮询状态。</p>
 *
 * @author noear 2026/5/9 created
 */
public class DingTalkLink implements Channel, Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(DingTalkLink.class);

    private final WorkspaceContext wsContext;
    /** 进程级网关：绑定归属与连接归属的单一真相源。 */
    private final ImGateway gateway;
    /** 本工作区 ID；绑定归属与连接归属都以它为准。 */
    private final String workspaceId;

    public DingTalkLink(WorkspaceContext wsContext) {
        this.wsContext = wsContext;
        this.workspaceId = wsContext.getMeta().getId();
        this.gateway = ImGateway.getInstance(wsContext.getEngine());

        // 历史无归属条目按会话目录探测认领（幂等）
        loadBindings();
    }

    // ==================== Channel 接口实现 ====================

    @Override
    public String getChannelName() {
        return "dingtalk";
    }

    @Override
    public boolean isBound(String sessionId) {
        if (gateway.findDingTalkBySession(workspaceId, sessionId) != null) {
            return true;
        }
        // QR 扫码流：尚未完成绑定但已有 pending 挂点时也算 bound
        return gateway.isPendingDingTalk(workspaceId, sessionId);
    }

    @Override
    public void sendReply(String sessionId, String reply, boolean isFinal) {
        sendReply(sessionId, reply, isFinal, null, null, null);
    }

    @Override
    public void sendReply(String sessionId, String reply, boolean isFinal,
                          String sourceUserId, String replyTarget, String messageId) {
        DingTalkBinding binding = replyBinding(currentBinding(sessionId), sourceUserId, replyTarget);

        if (binding == null) {
            // QR 流：尚未完成绑定（无 userId），记录日志
            if (gateway.isPendingDingTalk(workspaceId, sessionId)) {
                sendReplyViaQrPending(sessionId, reply);
            }
            return;
        }

        if (Assert.isEmpty(reply)) {
            return;
        }

        if (binding.userId == null || binding.userId.isEmpty()) {
            LOG.warn("[DingTalk] sendReply: binding.userId is null for session {}, cannot send via API", sessionId);
            return;
        }

        // 始终通过钉钉 OpenAPI 发送回复
        // （WebSocket Stream 回复仅用于同步 ACK，不适用于异步 AI 响应场景。
        //   ACK 阶段已用 data="{}" 回复了 CALLBACK，再用同一 messageId 发消息会被钉钉服务器丢弃。）
        sendReplyViaApi(binding, reply);
    }

    /**
     * 交互状态信号：只下发非流式提示文本，不走流式分片。
     */
    @Override
    public void sendStatus(String sessionId, ImStatus status, String detail,
                           String sourceUserId, String replyTarget, String messageId) {
        DingTalkBinding binding = replyBinding(currentBinding(sessionId), sourceUserId, replyTarget);
        if (binding == null || binding.userId == null || binding.userId.isEmpty()) {
            return;
        }
        String text = ImMessages.textOf(status, detail);
        if (text != null) {
            sendHintToBinding(binding, text);
        }
    }

    /** 会话当前绑定（进程级网关为单一真相源）。 */
    private DingTalkBinding currentBinding(String sessionId) {
        ImBindingRegistry.Binding binding = gateway.findDingTalkBySession(workspaceId, sessionId);
        if (binding == null) {
            return null;
        }
        DingTalkBinding result = new DingTalkBinding();
        result.userId = binding.getUserKey();
        result.appKey = binding.getIdentity() == null ? null : binding.getIdentity().getAppKey();
        result.appSecret = binding.getSecret();
        result.robotCode = result.appKey;
        result.lastMessageId = binding.getLastMessageId();
        result.workspaceId = binding.getWorkspaceId();
        return result;
    }

    // 固定本次收件人及机器人凭据，不能从后来变化的绑定取目标。
    static DingTalkBinding replyBinding(DingTalkBinding binding, String sourceUserId, String replyTarget) {
        if (binding == null) return null;
        DingTalkBinding snapshot = new DingTalkBinding();
        snapshot.userId = !Assert.isEmpty(replyTarget) ? replyTarget
                : !Assert.isEmpty(sourceUserId) ? sourceUserId : binding.userId;
        snapshot.robotCode = binding.robotCode;
        snapshot.appKey = binding.appKey;
        snapshot.appSecret = binding.appSecret;
        return snapshot;
    }

    // ==================== 生命周期 ====================

    /**
     * 拉起钉钉传输层并恢复持久化连接。
     *
     * <p>连接归进程级传输层所有，本方法幂等：连接已存在时不会重复创建。</p>
     */
    @Override
    public void run() {
        gateway.startDingTalk();
    }

    /**
     * 空实现：连接归进程级传输层所有，不随工作区关闭而断开。
     */
    public void stop() {
        // 连接由 ImGateway/DingTalkTransport 管理，工作区关闭不释放
    }

    // ==================== 绑定操作 ====================

    /**
     * 动态启动 Stream 连接（由前端绑定操作触发）。
     */
    public boolean startStream(String appKey, String appSecret, String sessionId) {
        if (appKey == null || appKey.isEmpty() || appSecret == null || appSecret.isEmpty()) {
            LOG.warn("[DingTalk] startStream: appKey or appSecret is empty");
            return false;
        }

        boolean ok = gateway.getDingTalkTransport().startStream(appKey, appSecret, workspaceId, sessionId);
        if (!ok) {
            return false;
        }

        // 已绑定到本会话时无需等待首条消息；否则登记待绑定挂点
        ImBindingRegistry.Binding bound = gateway.findDingTalkBySession(workspaceId, sessionId);
        if (bound != null && bound.getIdentity() != null
                && appKey.equals(bound.getIdentity().getAppKey())) {
            gateway.clearPendingDingTalk(appKey);
            LOG.info("[DingTalk] startStream: appKey={}, session already bound={}", appKeyForLog(appKey), sessionId);
        } else {
            gateway.setPendingDingTalk(appKey, workspaceId, sessionId);
            LOG.info("[DingTalk] startStream: appKey={}, pendingSession={}", appKeyForLog(appKey), sessionId);
        }

        return true;
    }

    /**
     * 手动绑定钉钉用户到指定会话（兼容旧接口；扫码授权成功后由 Web 层调用）。
     */
    public void bindSession(String sessionId, String userId, String robotCode) {
        if (sessionId == null || userId == null) {
            return;
        }
        // 兼容旧签名：robotCode 即 appKey 的别名
        String appKey = robotCode;
        String appSecret = null;
        // 能确定 appKey 时按 bot 身份精确查找：钉钉 staffId 是组织维度的，
        // 同一人可能同时绑定多个 bot，扫全部身份会命中错误的那条
        ImBindingRegistry.Binding existing = (appKey == null)
                ? gateway.findDingTalkByUserId(userId)
                : gateway.findDingTalkByUserId(appKey, userId);
        if (existing != null) {
            appSecret = existing.getSecret();
            if (appKey == null && existing.getIdentity() != null) {
                appKey = existing.getIdentity().getAppKey();
            }
        }

        ImGateway.AdoptResult adopted = gateway.adoptDingTalk(
                userId, appKey, appSecret, workspaceId, sessionId, true);
        gateway.clearPendingDingTalk(appKey);

        // 换绑到不同 appKey 后，旧 appKey 已无任何绑定时断开其进程级连接（避免孤儿连接）
        ImBindingRegistry.Binding previous = adopted.getPrevious();
        if (previous != null && previous.getIdentity() != null) {
            String previousAppKey = previous.getIdentity().getAppKey();
            if (previousAppKey != null && !previousAppKey.equals(appKey)
                    && gateway.findDingTalkByAppKey(previousAppKey) == null) {
                gateway.getDingTalkTransport().stopConnection(previousAppKey);
            }
        }

        LOG.info("[DingTalk] Session {} bound to DingTalk user {}", sessionId, userId);
    }

    /**
     * 解绑钉钉会话；该 appKey 无其它绑定时断开进程级连接。
     */
    public synchronized void unbindSession(String sessionId) {
        ImBindingRegistry.Binding binding = gateway.findDingTalkBySession(workspaceId, sessionId);
        if (binding == null) {
            // 可能是 QR 半绑定（pending）：清理挂点即可
            return;
        }

        gateway.removeDingTalk(workspaceId, sessionId);

        String appKey = binding.getIdentity() == null ? null : binding.getIdentity().getAppKey();
        if (appKey != null) {
            gateway.clearPendingDingTalk(appKey);
            if (gateway.findDingTalkByAppKey(appKey) == null) {
                gateway.getDingTalkTransport().stopConnection(appKey);
            }
        }

        LOG.info("[DingTalk] Session {} unbound", sessionId);
    }

    // ==================== Stream 状态查询 ====================

    /**
     * 获取当前 Stream 状态（供前端轮询）。
     */
    public Map<String, Object> getStreamStatus(String sessionId) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("streamStarted", gateway.getDingTalkTransport().isStarted());
        status.put("pending", gateway.isPendingDingTalk(workspaceId, sessionId));
        // 绑定状态以进程级网关为单一真相源：bound 指本工作区该会话已绑定；
        // boundElsewhere 指同名会话绑定在其它工作区（精确到真实绑定条目）。
        ImBindingRegistry.SessionStatus bindingStatus = gateway.dingtalkStatus(workspaceId, sessionId);
        boolean bound = bindingStatus.isBound();
        status.put("bound", bound);
        status.put("boundElsewhere", !bound && bindingStatus.isBoundElsewhere());
        status.put("boundWorkspaceId", bindingStatus.getWorkspaceId());
        status.put("boundSessionId", bindingStatus.getSessionId());
        return status;
    }

    /**
     * 获取所有已绑定会话 ID
     */
    public Set<String> getBoundSessionIds() {
        Set<String> result = new LinkedHashSet<>();
        for (ImBindingRegistry.Binding binding : gateway.listDingTalk(workspaceId)) {
            if (binding.getSessionId() != null) {
                result.add(binding.getSessionId());
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * 历史数据认领：把无归属绑定按会话目录探测补归属（幂等）。
     */
    public void loadBindings() {
        for (ImBindingRegistry.Binding unclaimed : gateway.listUnclaimedDingTalk()) {
            String sessionId = unclaimed.getSessionId();
            if (sessionId != null && Files.isDirectory(wsContext.getSessionsRoot().resolve(sessionId))) {
                gateway.claimDingTalk(unclaimed.getIdentity(), unclaimed.getUserKey(), workspaceId);
            }
        }
    }

    private static String appKeyForLog(String appKey) {
        return appKey == null ? "null" : appKey.substring(0, Math.min(8, appKey.length())) + "...";
    }

    // ==================== 消息发送 ====================

    /**
     * 按绑定直接发送一条轻量提示（走 OpenAPI，不依赖连接）。
     */
    private void sendHintToBinding(DingTalkBinding binding, String text) {
        final String appKey = binding.appKey;
        final String appSecret = binding.appSecret;
        final String robotCode = binding.robotCode != null && !binding.robotCode.isEmpty()
                ? binding.robotCode : binding.appKey;
        final String userId = binding.userId;
        RunUtil.async(() -> {
            try {
                String token = DingTalkClient.getAccessToken(appKey, appSecret);
                if (token != null) {
                    DingTalkClient.sendSingleMarkdownMessage(token, robotCode, userId, "提示", text);
                }
            } catch (Exception e) {
                LOG.warn("[DingTalk] Hint send error: {}", e.getMessage());
            }
        });
    }

    private static final int MAX_SEND_RETRIES = 3;

    /**
     * 通过 API 发送回复（钉钉通道固定走 OpenAPI）。
     */
    private void sendReplyViaApi(DingTalkBinding binding, String reply) {
        // 外层重试：应对 token 获取失败或偶发网络抖动
        for (int attempt = 0; attempt < MAX_SEND_RETRIES; attempt++) {
            try {
                String token = DingTalkClient.getAccessToken(binding.appKey, binding.appSecret);
                if (token == null) {
                    if (attempt < MAX_SEND_RETRIES - 1) {
                        long delay = 1000L * (1L << attempt);
                        LOG.warn("[DingTalk] Token fetch failed, retry in {}ms (attempt {}/{})",
                                delay, attempt + 1, MAX_SEND_RETRIES);
                        Thread.sleep(delay);
                        continue;
                    }
                    LOG.error("[DingTalk] Cannot send reply: no access token after {} attempts", MAX_SEND_RETRIES);
                    return;
                }

                final String fToken = token;
                final String fRobotCode = binding.robotCode != null && !binding.robotCode.isEmpty()
                        ? binding.robotCode : binding.appKey;
                final String fUserId = binding.userId;

                LOG.info("[DingTalk] sendReplyViaApi: robotCode={}, userId={}, replyLen={}, attempt={}",
                        fRobotCode, fUserId, reply.length(), attempt + 1);

                // 使用 Markdown 格式分段发送（含限速 + 重试）
                ChunkedSender.SendResult result = ChunkedSender.sendChunked(reply,
                        ChunkedSender.Config.dingtalk(),
                        (chunk, part) -> {
                            String title;
                            if (part > 1) {
                                title = "(" + part + ") " + DingTalkClient.extractTitle(chunk);
                            } else {
                                title = DingTalkClient.extractTitle(chunk);
                            }
                            boolean ok = DingTalkClient.sendSingleMarkdownMessage(fToken, fRobotCode, fUserId, title, chunk);
                            LOG.info("[DingTalk] sendSingleMarkdownMessage part={} ok={}, robotCode={}, userId={}",
                                    part, ok, fRobotCode, fUserId);
                            return ok;
                        });

                // 部分失败告警
                if (result.getFailedParts() > 0 && result.getFailedParts() < result.getTotalParts()) {
                    LOG.warn("[DingTalk] Partial send failure: {}/{} parts failed",
                            result.getFailedParts(), result.getTotalParts());
                }

                // 全部失败，走外层重试
                if (result.getFailedParts() == result.getTotalParts() && result.getTotalParts() > 0) {
                    if (attempt < MAX_SEND_RETRIES - 1) {
                        long delay = 1000L * (1L << attempt);
                        LOG.warn("[DingTalk] All parts failed, retry in {}ms (attempt {}/{})",
                                delay, attempt + 1, MAX_SEND_RETRIES);
                        Thread.sleep(delay);
                        continue;
                    }
                    LOG.error("[DingTalk] All send attempts failed for {} part(s)", result.getTotalParts());
                }

                // 发送成功，退出重试
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("[DingTalk] sendReplyViaApi interrupted");
                return;
            } catch (Exception e) {
                LOG.error("[DingTalk] sendReplyViaApi error (attempt {}/{}): {}",
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
     * QR 扫码半绑定状态发送降级：binding 中没有 userId（用户尚未在钉钉上发消息），
     * 无法通过单聊 API 发送。记录警告日志，等待用户发消息完成绑定。
     */
    private void sendReplyViaQrPending(String sessionId, String reply) {
        LOG.warn("[DingTalk] Cannot send reply to session {}: QR binding pending, " +
                "user needs to send a DingTalk message first", sessionId);
    }

    // ==================== 内部数据类 ====================

    public static class DingTalkBinding {
        public String userId;          // 钉钉用户 staffId / userId
        public String robotCode;       // 机器人编码（发送消息用）
        public volatile String lastMessageId;   // 最后处理的消息 ID（防重复）
        public String appKey;          // 保存的 AppKey（用于重启后恢复 Stream 连接）
        public String appSecret;       // 保存的 AppSecret
        public String workspaceId;     // 绑定归属的工作区 ID
    }
}
