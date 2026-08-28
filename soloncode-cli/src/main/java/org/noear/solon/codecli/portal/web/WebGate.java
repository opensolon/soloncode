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
package org.noear.solon.codecli.portal.web;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLDecision;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.util.CmdUtil;
import org.noear.solon.codecli.command.WebCommandContext;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.portal.web.event.WebEvent;
import org.noear.solon.codecli.portal.web.event.WebEventNames;
import org.noear.solon.codecli.portal.web.event.payload.SystemTracePayload;
import org.noear.solon.codecli.session.SessionMeta;
import org.noear.solon.codecli.util.LogDirUtil;
import org.noear.solon.codecli.workspace.WorkspaceLogRouter;
import org.noear.solon.codecli.util.ReasoningSupportUtil;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RunUtil;
import org.noear.solon.net.websocket.WebSocket;
import org.noear.solon.net.websocket.listener.SimpleWebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebGate - 前端统一 WebSocket 网关
 *
 * <p>作为后端的统一输出调度 + 统一输入入口，消除双通道问题。
 * 前端整个生命周期只维护一个 WebSocket 连接，不跟任何特定 sessionId 绑定。
 * 后端推送的所有消息包都携带 sessionId 字段，前端根据此字段分发到对应会话进行渲染。</p>
 *
 * @author noear 2026/5/8 created
 */
public class WebGate extends SimpleWebSocketListener {
    private static final Logger LOG = LoggerFactory.getLogger(WebGate.class);
    /** HITL 审批时前端回传的 callUuid（通过 session context 透传） */
    public static final String CTX_HITL_CALL_ID = "hitl.callId";

    /** 会话属性：本轮 agent 流是否已向客户端发送过 done（防 interrupt + doFinally 双发） */
    private static final String ATTR_STREAM_DONE_SENT = "streamDoneSent";

    private final WorkspaceManager workspaceManager;

    /** 流式响应构建器，负责组装 ReAct Agent 的流式输出并通过本网关推送 */
    private final WebStreamBuilder streamBuilder;

    /** 记录 WebSocket 连接对应的用户 ID（用于按用户隔离广播） */
    private final ConcurrentHashMap<String, String> socketUserMap = new ConcurrentHashMap<>();

