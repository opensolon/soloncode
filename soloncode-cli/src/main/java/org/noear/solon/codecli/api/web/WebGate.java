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
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.session.SessionActivity;
import org.noear.solon.codecli.session.steer.SteerMessage;
import org.noear.solon.codecli.session.steer.SteerOutputPort;
import org.noear.solon.codecli.session.queue.SessionQueue;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceMessageGateway;
import org.noear.solon.codecli.workspace.WorkspaceRegistry;
import org.noear.solon.codecli.workspace.WorkspaceRuntimePort;
import org.noear.solon.codecli.workspace.WorkspaceLogRouter;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.net.websocket.WebSocket;
import org.noear.solon.net.websocket.listener.SimpleWebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.Disposable;

/**
 * WebGate - 前端统一 WebSocket 网关（组合根）
 *
 * <p>作为后端的统一输出调度 + 统一输入入口，消除双通道问题。
 * 前端整个生命周期只维护一个 WebSocket 连接，不跟任何特定 sessionId 绑定。
 * 后端推送的所有消息包都携带 sessionId 字段，前端根据此字段分发到对应会话进行渲染。</p>
 *
 * <p>本类只保留 WebSocket 连接生命周期、统一输出出口（{@link #emitToClient} /
 * {@link #broadcastRaw}，可被测试覆写注入行为）与端口/兼容门面；输入受理、流执行、
 * 队列派发与事件门控分别委托给 {@link WebChatInputHandler}、{@link WebSessionRunner}、
 * {@link WebQueueDispatcher}、{@link WebEventPublisher}，它们之间的协作统一经本类中转。</p>
 *
 * @author noear 2026/5/8 created
 */
public class WebGate extends SimpleWebSocketListener implements WorkspaceRuntimePort, SteerOutputPort {
    private static final Logger LOG = LoggerFactory.getLogger(WebGate.class);

    /** HITL 审批时前端回传的 callUuid（通过 session context 透传） */
    public static final String CTX_HITL_CALL_ID = "hitl.callId";

    /** 会话属性：本轮 agent 流是否已向客户端发送过 done（防 interrupt + doFinally 双发） */
    private static final String ATTR_STREAM_DONE_SENT = WebEventPublisher.ATTR_STREAM_DONE_SENT;

    /** 会话属性：输入已被接纳但 Agent 流尚未完成注册，用于防止并发启动竞态。 */
    @Deprecated
    public static final String ATTR_INPUT_ADMITTING = SessionActivity.ATTR_INPUT_ADMITTING;

    private final WorkspaceRegistry workspaceRegistry;

    /** 流式响应构建器，负责组装 ReAct Agent 的流式输出并通过本网关推送 */
    private final WebStreamBuilder streamBuilder;

    /** 输入受理/命令/HITL/中断处理（经本网关中转协作） */
    private final WebChatInputHandler inputHandler;

    /** Agent 流回合执行器 */
    private final WebSessionRunner sessionRunner;

    /** Session 队列派发器 */
    private final WebQueueDispatcher queueDispatcher;

    /** 事件输出门（done 去重、流回合门） */
    private final WebEventPublisher eventPublisher;

    /** 记录 WebSocket 连接对应的用户 ID（用于按用户隔离广播） */
    private final ConcurrentHashMap<String, String> socketUserMap = new ConcurrentHashMap<>();

    public WebGate(WorkspaceRegistry workspaceRegistry) {
        this.workspaceRegistry = workspaceRegistry;
        this.streamBuilder = new WebStreamBuilder(this);
        this.inputHandler = new WebChatInputHandler(this);
        this.sessionRunner = new WebSessionRunner(this);
        this.queueDispatcher = new WebQueueDispatcher(this);
        this.eventPublisher = new WebEventPublisher(this);
    }

    // ═══════════════════════════════════════════════════════════════
    //  组件访问（包内协作中转）
    // ═══════════════════════════════════════════════════════════════

    WebEventPublisher getEventPublisher() {
        return eventPublisher;
    }

    WebQueueDispatcher getQueueDispatcher() {
        return queueDispatcher;
    }

    WebSessionRunner getSessionRunner() {
        return sessionRunner;
    }

