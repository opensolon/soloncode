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

import java.nio.file.Files;
import java.util.*;
import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.channel.ChunkedSender;
import org.noear.solon.codecli.channel.ImBindingRegistry;
import org.noear.solon.codecli.channel.ImGateway;
import org.noear.solon.codecli.channel.ImStatus;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RunUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书 Bot 通道（工作区侧薄适配层）。
 *
 * <p>本类不再持有任何 WebSocket 连接、协议处理或绑定路由状态：</p>
 * <ul>
 *   <li>连接与协议由进程级 {@link FeishuTransport} 持有（随进程存续，不随工作区关闭断开）；</li>
 *   <li>绑定归属与消息投递由进程级 {@link ImGateway} 裁决（openId 为主键，全局唯一）；</li>
 *   <li>本类只负责面向 Web 层的会话维度读写：启动/解绑、状态查询，以及出站回复发送。</li>
 * </ul>
 *
 * <p>绑定流程（与旧实现一致）：提交 App ID + App secret → 启动 Stream 连接并登记
 * 待绑定挂点 → 用户在飞书端发消息 → 网关按 openId 完成绑定 → 前端轮询状态。</p>
 *
 * @author noear 2026/5/9 created
 */
public class FeishuLink implements Channel, Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuLink.class);

    private final WorkspaceContext wsContext;
    /** 进程级网关：绑定归属与连接归属的单一真相源。 */
    private final ImGateway gateway;
    /** 本工作区 ID；绑定归属与连接归属都以它为准。 */
    private final String workspaceId;

    public FeishuLink(WorkspaceContext wsContext) {
        this.wsContext = wsContext;
        this.workspaceId = wsContext.getMeta().getId();
        this.gateway = ImGateway.getInstance(wsContext.getEngine());

        // 历史无归属条目按会话目录探测认领（幂等）
        loadBindings();
    }

    // ==================== Channel 接口实现 ====================

    @Override
    public String getChannelName() {
        return "feishu";
    }

    @Override
    public boolean isBound(String sessionId) {
        return gateway.findFeishuBySession(workspaceId, sessionId) != null;
    }

    @Override
    public void sendReply(String sessionId, String reply, boolean isFinal) {
        sendReply(sessionId, reply, isFinal, null, null, null);
    }

    @Override
    public void sendReply(String sessionId, String reply, boolean isFinal,
                          String sourceUserId, String replyTarget, String messageId) {
        FeishuBinding binding = replyBinding(currentBinding(sessionId), sourceUserId, replyTarget);
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
        FeishuBinding binding = replyBinding(currentBinding(sessionId), sourceUserId, replyTarget);
        if (binding == null || binding.appId == null || binding.appSecret == null) {
            return;
        }
        String text = org.noear.solon.codecli.channel.ImMessages.textOf(status, detail);
        if (text != null) {
            FeishuTransport.sendHint(binding.appId, binding.appSecret, binding.openId, text);
        }
    }

    /** 会话当前绑定（进程级网关为单一真相源）。 */
    private FeishuBinding currentBinding(String sessionId) {
        ImBindingRegistry.Binding binding = gateway.findFeishuBySession(workspaceId, sessionId);
        if (binding == null) {
            return null;
        }
        FeishuBinding result = new FeishuBinding();
        result.openId = binding.getUserKey();
        result.lastMessageId = binding.getLastMessageId() == null ? "" : binding.getLastMessageId();
        result.appId = binding.getIdentity() == null ? null : binding.getIdentity().getAppId();
        result.appSecret = binding.getSecret();
        result.workspaceId = binding.getWorkspaceId();
        return result;
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

    /**
     * 拉起飞书传输层并恢复持久化连接。
     *
     * <p>连接归进程级传输层所有，本方法幂等：连接已存在时不会重复创建。</p>
     */
    @Override
    public void run() {
        gateway.startFeishu();
    }

    /**
     * 空实现：连接归进程级传输层所有，不随工作区关闭而断开。
     */
    public void stop() {
        // 连接与租约由 ImGateway/FeishuTransport 管理，工作区关闭不释放
    }

    // ==================== 绑定操作 ====================

    /** 动态启动 Stream 连接；force=true 时允许把 appId 租约迁移到本工作区。 */
    public boolean startStream(String appId, String appSecret, String sessionId, boolean force) {
        if (appId == null || appId.isEmpty() || appSecret == null || appSecret.isEmpty()) {
            LOG.warn("[Feishu] startStream: appId or appSecret is empty");
            return false;
        }

        boolean ok = gateway.getFeishuTransport().startStream(appId, appSecret, workspaceId, sessionId, force);
        if (!ok) {
            return false;
        }

        // 已绑定到本会话时无需等待首条消息；否则登记待绑定挂点
        ImBindingRegistry.Binding bound = gateway.findFeishuBySession(workspaceId, sessionId);
        if (bound != null && bound.getIdentity() != null
                && appId.equals(bound.getIdentity().getAppId())) {
            gateway.clearPendingFeishu(appId);
            LOG.info("[Feishu] startStream: appId={}, session already bound={}",
                    appIdForLog(appId), sessionId);
        } else {
            gateway.setPendingFeishu(appId, workspaceId, sessionId);
            LOG.info("[Feishu] startStream: appId={}, pendingSession={}",
                    appIdForLog(appId), sessionId);
        }

        return true;
    }

    /** 保持旧调用方兼容，默认不迁移租约。 */
    public boolean startStream(String appId, String appSecret, String sessionId) {
        return startStream(appId, appSecret, sessionId, false);
    }

    /**
     * 绑定飞书用户到指定会话（扫码授权成功后由 Web 层调用）。
     */
    public void bindSession(String sessionId, String openId, String appId, String appSecret) {
        if (sessionId == null || openId == null) {
            return;
        }
        // 进程级网关为唯一真相源：openId 主键唯一，同一 bot 的旧绑定被原子替换（抢占迁移）
        ImGateway.AdoptResult adopted = gateway.adoptFeishu(
                openId, appId, appSecret, workspaceId, sessionId, true);
        gateway.clearPendingFeishu(appId);

        // 换绑到不同 appId 后，旧 appId 已无任何绑定时断开其进程级连接（避免孤儿连接）
        ImBindingRegistry.Binding previous = adopted.getPrevious();
        if (previous != null && previous.getIdentity() != null) {
            String previousAppId = previous.getIdentity().getAppId();
            if (previousAppId != null && !previousAppId.equals(appId)
                    && gateway.findFeishuByAppId(previousAppId) == null) {
                gateway.getFeishuTransport().stopConnection(previousAppId);
            }
        }

        LOG.info("[Feishu] Session {} bound to Feishu user {}", sessionId, openId);
    }

    /**
     * 解绑飞书会话；该 appId 无其它绑定时断开进程级连接。
     */
    public synchronized void unbindSession(String sessionId) {
        ImBindingRegistry.Binding binding = gateway.findFeishuBySession(workspaceId, sessionId);
        if (binding == null) {
            return;
        }

        gateway.removeFeishu(workspaceId, sessionId);

        String appId = binding.getIdentity() == null ? null : binding.getIdentity().getAppId();
        if (appId != null) {
            gateway.clearPendingFeishu(appId);
            if (gateway.findFeishuByAppId(appId) == null) {
                gateway.getFeishuTransport().stopConnection(appId);
            }
        }

        LOG.info("[Feishu] Session {} unbound", sessionId);
    }

    // ==================== 状态查询 ====================

    /**
     * 获取当前 Stream 状态（供前端轮询）。
     */
    public Map<String, Object> getStreamStatus(String sessionId) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("streamStarted", gateway.getFeishuTransport().isStarted());
        status.put("pending", gateway.isPendingFeishu(workspaceId, sessionId));
        // 绑定状态以进程级网关为单一真相源：bound 指本工作区该会话已绑定；
        // boundElsewhere 指同名会话绑定在其它工作区（精确到真实绑定条目）。
        ImBindingRegistry.SessionStatus bindingStatus = gateway.feishuStatus(workspaceId, sessionId);
        boolean bound = bindingStatus.isBound();
        status.put("bound", bound);
        status.put("boundElsewhere", !bound && bindingStatus.isBoundElsewhere());
        status.put("boundWorkspaceId", bindingStatus.getWorkspaceId());
        status.put("boundSessionId", bindingStatus.getSessionId());
        return status;
    }

    /**
     * 历史数据认领：把无归属绑定按会话目录探测补归属（幂等）。
     */
    public void loadBindings() {
        for (ImBindingRegistry.Binding unclaimed : gateway.listUnclaimedFeishu()) {
            String sessionId = unclaimed.getSessionId();
            if (sessionId != null && Files.isDirectory(wsContext.getSessionsRoot().resolve(sessionId))) {
                gateway.claimFeishu(unclaimed.getIdentity(), unclaimed.getUserKey(), workspaceId);
            }
        }
    }

    private static String appIdForLog(String appId) {
        return appId == null ? "null" : appId.substring(0, Math.min(8, appId.length())) + "...";
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
        public volatile String lastMessageId;  // 最后处理的消息 ID（防重复，volatile 保证多线程可见性）
        public String appId;          // 飞书应用 App ID（凭据，随绑定一起持久化）
        public String appSecret;      // 飞书应用 App Secret（凭据，随绑定一起持久化）
        public String workspaceId;    // 绑定归属的工作区 ID
    }
}
