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

import java.util.*;
import org.noear.solon.codecli.artifact.PublishedImage;
import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.channel.ChannelReply;
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
    private static final FeishuReplyQueue REPLY_QUEUE = new FeishuReplyQueue(64, 1024);

    /** 进程级网关：绑定归属与连接归属的单一真相源。 */
    private final ImGateway gateway;
    /** 本工作区 ID；绑定归属与连接归属都以它为准。 */
    private final String workspaceId;

    public FeishuLink(WorkspaceContext wsContext) {
        this.workspaceId = wsContext.getMeta().getId();
        this.gateway = ImGateway.getInstance(wsContext.getEngine());
        // 构造期不做任何副作用：这里既不认领历史条目、也不落盘。
        // ChannelHub 由 WorkspaceContext 构造，而工作区在 acp/cli 模式下同样会被创建，
        // 在构造器里改登记表会让「无入口端口模式不碰 IM」的约定失效。
    }

    /** 供渠道适配测试注入网关，不启动连接或创建工作区。 */
    protected FeishuLink(String workspaceId, ImGateway gateway) {
        this.workspaceId = workspaceId;
        this.gateway = gateway;
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

        enqueueReply(binding, () -> sendReplyDo(binding, reply));
    }

    /**
     * 结构化回复：固定绑定快照，在目标 FIFO 内上传并原位替换图片。
     * 上传结果（包括失败）只在本次回复中缓存，正文复用旧 Markdown 分片、重试与降级。
     */
    @Override
    public void sendReply(String sessionId, ChannelReply reply,
                          String sourceUserId, String replyTarget, String messageId) {
        if (reply == null || (Assert.isEmpty(reply.getText()) && !reply.hasImageReferences())) {
            return;
        }
        FeishuBinding binding = replyBinding(currentBinding(sessionId), sourceUserId, replyTarget);
        if (binding == null) {
            return;
        }
        if (binding.appId == null || binding.appSecret == null) {
            LOG.warn("[Feishu] Cannot send reply: binding credentials not initialized");
            return;
        }
        long imageBytes = 0;
        // 闭包持有完整引用快照，预算必须包含未上传（超出五图限制）的图片。
        for (PublishedImage image : reply.getImages()) {
            imageBytes += image.byteSize();
        }
        REPLY_QUEUE.enqueue(binding.appId, binding.openId, imageBytes, () -> {
            if (reply.hasImageReferences()) {
                sendStructuredReply(binding, reply);
            } else {
                sendReplyDo(binding, reply.getText());
            }
        }, this::submitReplyTask);
    }

    private void enqueueReply(FeishuBinding binding, Runnable task) {
        REPLY_QUEUE.enqueue(binding.appId, binding.openId, task, this::submitReplyTask);
    }
    /** 可替换的消费者启动边界；发送任务已经入队，覆盖此方法不会绕过串行调度。 */
    protected void submitReplyTask(Runnable task) {
        RunUtil.async(task);
    }

    private void sendStructuredReply(FeishuBinding binding, ChannelReply reply) {
        Map<String, String> uploaded = new HashMap<>();
        ChannelReply.RenderedImages markdown = reply.renderImageNodes(image -> {
            String id = image.getUuid();
            if (uploaded.containsKey(id)) {
                return uploaded.get(id);
            }
            if (Thread.currentThread().isInterrupted() || uploaded.size() >= 5) {
                return null;
            }
            // 先缓存失败，保证重复引用、异常及正文重试都不会触发再次上传。
            uploaded.put(id, null);
            try {
                byte[] bytes = image.getBytes();
                String filename = bytes.length >= 3 && bytes[0] == (byte) 0xff
                        && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff
                        ? "image.jpg" : "image.png";
                String key = uploadReplyImage(binding, bytes, filename);
                if (!Assert.isEmpty(key)) {
                    uploaded.put(id, key);
                } else {
                    LOG.warn("[Feishu] Reply image upload returned no image_key; using safe placeholder");
                }
            } catch (Exception e) {
                // 不把响应、原文件名或本地路径带入正文和日志。
                LOG.warn("[Feishu] Reply image upload failed; using safe placeholder");
            }
            return uploaded.get(id);
        }, ChannelReply.ExternalImagePolicy.HTTP_LINK);
        if (!Thread.currentThread().isInterrupted()) {
            sendReplyDo(binding, markdown.getText(), reply.renderImages(image -> null), markdown);
        }
    }

    protected String getReplyAccessToken(FeishuBinding binding) {
        return FeishuClient.getTenantAccessToken(binding.appId, binding.appSecret);
    }

    protected boolean sendReplyTextChunk(String token, String openId, String chunk, int part) {
        return FeishuClient.sendMdPostMessage(token, "open_id", openId,
                part > 1 ? "(" + part + ")" : "", chunk) != null;
    }

    protected boolean sendReplyPlainTextChunk(String token, String openId, String chunk) {
        return FeishuClient.sendMessage(token, "open_id", openId, chunk) != null;
    }

    protected String uploadReplyImage(FeishuBinding binding, byte[] bytes, String filename) {
        return FeishuClient.uploadImage(binding.appId, binding.appSecret, bytes, filename);
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
    protected FeishuBinding currentBinding(String sessionId) {
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
     * <p>这是本渠道唯一的进程级启动入口，由 {@code ChannelHub.start()} 调用，而后者只在
     * 入口运行时就绪后才被拉起（acp/cli 等无入口端口模式不会走到这里）。</p>
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

    private static String appIdForLog(String appId) {
        return appId == null ? "null" : appId.substring(0, Math.min(8, appId.length())) + "...";
    }

    // ==================== 消息发送 ====================

    private static final int MAX_SEND_RETRIES = 3;

    private void sendReplyDo(FeishuBinding binding, String reply) {
        sendReplyDo(binding, reply, reply);
    }

    private void sendReplyDo(FeishuBinding binding, String reply, String safeText) {
        sendReplyDo(binding, reply, safeText, null);
    }

    private void sendReplyDo(FeishuBinding binding, String reply, String safeText, ChannelReply.RenderedImages nodes) {
        // 外层重试：应对 token 获取失败或偶发网络抖动；上传已在此循环外完成。
        for (int attempt = 0; attempt < MAX_SEND_RETRIES; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                // 获取 tenant_access_token（使用绑定自己的凭据）
                String token = getReplyAccessToken(binding);
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
                ChunkedSender.SendFunc sender = (chunk, part) -> !Thread.currentThread().isInterrupted()
                        && sendReplyTextChunk(fToken, fOpenId, chunk, part);
                ChunkedSender.SendResult result = nodes == null
                        ? ChunkedSender.sendChunked(reply, ChunkedSender.Config.feishu(), sender)
                        : ChunkedSender.sendImageNodes(nodes, ChunkedSender.Config.feishu(), sender);

                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                // 部分发送失败告警（非全部失败，不需要降级，但需记录）
                if (result.getFailedParts() > 0 && result.getFailedParts() < result.getTotalParts()) {
                    LOG.warn("[Feishu] Partial send failure: {}/{} parts failed", result.getFailedParts(), result.getTotalParts());
                }

                // 如果全部失败，降级为纯文本发送
                if (result.getFailedParts() == result.getTotalParts() && result.getTotalParts() > 0) {
                    LOG.warn("[Feishu] MD post failed, degrading to plain text for {} part(s)", result.getTotalParts());
                    String cleanReply = cleanMarkdown(safeText);
                    if (cleanReply.isEmpty()) {
                        cleanReply = safeText;
                    }
                    ChunkedSender.SendResult textResult = ChunkedSender.sendChunked(cleanReply,
                            ChunkedSender.Config.feishu(),
                            (chunk, part) -> {
                                return !Thread.currentThread().isInterrupted()
                                        && sendReplyPlainTextChunk(fToken, fOpenId, chunk);
                            });

                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
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