    /**
     * 获取流式响应构建器。
     *
     * <p>供 WeChatLink 等外部组件引用，用于构建与 WebSocket 网关共享的流式输出管道。</p>
     *
     * @return 当前网关关联的 {@link WebStreamBuilder} 实例
     */
    public WebStreamBuilder getStreamBuilder() {
        return streamBuilder;
    }

    // ═══════════════════════════════════════════════════════════════
    //  WorkspaceMessageGateway / SteerOutputPort 实现
    // ═══════════════════════════════════════════════════════════════

    /**
     * Channel 通过中立工作区端口提交外部输入，避免依赖 WebGate 具体类型。
     */
    @Override
    public boolean acceptInput(WorkspaceContext workspaceContext,
                               String sessionId,
                               String input,
                               String source,
                               String sourceUserId,
                               String replyTarget,
                               String messageId) {
        return safeChatInput(workspaceContext, sessionId, input, source,
                sourceUserId, replyTarget, messageId);
    }

    /**
     * Channel 侧的统一错误通知，保持 WebEvent 的 error + done 协议不变。
     */
    @Override
    public void emitErrorAndDone(WorkspaceContext workspaceContext, String sessionId, String message) {
        emitToClient(workspaceContext, sessionId, WebEvent.ofError(message));
        emitToClient(workspaceContext, sessionId, WebEvent.ofDone());
    }

    @Override
    public void emitSteerApplied(WorkspaceContext wsContext, AgentSession session, String runId, List<SteerMessage> items) {
        emitToClient(wsContext, session.getSessionId(), WebEvent.ofSteerAppliedItems(runId, items));
    }