    public WebGate(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
        this.streamBuilder = new WebStreamBuilder(this);
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

    /**
     * 从当前请求上下文中解析用户 ID。
     * 优先从 UserAuthFilter 设置的上下文属性获取，否则从 token 中提取。
     */
    private static String resolveUserIdFromContext() {
        org.noear.solon.core.handle.Context ctx = org.noear.solon.core.handle.Context.current();
        if (ctx != null) {
            String userId = ctx.attr("user_id");
            if (userId != null) {
                return userId;
            }
            // 回退：从 token 中提取 userId
            try {
                String token = org.noear.solon.codecli.auth.UserLoginController.extractToken(ctx);
                if (token != null) {
                    org.noear.solon.codecli.auth.UserSessionManager sessionMgr =
                            org.noear.solon.Solon.context().getBean(org.noear.solon.codecli.auth.UserSessionManager.class);
                    if (sessionMgr != null) {
                        org.noear.solon.codecli.auth.UserSessionManager.UserSession session = sessionMgr.getSession(token);
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
        WorkspaceContext wctx = workspaceManager.getOrCreate(wsId);
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
        WorkspaceContext wctx = workspaceManager.getContextsCached(wsId);
        if (wctx == null) {
            wctx = workspaceManager.getContextsCached(null);
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
        WorkspaceContext wctx = workspaceManager.getContextsCached(wsId);
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
    public void emitToClient(WorkspaceContext wsContext,String sessionId, WebEvent<?> event) {
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
        // 用户认证启用时，只推送给拥有该会话的用户连接，实现会话隔离
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
                    // 如果用户认证启用，检查该 socket 是否属于会话所有者
                    if (!socketUserMap.isEmpty()) {
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
     * 流级 done 只发一次；返回 true 表示本次真正发出。
     *
     * <p>覆盖正常完成、异常、用户 interrupt 等路径，避免 dispose + doFinally 与
     * interrupt 显式 ofDone 造成双 done。</p>
     */
    private boolean emitDoneOnce(WorkspaceContext wsContext ,AgentSession session) {
        if (session == null) {
            return false;
        }
        AtomicBoolean doneSent = (AtomicBoolean) session.attrs()
                .computeIfAbsent(ATTR_STREAM_DONE_SENT, k -> new AtomicBoolean(false));
        if (!doneSent.compareAndSet(false, true)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[WebGate] skip duplicate done for session {}", session.getSessionId());
            }
            return false;
        }
        emitToClient(wsContext,session.getSessionId(), WebEvent.ofDone());
        return true;
    }

    /**
     * 新开流前重置 done 标记，避免上一轮 streamDoneSent 挡住本轮 done。
     */
    private void resetStreamDoneSent(AgentSession session) {
        if (session == null) {
            return;
        }
        session.attrs().put(ATTR_STREAM_DONE_SENT, new AtomicBoolean(false));
    }

    /**
     * 新开流统一入口：后端 done 门与前端流门必须成对重置。
     *
     * <p>后端 {@link #resetStreamDoneSent} 只解开自己的 done 去重门，前端在收到上一轮
     * done 后会把该会话置为 _streamClosed=true 并丢弃后续所有 chunk。若只重置后端，
     * 同一会话第二段流（HITL 恢复、命令触发的 agent 任务、Loop/Goal 续跑等）在后端
     * 正常 emit，前端却全部静默丢弃 —— 表现为「任务跑着突然没输出、新开会话又正常」。
     * 因此这里在开流前额外下发 system.reset 解封前端。</p>
     */
    private void beginStreamTurn(WorkspaceContext wsContext, AgentSession session) {
        resetStreamDoneSent(session);

        if (session != null) {
            // 新任务开流：清上一轮 runId 与残留插话邮箱。
            // onAgentEnd 不在 finally 中（interrupt/异常路径不触发），若不在此清理，
            // 上一任务未消费的插话会在新任务第二轮被注入，破坏方案 A 的任务级隔离。
            // 残留不能静默丢弃（包括 HITL 挂起期间提交的插话），一律广播 dropped 让前端转排队
            session.attrs().remove(SteerInterceptor.ATTR_ACTIVE_RUN_ID);
            @SuppressWarnings("unchecked")
            Queue<String> staleBox = (Queue<String>) session.attrs().remove(SteerInterceptor.ATTR_STEER_BOX);

            emitToClient(wsContext, session.getSessionId(), WebEvent.ofResetStream());

            if (staleBox != null && staleBox.isEmpty() == false) {
                java.util.List<String> stale = new java.util.ArrayList<>();
                for (String t; (t = staleBox.poll()) != null; ) {
                    stale.add(t);
                }
                emitToClient(wsContext, session.getSessionId(), WebEvent.ofSteerDropped(null, stale));
            }
        }
    }

    /**
     * 流未建立（或已失败）时的 done 出口。
     *
     * <p>done 门只在 {@link #beginStreamTurn} 里复位，因此开流之前的异常出口（参数解析、
     * 模型/agent 解析、附件落盘、命令分发）直接 {@link #emitDoneOnce} 会被上一轮遗留的
     * doneSent=true 静默吞掉 —— 前端已在发送时置 isStreaming=true，收不到 done 就永久
     * 停在「正在思考」。故无活跃流时先复位门；有活跃流则不抢发，交给那条流的
     * {@code doFinally} 收尾。</p>
     */
    private void emitDoneGuarded(WorkspaceContext wsContext, AgentSession session) {
        if (isSessionBusy(session) == false) {
            resetStreamDoneSent(session);
        }

        emitDoneOnce(wsContext, session);
    }

    /**
     * 一轮流启动失败的收尾：发 error、归还句柄槽位、确保前端能收到 done。
     *
     * <p>订阅动作被调度到 boundedElastic 后，开流前的异常（如模型未配置、agent 不存在、
     * 管道装配失败）不再落入 {@code onChatInput} 的 catch，而是被调度器吞掉：若不处理，
     * composite 会长驻 attrs 使 {@link #isSessionBusy} 永久为真（Stop 也只能发假 done），
     * 且前端收不到任何终态包。</p>
     */
    private void failStreamTurn(WorkspaceContext wsContext, AgentSession session, Disposable.Composite composite, Throwable e) {
        LOG.error("Task fail: {}", e.getMessage(), e);

        emitToClient(wsContext, session.getSessionId(), WebEvent.ofError(e));

        if (composite != null) {
            //本轮未挂上任何流（self=null）：仅当槽位已无其它活跃流时才清空
            releaseStreamSlot(session, composite, null);
        }

        emitDoneGuarded(wsContext, session);
    }

    /**
     * 广播原始 JSON 字符串到所有 WebSocket 连接。
     *
     * <p>与 {@link #emitToClient} 不同，此方法不注入 sessionId，
     * 适用于系统级事件（如文件变化通知）等需要全局广播的场景。</p>
     *
     * @param json 待广播的原始 JSON 字符串
     */
    public void broadcastRaw(String workspaceId,String json) {
        WorkspaceContext wsContext = workspaceManager.getContextsCached(workspaceId);

        if (wsContext == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[WebGate] broadcastRaw skipped: workspace '{}' not in memory (LRU-released or unknown)", workspaceId);
            }
        }

        broadcastRaw(wsContext, json);
    }

    public void broadcastRaw(WorkspaceContext wsContext,String json) {
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
                hitlAction, source, null, null, null);
    }

    /**
     * 用户聊天输入入口（由 WebController HTTP 接口调用）。
     *
     * <p>核心处理流程：</p>
     * <ol>
     *   <li>解析 Agent 指定前缀（如 "@agentName 消息内容"）</li>
     *   <li>处理 HITL（Human-in-the-Loop）审批/拒绝操作</li>
     *   <li>处理文件附件上传（图片走 Base64 编码，其他走文件路径引用）</li>
     *   <li>判断是否为斜杠命令（/command），若是则走命令分发</li>
     *   <li>构建 Prompt 并启动 Agent 流式任务</li>
     * </ol>
     *
     * @param sessionId       会话标识
     * @param sessionCwd      会话当前工作目录，用于 Agent 执行文件操作的基准路径
     * @param input           用户输入的文本内容
     * @param selectedModel   用户选择的 AI 模型标识（可为 null，表示使用默认模型）
     * @param attachments     上传的文件附件数组（可为 null）
     * @param attachmentTypes 附件类型数组，与 attachments 一一对应（如 "image"）
     * @param hitlAction      HITL 操作类型，取值 "approve" 或 "reject"（可为 null）
     * @param source          消息来源通道标识
     * @param reasoningEffort 请求级推理水平（可选，写入会话后由 StreamBuilder 注入）
     * @param thinkingMode    请求级思考模式 on|off（可选，独立于推理强度，写入会话后由 StreamBuilder 注入）
     * @param selectedAgent   选择器指定的子代理（可选；空值时使用主 Agent）
     */
    public void onChatInput(WorkspaceContext wsContext,
                            String sessionId,
                            String sessionCwd,
                            String input, String selectedModel,
                            UploadedFile[] attachments, String[] attachmentTypes,
                            String hitlAction, String source,
                            String reasoningEffort, String thinkingMode, String selectedAgent) {
        AgentSession session = null;
        try {
            // 本 WebGate 实例已绑定所属工作区的引擎（与 connections 同一上下文），
            // 无需再从 Context.current()/sessionId 猜测引擎，避免异步线程下回退默认引擎。
            // 用户认证启用时，记录 userId 便于后续会话路径隔离
            String userId = resolveUserIdFromContext();
            session = wsContext.getSessionManager().getSession(sessionId, userId);

            // 写入会话级模型 / 推理（后续 StreamBuilder 与旁路任务均可读取）
            if (Assert.isNotEmpty(selectedModel)) {
                session.getContext().put(HarnessEngine.CTX_MODEL_SELECTED, selectedModel);
            }
            // 写入会话级子代理选择（与模型一样的持久化逻辑）
            session.getContext().put(HarnessEngine.CTX_AGENT_SELECTED,
                    selectedAgent != null ? selectedAgent : "");
            boolean effortProvided = reasoningEffort != null;
            ReasoningSupportUtil.putSessionEffort(session, reasoningEffort, effortProvided);
            boolean modeProvided = thinkingMode != null;
            ReasoningSupportUtil.putSessionThinkingMode(session, thinkingMode, modeProvided);

            String agentName = null;
            String currentInput = input;

            if (currentInput != null && currentInput.startsWith("@")) {
                int agentNameIdx = currentInput.indexOf(" ");
                if (agentNameIdx > 0) {
                    String explicitAgent = currentInput.substring(1, agentNameIdx);
                    if (wsContext.getEngine().getAgentManager().hasAgent(explicitAgent)) {
                        agentName = explicitAgent;
                        currentInput = currentInput.substring(agentNameIdx + 1);
                    }
                }
            }

            // 输入开头的有效 @子代理 优先；否则使用选择器传入的有效子代理；都没有时使用主 Agent。
            if (agentName == null && Assert.isNotEmpty(selectedAgent)
                    && wsContext.getEngine().getAgentManager().hasAgent(selectedAgent)) {
                agentName = selectedAgent;
            }


            // HITL approve/reject handling（按 callUuid 精确决策，支持批量逐卡审批）
            if (Assert.isNotEmpty(hitlAction)) {
                // callId 通过 session context 透传（前端 POST hitlCallId），避免全链路改签名
                String hitlCallId = session.getContext().getAs(CTX_HITL_CALL_ID);
                session.getContext().remove(CTX_HITL_CALL_ID);

                HITLTask task = Assert.isNotEmpty(hitlCallId)
                        ? HITL.getPendingTaskByCallUuid(session, hitlCallId)
                        : HITL.getPendingTask(session); // 无 callId 时回退单任务（兼容旧前端）

                if (task != null) {
                    if ("approve".equals(hitlAction)) {
                        HITL.approve(session, task);
                    } else {
                        HITL.reject(session, task);
                    }
                }

                // 恢复时机：批量场景下前端逐卡点击会发多次决策，
                // 仅当本批所有挂起任务都已有决策时才恢复流，否则只写决策不 resume。
                if (allHitlDecided(session)) {
                    performAgentTaskAsync(wsContext,session, sessionCwd, null, selectedModel, agentName);
                }
                return;
            }

            // Handle file upload
            List<ImageBlock> imageBlocks = new ArrayList<>();
            List<String> imageFileNames = new ArrayList<>();
            List<String> fileAttachments = new ArrayList<>();

            if (attachments != null) {
                for (int i = 0; i < attachments.length; i++) {
                    UploadedFile attachment = attachments[i];
                    String fileName = attachment.getName();
                    if (fileName != null && !fileName.contains("..") && !fileName.contains("/") && !fileName.contains("\\")) {
                        String ext = "." + attachment.getExtension();
                        Path uploadDir = Paths.get(wsContext.getEngine().getWorkspace(), ".uploads").toAbsolutePath().normalize();
                        Files.createDirectories(uploadDir);
                        Path savePath = uploadDir.resolve(fileName).toAbsolutePath().normalize();
                        fileName = ".uploads/" + fileName;

                        if (savePath.startsWith(Paths.get(wsContext.getEngine().getWorkspace()).toAbsolutePath().normalize())) {
                            Files.copy(attachment.getContent(), savePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                            if (isImageAttachment(ext, attachmentTypes != null && i < attachmentTypes.length ? attachmentTypes[i] : null)) {
                                byte[] bytes = Files.readAllBytes(savePath);
                                String base64 = Base64.getEncoder().encodeToString(bytes);
                                String mime = extensionToMime(ext);
                                imageBlocks.add(ImageBlock.ofBase64(base64, mime));
                                imageFileNames.add(fileName);
                            } else {
                                fileAttachments.add(fileName);
                            }
                        }
                    }
                }
            }

            // Build input text with file attachment prefix
            if (!fileAttachments.isEmpty()) {
                String filePrefix = fileAttachments.stream()
                        .map(f -> "[附件: " + f + "]")
                        .collect(java.util.stream.Collectors.joining("\n"));
                if (currentInput == null || currentInput.isEmpty()) {
                    currentInput = filePrefix + "\n请帮我处理这些附件";
                } else {
                    currentInput = filePrefix + "\n" + currentInput;
                }
            }

            if (Assert.isNotEmpty(currentInput) || !imageBlocks.isEmpty()) {
                if (currentInput == null || currentInput.isEmpty()) {
                    currentInput = imageBlocks.size() > 1 ? "请描述这些图片" : "请描述这张图片";
                }

                // 命令分发
                if (currentInput.startsWith("/") && imageBlocks.isEmpty()) {
                    if (isCommand(wsContext, session, sessionCwd, currentInput, selectedModel, agentName)) {
                        return;
                    }
                }

                Prompt prompt;
                if (!imageBlocks.isEmpty()) {
                    Contents contents = new Contents();
                    contents.addBlock(TextBlock.of(currentInput));
                    for (ImageBlock block : imageBlocks) {
                        contents.addBlock(block);
                    }
                    // 构建附件元数据（含图片文件名），供历史消息恢复时前端渲染文件名标签
                    String attachMeta = buildAttachmentMeta(imageFileNames);
                    UserMessage userMsg = new UserMessage(contents).addMetadata("source", source);
                    if (attachMeta != null) {
                        userMsg.addMetadata("attachments", attachMeta);
                    }
                    addAgentMeta(userMsg, agentName);
                    prompt = Prompt.of(userMsg);
                } else {
                    // 文件附件已在 currentInput 前缀写入文件名（[附件: xxx]），ndjson 有记录
                    UserMessage userMsg = ChatMessage.ofUser(currentInput).addMetadata("source", source);
                    addAgentMeta(userMsg, agentName);
                    prompt = Prompt.of(userMsg);
                }

                // 流式处理：输出通过 WebSocket 推送
                performAgentTaskAsync(wsContext, session, sessionCwd, prompt, selectedModel, agentName);
            }
        } catch (Exception e) {
            LOG.error("Task fail: {}", e.getMessage(), e);
            emitToClient(wsContext, sessionId, WebEvent.ofError(e));
            // 流可能尚未建立：有 session 走去重出口，否则直接发 done
            if (session != null) {
                emitDoneGuarded(wsContext, session);
            } else {
                emitToClient(wsContext, sessionId, WebEvent.ofDone());
            }
        } finally {
            if (session != null) {
                if (session.isEmpty() && Assert.isNotEmpty(input)) {
                    //如果是空，可能发的是 command（还没有对话记录）
                    try {
                        Path sessionPath = wsContext.getSessionPath(sessionId);
                        SessionMeta meta = SessionMeta.load(sessionPath);
                        if (Assert.isEmpty(meta.getLabel())) {
                            // 从用户输入生成 label（空会话场景，如纯命令输入）
                            String label = input.trim();
                            if (label.length() > 50) {
                                label = label.substring(0, 50);
                            }
                            meta.setLabel(label);
                            meta.save(sessionPath);
                        }
                    } catch (Throwable e) {
                        LOG.warn("[WebGate] Failed to generate label for session {}: {}", sessionId, e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 执行 Agent 流式任务。
     *
     * <p>通过 {@link WebStreamBuilder} 构建 ReAct Agent 的响应流，
     * 订阅流数据并通过 {@link #emitToClient} 逐条推送至前端。
     * 同时将 RxJava {@link Disposable} 保存到会话属性中，以支持 {@link #interruptSession} 中断。</p>
     *
     * @param session      Agent 会话实例
     * @param sessionCwd   会话当前工作目录
     * @param prompt       用户输入的 Prompt（为 null 时表示 HITL 恢复等无需新 Prompt 的场景）
     * @param selectedModel 用户选择的 AI 模型标识
     * @param agentName    指定 Agent 名称（可为 null，表示使用默认 Agent）
     */
    /**
     * 判断本批所有 HITL 挂起任务是否均已有决策。
     *
     * <p>批量场景下前端逐卡点击会发多次决策 POST，仅当无任何未决策项时
     * 才应恢复流，避免过早 resume 将未决策任务带走。</p>
     *
     * @param session 当前会话
     * @return 均已决策（或无挂起任务）返回 true
     */
    private boolean allHitlDecided(AgentSession session) {
        List<HITLTask> pending = HITL.getPendingTasks(session);
        if (pending == null || pending.isEmpty()) {
            return true;
        }
        for (HITLTask t : pending) {
            HITLDecision decision = HITL.getDecision(session, t);
            if (decision == null) {
                return false;
            }
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    //  工作区日志打标（WorkspaceLogRouter 依据 MDC 分流到各自日志文件）
    // ═══════════════════════════════════════════════════════════════

    /** 当前工作区的日志标识（无上下文时为 null，走默认路由） */
    private static String wsLogKey(WorkspaceContext wsContext) {
        return wsContext == null ? null : LogDirUtil.workspaceKey(wsContext.getMeta().getPath());
    }

    private void performAgentTaskAsync(WorkspaceContext wsContext, AgentSession session, String sessionCwd, Prompt prompt, String selectedModel, String agentName) {
        //订阅前（调用线程内）先注册 composite：订阅动作被调度到别的线程异步执行，若把注册也放进去，
        //这段窗口内 isSessionBusy 会误判空闲（并发 input 可能在同一会话上开出第二条流），
        //且 interruptSession 取不到 disposable，只会补发一个假 done —— 任务照跑且再也无法取消。
        Disposable.Composite composite = (Disposable.Composite) session.attrs()
                .computeIfAbsent("disposable", k -> Disposables.composite());

        //agent 执行主体在 boundedElastic 线程上跑，订阅动作包在工作区日志作用域里：
        //同步源的整个管道执行发生在 subscribe() 调用栈内，故管道主体线程携带工作区标记
        final String wsLogKey = wsLogKey(wsContext);
        Runnable subscribeAction = () -> {
            Object logScope = WorkspaceLogRouter.beginScopeByKey(wsLogKey);
            try {
                doSubscribeAgentTask(wsContext, session, sessionCwd, prompt, selectedModel, agentName, composite);
            } catch (Throwable e) {
                //调度线程内的异常不会回到 onChatInput 的 catch，必须自行收尾
                failStreamTurn(wsContext, session, composite, e);
            } finally {
                WorkspaceLogRouter.endScope(logScope);
            }
        };

        if (wsLogKey == null) {
            subscribeAction.run();
        } else {
            Schedulers.boundedElastic().schedule(subscribeAction);
        }
    }

    private void doSubscribeAgentTask(WorkspaceContext wsContext, AgentSession session, String sessionCwd, Prompt prompt, String selectedModel, String agentName, Disposable.Composite composite) {
        String sessionId = session.getSessionId();

        //调度窗口期内已被 interrupt：不开流、不发 reset（否则刚收尾的前端会被解封成幽灵流）。
        //与 sync 路径对称地补发终态：若 interrupt 已发过 done，会被 emitDoneOnce 的去重门
        //静默吞掉（无害）；若上游异常路径未发过，则前端不至因收不到任何事件而卡在等待态
        if (composite.isDisposed()) {
            LOG.info("[WebGate] Session {} task aborted before subscribe (interrupted)", sessionId);
            emitDoneOnce(wsContext, session);
            return;
        }

        if (selectedModel != null) {
            session.getContext().put(HarnessEngine.CTX_MODEL_SELECTED, selectedModel);
        } else {
            selectedModel = session.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED);
        }

        ChatModel chatModel = wsContext.getEngine().getModelOrDefInstance(selectedModel);
        ReActAgent agent = wsContext.getEngine().getAgentOrMain(agentName);

        // 新开流前重置：后端 done 门 + 前端流门（否则上一轮 done 会让本轮输出被前端丢弃）
        beginStreamTurn(wsContext, session);

        AtomicReference<Disposable> selfRef = new AtomicReference<>();
        Disposable disposable = streamBuilder.buildStreamFlux(wsContext, session, agent, chatModel, sessionCwd, prompt)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(line -> {
                    emitToClient(wsContext, sessionId, line);
                })
                .doOnError(e -> {
                    LOG.error("Task fail: {}", e.getMessage(), e);

                    emitToClient(wsContext,sessionId, WebEvent.ofError(e));
                })
                .doFinally(s -> {
                    releaseStreamSlot(session, composite, selfRef.get());  // 只摘自己那条流

                    // 流级终态只发一次（含 dispose / 正常 complete / error）
                    emitDoneOnce(wsContext,session);

                    //MDC 不在此处清理：Reactor 调度钩子会在任务结束时自动还原线程现场，
                    //提前 remove 反而会让同一任务后续日志丢掉工作区归属
                })
                .subscribe();

        selfRef.set(disposable);
        // add 到 composite：若 composite 已被 dispose()（interrupt 先到达），会立即 dispose 该 disposable
        composite.add(disposable);

    }

    /**
     * 流结束时归还句柄槽位：只摘自己那条流。
     *
     * <p>HITL 审批恢复、命令触发的 agent 任务、连发 input 都会把多条流挂进同一个 composite。
     * 若在 doFinally 里无条件 {@code remove("disposable")}，第一条流结束就把槽位摘空 ——
     * 此后 {@link #isSessionBusy} 转假、Stop 取不到 composite 只能补一个假 done，
     * 仍在跑的另一条流便再也无法取消。故先从 composite 摘掉自己，仅当已无活跃流时才清空槽位；
     * 清空按值条件删除，避免误删后续新轮次刚放进去的 composite。</p>
     */
    private static void releaseStreamSlot(AgentSession session, Disposable.Composite composite, Disposable self) {
        if (self != null) {
            composite.remove(self);
        }

        if (composite.size() == 0) {
            session.attrs().remove("disposable", composite);
        }
    }

    /**
     * 执行 Agent 流式任务。
     *
     * <p>通过 {@link WebStreamBuilder} 构建 ReAct Agent 的响应流，
     * 订阅流数据并通过 {@link #emitToClient} 逐条推送至前端。
     * 同时将 RxJava {@link Disposable} 保存到会话属性中，以支持 {@link #interruptSession} 中断。</p>
     *
     * @param session      Agent 会话实例
     * @param sessionCwd   会话当前工作目录
     * @param prompt       用户输入的 Prompt（为 null 时表示 HITL 恢复等无需新 Prompt 的场景）
     * @param selectedModel 用户选择的 AI 模型标识
     * @param agentName    指定 Agent 名称（可为 null，表示使用默认 Agent）
     */
    private String performAgentTaskSync(WorkspaceContext wsContext, AgentSession session, String sessionCwd, Prompt prompt, String selectedModel, String agentName) {
        String sessionId = session.getSessionId();

        if (selectedModel != null) {
            session.getContext().put(HarnessEngine.CTX_MODEL_SELECTED, selectedModel);
        } else {
            selectedModel = session.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED);
        }

        ChatModel chatModel = wsContext.getEngine().getModelOrDefInstance(selectedModel);
        ReActAgent agent = wsContext.getEngine().getAgentOrMain(agentName);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        AtomicReference<String> finalAnswerRef = new AtomicReference<>("");

        // 先注册句柄槽位，再开流：注册必须早于 beginStreamTurn。否则在「门已复位、句柄未挂」的窗口里，
        // interrupt 会误入 no-active-stream 分支并真的发出一个 done，而随后的调度照常订阅 ——
        // 本轮既不可取消（Loop 会一直跑），输出又因前端已收尾而被丢弃。
        Disposable.Composite composite = (Disposable.Composite)session.attrs().computeIfAbsent("disposable", k->Disposables.composite());

        final String wsLogKey = wsLogKey(wsContext);
        Runnable subscribeAction = () -> {
            Object logScope = WorkspaceLogRouter.beginScopeByKey(wsLogKey);
            try {
                //调度窗口期内已被 interrupt：不开流、不发 reset（否则刚收尾的前端会被解封成幽灵流）；
                //但必须释放闩锁，否则同步等待方（Loop）永久阻塞
                if (composite.isDisposed()) {
                    LOG.info("[WebGate] Session {} task aborted before subscribe (interrupted)", sessionId);
                    emitDoneOnce(wsContext, session);
                    countDownLatch.countDown();
                    return;
                }

                // 新开流前重置：后端 done 门 + 前端流门（与 async 路径对称，放在 isDisposed 之后，
                // 避免为一个已取消的轮次解封前端）
                beginStreamTurn(wsContext, session);

                AtomicReference<Disposable> selfRef = new AtomicReference<>();
                Disposable d = streamBuilder.buildStreamFlux(wsContext, session, agent, chatModel, sessionCwd, prompt)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(line -> {
                    emitToClient(wsContext,sessionId, line);

                    if (WebEventNames.SYSTEM_TRACE.equals(line.getEvent()) && line.getPayload() instanceof SystemTracePayload) {
                        SystemTracePayload tracePayload = (SystemTracePayload) line.getPayload();
                        if (Assert.isNotEmpty(tracePayload.getFinalAnswer())) {
                            finalAnswerRef.set(tracePayload.getFinalAnswer());
                        }
                    }
                })
                .doOnError(e -> {
                    LOG.error("Task fail: {}", e.getMessage(), e);

                    emitToClient(wsContext,sessionId, WebEvent.ofError(e));
                })
                .doFinally(s -> {
                    releaseStreamSlot(session, composite, selfRef.get());  // 只摘自己那条流

                    // 流级终态只发一次（含 dispose / 正常 complete / error）
                    emitDoneOnce(wsContext,session);
                    countDownLatch.countDown();

                    //MDC 不在此处清理：Reactor 调度钩子会在任务结束时自动还原线程现场，
                    //提前 remove 反而会让同一任务后续日志丢掉工作区归属
                }).subscribe();
                //subscribe 后立即挂上：同步等待线程可能已读到 null，若不在任务内补挂则 Stop/interrupt 无法取消本轮
                selfRef.set(d);
                composite.add(d);
            } catch (Throwable e) {
                //调度线程内的异常不会回到调用方；未订阅成功则 doFinally 不会跑，
                //此处必须补发终态包并释放闩锁，否则同步等待方（Loop）永久阻塞
                failStreamTurn(wsContext, session, composite, e);
                countDownLatch.countDown();
            } finally {
                WorkspaceLogRouter.endScope(logScope);
            }
        };

        if (wsLogKey == null) {
            subscribeAction.run();
        } else {
            //订阅调度到携带 MDC 的 boundedElastic 线程：管道主体执行线程携带工作区标记
            Schedulers.boundedElastic().schedule(subscribeAction);
        }

        //等待订阅完成（闩锁在 doFinally 释放）；disposable 已在 subscribeAction 内挂入 composite，Stop/interrupt 可随时取消
        RunUtil.runAndTry(() -> {
            try {
                countDownLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return finalAnswerRef.get();
    }

    /**
     * 尝试将用户输入解析为斜杠命令并执行。
     *
     * <p>解析输入字符串中的命令名和参数，查找已注册的 {@link Command} 并执行。
     * 若命令执行后产生非 Agent 任务结果，会通过 WebSocket 推送命令输出；
     * 若为 rewind 命令，会发送特殊的回退事件通知前端删除历史 DOM。</p>
     *
     * @param session      Agent 会话实例
     * @param sessionCwd   会话当前工作目录
     * @param input        用户输入的完整文本（以 "/" 开头）
     * @param selectedModel 用户选择的 AI 模型标识
     * @param agentName    指定 Agent 名称
     * @return true 表示输入已被识别为命令并执行，false 表示非命令输入
     * @throws Exception 命令执行过程中可能抛出的异常
     */
    private boolean isCommand(WorkspaceContext wsContext, AgentSession session, String sessionCwd, String input, String selectedModel, String agentName) throws Exception {
        if (!input.startsWith("/")) {
            return false;
        }

        // 解析命令名和参数
        List<String> parts = CmdUtil.parseArguments(input.trim().substring(1));
        String cmdName = parts.get(0).toLowerCase();
        List<String> args = parts.size() > 1
                ? parts.subList(1, parts.size())
                : Collections.emptyList();

        // 查找命令
        Command command = wsContext.getEngine().getCommandRegistry().find(cmdName);
        if (command == null) {
            return false;
        }

        // 构建 context（注入 agentTaskRunner 回调）；命令执行的工作区语境由本 WebGate 实例已绑定的 engine 确定。
        WebCommandContext ctx = new WebCommandContext(session, wsContext.getEngine(), input, cmdName, args,
                (prompt, model) -> {
                    try {
                        //防重入：会话已有流在跑（含上一轮 doFinally 尚未归还槽位的瞬时窗口）时，
                        //再开一条流会与在跑流共享同一 ReActTrace —— 旧流把 route 置 END 后
                        //新流立即空转结束，表现为「点了没反应、马上结束」。故忙碌时忽略并提示
                        if (isSessionBusy(session)) {
                            LOG.warn("[WebGate] Session {} agent task via /{} skipped: another task in progress", session.getSessionId(), cmdName);
                            emitToClient(wsContext, session.getSessionId(), WebEvent.ofCommand("当前有任务正在执行，本次触发已忽略"));
                            return;
                        }

                        if (model == null) {
                            model = selectedModel;
                        }

                        performAgentTaskAsync(wsContext, session, sessionCwd, Prompt.of(prompt), model, agentName);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        // 执行命令
        command.execute(ctx);


        if (ctx.isAgentTask() == false) {
            // 命令回执自成一轮：只要此刻没有 agent 流在跑，就用完整的「reset ... done」信封包住它 ——
            // 前端在上一轮 done 之后会置 _streamClosed 并丢弃后续 chunk，而命令事件不带 runId、
            // 自愈判据用不上，缺了 reset 时远端触发（IM/API）的命令回执在 Web 端会静默消失。
            // 反之若会话繁忙（流式期间来一条 /status），本轮归那条流：既不解封也不抢发 done，
            // 否则会让前端提前收尾并丢掉该流后续输出（runId 未变，自愈同样兜不住）。
            boolean ownTurn = (isSessionBusy(session) == false);
            if (ownTurn) {
                beginStreamTurn(wsContext, session);
            }

            // rewind 命令走特殊通道：发送 rewind 事件让前端同步删除 DOM
            if ("rewind".equals(cmdName)) {
                int rewindCount = 1;
                if (!args.isEmpty()) {
                    try {
                        rewindCount = Integer.parseInt(args.get(0));
                    } catch (NumberFormatException ignored) {
                    }
                }

                //加一条删掉自己发出的一条
                /* 被回退的那一轮，其执行过程还留在上下文快照里（__main 的 ReActTrace）。
                 * 不清掉的话，刷新页面时 /messages/last-trace 会把已删掉的思考与工具卡
                 * 原样回放回来 —— 与 /web/chat/rewind 同理。 */
                session.getContext().remove(AgentFlags.TRACE_KEY_MAIN);
                session.updateSnapshot();

                emitToClient(wsContext, session.getSessionId(), WebEvent.ofRewind(rewindCount + 1));
            } else {
                final String text;
                if (ctx.getOutputBuffer().length() > 0) {
                    text = ctx.getOutputBuffer().toString();
                } else {
                    text = "命令执行完成";
                }

                emitToClient(wsContext, session.getSessionId(), WebEvent.ofCommand(text));

                // 命令执行后通知所有绑定的 IM 通道（微信/飞书/钉钉等）
                streamBuilder.replyToBoundChannel(wsContext, session.getSessionId(), text, true);
            }

            // done 走统一出口（emitDoneOnce），使「所有 done 都经过去重门」这条不变量不被绕过；
            // done 门已由上面的 beginStreamTurn 复位，否则会被上一轮的 doneSent 挡掉。
            if (ownTurn) {
                emitDoneOnce(wsContext, session);
            }
        }

        return true;
    }


    /**
     * 判断指定会话是否有 AI 任务正在执行。
     *
     * <p>通过检查会话属性中保存的 {@link Disposable} 对象是否仍处于活跃状态来判断。</p>
     *
     * @param session Agent 会话实例
     * @return true 表示会话有正在执行的 AI 任务
     */
    private boolean isSessionBusy(AgentSession session) {
        Object slot = session.attrs().get("disposable");
        if (slot instanceof Disposable.Composite) {
            return !((Disposable.Composite) slot).isDisposed();
        }
        return false;
    }

    /**
     * 判断指定会话是否有 AI 任务正在执行（按 sessionId 查询）。
     *
     * <p>供 LoopScheduler 等外部组件在定时触发前判断会话是否繁忙，繁忙则跳过本次执行。
     * 会话不存在或查询异常时按非繁忙处理。</p>
     *
     * @param sessionId 会话标识
     * @return true 表示会话有正在执行的 AI 任务
     */
    public boolean isSessionBusy(HarnessEngine engine, String sessionId) {
        try {
            return isSessionBusy(engine.getSession(sessionId));
        } catch (Exception e) {
            LOG.warn("[WebGate] busy check failed for session {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 安全聊天输入入口。
     *
     * <p>在调用 {@link #onChatInput} 之前先检查会话是否繁忙（有 AI 任务正在执行），
     * 若繁忙则跳过本次输入并记录警告日志。用于 IM 回调等需要避免并发冲突的场景。</p>
     *
     * @param sessionId 会话标识
     * @param input     用户输入文本
     * @param source    调用来源标识（用于日志记录，如 "Feishu"），同时用于标记消息来源通道
     * @return true 表示输入已接受并进入处理流程；false 表示会话繁忙已跳过
     */
    public boolean safeChatInput(WorkspaceContext wsContext,String sessionId, String input, String source) {
        try {
            AgentSession session = wsContext.getEngine().getSession(sessionId);
            if (isSessionBusy(session)) {
                // 检查是否为暂停/中断命令：允许在任务执行中穿过忙碌检查
                if (input != null && input.startsWith("/")) {
                    List<String> parts = CmdUtil.parseArguments(input.trim().substring(1));
                    String cmdName = parts.get(0).toLowerCase();
                    if ("interrupt".equals(cmdName) || "exit".equals(cmdName)) {
                        emitToClient(wsContext, sessionId, WebEvent.ofUserInput(input, source));
                        onChatInput(wsContext, sessionId, null, input, null, null, null, null, source);
                        return true;
                    }
                }

                LOG.warn("[WebGate] {} event skipped for session {}: task in progress", source, sessionId);
                return false;
            }
        } catch (Exception e) {
            LOG.warn("[WebGate] {} event check failed for session {}: {}", source, sessionId, e.getMessage());
            return false;
        }

        // 先推送用户消息到前端，确保对话记录中显示用户侧消息
        emitToClient(wsContext,sessionId, WebEvent.ofUserInput(input, source));

        onChatInput(wsContext, sessionId, null, input, null, null, null, null, source);
        return true;
    }


    /**
     * Loop 专用：安全聊天输入入口，无限等待捕获本轮响应文本。
     *
     * <p>
     * 适用于可能长时间执行的 Loop goal 任务。
     * 该方法仍会向前端推送完整流式消息，同时等待响应流结束。
     *
     * @param sessionId  会话标识
     * @param input      用户输入文本
     * @param source     调用来源标识
     * @return 捕获到的 AI 文本；会话繁忙或无文本时返回 null
     */
    public String safeChatInputAndCaptureLoop(String workspaceId,String sessionId, String input, String source) {
       WorkspaceContext wsContext =  workspaceManager.getContextsCached(workspaceId);

        AgentSession session;
        try {
            // 使用用户隔离的会话管理器：safeChatInputAndCaptureLoop 由 Loop 调度器调用，
            // 需要通过 sessionId 从 SessionManager 的 sessionUserMap 中查找 userId
            session = wsContext.getSessionManager().getSession(sessionId);
            if (isSessionBusy(session)) {
                LOG.warn("[WebGate] {} event skipped for session {}: task in progress", source, sessionId);
                return null;
            }
        } catch (Throwable e) {
            LOG.warn("[WebGate] {} event check failed for session {}: {}", source, sessionId, e.getMessage());
            return null;
        }

        // 前端流门解封由 performAgentTaskSync → beginStreamTurn 统一下发（system.reset），
        // 此处不再重复发送；user_input 先到也会解封，二者不冲突。
        emitToClient(wsContext, sessionId, WebEvent.ofUserInput(input, source));

        String agentName = null;
        String currentInput = input;
        if (currentInput != null && currentInput.startsWith("@")) {
            int agentNameIdx = currentInput.indexOf(" ");
            if (agentNameIdx > 0) {
                agentName = currentInput.substring(1, agentNameIdx);
                if (wsContext.getEngine().getAgentManager().hasAgent(agentName)) {
                    currentInput = currentInput.substring(agentNameIdx + 1);
                }
            }
        }

        ChatMessage chatMessage = ChatMessage.ofUser(currentInput).addMetadata("source", source);
        return performAgentTaskSync(wsContext, session, null, Prompt.of(chatMessage), null, agentName);
    }


    // ═══════════════════════════════════════════════════════════════
    //  工具方法 —— 附件类型判断与 MIME 映射
    // ═══════════════════════════════════════════════════════════════

    /** 支持的图片扩展名集合 */
    private static final Set<String> IMAGE_EXTENSIONS = org.noear.solon.Utils.asSet(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg");

    /**
     * 判断附件是否为图片类型。
     *
     * @param ext             文件扩展名（含点号，如 ".png"）
     * @param attachmentsType 前端传递的附件类型标识（如 "image"）
     * @return true 表示该附件应作为图片处理
     */
    private static boolean isImageAttachment(String ext, String attachmentsType) {
        return "image".equals(attachmentsType) && IMAGE_EXTENSIONS.contains(ext);
    }

    /**
     * 将文件扩展名映射为 MIME 类型。
     *
     * @param ext 文件扩展名（含点号，如 ".jpg"）
     * @return 对应的 MIME 类型字符串，未匹配时默认返回 "image/png"
     */
    private static String extensionToMime(String ext) {
        switch (ext) {
            case ".jpg":
            case ".jpeg":
                return "image/jpeg";
            case ".png":
                return "image/png";
            case ".gif":
                return "image/gif";
            case ".webp":
                return "image/webp";
            case ".bmp":
                return "image/bmp";
            case ".svg":
                return "image/svg+xml";
            default:
                return "image/png";
        }
    }

    /**
     * 构建附件元数据 JSON 数组字符串（用于存入 ndjson metadata.attachments）。
     *
     * @param imageFileNames 图片文件名列表（已校验安全的文件名）
     * @return JSON 数组字符串，如 [{"name":"photo.jpg","type":"image"}]；列表为空则返回 null
     */
    private static String buildAttachmentMeta(List<String> imageFileNames) {
        if (imageFileNames == null || imageFileNames.isEmpty()) {
            return null;
        }
        // 手动构建 JSON 避免依赖 ONode 序列化细节
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < imageFileNames.size(); i++) {
            if (i > 0) sb.append(",");
            String name = imageFileNames.get(i);
            // 简单转义双引号和反斜杠（文件名已校验无 / \ ..）
            String escaped = name.replace("\\", "\\\\").replace("\"", "\\\"");
            sb.append("{\"name\":\"").append(escaped).append("\",\"type\":\"image\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 写入用户消息的子代理元数据（存入 ndjson metadata.agent）。
     *
     * <p>供历史消息恢复时前端标注「这条消息交给了哪个子代理」；
     * 空值（使用主 Agent）不写入，保持旧记录格式一致。</p>
     *
     * @param userMsg   用户消息
     * @param agentName 最终生效的子代理名（可为 null/空，表示主 Agent）
     */
    private static void addAgentMeta(UserMessage userMsg, String agentName) {
        if (userMsg != null && Assert.isNotEmpty(agentName)) {
            userMsg.addMetadata("agent", agentName);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  会话中断支持
    // ═══════════════════════════════════════════════════════════════

    /**
     * 中断指定会话的当前 AI 任务。
     *
     * <p>仅当会话存在活跃流（disposable 未 dispose）时发出取消语义；
     * 无活跃流时不写历史、不推送取消 error/trace，仅在尚未发过 done 时兜底
     * {@link #emitDoneOnce}，避免双击 Stop 或自然结束后仍刷「用户已取消任务」。</p>
     *
     * <p>有活跃流时同线程保证包序：error → 可选 trace → done → dispose。
     * dispose 触发的 {@code doFinally} 中 {@link #emitDoneOnce} 因 CAS 跳过，不会双发 done。</p>
     *
     * @param sessionId 待中断的会话标识
     */
    public void interruptSession(WorkspaceContext wsContext, String sessionId) {
        try {
            AgentSession session = wsContext.getSessionManager().getSession(sessionId);
            Object slot = session.attrs().remove("disposable");

            // 无活跃流：不发取消语义；若尚未发 done 则兜底，便于前端收尾
            if (!(slot instanceof Disposable.Composite)) {
                boolean sent = emitDoneOnce(wsContext, session);
                if (sent) {
                    LOG.info("[WebGate] Session {} interrupt ignored (no active stream), emitted fallback done", sessionId);
                } else {
                    LOG.info("[WebGate] Session {} interrupt ignored (no active stream)", sessionId);
                }
                return;
            }

            Disposable.Composite composite = (Disposable.Composite) slot;
            if (composite.isDisposed()) {
                boolean sent = emitDoneOnce(wsContext, session);
                LOG.info("[WebGate] Session {} interrupt ignored (already disposed), fallback done={}", sessionId, sent);
                return;
            }

            // 1) 取消语义：error + 可选 final/trace（落库消息带上 runId，便于按 runId 锚点删除）
            AssistantMessage cancelMessage = ChatMessage.ofAssistant("用户已取消任务.");
            ReActTrace trace = session.getContext().getAs(AgentFlags.TRACE_KEY_MAIN);
            if (trace != null) {
                cancelMessage.addMetadata(AgentTrace.META_RUN_ID, trace.getRunId());
            }
            session.addMessage(cancelMessage);
            emitToClient(wsContext, sessionId, WebEvent.ofError("用户已取消任务."));

            if (trace != null) {
                Long totalTokens = (trace.getMetrics() != null) ? trace.getMetrics().getTotalTokens() : 0L;
            long elapsedSeconds = 0L;
            if (trace.getBeginTimeMs() > 0) {
                elapsedSeconds = (System.currentTimeMillis() - trace.getBeginTimeMs()) / 1000;
            }
            emitToClient(wsContext, sessionId, WebEvent.ofTrace(null, totalTokens, elapsedSeconds, "用户已取消任务."));
            }

            // 2) 同线程先发 done，再 dispose；doFinally 中 emitDoneOnce 因 CAS 跳过
            emitDoneOnce(wsContext, session);
            composite.dispose();
            LOG.info("[WebGate] Session {} interrupted", sessionId);
        } catch (Exception e) {
            LOG.error("[WebGate] Interrupt failed for session {}: {}", sessionId, e.getMessage());
        }
    }
}