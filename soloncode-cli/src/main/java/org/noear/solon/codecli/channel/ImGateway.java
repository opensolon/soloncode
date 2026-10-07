/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.noear.solon.codecli.channel;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.channel.dingtalk.DingTalkTransport;
import org.noear.solon.codecli.channel.feishu.FeishuAppLeaseRegistry;
import org.noear.solon.codecli.channel.feishu.FeishuTransport;
import org.noear.solon.codecli.channel.wechat.WeChatClient;
import org.noear.solon.codecli.channel.wechat.WeChatLink;
import org.noear.solon.codecli.channel.wechat.WeChatTransport;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceMessageGateway;
import org.noear.solon.codecli.workspace.WorkspaceRegistry;
import org.noear.solon.core.util.RunUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 进程级 IM 网关：绑定归属、连接归属与消息投递的单一真相源。
 *
 * <p>把原本散落在各工作区 {@code FeishuLink} 实例上的「谁是某个 bot/用户的归属者」
 * 收拢为一个进程级对象，解决同一 bot 先后绑定不同工作区会话导致的错乱：</p>
 * <ul>
 *   <li>绑定主键为 channel + identity + userKey（identity 为 appId/appKey/botToken），全局唯一；
 *       同一 bot（同一 identity）同时只能绑定一个对话，不同 bot 天然并存；</li>
 *   <li>连接归属由 {@link FeishuTransport} + {@link FeishuAppLeaseRegistry} 保证进程内每 appId 至多一条；</li>
 *   <li>消息投递时按绑定挂点动态解析工作区（可重新唤醒被 LRU 释放的工作区），
 *       连接不随工作区关闭而断开；</li>
 *   <li>绑定被抢占（迁移）时通过返回值暴露 previous，由调用方对被迁出方执行降级。</li>
 * </ul>
 *
 * @author noear
 */
public class ImGateway implements FeishuTransport.Sink, DingTalkTransport.Sink, WeChatTransport.Sink {
    private static final Logger LOG = LoggerFactory.getLogger(ImGateway.class);

    public static final String CHANNEL_FEISHU = "feishu";
    public static final String CHANNEL_DINGTALK = "dingtalk";
    public static final String CHANNEL_WECHAT = "wechat";

    private static volatile ImGateway instance;

    private final ImBindingStore store;
    private final ImBindingRegistry registry = new ImBindingRegistry();
    private final FeishuAppLeaseRegistry feishuLeases = new FeishuAppLeaseRegistry();

    /** appId -> 待绑定的会话挂点（等待该应用的首条用户消息完成绑定）。 */
    private final Map<String, PendingBind> pendingFeishu = new ConcurrentHashMap<>();

    /** appKey -> 待绑定的会话挂点（等待该应用的首条用户消息完成绑定）。 */
    private final Map<String, PendingBind> pendingDingTalk = new ConcurrentHashMap<>();

    /** 进程级飞书连接传输层，惰性创建。 */
    private volatile FeishuTransport feishuTransport;
    /** 进程级钉钉连接传输层，惰性创建。 */
    private volatile DingTalkTransport dingTalkTransport;
    /** 进程级微信长轮询传输层，惰性创建。 */
    private volatile WeChatTransport weChatTransport;
    /** 工作区解析端口（由 WorkspaceManager 注入），用于消息投递时唤醒工作区。 */
    private volatile WorkspaceRegistry workspaces;
    /** workspaceId -> 物理路径，仅供连接线程日志归属。 */
    private volatile Function<String, String> workspacePathResolver;
    /** 进程退出钩子只注册一次。 */
    private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean();

    ImGateway(ImBindingStore store) {
        this.store = store;
    }

    /**
     * 组合构造：同时指定绑定存储与微信传输层。
     *
     * <p>生产路径由 {@link #getInstance(HarnessEngine)} 惰性装配；此构造供测试与定制装配使用。
     * 微信绑定与飞书/钉钉同住一个登记表，不再需要单独的微信存储。</p>
     */
    public ImGateway(ImBindingStore store, WeChatTransport weChatTransport) {
        this.store = store;
        this.weChatTransport = weChatTransport;
    }

    /**
     * 进程级单例：绑定登记表落在<strong>用户级</strong> harness 目录
     * （{@code <userHome>/<harnessChannels>/im-bindings.json}）。
     *
     * <p>登记表是「进程级、跨工作区」的单一真相源，只存在于用户级目录一处，位置必须与
     * 进程启动目录无关。切勿引入 {@code getUserDir()} 分量：它返回
     * {@code System.getProperty("user.dir")}（进程启动目录），会让同一进程随启动位置
     * 读写不同文件。登记表是后来新增的规范，不存在任何历史位置的同格式文件，因此这里
     * 也不做「按启动目录回收」——否则等于把「位置取决于从哪儿启动」重新装回去。</p>
     */
    public static ImGateway getInstance(HarnessEngine engine) {
        return getInstance(storePath(engine.getUserHome(), engine.getHarnessChannels()));
    }

    /** 绑定登记表路径：{@code <harnessRoot>/<harnessChannels>/im-bindings.json}。 */
    static Path storePath(String harnessRoot, String harnessChannels) {
        return Paths.get(harnessRoot, harnessChannels, ImBindingStore.STORE_FILE).toAbsolutePath();
    }