    /** 未消费插话先落入 session 队列；只有保存成功才通知视图已转为排队。 */
    @Override
    public void handleDroppedSteers(WorkspaceContext wsContext, AgentSession session, Queue<SteerMessage> box, String runId) {
        if (session != null && wsContext != null) {
            SessionQueue.bindStorage(session, wsContext.getSessionPath(session.getSessionId()));
        }
        List<SteerMessage> dropped = new ArrayList<>();
        List<SteerMessage> failed = new ArrayList<>();
        for (SteerMessage message; (message = box.poll()) != null; ) {
            String source = message.getSource();
            if (source == null || source.trim().isEmpty()) source = "WEB";
            int position = SessionQueue.enqueue(session, message.getText(), source,
                    message.getSourceUserId(), message.getReplyTarget(), message.getMessageId());
            if (position < 0) failed.add(message);
            else dropped.add(message);
        }
        if (!failed.isEmpty()) {
            // 保存失败时仍留在易失邮箱，下一次开流可重试；同时明确告知用户尚未排队。
            synchronized (session.attrs()) {
                @SuppressWarnings("unchecked")
                Queue<SteerMessage> pending = (Queue<SteerMessage>) session.attrs().get(org.noear.solon.codecli.session.steer.SteerInterceptor.ATTR_STEER_BOX);
                if (pending == null) {
                    pending = new java.util.concurrent.ConcurrentLinkedQueue<>();
                    session.attrs().put(org.noear.solon.codecli.session.steer.SteerInterceptor.ATTR_STEER_BOX, pending);
                }
                pending.addAll(failed);
            }
            LOG.warn("[WebGate] {} unconsumed steers could not be queued for session {}", failed.size(), session.getSessionId());
            emitToClient(wsContext, session.getSessionId(), WebEvent.ofError("插话尚未排队，已暂存；请清理队列后继续"));
        }
        if (!dropped.isEmpty()) {
            emitToClient(wsContext, session.getSessionId(), WebEvent.ofSteerDroppedItems(runId, dropped));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  WorkspaceRuntimePort 实现
    // ═══════════════════════════════════════════════════════════════

    /** 工作区上下文完成初始化后恢复 session 队列，包括旧版 Web 队列。 */
    @Override
    public void recoverSessionQueues(WorkspaceContext wsContext) {
        queueDispatcher.recoverSessionQueues(wsContext);
    }

    /** 从队头领取队列任务并派发给输入处理（队列 HTTP 接口取消/确认后触发）。
     *
     * <p>业务实现见 {@link WebQueueDispatcher#drainSessionQueue}。</p>
     */
    @Override
    public void drainSessionQueue(WorkspaceContext wsContext, AgentSession session) {
        queueDispatcher.drainSessionQueue(wsContext, session);
    }

    @Override
    public boolean isSessionBusy(HarnessEngine engine, String sessionId) {
        try {
            return SessionActivity.isBusy(engine.getSession(sessionId));
        } catch (Exception e) {
            LOG.warn("[WebGate] busy check failed for session {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 广播原始 JSON 字符串到所有 WebSocket 连接。
     *
     * <p>与 {@link #emitToClient} 不同，此方法不注入 sessionId，
     * 适用于系统级事件（如文件变化通知）等需要全局广播的场景。</p>
     *
     * @param json 待广播的原始 JSON 字符串
     */
    @Override
    public void broadcastRaw(String workspaceId, String json) {
        WorkspaceContext wsContext = workspaceRegistry.getContextsCached(workspaceId);

        if (wsContext == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[WebGate] broadcastRaw skipped: workspace '{}' not in memory (LRU-released or unknown)", workspaceId);
            }
        }

        broadcastRaw(wsContext, json);
    }

    /**
     * Loop 专用：安全聊天输入入口，无限等待捕获本轮响应文本。
     *
     * <p>适用于可能长时间执行的 Loop goal 任务。
     * 该方法仍会向前端推送完整流式消息，同时等待响应流结束。</p>
     *
     * @return 捕获到的 AI 文本；会话繁忙或无文本时返回 null
     */
    @Override
    public String runLoop(String workspaceId, String sessionId, String input, String source) {
        WorkspaceContext wsContext = workspaceRegistry.getContextsCached(workspaceId);
        return inputHandler.safeChatInputAndCaptureLoop(wsContext, sessionId, input, source);
    }

    // ═══════════════════════ ATTR_QUEUE_DISPATCH 派发线程标记兼容 ═══════════════════════

    /** 会话属性：队列派发线程标记（由 WebQueueDispatcher 维护）。 */
    static final String ATTR_QUEUE_DISPATCH = WebQueueDispatcher.ATTR_QUEUE_DISPATCH;

    // ═══════════════════════════════════════════════════════════════
    //  WebSocket 生命周期管理
    // ═══════════════════════════════════════════════════════════════

    /**
     * WebSocket 连接建立时回调。
     *
     * <p>将新连接加入连接池，后续出站消息将自动广播至此连接。</p>
     *
     * @param socket 新建立的 WebSocket 连接
     */
    @Override
    public void onOpen(WebSocket socket) {
        String wsId = socket.param("workspaceId"); // 统一：只认 workspaceId；旧参数 workspace 已废弃
        WorkspaceContext wctx = workspaceRegistry.getOrCreate(wsId);
        if (wctx == null) {
            // 失效工作区 ID：与 WorkspaceFilter 的 404 语义对齐，拒绝握手而非静默回退默认工作区
            // （静默回退会掩盖错误，并导致连接被误归入默认工作区连接池）
            LOG.warn("[WebGate] Reject websocket with unknown workspaceId: {}", wsId);
            socket.close();
            return;
        }

        // 如果用户认证启用，验证 WebSocket 连接携带的 user_token
        String userToken = socket.param("user_token");
        if (userToken != null && !userToken.isEmpty()) {
            try {
                org.noear.solon.codecli.auth.UserSessionManager sessionMgr =
                        org.noear.solon.Solon.context().getBean(org.noear.solon.codecli.auth.UserSessionManager.class);
                if (sessionMgr != null) {
                    org.noear.solon.codecli.auth.UserSessionManager.UserSession userSession = sessionMgr.getSession(userToken);
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
     * 按 workspaceId 解析目标连接池：命中工作区上下文则用其共享连接池，否则回退本实例 connections。
     *
     * <p>注意：只查内存缓存，严禁触发 getOrCreate——LRU 回收 close 时逐个 socket.close 会异步
     * 触发 onClose，若此处 getOrCreate 会把刚释放的工作区原地“复活”（连带 engine/FileWatch 泄漏）。</p>
     */
    private List<WebSocket> resolveConnections(String wsId) {
        WorkspaceContext wctx = workspaceRegistry.getContextsCached(wsId);
        if (wctx == null) {
            wctx = workspaceRegistry.getContextsCached(null);
        }
        if (wctx == null) {
            // initDefaultWorkspace 失败等极端场景下 defaultContext 可能为 null，防御避免 NPE
            return Collections.emptyList();
        }

        return wctx.getConnections();
    }

    /**
     * WebSocket 连接关闭时回调。
     *
     * @param socket 已关闭的 WebSocket 连接
     */
    @Override
    public void onClose(WebSocket socket) {
        String wsId = socket.param("workspaceId");
        // 清理用户映射
        socketUserMap.remove(socket.id());
        // 与 onOpen 对称：从目标工作区连接池中移除；兵底同时从本实例 connections 移除（共享引用时为同一列表，重复 remove 无害）。
        resolveConnections(wsId).remove(socket);

        //只查缓存定位日志归属，严禁 getOrCreate（同 resolveConnections 的理由：会把刚释放的工作区原地复活）
        WorkspaceContext wctx = workspaceRegistry.getContextsCached(wsId);
        Object logScope = WorkspaceLogRouter.beginScope(wctx == null ? null : wctx.getMeta().getPath());
        try {
            LOG.info("[WebGate] WebSocket closed: {}", socket.id());
        } finally {
            WorkspaceLogRouter.endScope(logScope);
        }
    }

    /**
     * WebSocket 文本消息接收回调。
     *
     * <p>当前仅处理心跳检测（ping/pong），业务消息通过 HTTP 接口入口进入。</p>
     *
     * @param socket 来源 WebSocket 连接
     * @param text   接收到的文本消息
     */
    @Override
    public void onMessage(WebSocket socket, String text) throws IOException {
        // 心跳处理
        if ("ping".equals(text)) {
            socket.send("pong");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  输出端口 —— 向前端推送消息
    // ═══════════════════════════════════════════════════════════════

    /**
     * 统一输出：将消息块通过 WebSocket 推送至前端。
     *
     * <p>将 sessionId 注入到消息块中，然后序列化为 JSON 广播给所有已连接的前端。
     * 前端根据消息中的 sessionId 字段路由到对应的会话面板进行渲染。</p>
     *
     * @param sessionId 会话标识，用于前端路由消息到正确的会话面板
     * @param event 待推送的消息块（可为文本流、错误、完成信号等多种类型）
     */
    public void emitToClient(WorkspaceContext wsContext, String sessionId, WebEvent<?> event) {
        if (event == null) {
            return;
        } else {
            event.setSessionId(sessionId);
        }

        // 确保消息中包含 sessionId
        String enriched = ONode.serialize(event);

        if (LOG.isDebugEnabled()) {
            LOG.debug("emit: " + enriched);
        }

        // 推送严格按 socket 所属工作区分组：直接遍历本实例（= 所属工作区上下文）的连接池，
        // 不再依赖 Context.current() 猜测（异步流线程下为 null 会回退到默认工作区而串流）。
        // 对话隔离开启时，只推送给拥有该会话的用户连接；关闭时所有已登录用户共享流。
        // 从会话元数据中获取会话所有者
        String sessionOwnerId = null;
        try {
            java.nio.file.Path sessionDir = wsContext.getSessionPath(sessionId);
            if (java.nio.file.Files.isDirectory(sessionDir)) {
                org.noear.solon.codecli.session.SessionMeta meta = org.noear.solon.codecli.session.SessionMeta.load(sessionDir);
                sessionOwnerId = meta.getOwnerUserId();
            }
        } catch (Exception e) {
            // 忽略异常
        }
        for (WebSocket socket : wsContext.getConnections()) {
            if (socket != null) {
                try {
                    // 仅在认证和对话隔离同时开启时检查 socket 是否属于会话所有者
                    org.noear.solon.codecli.auth.UserAuthConfig authConfig = wsContext.getSettings() == null
                            ? null : wsContext.getSettings().getUserAuth();
                    boolean isolate = authConfig != null && authConfig.isEnabled()
                            && authConfig.isConversationIsolationEnabled();
                    if (isolate && !socketUserMap.isEmpty()) {
                        String socketUserId = socketUserMap.get(socket.id());
                        if (socketUserId == null) {
                            // 未认证的连接，跳过
                            continue;
                        }
                        // 如果会话有所有者，只推送给所有者
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
    //  兼容门面（测试与旧扩展的反射/覆写入口）
    // ═══════════════════════════════════════════════════════════════

    /** 流结束时归还句柄槽位：只摘自己那条流（实现移至 {@link WebSessionRunner}）。 */
    static void releaseStreamSlot(AgentSession session, Disposable.Composite composite, Disposable self) {
        WebSessionRunner.releaseStreamSlot(session, composite, self);
    }

    /** 判断指定会话是否处于输入受理或 Agent 流执行状态（实现委托 {@link SessionActivity}）。 */
    private boolean isSessionBusy(AgentSession session) {
        return SessionActivity.isBusy(session);
    }

    /** 有活跃流时不动终态门；否则复位后发 done（实现移至 {@link WebEventPublisher}）。 */
    private void emitDoneGuarded(WorkspaceContext wsContext, AgentSession session) {
        eventPublisher.emitDoneGuarded(wsContext, session);
    }

    /**
     * 兼容入口：流状态属于 session 运行时，不属于 WebGate。
     */
    @Deprecated
    public static boolean hasActiveStream(AgentSession session) {
        return SessionActivity.hasActiveStream(session);
    }

    /**
     * 兼容入口：输入受理状态属于 session 运行时，不属于 WebGate。
     */
    @Deprecated
    public static boolean isInputAdmitting(AgentSession session) {
        return SessionActivity.isInputAdmitting(session);
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
     *
     * <p>业务实现见 {@link WebChatInputHandler#onChatInput}；处理流程：忙态入队 →
     * Agent 前缀解析 → HITL 审批 → 附件落盘 → 斜杠命令分发 → Agent 流式任务。</p>
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

    /** 带有不可变来源/回复路由快照的统一输入入口。 */
    public void onChatInput(WorkspaceContext wsContext,
                            String sessionId, String sessionCwd,
                            String input, String selectedModel,
                            UploadedFile[] attachments, String[] attachmentTypes,
                            String hitlAction, String source,
                            String reasoningEffort, String thinkingMode, String selectedAgent,
                            String sourceUserId, String replyTarget, String messageId) {
        inputHandler.onChatInput(wsContext, sessionId, sessionCwd, input, selectedModel, attachments, attachmentTypes,
                hitlAction, source, reasoningEffort, thinkingMode, selectedAgent, sourceUserId, replyTarget, messageId);
    }

    /**
     * 安全聊天输入入口。
     *
     * <p>在调用 {@link #onChatInput} 之前先检查会话是否繁忙（有 AI 任务正在执行），
     * 若繁忙则跳过本次输入并记录警告日志。用于 IM 回调等需要避免并发冲突的场景。</p>
     *
     * @return true 表示输入已接受并进入处理流程；false 表示会话繁忙已跳过
     */
    public boolean safeChatInput(WorkspaceContext wsContext, String sessionId, String input, String source) {
        return safeChatInput(wsContext, sessionId, input, source, null, null, null, null);
    }

    public boolean safeChatInput(WorkspaceContext wsContext, String sessionId, String input, String source,
                                 String sourceUserId, String replyTarget, String messageId) {
        return safeChatInput(wsContext, sessionId, input, source, null, sourceUserId, replyTarget, messageId);
    }

    /**
     * 安全聊天输入入口，并在输入确认受理、启动处理前执行回调。
     */
    public boolean safeChatInput(WorkspaceContext wsContext, String sessionId, String input, String source,
                                 Runnable acceptedHook) {
        return safeChatInput(wsContext, sessionId, input, source, acceptedHook, null, null, null);
    }

    private boolean safeChatInput(WorkspaceContext wsContext, String sessionId, String input, String source,
                                  Runnable acceptedHook, String sourceUserId, String replyTarget, String messageId) {
        return inputHandler.safeChatInput(wsContext, sessionId, input, source, acceptedHook,
                sourceUserId, replyTarget, messageId);
    }

    /**
     * Loop 专用旧入口，保留给已有 Web 代码和扩展。
     */
    @Deprecated
    public String safeChatInputAndCaptureLoop(String workspaceId, String sessionId, String input, String source) {
        WorkspaceContext wsContext = workspaceRegistry.getContextsCached(workspaceId);
        return inputHandler.safeChatInputAndCaptureLoop(wsContext, sessionId, input, source);
    }

    /**
     * 中断指定会话的当前 AI 任务。
     *
     * <p>业务实现见 {@link WebChatInputHandler#interruptSession}；包序保证：
     * error → 可选 trace → done → dispose。</p>
     *
     * @param sessionId 待中断的会话标识
     */
    @Override
    public boolean interruptSession(WorkspaceContext wsContext, String sessionId) {
        return inputHandler.interruptSession(wsContext, sessionId);
    }
}
