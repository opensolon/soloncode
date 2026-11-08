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
package org.noear.solon.codecli.api.web;

import org.noear.snack4.ONode;
import org.noear.solon.Solon;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessResult;
import org.noear.solon.ai.harness.command.BufferedCommandContextFactory;
import org.noear.solon.ai.harness.host.HarnessHost;
import org.noear.solon.ai.harness.host.SessionAdmin;
import org.noear.solon.ai.harness.host.SubmitResult;
import org.noear.solon.ai.harness.host.SubmitSpec;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.auth.UserAuthConfig;
import org.noear.solon.codecli.auth.UserLoginController;
import org.noear.solon.codecli.auth.UserSessionManager;
import org.noear.solon.codecli.session.SessionActivity;
import org.noear.solon.codecli.session.SessionIdOwnership;
import org.noear.solon.codecli.session.SessionMeta;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceLogRouter;
import org.noear.solon.codecli.workspace.WorkspaceRegistry;
import org.noear.solon.codecli.workspace.WorkspaceRuntimePort;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.websocket.WebSocket;
import org.noear.solon.net.websocket.listener.SimpleWebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebGate - 前端统一 WebSocket 网关（组合根）
 *
 * <p>前端整个生命周期只维护一个 WebSocket 连接，不跟任何特定 sessionId 绑定；
 * 后端推送的所有消息包都携带 sessionId 字段，前端据此分发到对应会话进行渲染。</p>
 *
 * <p>编排（受理 → 开流 → 终态 → 续发积压、两段式中断、goal / loop 后台轮次）全部交给
 * {@link HarnessHost}（一个工作区 = 一个租户，租户键为工作区 ID）。本类只保留：</p>
 * <ul>
 *   <li>WebSocket 连接生命周期与统一输出出口（{@link #emitToClient} / {@link #broadcastRaw}，可被测试覆写）；</li>
 *   <li>入口适配：HTTP / IM 输入 → {@code host.submit}，停止 → {@code host.interrupt}；</li>
 *   <li>呈现：{@link WebHostSink}（会话级）与 {@link WebRunSink}（每轮）。</li>
 * </ul>
 *
 * @author noear 2026/5/8 created
 */
public class WebGate extends SimpleWebSocketListener implements WorkspaceRuntimePort {
    private static final Logger LOG = LoggerFactory.getLogger(WebGate.class);

    /** HITL 审批时前端回传的 callUuid（通过 session context 透传） */
    public static final String CTX_HITL_CALL_ID = "hitl.callId";

    private final WorkspaceRegistry workspaceRegistry;

    /** 流式响应构建器（SAEP 管道装配 + IM 回复投递） */
    private final WebStreamBuilder streamBuilder;

    /** 事件输出门（done 去重、流回合门） */
    private final WebEventPublisher eventPublisher;

    /** 宿主骨架：受理、开流、终态、续发、中断、后台轮次 */
    private final HarnessHost host;

    /** 记录 WebSocket 连接对应的用户 ID（用于按用户隔离广播） */
    private final ConcurrentHashMap<String, String> socketUserMap = new ConcurrentHashMap<>();

    public WebGate(WorkspaceRegistry workspaceRegistry) {
        this.workspaceRegistry = workspaceRegistry;
        this.streamBuilder = new WebStreamBuilder(this);
        this.eventPublisher = new WebEventPublisher(this);
        this.host = HarnessHost.builder()
                .engines(this::engineOf)
                .sink(new WebHostSink(this))
                // 框架代跑的动作（开流、续发、后台轮次）都进入工作区日志作用域
                .wrap((tenant, r) -> {
                    WorkspaceContext ctx = contextOf(tenant);
                    return ctx == null ? r : WorkspaceLogRouter.withWorkspaceLogKey(ctx.getMeta().getPath(), r);
                })
                .input((tenant, in) -> {
                    // 命令内部触发的任务只记录意图（分发器交给引擎二段装配），流由骨架统一发起
                    in.commandContextFactory(new BufferedCommandContextFactory());
                    if (in.isProgram()) {
                        // 用户写的定时提示词可指定 @子代理；以 / 开头也不当命令（程序轮次本身不识别命令）
                        in.agentAware(true);
                        // 来源写进用户消息元数据（历史回放原样展示）：与实时上屏同一标签（Goal / Loop）
                        in.origin(InputOrigin.channel(WebRunSink.programLabel(in.getOrigin().getChannel())));
                    }
                })
                // 只接管 web / 自动化会话的后台轮次（桌面会话由桌面端执行器认领）
                .programRuns(SessionIdOwnership::isWeb)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  组件访问（包内协作）
    // ═══════════════════════════════════════════════════════════════

    WebEventPublisher getEventPublisher() {
        return eventPublisher;
    }

    /**
     * 获取流式响应构建器（供 WeChatLink 等外部组件引用，与 WebSocket 网关共享 IM 投递通道）。
     */
    public WebStreamBuilder getStreamBuilder() {
        return streamBuilder;
    }

    /** 宿主骨架（包内与测试可见） */
    HarnessHost getHost() {
        return host;
    }

    /** 租户键：工作区 ID（一个工作区 = 一个租户） */
    static String tenantOf(WorkspaceContext ctx) {
        return ctx.getMeta().getId();
    }

    /**
     * 租户 → 工作区上下文：只查缓存（严禁 getOrCreate，会把刚释放的工作区原地复活）。
     * 工作区在放进缓存之后才通知本入口（见 WorkspaceManager#notifyContextReady），这里一定查得到。
     */
    WorkspaceContext contextOf(String tenant) {
        return workspaceRegistry == null ? null : workspaceRegistry.getContextsCached(tenant);
    }

    private HarnessEngine engineOf(String tenant) {
        WorkspaceContext ctx = contextOf(tenant);
        if (ctx == null) {
            throw new IllegalStateException("workspace not in memory: " + tenant);
        }
        return ctx.getEngine();
    }

    /**
     * 新开一轮：后端 done 门与前端流门必须成对复位。
     *
     * <p>前端在收到上一轮 done 后会把该会话置为 _streamClosed 并丢弃后续所有 chunk；若只复位后端，
     * 同一会话的第二段流（HITL 恢复、命令触发的任务、Loop/Goal 续跑等）前端会全部静默丢弃。</p>
     */
    void beginStreamTurn(WorkspaceContext wsContext, AgentSession session) {
        eventPublisher.resetStreamDoneSent(session);
        if (session != null) {
            emitToClient(wsContext, session.getSessionId(), WebEvent.ofResetStream());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  WorkspaceRuntimePort 实现
    // ═══════════════════════════════════════════════════════════════

    /**
     * Channel（IM 等外部入口）通过中立工作区端口提交输入。
     *
     * <p>会话忙时普通消息默认「插话」（与 web 前端流式中 Enter 的默认一致），插不进去再降级为排队；
     * 受理 / 排队 / 插话 / 拒收都会给来源渠道一条状态信号（见 {@link WebHostSink}）。</p>
     *
     * @return true = 已受理（含排队、插话）；false = 未受理（已向来源端回报）
     */
    @Override
    public boolean acceptInput(WorkspaceContext workspaceContext,
                               String sessionId,
                               String input,
                               String source,
                               String sourceUserId,
                               String replyTarget,
                               String messageId) {
        return safeChatInput(workspaceContext, sessionId, input, source, sourceUserId, replyTarget, messageId);
    }

    /**
     * Channel 侧的统一错误通知，保持 WebEvent 的 error + done 协议不变。
     */
    @Override
    public void emitErrorAndDone(WorkspaceContext workspaceContext, String sessionId, String message) {
        emitToClient(workspaceContext, sessionId, WebEvent.ofError(message));
        emitToClient(workspaceContext, sessionId, WebEvent.ofDone());
    }

    /**
     * 工作区上下文已放进缓存：预热骨架（首次绑定引擎时框架登记后台执行器、按索引恢复 goal 调度、续发积压），
     * 然后做一次性的旧数据迁移（索引上线前的存量 goal / 积压补登记、旧版队列文件）。
     */
    @Override
    public void onWorkspaceReady(WorkspaceContext wsContext) {
        if (wsContext == null) {
            return;
        }
        String tenant = tenantOf(wsContext);
        if (host.warmup(Collections.singletonList(tenant)) > 0) {
            WebSessionRecovery.migrateOnce(wsContext, host.admin(tenant));
        }
    }

    /** 会话管理态（队列 / 插话 / goal / loop 的管理接口）：成功后自动续发积压 */
    @Override
    public SessionAdmin sessionAdmin(WorkspaceContext wsContext) {
        return host.admin(tenantOf(wsContext));
    }

    @Override
    public boolean isSessionBusy(HarnessEngine engine, String sessionId) {
        try {
            // 只取已存在的会话：判忙不应顺带新建（不存在即不忙）
            AgentSession session = engine.getSessionOrNull(sessionId);
            return session != null && SessionActivity.isBusy(session);
        } catch (Exception e) {
            LOG.warn("[WebGate] busy check failed for session {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isSessionOccupied(HarnessEngine engine, String sessionId) {
        try {
            AgentSession session = engine.getSessionOrNull(sessionId);
            return session != null && engine.status(session).isOccupied();
        } catch (Exception e) {
            LOG.warn("[WebGate] occupied check failed for session {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 广播原始 JSON 字符串到指定工作区的所有 WebSocket 连接（不注入 sessionId，系统级事件用）。
     */
    @Override
    public void broadcastRaw(String workspaceId, String json) {
        WorkspaceContext wsContext = workspaceRegistry == null ? null : workspaceRegistry.getContextsCached(workspaceId);

        if (wsContext == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[WebGate] broadcastRaw skipped: workspace '{}' not in memory (LRU-released or unknown)", workspaceId);
            }
        }

        broadcastRaw(wsContext, json);
    }

    // ═══════════════════════════════════════════════════════════════
    //  WebSocket 生命周期管理
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void onOpen(WebSocket socket) {
        String wsId = socket.param("workspaceId"); // 统一：只认 workspaceId；旧参数 workspace 已废弃
        WorkspaceContext wctx = workspaceRegistry.getOrCreate(wsId);
        if (wctx == null) {
            // 失效工作区 ID：与 WorkspaceFilter 的 404 语义对齐，拒绝握手而非静默回退默认工作区
            LOG.warn("[WebGate] Reject websocket with unknown workspaceId: {}", wsId);
            socket.close();
            return;
        }

        // 如果用户认证启用，验证 WebSocket 连接携带的 user_token
        String userToken = socket.param("user_token");
        if (userToken != null && !userToken.isEmpty()) {
            try {
                UserSessionManager sessionMgr = Solon.context().getBean(UserSessionManager.class);
                if (sessionMgr != null) {
                    UserSessionManager.UserSession userSession = sessionMgr.getSession(userToken);
                    if (userSession != null) {
                        socketUserMap.put(socket.id(), userSession.getUserId());
                    }
                }
            } catch (Exception e) {
                LOG.warn("[WebGate] Failed to authenticate user_token: {}", e.getMessage());
            }
        }

        //WS 回调跑在容器 IO 线程上（不经 WorkspaceFilter），不打标则日志全部回退到启动工作区
        Object logScope = WorkspaceLogRouter.beginScope(wctx.getMeta().getPath());
        try {
            wctx.getConnections().add(socket);
            LOG.info("[WebGate] WebSocket opened: {}, workspace: {}", socket.id(), wsId);
        } finally {
            WorkspaceLogRouter.endScope(logScope);
        }
    }

    /**
     * 按 workspaceId 解析目标连接池（只查内存缓存，严禁 getOrCreate：LRU 回收 close 时
     * 逐个 socket.close 会异步触发 onClose，getOrCreate 会把刚释放的工作区原地复活）。
     */
    private List<WebSocket> resolveConnections(String wsId) {
        WorkspaceContext wctx = workspaceRegistry.getContextsCached(wsId);
        if (wctx == null) {
            wctx = workspaceRegistry.getContextsCached(null);
        }
        if (wctx == null) {
            return Collections.emptyList();
        }

        return wctx.getConnections();
    }

    @Override
    public void onClose(WebSocket socket) {
        String wsId = socket.param("workspaceId");
        socketUserMap.remove(socket.id());
        resolveConnections(wsId).remove(socket);

        WorkspaceContext wctx = workspaceRegistry.getContextsCached(wsId);
        Object logScope = WorkspaceLogRouter.beginScope(wctx == null ? null : wctx.getMeta().getPath());
        try {
            LOG.info("[WebGate] WebSocket closed: {}", socket.id());
        } finally {
            WorkspaceLogRouter.endScope(logScope);
        }
    }

    /** 当前仅处理心跳检测（ping/pong），业务消息通过 HTTP 接口入口进入。 */
    @Override
    public void onMessage(WebSocket socket, String text) throws IOException {
        if ("ping".equals(text)) {
            socket.send("pong");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  输出端口 —— 向前端推送消息
    // ═══════════════════════════════════════════════════════════════

    /**
     * 统一输出：将消息块通过 WebSocket 推送至前端（注入 sessionId，按工作区连接池与会话所有者下发）。
     */
    public void emitToClient(WorkspaceContext wsContext, String sessionId, WebEvent<?> event) {
        if (event == null) {
            return;
        } else {
            event.setSessionId(sessionId);
        }

        String enriched = ONode.serialize(event);

        if (LOG.isDebugEnabled()) {
            LOG.debug("emit: " + enriched);
        }
        if (wsContext == null) {
            return;
        }

        // 推送严格按 socket 所属工作区分组；对话隔离开启时只推送给拥有该会话的用户连接。
        // 会话主人要读盘（_meta）：只在隔离开启时读，不让每个流式分片都碰一次磁盘
        UserAuthConfig authConfig = wsContext.getSettings() == null ? null : wsContext.getSettings().getUserAuth();
        boolean isolate = authConfig != null && authConfig.isEnabled()
                && authConfig.isConversationIsolationEnabled() && !socketUserMap.isEmpty();
        String sessionOwnerId = isolate ? loadSessionOwner(wsContext, sessionId) : null;
        for (WebSocket socket : wsContext.getConnections()) {
            if (socket != null) {
                try {
                    if (isolate) {
                        String socketUserId = socketUserMap.get(socket.id());
                        if (socketUserId == null) {
                            continue;
                        }
                        if (sessionOwnerId != null && !sessionOwnerId.isEmpty()) {
                            if (!socketUserId.equals(sessionOwnerId)) {
                                continue;
                            }
                        }
                    }
                    socket.send(enriched);
                } catch (Throwable e) {
                    LOG.warn("[WebGate] Failed to send to socket {}: {}", socket.id(), e.getMessage());
                }
            }
        }
    }

    /** 会话主人（对话隔离用；会话目录不存在或读取失败时为 null） */
    private static String loadSessionOwner(WorkspaceContext wsContext, String sessionId) {
        try {
            Path sessionDir = wsContext.getSessionPath(sessionId);
            if (Files.isDirectory(sessionDir)) {
                return SessionMeta.load(sessionDir).getOwnerUserId();
            }
        } catch (Exception e) {
            // 忽略异常
        }
        return null;
    }

    /**
     * 广播原始 JSON 字符串到指定工作区的所有 WebSocket 连接。
     */
    @Override
    public void broadcastRaw(WorkspaceContext wsContext, String json) {
        if (wsContext == null) {
            return;
        }

        for (WebSocket socket : wsContext.getConnections()) {
            if (socket != null) {
                try {
                    socket.send(json);
                } catch (Throwable e) {
                    LOG.warn("[WebGate] broadcastRaw failed for {}: {}", socket.id(), e.getMessage());
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  兼容门面
    // ═══════════════════════════════════════════════════════════════

    /** 有活跃流时不动终态门；否则复位后发 done（实现见 {@link WebEventPublisher}）。 */
    private void emitDoneGuarded(WorkspaceContext wsContext, AgentSession session) {
        eventPublisher.emitDoneGuarded(wsContext, session);
    }

    // ═══════════════════════════════════════════════════════════════
    //  输入端口 —— 接收并处理用户请求
    // ═══════════════════════════════════════════════════════════════

    /**
     * 用户聊天输入入口（含推理选项）。
     */
    public void onChatInput(WorkspaceContext wsContext,
                            String sessionId,
                            String sessionCwd,
                            String input, String selectedModel,
                            UploadedFile[] attachments, String[] attachmentTypes,
                            String hitlAction, String source) {
        onChatInput(wsContext, sessionId, sessionCwd, input, selectedModel, attachments, attachmentTypes,
                hitlAction, source, null, null, null, null, null, null);
    }

    /**
     * 用户聊天输入入口（由 ChatWebController HTTP 接口调用）。
     */
    @Override
    public void onChatInput(WorkspaceContext wsContext,
                            String sessionId,
                            String sessionCwd,
                            String input, String selectedModel,
                            UploadedFile[] attachments, String[] attachmentTypes,
                            String hitlAction, String source,
                            String reasoningEffort, String thinkingMode, String selectedAgent) {
        onChatInput(wsContext, sessionId, sessionCwd, input, selectedModel, attachments, attachmentTypes,
                hitlAction, source, reasoningEffort, thinkingMode, selectedAgent, null, null, null);
    }

    /**
     * 带有不可变来源/回复路由快照的统一输入入口。
     *
     * <p>受理判定（忙 / 待审批 / 积压 / 派发 / 忙时可执行命令）、入积压、解析、命令识别、Prompt 构建、
     * 请求装配在引擎唯一入口 handle 里完成；结局分派、开流、终态、续发在 HarnessHost 里完成。
     * 这里只做：会话认领（按用户隔离）、HITL / 续流 / 普通消息三种输入的组装、会话标签生成。</p>
     */
    public void onChatInput(WorkspaceContext wsContext,
                            String sessionId, String sessionCwd,
                            String input, String selectedModel,
                            UploadedFile[] attachments, String[] attachmentTypes,
                            String hitlAction, String source,
                            String reasoningEffort, String thinkingMode, String selectedAgent,
                            String sourceUserId, String replyTarget, String messageId) {
        submit(wsContext, sessionId, sessionCwd, input, selectedModel, attachments, attachmentTypes, hitlAction,
                InputOrigin.of(source, sourceUserId, replyTarget, messageId),
                reasoningEffort, thinkingMode, selectedAgent, false, false);
    }

    /**
     * 安全聊天输入入口（IM 回调等外部入口）：会话忙时先插话、再排队，并向来源端回报状态。
     *
     * @return true 表示输入已受理（含排队、插话）；false 表示未受理
     */
    public boolean safeChatInput(WorkspaceContext wsContext, String sessionId, String input, String source) {
        return safeChatInput(wsContext, sessionId, input, source, null, null, null);
    }

    public boolean safeChatInput(WorkspaceContext wsContext, String sessionId, String input, String source,
                                 String sourceUserId, String replyTarget, String messageId) {
        InputOrigin origin = InputOrigin.of(source, sourceUserId, replyTarget, messageId);
        // HITL 挂起时的拒收（豁免 /resume 与忙时可执行命令）由引擎受理判定给出 HITL_PENDING，
        // IM 来源只告知来源端、不打扰停在审批卡上的 Web 端（见 WebHostSink#rejected）
        try {
            AgentSession session = wsContext.getEngine().getSession(sessionId);
            // 裸斜杠（如 "/"）无命令名：忙时跳过并告警，不进入穿透判定也不排队
            if (input != null && "/".equals(input.trim()) && SessionActivity.isBusy(session)) {
                LOG.warn("[WebGate] {} event skipped for session {}: empty command", source, sessionId);
                return false;
            }
        } catch (Exception e) {
            LOG.warn("[WebGate] {} event check failed for session {}: {}", source, sessionId, e.getMessage());
            return false;
        }

        SubmitResult r = submit(wsContext, sessionId, null, input, null, null, null, null, origin,
                null, null, null, true, true);
        if (r == null) {
            return false;
        }
        if (r.getKind() == SubmitResult.Kind.REJECTED) {
            // 含非持有节点（原因 NOT_OWNER）；来源端已由 WebHostSink#rejected 回报
            LOG.warn("[WebGate] {} event not accepted for session {}: {}", source, sessionId, r);
            return false;
        }
        return true;
    }

    /**
     * 统一提交：组装输入交给骨架。
     *
     * @param echo          受理后回放用户消息（IM / 外部入口：前端没有本地上屏）
     * @param steerWhenBusy 会话忙时先插话（IM 入口的默认语义）
     * @return 提交结果；入口异常时为 null（已向前端补发错误与终态）
     */
    private SubmitResult submit(WorkspaceContext wsContext, String sessionId, String sessionCwd,
                                String input, String selectedModel,
                                UploadedFile[] attachments, String[] attachmentTypes,
                                String hitlAction, InputOrigin origin,
                                String reasoningEffort, String thinkingMode, String selectedAgent,
                                boolean echo, boolean steerWhenBusy) {
        AgentSession session = null;
        try {
            // 认领会话：对话隔离开启时按用户校验 / 认领归属，关闭时共享会话（首条消息即建会话）
            String userId = resolveUserIdFromContext(wsContext);
            session = wsContext.getSessionManager().getOwnedSession(sessionId, userId);
            String tenant = tenantOf(wsContext);

            final boolean isHitl = Assert.isNotEmpty(hitlAction);
            // callId 通过 session context 透传（前端 POST hitlCallId），避免全链路改签名
            String hitlCallId = null;
            if (isHitl) {
                hitlCallId = session.getContext().getAs(CTX_HITL_CALL_ID);
                session.getContext().remove(CTX_HITL_CALL_ID);
            }
            // 历史前端兼容：旧版以文本 "/resume" 触发续流，转为显式 resume 语义（同时豁免待审批拦截）
            final boolean resume = isHitl || "/resume".equals(input != null ? input.trim() : null);
            // 协议字符串在边界解析，脏值这里报错而非静默当拒绝
            final HITLAction action = isHitl ? HITLAction.of(hitlAction) : null;
            final String callId = hitlCallId;

            // 续流用的子代理取引擎的会话级记忆（审批请求通常不带 selectedAgent）；
            // 推理强度 / 思考模式 / cwd 是宿主域参数：作为属性交给引擎，忙时原样进积压、续发时原样带回
            SubmitSpec spec = SubmitSpec.of(resume ? null : input)
                    .origin(isHitl ? InputOrigin.NONE : origin)
                    .whenBusy(steerWhenBusy ? SubmitSpec.BusyPolicy.STEER : SubmitSpec.BusyPolicy.QUEUE)
                    .customize(in -> {
                        in.resume(resume)
                                .selectedAgent(selectedAgent)
                                .selectedModel(selectedModel)
                                .attr(WebInputAttrs.REASONING_EFFORT, reasoningEffort)
                                .attr(WebInputAttrs.THINKING_MODE, thinkingMode)
                                .attr(WebInputAttrs.SESSION_CWD, Assert.isEmpty(sessionCwd) ? null : sessionCwd)
                                .attr(WebInputAttrs.ECHO, echo ? "1" : null);
                        if (isHitl) {
                            in.hitl(action, callId);
                        } else {
                            in.attachments(WebAttachmentStore.toAttachments(
                                    wsContext.getEngine().getWorkspace(), attachments, attachmentTypes));
                        }
                    });
            return host.submit(tenant, sessionId, spec);
        } catch (Exception e) {
            LOG.error("Task fail: {}", e.getMessage(), e);
            emitToClient(wsContext, sessionId, WebEvent.ofError(e));
            // 失败终态同步到 IM（与 web 一致）
            streamBuilder.replyErrorToBoundChannels(wsContext, session, e);
            if (session != null) {
                eventPublisher.emitDoneGuarded(wsContext, session);
            } else {
                emitToClient(wsContext, sessionId, WebEvent.ofDone());
            }
            return null;
        } finally {
            if (session != null) {
                generateLabel(wsContext, session, input);
            }
        }
    }

    /** 空会话（如纯命令输入，还没有对话记录）从用户输入生成标签 */
    private static void generateLabel(WorkspaceContext wsContext, AgentSession session, String input) {
        if (session.isEmpty() == false || Assert.isEmpty(input)) {
            return;
        }
        try {
            Path sessionPath = wsContext.getSessionPath(session.getSessionId());
            SessionMeta meta = SessionMeta.load(sessionPath);
            if (Assert.isEmpty(meta.getLabel())) {
                String label = input.trim();
                if (label.length() > 50) {
                    label = label.substring(0, 50);
                }
                meta.setLabel(label);
                meta.save(sessionPath);
            }
        } catch (Throwable e) {
            LOG.warn("[WebGate] Failed to generate label for session {}: {}", session.getSessionId(), e.getMessage());
        }
    }

    /**
     * 中断指定会话的当前 AI 任务（用户点「停止」：清积压 + 停流）。
     *
     * <p>两段式中断与取消屏障由 HarnessHost 完成：停止之后本轮不再有事件，以 finished(CANCELLED) 收尾，
     * 取消回执与 done 由本轮的 {@link WebRunSink} 发出。没有在跑的轮次时这里兜底补一个 done。</p>
     *
     * @return true = 已处理（含「没有在跑的任务」）；false = 会话不存在、非持有节点或中断失败
     */
    @Override
    public boolean interruptSession(WorkspaceContext wsContext, String sessionId) {
        try {
            HarnessResult<Boolean> r = host.interrupt(tenantOf(wsContext), sessionId);
            if (r.isOk() == false) {
                LOG.info("[WebGate] Session {} interrupt rejected: {}", sessionId, r);
                return false;
            }
            if (Boolean.TRUE.equals(r.getValue())) {
                LOG.info("[WebGate] Session {} interrupted", sessionId);
                return true;
            }
            // 没有在跑的任务：前端可能仍停在流式态，尚未发过 done 时兜底补一个
            AgentSession session = wsContext.getEngine().getSessionOrNull(sessionId);
            boolean sent = eventPublisher.emitDoneOnce(wsContext, session);
            LOG.info("[WebGate] Session {} interrupt ignored (no active stream){}", sessionId,
                    sent ? ", emitted fallback done" : "");
            return true;
        } catch (Exception e) {
            LOG.error("[WebGate] Interrupt failed for session {}: {}", sessionId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 从当前请求上下文中解析用户 ID（仅在对话隔离开启时；否则使用共享会话）。
     */
    private static String resolveUserIdFromContext(WorkspaceContext wsContext) {
        UserAuthConfig authConfig = wsContext == null || wsContext.getSettings() == null
                ? null : wsContext.getSettings().getUserAuth();
        if (authConfig == null || !authConfig.isEnabled() || !authConfig.isConversationIsolationEnabled()) {
            return null;
        }
        Context ctx = Context.current();
        if (ctx != null) {
            String userId = ctx.attr("user_id");
            if (userId != null) {
                return userId;
            }
            // 回退：从 token 中提取 userId
            try {
                String token = UserLoginController.extractToken(ctx);
                if (token != null) {
                    UserSessionManager sessionMgr = Solon.context().getBean(UserSessionManager.class);
                    if (sessionMgr != null) {
                        UserSessionManager.UserSession session = sessionMgr.getSession(token);
                        if (session != null) {
                            return session.getUserId();
                        }
                    }
                }
            } catch (Exception e) {
                // 忽略异常，回退返回 null
            }
        }
        return null;
    }
}