    /** 进程级单例；storePath 只在首次创建时生效。 */
    public static ImGateway getInstance(Path storePath) {
        ImGateway local = instance;
        if (local == null) {
            synchronized (ImGateway.class) {
                local = instance;
                if (local == null) {
                    local = new ImGateway(new ImBindingStore(storePath));
                    local.reload();
                    instance = local;
                }
            }
        }
        return local;
    }

    public FeishuAppLeaseRegistry getFeishuLeases() {
        return feishuLeases;
    }

    /**
     * 注入工作区解析端口与路径解析器（由 WorkspaceManager 在入口就绪时调用）。
     *
     * <p>这是「入口运行时已就绪」的信号，同时也是注册进程退出钩子的时机：acp/cli 等
     * 无入口端口模式下不会走到这里，也就不会拉起 IM 连接。</p>
     */
    public void setWorkspaces(WorkspaceRegistry workspaces, Function<String, String> workspacePathResolver) {
        this.workspaces = workspaces;
        this.workspacePathResolver = workspacePathResolver;
        ensureShutdownHook();
    }

    /**
     * 注册进程退出钩子（幂等）：保证 IM 连接优雅关闭，不被 OS 硬断。
     *
     * <p>注意 {@code /exit} 命令先走 {@code System.exit(0)}（会触发钩子），5 秒后才
     * {@code halt(0)} 兜底，因此钩子有足够时间完成关闭。</p>
     */
    private void ensureShutdownHook() {
        if (shutdownHookRegistered.compareAndSet(false, true)) {
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "im-gateway-shutdown"));
            } catch (IllegalStateException e) {
                // JVM 已在退出流程中，忽略
            }
        }
    }

    /** 进程退出时统一停止全部 IM 渠道连接（飞书/钉钉 WS + 微信长轮询）。 */
    public void stop() {
        stopFeishu();
        stopDingTalk();
        stopWeChat();
    }

    /** 进程级飞书传输层（惰性创建）。 */
    public FeishuTransport getFeishuTransport() {
        FeishuTransport local = feishuTransport;
        if (local == null) {
            synchronized (this) {
                local = feishuTransport;
                if (local == null) {
                    local = new FeishuTransport(this, feishuLeases,
                            id -> workspacePathResolver == null ? null : workspacePathResolver.apply(id));
                    feishuTransport = local;
                }
            }
        }
        return local;
    }

    /**
     * 拉起飞书传输层并恢复持久化绑定对应的连接（每 appId 至多一条）。
     *
     * <p>进程级一次性动作：不再由各工作区激活时分别拉起，避免同一 appId 出现多条连接。</p>
     */
    public synchronized void startFeishu() {
        if (workspaces == null) {
            return;
        }
        FeishuTransport transport = getFeishuTransport();
        Set<String> started = new HashSet<>();
        for (ImBindingRegistry.Binding binding : registry.snapshot().values()) {
            if (!CHANNEL_FEISHU.equals(binding.getChannel())) {
                continue;
            }
            if (binding.getIdentity() == null || binding.getIdentity().getAppId() == null) {
                continue;
            }
            String appId = binding.getIdentity().getAppId();
            if (!started.add(appId)) {
                continue;
            }
            // 无归属条目不能建连接：消息投递时解析不出工作区，会被当作悬挂绑定删掉
            if (binding.getWorkspaceId() == null || binding.getSessionId() == null) {
                continue;
            }
            String secret = binding.getSecret();
            if (secret == null || secret.isEmpty()) {
                continue;
            }
            transport.startStream(appId, secret, binding.getWorkspaceId(), binding.getSessionId(), false);
        }
    }

    /** 停止全部进程级飞书连接（进程退出时调用）。 */
    public void stopFeishu() {
        FeishuTransport transport = feishuTransport;
        if (transport != null) {
            transport.stopAll();
        }
    }

    /** 进程级钉钉传输层（惰性创建）。 */
    public DingTalkTransport getDingTalkTransport() {
        DingTalkTransport local = dingTalkTransport;
        if (local == null) {
            synchronized (this) {
                local = dingTalkTransport;
                if (local == null) {
                    local = new DingTalkTransport(this,
                            id -> workspacePathResolver == null ? null : workspacePathResolver.apply(id));
                    dingTalkTransport = local;
                }
            }
        }
        return local;
    }

    /**
     * 拉起钉钉传输层并恢复持久化绑定对应的连接（每 appKey 至多一条）。
     *
     * <p>进程级一次性动作：不再由各工作区激活时分别拉起，避免同一 appKey 出现多条连接。</p>
     */
    public synchronized void startDingTalk() {
        if (workspaces == null) {
            return;
        }
        DingTalkTransport transport = getDingTalkTransport();
        Set<String> started = new HashSet<>();
        for (ImBindingRegistry.Binding binding : registry.snapshot().values()) {
            if (!CHANNEL_DINGTALK.equals(binding.getChannel())) {
                continue;
            }
            if (binding.getIdentity() == null || binding.getIdentity().getAppKey() == null) {
                continue;
            }
            String appKey = binding.getIdentity().getAppKey();
            if (!started.add(appKey)) {
                continue;
            }
            // 无归属条目不能建连接：消息投递时解析不出工作区，会被当作悬挂绑定删掉
            if (binding.getWorkspaceId() == null || binding.getSessionId() == null) {
                continue;
            }
            String secret = binding.getSecret();
            if (secret == null || secret.isEmpty()) {
                continue;
            }
            transport.startStream(appKey, secret, binding.getWorkspaceId(), binding.getSessionId());
        }
    }

    /** 停止全部进程级钉钉连接（进程退出时调用）。 */
    public void stopDingTalk() {
        DingTalkTransport transport = dingTalkTransport;
        if (transport != null) {
            transport.stopAll();
        }
    }

    // ==================== 微信通道（长轮询连接化） ====================

    /** 进程级微信传输层（惰性创建）。 */
    public WeChatTransport getWeChatTransport() {
        WeChatTransport local = weChatTransport;
        if (local == null) {
            synchronized (this) {
                local = weChatTransport;
                if (local == null) {
                    local = new WeChatTransport();
                    weChatTransport = local;
                }
            }
        }
        return local;
    }

    /**
     * 拉起微信传输层并恢复登记表中绑定对应的长轮询连接。
     *
     * <p>微信与飞书/钉钉同住一张登记表：绑定不再有独立存储，落盘由 {@link #save()}
     * 单点负责，因此这里直接读内存登记表而不是读文件。</p>
     */
    public synchronized void startWeChat() {
        if (workspaces == null) {
            return;
        }
        for (ImBindingRegistry.Binding binding : registry.snapshot().values()) {
            if (!CHANNEL_WECHAT.equals(binding.getChannel())) {
                continue;
            }
            WeChatLink.WeChatBinding restored = toWeChatBinding(binding);
            if (restored == null || restored.workspaceId == null
                    || restored.botToken == null || restored.ilinkUserId == null) {
                continue;
            }
            getWeChatTransport().ensureConnected(this, restored.workspaceId, binding.getSessionId(),
                    restored.botToken, restored.ilinkBotId, restored.ilinkUserId, restored.baseUrl);
        }
    }

    /** 停止全部进程级微信连接（进程退出时调用）。 */
    public void stopWeChat() {
        WeChatTransport transport = weChatTransport;
        if (transport != null) {
            transport.stopAll();
        }
    }

    /**
     * 会话被删除时主动清理其在三个渠道上的绑定，避免悬挂路由。
     *
     * <p>这是消息到达时惰性校验之外的前置优化：不依赖「下一条消息」来收敛状态。
     * 微信会同步断开该会话的长轮询连接；飞书/钉钉的连接是 appId/appKey 维度的，
     * 不随会话消亡，仅解除绑定（后续该用户的消息按未绑定回引导提示）。</p>
     */
    public synchronized void onSessionRemoved(String workspaceId, String sessionId) {
        if (workspaceId == null || sessionId == null) {
            return;
        }
        ImBindingRegistry.Binding feishu = removeFeishu(workspaceId, sessionId);
        ImBindingRegistry.Binding dingTalk = removeDingTalk(workspaceId, sessionId);
        ImBindingRegistry.Binding weChat = removeWeChat(workspaceId, sessionId);
        if (feishu != null || dingTalk != null || weChat != null) {
            LOG.info("[IM] Session removed, bindings cleaned: workspaceId={}, sessionId={}",
                    workspaceId, sessionId);
        }
    }

    /**
     * 绑定或迁移一个微信用户。
     *
     * <p>与飞书/钉钉语义对齐：同一 userKey 在别的<b>工作区</b>已被占用时，force=false 返回冲突；
     * 同一工作区内换会话沿用旧有的自动迁移语义。重复确认（同一会话同一凭据）原样保留回复目标。</p>
     *
     * <p>归属粒度是<b>微信账号</b>（ilinkUserId），不是 bot：ilinkUserId 由服务端按账号签发，
     * 天然按账号唯一，因此不需要飞书/钉钉那种「一个 bot 一个对话」的约束。</p>
     */
    public synchronized AdoptResult adoptWeChat(String ilinkUserId, String botToken, String ilinkBotId,
                                                String baseUrl, String workspaceId, String sessionId, boolean force) {
        if (sessionId == null || botToken == null || ilinkUserId == null) {
            return new AdoptResult(false, null, null);
        }
        String normalizedBaseUrl = WeChatClient.normalizeBaseUrl(baseUrl);
        // 身份维取 ilinkBotId（稳定，不随重新授权换发）；缺失时回退 botToken
        ImBindingRegistry.Identity identity = new ImBindingRegistry.Identity(null, null,
                ilinkBotId == null || ilinkBotId.isEmpty() ? botToken : ilinkBotId);
        ImBindingRegistry.Binding current = registry.find(CHANNEL_WECHAT, identity, ilinkUserId);

        // 1) 重复确认：同一会话、同一归属、同一凭据 —— 必须原样保留回复目标
        if (current != null
                && Objects.equals(current.getWorkspaceId(), workspaceId)
                && Objects.equals(current.getSessionId(), sessionId)
                && Objects.equals(current.getSecret(), botToken)) {
            String known = current.getRuntime(ImBindingStore.RT_WECHAT_BASE_URL);
            if (normalizedBaseUrl != null && !normalizedBaseUrl.equals(known)) {
                current.putRuntime(ImBindingStore.RT_WECHAT_BASE_URL, normalizedBaseUrl);
                save();
            }
            getWeChatTransport().ensureConnected(this, workspaceId, sessionId, botToken, ilinkBotId,
                    ilinkUserId, known);
            return new AdoptResult(true, null, null);
        }

        // 2) 跨工作区占用检测（同工作区内换会话不算冲突；历史无归属条目不构成冲突，允许被本次绑定认领）
        ImBindingRegistry.Binding conflict = null;
        for (ImBindingRegistry.Binding binding : registry.findAllByUserKey(CHANNEL_WECHAT, ilinkUserId)) {
            if (binding.getWorkspaceId() == null || Objects.equals(binding.getWorkspaceId(), workspaceId)) {
                continue;
            }
            conflict = binding;
        }
        if (conflict != null && !force) {
            return new AdoptResult(false, conflict, null);
        }

        // 3) 清理同一账号的旧条目，再写入新绑定
        registry.removeAllByUserKey(CHANNEL_WECHAT, ilinkUserId);
        ImBindingRegistry.Binding adopted = new ImBindingRegistry.Binding(CHANNEL_WECHAT, ilinkUserId,
                identity, workspaceId, sessionId, System.currentTimeMillis(), botToken, null);
        adopted.putRuntime(ImBindingStore.RT_WECHAT_BASE_URL, normalizedBaseUrl);
        registry.put(adopted);
        save();

        WeChatTransport transport = getWeChatTransport();
        transport.disconnectByUserKey(ilinkUserId);
        transport.ensureConnected(this, workspaceId, sessionId, botToken, ilinkBotId, ilinkUserId,
                normalizedBaseUrl);

        return new AdoptResult(true, null, conflict);
    }

    /** 解除某会话在本工作区的微信绑定（含断开长轮询连接）。 */
    public synchronized ImBindingRegistry.Binding removeWeChat(String workspaceId, String sessionId) {
        if (sessionId == null) {
            return null;
        }
        ImBindingRegistry.Binding removed = registry.findBySession(CHANNEL_WECHAT, workspaceId, sessionId);
        if (removed == null) {
            return null;
        }
        registry.remove(CHANNEL_WECHAT, removed.getIdentity(), removed.getUserKey(), workspaceId, sessionId);
        save();
        getWeChatTransport().disconnectBySession(workspaceId, sessionId);
        return removed;
    }

    /** 指定会话是否已绑定微信（归属本工作区）。 */
    public boolean isWeChatBound(String workspaceId, String sessionId) {
        return wechatStatus(workspaceId, sessionId).isBound();
    }

    /**
     * 会话维度的微信绑定状态（本地 / 异地 / 未绑定）。
     *
     * <p>与飞书 / 钉钉的 {@code statusForSession} 语义对齐：先看本工作区是否持有该会话的绑定，
     * 否则看是否有其它工作区持有同名会话的绑定（供前端按钮呈现「已绑定到别处」第三态）。</p>
     */
    public synchronized WeChatStatus wechatStatus(String workspaceId, String sessionId) {
        if (sessionId == null) {
            return new WeChatStatus(false, false, null, null);
        }
        for (ImBindingRegistry.Binding binding : registry.snapshot().values()) {
            if (!CHANNEL_WECHAT.equals(binding.getChannel())
                    || !Objects.equals(sessionId, binding.getSessionId())) {
                continue;
            }
            if (Objects.equals(binding.getWorkspaceId(), workspaceId)) {
                return new WeChatStatus(true, false, binding.getWorkspaceId(), sessionId);
            }
            if (binding.getWorkspaceId() == null) {
                // 历史无归属条目：不视为占用，等待重新绑定认领
                return new WeChatStatus(false, false, null, null);
            }
            return new WeChatStatus(false, true, binding.getWorkspaceId(), sessionId);
        }
        return new WeChatStatus(false, false, null, null);
    }

    /** 会话维度的微信绑定状态快照。 */
    public static final class WeChatStatus {
        private final boolean bound;
        private final boolean boundElsewhere;
        private final String workspaceId;
        private final String sessionId;

        private WeChatStatus(boolean bound, boolean boundElsewhere,
                             String workspaceId, String sessionId) {
            this.bound = bound;
            this.boundElsewhere = boundElsewhere;
            this.workspaceId = workspaceId;
            this.sessionId = sessionId;
        }

        public boolean isBound() { return bound; }
        public boolean isBoundElsewhere() { return boundElsewhere; }
        public String getWorkspaceId() { return workspaceId; }
        public String getSessionId() { return sessionId; }
    }

    /** 出站回复（走进程级连接持有的回复目标）。 */
    public void sendWeChatReply(String workspaceId, String sessionId, String text, boolean isFinal,
                                String sourceUserId, String replyTarget, String messageId) {
        getWeChatTransport().sendReply(workspaceId, sessionId, text, isFinal,
                sourceUserId, replyTarget, messageId);
    }

    /** 出站交互状态信号。 */
    public void sendWeChatStatus(String workspaceId, String sessionId, ImStatus status, String detail,
                                 String sourceUserId, String replyTarget, String messageId) {
        getWeChatTransport().sendStatus(workspaceId, sessionId, status, detail,
                sourceUserId, replyTarget, messageId);
    }

    /**
     * 把登记表条目还原为微信引擎工作集视图。
     *
     * <p>映射关系：userKey=ilinkUserId、identity=ilinkBotId、secret=botToken，
     * 接入点与回复目标在 runtime 扩展位。secret 缺失（旧数据）时返回 null，
     * 调用方按「未绑定」处理。</p>
     */
    static WeChatLink.WeChatBinding toWeChatBinding(ImBindingRegistry.Binding binding) {
        if (binding == null) {
            return null;
        }
        String botToken = binding.getSecret();
        if (botToken == null || botToken.isEmpty()) {
            return null;
        }
        WeChatLink.WeChatBinding out = new WeChatLink.WeChatBinding();
        out.botToken = botToken;
        out.ilinkBotId = binding.getIdentity() == null ? null : binding.getIdentity().getBotToken();
        out.ilinkUserId = binding.getUserKey();
        out.workspaceId = binding.getWorkspaceId();
        // 接入点不可信就丢：登记表里的值是持久化数据，可能是被改过的 redirect_host
        out.baseUrl = WeChatClient.normalizeBaseUrl(binding.getRuntime(ImBindingStore.RT_WECHAT_BASE_URL));
        out.restoreReplyTarget(binding.getRuntime(ImBindingStore.RT_WECHAT_LAST_FROM_USER),
                binding.getRuntime(ImBindingStore.RT_WECHAT_LAST_CONTEXT_TOKEN));
        return out;
    }

    // ==================== WeChatTransport.Sink 实现 ====================

    @Override
    public boolean onWeChatText(String workspaceId, String sessionId, String userKey,
                                String text, String replyTarget, String sourceUserId) {
        return deliverWeChat(workspaceId, sessionId, userKey, text);
    }

    @Override
    public void onWeChatExpired(String workspaceId, String sessionId, String userKey) {
        LOG.info("[WeChat] Login state expired, drop binding: workspaceId={}, sessionId={}", workspaceId, sessionId);
        removeWeChat(workspaceId, sessionId);
    }

    /**
     * 投递一条微信消息：按绑定挂点唤醒工作区（含 LRU 已释放的），并做会话存活校验。
     */
    private boolean deliverWeChat(String workspaceId, String sessionId, String userKey, String text) {
        try {
            WorkspaceRegistry registry = this.workspaces;
            if (registry == null) {
                LOG.warn("[WeChat] workspace registry not ready, drop message from {}", userKey);
                return false;
            }

            WorkspaceContext ctx = registry.getOrCreate(workspaceId);
            if (ctx == null) {
                LOG.warn("[WeChat] workspace not found for binding, drop: workspaceId={}", workspaceId);
                removeWeChat(workspaceId, sessionId);
                return false;
            }

            // 惰性校验收敛绑定生命周期：会话目录不存在说明会话已删除，解除悬挂绑定
            if (sessionId != null && !Files.isDirectory(ctx.getSessionsRoot().resolve(sessionId))) {
                LOG.info("[WeChat] Session no longer exists, drop binding: workspaceId={}, sessionId={}",
                        workspaceId, sessionId);
                removeWeChat(workspaceId, sessionId);
                return false;
            }

            WorkspaceMessageGateway messageGateway = ctx.getMessageGateway();
            if (messageGateway == null) {
                LOG.warn("[WeChat] message gateway not ready, drop message from {}", userKey);
                return false;
            }

            return messageGateway.acceptInput(ctx, sessionId, text, "WeChat", userKey, null, null);
        } catch (Exception e) {
            LOG.error("[WeChat] Message delivery error: {}", e.getMessage(), e);
            return false;
        }
    }

    /** 从存储重新加载全部绑定（覆盖内存登记表）。 */
    public synchronized void reload() {
        registry.replaceAll(store.load());
    }

    /** 绑定或迁移一个飞书用户；force=true 时允许抢占其它工作区的绑定。 */
    public synchronized AdoptResult adoptFeishu(String openId, String appId, String appSecret,
                                                String workspaceId, String sessionId, boolean force) {
        ImBindingRegistry.Result result = registry.adopt(CHANNEL_FEISHU, openId,
                new ImBindingRegistry.Identity(appId, null, null),
                workspaceId, sessionId, force, appSecret);
        if (result.isAccepted()) {
            save();
        }
        return new AdoptResult(result.isAccepted(), result.getConflict(), result.getPrevious());
    }

    /** 解除某会话在本工作区的飞书绑定。 */
    public synchronized ImBindingRegistry.Binding removeFeishu(String workspaceId, String sessionId) {
        ImBindingRegistry.Binding removed =
                registry.removeBySession(CHANNEL_FEISHU, workspaceId, sessionId);
        if (removed != null) {
            save();
        }
        return removed;
    }

    public synchronized ImBindingRegistry.Binding findFeishuByOpenId(String openId) {
        return registry.findAny(CHANNEL_FEISHU, openId);
    }

    /** 精确查找指定 appId 的飞书绑定；多 bot 并存时必须用它，否则会命中另一个 bot 的绑定。 */
    public synchronized ImBindingRegistry.Binding findFeishuByOpenId(String appId, String openId) {
        return registry.find(CHANNEL_FEISHU, new ImBindingRegistry.Identity(appId, null, null), openId);
    }

    public synchronized ImBindingRegistry.Binding findFeishuBySession(String workspaceId, String sessionId) {
        return registry.findBySession(CHANNEL_FEISHU, workspaceId, sessionId);
    }

    public synchronized ImBindingRegistry.Binding findFeishuByAppId(String appId) {
        return registry.findByAppId(CHANNEL_FEISHU, appId);
    }

    public synchronized List<ImBindingRegistry.Binding> listFeishu(String workspaceId) {
        return registry.listByWorkspace(CHANNEL_FEISHU, workspaceId);
    }

    /** 会话维度的绑定状态（本地/异地/未绑定）。 */
    public synchronized ImBindingRegistry.SessionStatus feishuStatus(String workspaceId, String sessionId) {
        return registry.statusForSession(CHANNEL_FEISHU, workspaceId, sessionId);
    }

    /** 更新去重缓存（最后处理的消息 ID）；不触发落盘。 */
    public synchronized void updateLastMessageId(String appId, String openId, String messageId) {
        registry.updateLastMessageId(CHANNEL_FEISHU,
                new ImBindingRegistry.Identity(appId, null, null), openId, messageId);
    }

    // ==================== 钉钉绑定（与飞书对称） ====================

    /** 绑定或迁移一个钉钉用户；force=true 时允许抢占其它工作区的绑定。 */
    public synchronized AdoptResult adoptDingTalk(String userId, String appKey, String appSecret,
                                                  String workspaceId, String sessionId, boolean force) {
        ImBindingRegistry.Result result = registry.adopt(CHANNEL_DINGTALK, userId,
                new ImBindingRegistry.Identity(null, appKey, null),
                workspaceId, sessionId, force, appSecret);
        if (result.isAccepted()) {
            save();
        }
        return new AdoptResult(result.isAccepted(), result.getConflict(), result.getPrevious());
    }

    /** 解除某会话在本工作区的钉钉绑定。 */
    public synchronized ImBindingRegistry.Binding removeDingTalk(String workspaceId, String sessionId) {
        ImBindingRegistry.Binding removed =
                registry.removeBySession(CHANNEL_DINGTALK, workspaceId, sessionId);
        if (removed != null) {
            save();
        }
        return removed;
    }

    public synchronized ImBindingRegistry.Binding findDingTalkByUserId(String userId) {
        return registry.findAny(CHANNEL_DINGTALK, userId);
    }

    /** 精确查找指定 appKey 的钉钉绑定；钉钉 staffId 是组织维度的，多 bot 时必须用它。 */
    public synchronized ImBindingRegistry.Binding findDingTalkByUserId(String appKey, String userId) {
        return registry.find(CHANNEL_DINGTALK, new ImBindingRegistry.Identity(null, appKey, null), userId);
    }

    public synchronized ImBindingRegistry.Binding findDingTalkBySession(String workspaceId, String sessionId) {
        return registry.findBySession(CHANNEL_DINGTALK, workspaceId, sessionId);
    }

    public synchronized ImBindingRegistry.Binding findDingTalkByAppKey(String appKey) {
        return registry.findByAppKey(CHANNEL_DINGTALK, appKey);
    }

    public synchronized List<ImBindingRegistry.Binding> listDingTalk(String workspaceId) {
        return registry.listByWorkspace(CHANNEL_DINGTALK, workspaceId);
    }

    /** 会话维度的钉钉绑定状态（本地/异地/未绑定）。 */
    public synchronized ImBindingRegistry.SessionStatus dingtalkStatus(String workspaceId, String sessionId) {
        return registry.statusForSession(CHANNEL_DINGTALK, workspaceId, sessionId);
    }

    /** 更新钉钉去重缓存（最后处理的消息 ID）；不触发落盘。 */
    public synchronized void updateDingTalkLastMessageId(String appKey, String userId, String messageId) {
        registry.updateLastMessageId(CHANNEL_DINGTALK,
                new ImBindingRegistry.Identity(null, appKey, null), userId, messageId);
    }

    // ==================== 待绑定挂点（pending） ====================

    /** 登记某 appId 的待绑定会话挂点（等待该应用的首条用户消息完成绑定）。 */
    public void setPendingFeishu(String appId, String workspaceId, String sessionId) {
        if (appId == null || workspaceId == null || sessionId == null) {
            return;
        }
        pendingFeishu.put(appId, new PendingBind(workspaceId, sessionId));
    }

    public void clearPendingFeishu(String appId) {
        if (appId != null) {
            pendingFeishu.remove(appId);
        }
    }

    /** 指定会话是否处于待绑定状态。 */
    public boolean isPendingFeishu(String workspaceId, String sessionId) {
        for (PendingBind pending : pendingFeishu.values()) {
            if (Objects.equals(workspaceId, pending.workspaceId)
                    && Objects.equals(sessionId, pending.sessionId)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 待绑定挂点（钉钉） ====================

    /** 登记某 appKey 的待绑定会话挂点（等待该应用的首条用户消息完成绑定）。 */
    public void setPendingDingTalk(String appKey, String workspaceId, String sessionId) {
        if (appKey == null || workspaceId == null || sessionId == null) {
            return;
        }
        pendingDingTalk.put(appKey, new PendingBind(workspaceId, sessionId));
    }

    public void clearPendingDingTalk(String appKey) {
        if (appKey != null) {
            pendingDingTalk.remove(appKey);
        }
    }

    /** 指定会话是否处于钉钉待绑定状态。 */
    public boolean isPendingDingTalk(String workspaceId, String sessionId) {
        for (PendingBind pending : pendingDingTalk.values()) {
            if (Objects.equals(workspaceId, pending.workspaceId)
                    && Objects.equals(sessionId, pending.sessionId)) {
                return true;
            }
        }
        return false;
    }

    // ==================== FeishuTransport.Sink 实现 ====================

    @Override
    public void onFeishuText(String appId, String appSecret, String openId, String text, String messageId) {
        // 首条消息完成 pending 绑定
        PendingBind pending = pendingFeishu.remove(appId);
        if (pending != null) {
            adoptFeishu(openId, appId, appSecret, pending.workspaceId, pending.sessionId, true);
        }

        ImBindingRegistry.Binding binding = findFeishuByOpenId(appId, openId);
        if (binding == null || binding.getSessionId() == null) {
            // 区分「该 bot 尚未绑定」与「该 bot 已被别的对话占用」，避免给出误导性提示
            ImBindingRegistry.Binding taken = findFeishuByAppId(appId);
            boolean botTaken = taken != null && !Objects.equals(openId, taken.getUserKey());
            LOG.warn("[Feishu] Received message from unbound user: appId={}, openId={}, botTaken={}",
                    appId, openId, botTaken);
            FeishuTransport.sendHint(appId, appSecret, openId,
                    botTaken ? ImMessages.HINT_BOT_TAKEN() : ImMessages.HINT_UNBOUND());
            return;
        }

        if (messageId != null && messageId.equals(binding.getLastMessageId())) {
            return;
        }

        final String wsId = binding.getWorkspaceId();
        final String sessionId = binding.getSessionId();
        RunUtil.async(() -> deliverFeishu(wsId, sessionId, openId, text, messageId, appId, appSecret));
    }

    @Override
    public void onFeishuNonText(String appId, String appSecret, String openId) {
        // 统一回执：web 支持附件，IM 暂不支持时不能让用户以为消息已发出
        FeishuTransport.sendHint(appId, appSecret, openId, ImMessages.HINT_NON_TEXT());
    }

    @Override
    public void onFeishuConnected(String appId) {
        // 重连后清空该 appId 的去重缓存，保证飞书重推的事件能被处理
        for (ImBindingRegistry.Binding binding : registry.snapshot().values()) {
            if (CHANNEL_FEISHU.equals(binding.getChannel())
                    && binding.getIdentity() != null
                    && Objects.equals(appId, binding.getIdentity().getAppId())) {
                registry.updateLastMessageId(CHANNEL_FEISHU, binding.getIdentity(), binding.getUserKey(), "");
            }
        }
    }

    // ==================== DingTalkTransport.Sink 实现 ====================

    @Override
    public void onDingTalkText(String appKey, String appSecret, String userId, String text, String messageId) {
        // 首条消息完成 pending 绑定
        PendingBind pending = pendingDingTalk.remove(appKey);
        if (pending != null) {
            adoptDingTalk(userId, appKey, appSecret, pending.workspaceId, pending.sessionId, true);
        }

        ImBindingRegistry.Binding binding = findDingTalkByUserId(appKey, userId);
        if (binding == null || binding.getSessionId() == null) {
            // 区分「该 bot 尚未绑定」与「该 bot 已被别的对话占用」；钉钉 staffId 是组织维度的，
            // 同一人用不同 bot 会得到相同 userId，因此这里必须按 appKey 判定是否已被占用。
            ImBindingRegistry.Binding taken = findDingTalkByAppKey(appKey);
            boolean botTaken = taken != null && !Objects.equals(userId, taken.getUserKey());
            LOG.warn("[DingTalk] Received message from unbound user: appKey={}, userId={}, botTaken={}",
                    appKey, userId, botTaken);
            DingTalkTransport.sendHint(appKey, appSecret, userId,
                    botTaken ? ImMessages.HINT_BOT_TAKEN() : ImMessages.HINT_UNBOUND());
            return;
        }

        if (messageId != null && messageId.equals(binding.getLastMessageId())) {
            return;
        }

        final String wsId = binding.getWorkspaceId();
        final String sessionId = binding.getSessionId();
        RunUtil.async(() -> deliverDingTalk(wsId, sessionId, userId, text, messageId, appKey, appSecret));
    }

    @Override
    public void onDingTalkNonText(String appKey, String appSecret, String userId) {
        // 非文本消息不完成绑定（与旧实现保持一致：绑定前不会因图片消息被认领）
        ImBindingRegistry.Binding binding = findDingTalkByUserId(appKey, userId);
        String hint;
        if (binding != null) {
            hint = ImMessages.HINT_NON_TEXT();
        } else {
            ImBindingRegistry.Binding taken = findDingTalkByAppKey(appKey);
            boolean botTaken = taken != null && !Objects.equals(userId, taken.getUserKey());
            hint = botTaken ? ImMessages.HINT_BOT_TAKEN() : ImMessages.HINT_UNBOUND();
        }
        DingTalkTransport.sendHint(appKey, appSecret, userId, hint);
    }

    @Override
    public void onDingTalkConnected(String appKey) {
        for (ImBindingRegistry.Binding binding : registry.snapshot().values()) {
            if (CHANNEL_DINGTALK.equals(binding.getChannel())
                    && binding.getIdentity() != null
                    && Objects.equals(appKey, binding.getIdentity().getAppKey())) {
                registry.updateLastMessageId(CHANNEL_DINGTALK, binding.getIdentity(), binding.getUserKey(), "");
            }
        }
    }

    /**
     * 投递一条钉钉消息：按绑定挂点唤醒工作区（含 LRU 已释放的），并做会话存活校验。
     */
    private void deliverDingTalk(String workspaceId, String sessionId, String userId, String text,
                                 String messageId, String appKey, String appSecret) {
        try {
            WorkspaceRegistry registry = this.workspaces;
            if (registry == null) {
                LOG.warn("[DingTalk] workspace registry not ready, drop message from {}", userId);
                return;
            }

            WorkspaceContext ctx = registry.getOrCreate(workspaceId);
            if (ctx == null) {
                LOG.warn("[DingTalk] workspace not found for binding, drop: workspaceId={}", workspaceId);
                removeDingTalk(workspaceId, sessionId);
                DingTalkTransport.sendHint(appKey, appSecret, userId, ImMessages.HINT_UNBOUND());
                return;
            }

            // 惰性校验收敛绑定生命周期：会话目录不存在说明会话已删除，解除悬挂绑定
            if (sessionId != null && !Files.isDirectory(ctx.getSessionsRoot().resolve(sessionId))) {
                LOG.info("[DingTalk] Session no longer exists, drop binding: workspaceId={}, sessionId={}",
                        workspaceId, sessionId);
                removeDingTalk(workspaceId, sessionId);
                DingTalkTransport.sendHint(appKey, appSecret, userId, ImMessages.HINT_UNBOUND());
                return;
            }

            WorkspaceMessageGateway messageGateway = ctx.getMessageGateway();
            if (messageGateway == null) {
                LOG.warn("[DingTalk] message gateway not ready, drop message from {}", userId);
                return;
            }

            boolean accepted = messageGateway.acceptInput(ctx, sessionId, text, "DingTalk", userId, null, messageId);
            if (accepted && messageId != null) {
                updateDingTalkLastMessageId(appKey, userId, messageId);
            }
        } catch (Exception e) {
            LOG.error("[DingTalk] Message delivery error: {}", e.getMessage(), e);
        }
    }

    /**
     * 投递一条飞书消息：按绑定挂点唤醒工作区（含 LRU 已释放的），并做会话存活校验。
     */
    private void deliverFeishu(String workspaceId, String sessionId, String openId, String text,
                               String messageId, String appId, String appSecret) {
        try {
            WorkspaceRegistry registry = this.workspaces;
            if (registry == null) {
                LOG.warn("[Feishu] workspace registry not ready, drop message from {}", openId);
                return;
            }

            WorkspaceContext ctx = registry.getOrCreate(workspaceId);
            if (ctx == null) {
                LOG.warn("[Feishu] workspace not found for binding, drop: workspaceId={}", workspaceId);
                removeFeishu(workspaceId, sessionId);
                FeishuTransport.sendHint(appId, appSecret, openId, ImMessages.HINT_UNBOUND());
                return;
            }

            // 惰性校验收敛绑定生命周期：会话目录不存在说明会话已删除，解除悬挂绑定
            if (sessionId != null && !Files.isDirectory(ctx.getSessionsRoot().resolve(sessionId))) {
                LOG.info("[Feishu] Session no longer exists, drop binding: workspaceId={}, sessionId={}",
                        workspaceId, sessionId);
                removeFeishu(workspaceId, sessionId);
                FeishuTransport.sendHint(appId, appSecret, openId, ImMessages.HINT_UNBOUND());
                return;
            }

            WorkspaceMessageGateway messageGateway = ctx.getMessageGateway();
            if (messageGateway == null) {
                LOG.warn("[Feishu] message gateway not ready, drop message from {}", openId);
                return;
            }

            boolean accepted = messageGateway.acceptInput(ctx, sessionId, text, "Feishu", openId, null, messageId);
            if (accepted && messageId != null) {
                // 消息被接受进入处理流程后才记录 lastMessageId，
                // 避免 WS 断连重试时因 lastMessageId 已设置而跳过未处理的消息
                updateLastMessageId(appId, openId, messageId);
            }
        } catch (Exception e) {
            LOG.error("[Feishu] Message delivery error: {}", e.getMessage(), e);
        }
    }

    private void save() {
        store.save(registry.snapshot());
    }

    /** 待绑定挂点。 */
    private static final class PendingBind {
        private final String workspaceId;
        private final String sessionId;

        private PendingBind(String workspaceId, String sessionId) {
            this.workspaceId = workspaceId;
            this.sessionId = sessionId;
        }
    }

    /** 绑定/迁移结果：accepted=false 时 conflict 描述占用者；accepted=true 时 previous 是被替换/抢占的旧绑定。 */
    public static final class AdoptResult {
        private final boolean accepted;
        private final ImBindingRegistry.Binding conflict;
        private final ImBindingRegistry.Binding previous;

        AdoptResult(boolean accepted, ImBindingRegistry.Binding conflict,
                    ImBindingRegistry.Binding previous) {
            this.accepted = accepted;
            this.conflict = conflict;
            this.previous = previous;
        }

        public boolean isAccepted() { return accepted; }
        public ImBindingRegistry.Binding getConflict() { return conflict; }
        public ImBindingRegistry.Binding getPrevious() { return previous; }
    }
}
