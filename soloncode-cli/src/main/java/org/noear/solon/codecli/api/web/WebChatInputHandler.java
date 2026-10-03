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
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLDecision;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
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
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.session.SessionActivity;
import org.noear.solon.codecli.session.SessionMeta;
import org.noear.solon.codecli.session.queue.SessionQueue;
import org.noear.solon.codecli.session.queue.SessionQueueItem;
import org.noear.solon.codecli.util.TraceUtil;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天输入的受理、分发与中断：忙态入队、斜杠命令、HITL 审批、Loop 同步捕获与 interrupt。
 *
 * <p>从 WebGate 拆出的输入职责。事件下发、流执行与队列派发均经组合根 {@link WebGate}
 * 中转，与输出/流/队列组件保持单向依赖；忙态判定、队列语义、命令回执信封与
 * interrupt 包序（error → 可选 trace → done → dispose）与拆分前逐字一致。</p>
 *
 * @author noear 2026/5/8 created
 */
class WebChatInputHandler {
    private static final Logger LOG = LoggerFactory.getLogger(WebChatInputHandler.class);

    private final WebGate gate;

    WebChatInputHandler(WebGate gate) {
        this.gate = gate;
    }

    /**
     * 用户聊天输入入口（由 ChatWebController HTTP 接口调用）。
     *
     * <p>核心处理流程：</p>
     * <ol>
     *   <li>解析 Agent 指定前缀（如 "@agentName 消息内容"）</li>
     *   <li>处理 HITL（Human-in-the-Loop）审批/拒绝操作</li>
     *   <li>处理文件附件上传（图片走 Base64 编码，其他走文件路径引用）</li>
     *   <li>判断是否为斜杠命令（/command），若是则走命令分发</li>
     *   <li>构建 Prompt 并启动 Agent 流式任务</li>
     * </ol>
     */
    void onChatInput(WorkspaceContext wsContext,
                     String sessionId,
                     String sessionCwd,
                     String input, String selectedModel,
                     UploadedFile[] attachments, String[] attachmentTypes,
                     String hitlAction, String source,
                     String reasoningEffort, String thinkingMode, String selectedAgent,
                     String sourceUserId, String replyTarget, String messageId) {
        AgentSession session = null;
        boolean admitted = false;
        try {
            // 本网关实例已绑定所属工作区的引擎（与 connections 同一上下文），
            // 无需再从 Context.current()/sessionId 猜测引擎，避免异步线程下回退默认引擎。
            // 仅在对话隔离开启时记录 userId，关闭隔离时使用共享会话
            String userId = resolveUserIdFromContext(wsContext);
            session = wsContext.getSessionManager().getSession(sessionId, userId);
            // 同一把 session 锁内检查忙态并占位，避免两个窗口同时看见空闲并各开一条流。
            SessionQueue.bindStorage(session, wsContext.getSessionPath(sessionId));
            synchronized (session.attrs()) {
                boolean dispatch = Thread.currentThread().equals(session.attrs().get(WebQueueDispatcher.ATTR_QUEUE_DISPATCH));
                boolean busyCommand = false;
                if (input != null && input.startsWith("/")) {
                    List<String> parts = CmdUtil.parseArguments(input.trim().substring(1));
                    if (!parts.isEmpty()) {
                        Command command = wsContext.getEngine().getCommandRegistry().find(parts.get(0).toLowerCase());
                        busyCommand = command != null && !command.cliOnly() && command.runnableWhenBusy();
                    }
                }
                if ((SessionActivity.isBusy(session) || SessionQueue.pendingSize(session) > 0)
                        && !dispatch && !busyCommand) {
                    if (attachments != null && attachments.length > 0) {
                        throw new IllegalStateException("Attachments cannot be queued while session is busy");
                    }
                    int position = SessionQueue.enqueue(session, input, source, sourceUserId, replyTarget, messageId,
                            null, selectedModel, reasoningEffort, thinkingMode, selectedAgent, false);
                    if (position < 0) throw new IllegalStateException("session queue is full or unavailable");
                    gate.emitToClient(wsContext, sessionId, WebEvent.ofUserInput(input, source));
                    gate.getQueueDispatcher().drainSessionQueue(wsContext, session);
                    return;
                }
                // 忙态命令是旁路操作，不能改写正在运行任务的回复目标。
                boolean sideCommand = busyCommand && SessionActivity.isBusy(session);
                // 只有已经在真实任务上运行的旁路命令才不占启动闩。
                // runnableWhenBusy 命令在空闲会话中也可能启动任务（如 /steer），
                // 此时必须先占位，否则它与普通输入之间仍存在并发开流窗口。
                if (!dispatch && !sideCommand) {
                    session.attrs().put(SessionActivity.ATTR_INPUT_ADMITTING, Boolean.TRUE);
                    admitted = true;
                }
                if (!sideCommand) {
                    if (dispatch && session.attrs().get("session.queue.executing") instanceof SessionQueueItem) {
                        SessionQueueItem queued = (SessionQueueItem) session.attrs().get("session.queue.executing");
                        setReplyRoute(session, queued.getSource(), queued.getSourceUserId(), queued.getReplyTarget(), queued.getMessageId());
                    } else {
                        setReplyRoute(session, source, sourceUserId, replyTarget, messageId);
                    }
                }
            }

            // 写入会话级模型 / 推理（后续 StreamBuilder 与旁路任务均可读取）
            if (Assert.isNotEmpty(selectedModel)) {
                session.getContext().put(HarnessEngine.CTX_MODEL_SELECTED, selectedModel);
            }
            // 写入会话级子代理选择（与模型一样的持久化逻辑）
            session.getContext().put(HarnessEngine.CTX_AGENT_SELECTED,
                    selectedAgent != null ? selectedAgent : "");
            boolean effortProvided = reasoningEffort != null;
            org.noear.solon.codecli.util.ReasoningSupportUtil.putSessionEffort(session, reasoningEffort, effortProvided);
            boolean modeProvided = thinkingMode != null;
            org.noear.solon.codecli.util.ReasoningSupportUtil.putSessionThinkingMode(session, thinkingMode, modeProvided);

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
                String hitlCallId = session.getContext().getAs(WebGate.CTX_HITL_CALL_ID);
                session.getContext().remove(WebGate.CTX_HITL_CALL_ID);

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
                    gate.getSessionRunner().performAgentTaskAsync(wsContext, session, sessionCwd, null, selectedModel, agentName);
                }
                return;
            }

            // Handle file upload
            List<ImageBlock> imageBlocks = new ArrayList<>();
            List<String> imageFileNames = new ArrayList<>();
            List<String> fileAttachments = new ArrayList<>();

            WebAttachments.saveAttachments(wsContext.getEngine().getWorkspace(), attachments, attachmentTypes,
                    imageBlocks, imageFileNames, fileAttachments);

            // Build input text with file attachment prefix
            currentInput = WebAttachments.withFilePrefix(currentInput, fileAttachments);

            if (Assert.isNotEmpty(currentInput) || !imageBlocks.isEmpty()) {
                if (currentInput == null || currentInput.isEmpty()) {
                    currentInput = imageBlocks.size() > 1 ? "请描述这些图片" : "请描述这张图片";
                }

                // 命令分发
                if (currentInput.startsWith("/") && imageBlocks.isEmpty()) {
                    if (isCommand(wsContext, session, sessionCwd, currentInput, selectedModel, agentName, source,
                            sourceUserId, replyTarget, messageId)) {
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
                    String attachMeta = WebAttachments.buildAttachmentMeta(imageFileNames);
                    UserMessage userMsg = new UserMessage(contents).addMetadata("source", source);
                    if (attachMeta != null) {
                        userMsg.addMetadata("attachments", attachMeta);
                    }
                    WebAttachments.addAgentMeta(userMsg, agentName);
                    prompt = Prompt.of(userMsg);
                } else {
                    // 文件附件已在 currentInput 前缀写入文件名（[附件: xxx]），ndjson 有记录
                    UserMessage userMsg = ChatMessage.ofUser(currentInput).addMetadata("source", source);
                    WebAttachments.addAgentMeta(userMsg, agentName);
                    prompt = Prompt.of(userMsg);
                }

                // 流式处理：输出通过 WebSocket 推送
                gate.getSessionRunner().performAgentTaskAsync(wsContext, session, sessionCwd, prompt, selectedModel, agentName);
            }
        } catch (Exception e) {
            LOG.error("Task fail: {}", e.getMessage(), e);
            if (session != null && Thread.currentThread().equals(session.attrs().get(WebQueueDispatcher.ATTR_QUEUE_DISPATCH))) {
                session.attrs().remove("session.queue.executing");
                throw new IllegalStateException("Queued task could not start", e);
            }
            gate.emitToClient(wsContext, sessionId, WebEvent.ofError(e));
            // 流可能尚未建立：有 session 走去重出口，否则直接发 done
            if (session != null) {
                gate.getEventPublisher().emitDoneGuarded(wsContext, session);
            } else {
                gate.emitToClient(wsContext, sessionId, WebEvent.ofDone());
            }
        } finally {
            if (session != null) {
                if (admitted) {
                    synchronized (session.attrs()) { session.attrs().remove(SessionActivity.ATTR_INPUT_ADMITTING); }
                    gate.getQueueDispatcher().drainSessionQueue(wsContext, session);
                }
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
     * 判断本批所有 HITL 挂起任务是否均已有决策。
     *
     * <p>批量场景下前端逐卡点击会发多次决策 POST，仅当无任何未决策项时
     * 才应恢复流，避免过早 resume 将未决策任务带走。</p>
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

    /**
     * 尝试将用户输入解析为斜杠命令并执行。
     *
     * <p>解析输入字符串中的命令名和参数，查找已注册的 {@link Command} 并执行。
     * 若命令执行后产生非 Agent 任务结果，会通过 WebSocket 推送命令输出；
     * 若为 rewind 命令，会发送特殊的回退事件通知前端删除历史 DOM。</p>
     *
     * @return true 表示输入已被识别为命令并执行，false 表示非命令输入
     */
    private boolean isCommand(WorkspaceContext wsContext, AgentSession session, String sessionCwd, String input, String selectedModel, String agentName, String source,
                              String sourceUserId, String replyTarget, String messageId) throws Exception {
        if (!input.startsWith("/")) {
            return false;
        }

        // 解析命令名和参数
        List<String> parts = CmdUtil.parseArguments(input.trim().substring(1));
        if (parts.isEmpty()) {
            return false;
        }
        String cmdName = parts.get(0).toLowerCase();
        List<String> args = parts.size() > 1
                ? parts.subList(1, parts.size())
                : Collections.emptyList();

        // 查找命令
        Command command = wsContext.getEngine().getCommandRegistry().find(cmdName);
        if (command == null) {
            return false;
        }
        if (command.cliOnly()) {
            LOG.warn("[WebGate] CLI-only command /{} rejected from Web source {}", cmdName, source);
            return true;
        }

        // 构建 context（注入 agentTaskRunner 回调）；命令执行的工作区语境由本网关实例已绑定的 engine 确定。
        WebCommandContext ctx = new WebCommandContext(session, source, sourceUserId, replyTarget, messageId,
                wsContext.getEngine(), input, cmdName, args,
                (prompt, model) -> {
                    try {
                        //防重入：会话已有流在跑（含上一轮 doFinally 尚未归还槽位的瞬时窗口）时，
                        //再开一条流会与在跑流共享同一 ReActTrace —— 旧流把 route 置 END 后
                        //新流立即空转结束，表现为「点了没反应、马上结束」。故忙碌时忽略并提示
                        // 当前输入可能仍持有 input.admitting 占位；这里已经是该输入派生出的 Agent 任务，
                        // 只能用真实流句柄判断是否有其它任务运行，避免 /continue、/rerun 被自身拦截。
                        if (SessionActivity.hasActiveStream(session)) {
                            LOG.warn("[WebGate] Session {} agent task via /{} skipped: another task in progress", session.getSessionId(), cmdName);
                            gate.emitToClient(wsContext, session.getSessionId(), WebEvent.ofCommand("当前有任务正在执行，本次触发已忽略"));
                            return;
                        }

                        if (model == null) {
                            model = selectedModel;
                        }

                        gate.getSessionRunner().performAgentTaskAsync(wsContext, session, sessionCwd, Prompt.of(prompt), model, agentName);
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
            // 输入受理占位不是 agent 流；命令独占时仍须发 reset/done。
            boolean ownTurn = !SessionActivity.hasActiveStream(session);
            if (ownTurn) {
                gate.getSessionRunner().beginStreamTurn(wsContext, session);
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
                TraceUtil.removeCurrentTrace(session);
                session.updateSnapshot();

                gate.emitToClient(wsContext, session.getSessionId(), WebEvent.ofRewind(rewindCount + 1));
            } else {
                final String text;
                if (ctx.getOutputBuffer().length() > 0) {
                    text = ctx.getOutputBuffer().toString();
                } else {
                    text = "命令执行完成";
                }

                gate.emitToClient(wsContext, session.getSessionId(), WebEvent.ofCommand(text));

                // 忙态命令使用自身的回复目标，不消费运行任务的路由。
                if (ownTurn) gate.getStreamBuilder().replyToBoundChannel(wsContext, session.getSessionId(), text, true);
                else gate.getStreamBuilder().replyToBoundChannel(wsContext, session.getSessionId(), text, true,
                        source, sourceUserId, replyTarget, messageId);
            }

            // done 走统一出口（emitDoneOnce），使「所有 done 都经过去重门」这条不变量不被绕过；
            // done 门已由上面的 beginStreamTurn 复位，否则会被上一轮的 doneSent 挡掉。
            if (ownTurn) {
                gate.getEventPublisher().emitDoneOnce(wsContext, session);
                // 空闲态命令收尾后尝试 drain：覆盖空闲时执行 /queue 入队后需立即发起的场景。
                gate.getQueueDispatcher().drainSessionQueue(wsContext, session);
            }
        }

        return true;
    }

    /**
     * 安全聊天输入入口，并在输入确认受理、启动处理前执行回调。
     *
     * <p>回调适合发布与本次输入绑定的请求级上下文。它不会在会话繁忙时执行，
     * 且发生在 onChatInput 之前，避免异步任务已经产生输出后才发布上下文。</p>
     */
    boolean safeChatInput(WorkspaceContext wsContext, String sessionId, String input, String source,
                          Runnable acceptedHook, String sourceUserId, String replyTarget, String messageId) {
        try {
            AgentSession session = wsContext.getEngine().getSession(sessionId);
            if (SessionActivity.isBusy(session)) {
                // 忙碌穿透：是否允许在任务执行中受理，由命令自描述（runnableWhenBusy）决定，
                // 不再硬编码 interrupt/exit 白名单。
                if (input != null && input.startsWith("/")) {
                    List<String> parts = CmdUtil.parseArguments(input.trim().substring(1));
                    if (parts.isEmpty()) {
                        LOG.warn("[WebGate] {} event skipped for session {}: empty command", source, sessionId);
                        return false;
                    }
                    String cmdName = parts.get(0).toLowerCase();
                    Command command = wsContext.getEngine().getCommandRegistry().find(cmdName);
                    if (command != null && !command.cliOnly() && command.runnableWhenBusy()) {
                        gate.emitToClient(wsContext, sessionId, WebEvent.ofUserInput(input, source));
                        onChatInput(wsContext, sessionId, null, input, null, null, null, null, source,
                                null, null, null, sourceUserId, replyTarget, messageId);
                        return true;
                    }
                }

                // 普通输入也是 session 任务：繁忙时进入统一持久化队列，而不是只让调用方重试。
                SessionQueue.bindStorage(session, wsContext.getSessionPath(sessionId));
                int position = SessionQueue.enqueue(session, input, source, sourceUserId, replyTarget, messageId);
                if (position >= 0) {
                    gate.emitToClient(wsContext, sessionId, WebEvent.ofUserInput(input, source));
                    gate.getQueueDispatcher().drainSessionQueue(wsContext, session);
                    return true;
                }
                LOG.warn("[WebGate] {} event could not be queued for busy session {}", source, sessionId);
                return false;
            }

            // 必须在 onChatInput 之前发布：onChatInput 会异步调度任务，快速任务可能立即输出。
            if (acceptedHook != null) {
                acceptedHook.run();
            }
        } catch (Exception e) {
            LOG.warn("[WebGate] {} event check failed for session {}: {}", source, sessionId, e.getMessage());
            return false;
        }

        // 回复目标仅在 onChatInput 真正受理本轮时设置；提前写入会覆盖运行任务。
        // 先推送用户消息到前端，确保对话记录中显示用户侧消息
        gate.emitToClient(wsContext, sessionId, WebEvent.ofUserInput(input, source));

        onChatInput(wsContext, sessionId, null, input, null, null, null, null, source,
                null, null, null, sourceUserId, replyTarget, messageId);
        return true;
    }

    /**
     * Loop 专用旧入口：无限等待捕获本轮响应文本。
     *
     * @return 捕获到的 AI 文本；会话繁忙或无文本时返回 null
     */
    String safeChatInputAndCaptureLoop(WorkspaceContext wsContext, String sessionId, String input, String source) {
        AgentSession session;
        try {
            // 使用用户隔离的会话管理器：由 Loop 调度器调用，
            // 需要通过 sessionId 从 SessionManager 的 sessionUserMap 中查找 userId
            session = wsContext.getSessionManager().getSession(sessionId);
            if (SessionActivity.isBusy(session)) {
                LOG.warn("[WebGate] {} event skipped for session {}: task in progress", source, sessionId);
                return null;
            }
        } catch (Throwable e) {
            LOG.warn("[WebGate] {} event check failed for session {}: {}", source, sessionId, e.getMessage());
            return null;
        }

        // 前端流门解封由 performAgentTaskSync → beginStreamTurn 统一下发（system.reset），
        // 此处不再重复发送；user_input 先到也会解封，二者不冲突。
        gate.emitToClient(wsContext, sessionId, WebEvent.ofUserInput(input, source));

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
        return gate.getSessionRunner().performAgentTaskSync(wsContext, session, null, Prompt.of(chatMessage), null, agentName);
    }

    /**
     * 中断指定会话的当前 AI 任务。
     *
     * <p>仅当会话存在活跃流（disposable 未 dispose）时发出取消语义；
     * 无活跃流时不写历史、不推送取消 error/trace，仅在尚未发过 done 时兜底
     * emitDoneOnce，避免双击 Stop 或自然结束后仍刷「用户已取消任务」。</p>
     *
     * <p>有活跃流时同线程保证包序：error → 可选 trace → done → dispose。
     * dispose 触发的 {@code doFinally} 中 emitDoneOnce 因 CAS 跳过，不会双发 done。</p>
     */
    boolean interruptSession(WorkspaceContext wsContext, String sessionId) {
        try {
            AgentSession session = wsContext.getSessionManager().getSession(sessionId);
            SessionQueue.bindStorage(session, wsContext.getSessionPath(sessionId));
            // 清队列保存失败时不终止当前流：否则 doFinally 会续发原队列，Stop 不能报告成功。
            if (!SessionQueue.cancelPending(session)) {
                LOG.warn("[WebGate] Stop rejected: could not persist queue cancellation for session {}", sessionId);
                return false;
            }
            Object slot = session.attrs().remove("disposable");

            // 无活跃流：不发取消语义；若尚未发 done 则兜底，便于前端收尾
            if (!(slot instanceof reactor.core.Disposable.Composite)) {
                boolean sent = gate.getEventPublisher().emitDoneOnce(wsContext, session);
                if (sent) {
                    LOG.info("[WebGate] Session {} interrupt ignored (no active stream), emitted fallback done", sessionId);
                } else {
                    LOG.info("[WebGate] Session {} interrupt ignored (no active stream)", sessionId);
                }
                return true;
            }

            reactor.core.Disposable.Composite composite = (reactor.core.Disposable.Composite) slot;
            if (composite.isDisposed()) {
                boolean sent = gate.getEventPublisher().emitDoneOnce(wsContext, session);
                LOG.info("[WebGate] Session {} interrupt ignored (already disposed), fallback done={}", sessionId, sent);
                return true;
            }

            // 1) 取消语义：error + 可选 final/trace（落库消息带上 runId，便于按 runId 锚点删除）
            AssistantMessage cancelMessage = ChatMessage.ofAssistant("用户已取消任务.");
            ReActTrace trace = TraceUtil.getCurrentTrace(session);
            if (trace != null) {
                cancelMessage.addMetadata(AgentTrace.META_RUN_ID, trace.getRunId());
            }
            session.addMessage(cancelMessage);
            // 中断时 agent 的 call() 收尾被跳过，这里必须补快照：把当前 trace（含已完成的思考/工具调用）
            // 与取消消息一起持久化，否则进程重启/会话重载后本轮 trace 会丢失。
            // 参照 rewind 路径（见 isCommand 中 /rewind 处理）的做法。
            session.updateSnapshot();
            gate.emitToClient(wsContext, sessionId, WebEvent.ofError("用户已取消任务."));

            if (trace != null) {
                Long totalTokens = (trace.getMetrics() != null) ? trace.getMetrics().getTotalTokens() : 0L;
                long elapsedSeconds = 0L;
                if (trace.getBeginTimeMs() > 0) {
                    elapsedSeconds = (System.currentTimeMillis() - trace.getBeginTimeMs()) / 1000;
                }
                gate.emitToClient(wsContext, sessionId, WebEvent.ofTrace(null, totalTokens, elapsedSeconds, "用户已取消任务."));
            }

            // 2) 同线程先发 done，再 dispose；doFinally 中 emitDoneOnce 因 CAS 跳过
            gate.getEventPublisher().emitDoneOnce(wsContext, session);
            composite.dispose();
            LOG.info("[WebGate] Session {} interrupted", sessionId);
            return true;
        } catch (Exception e) {
            LOG.error("[WebGate] Interrupt failed for session {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 从当前请求上下文中解析用户 ID。
     * 优先从 UserAuthFilter 设置的上下文属性获取，否则从 token 中提取。
     */
    private static String resolveUserIdFromContext(WorkspaceContext wsContext) {
        org.noear.solon.codecli.auth.UserAuthConfig authConfig = wsContext == null || wsContext.getSettings() == null
                ? null : wsContext.getSettings().getUserAuth();
        if (authConfig == null || !authConfig.isEnabled() || !authConfig.isConversationIsolationEnabled()) {
            return null;
        }
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

    private static void setReplyRoute(AgentSession session, String source, String sourceUserId, String replyTarget, String messageId) {
        if (session == null) return;
        boolean imSource = false;
        if (source != null) {
            imSource = "wechat".equalsIgnoreCase(source) || "feishu".equalsIgnoreCase(source)
                    || "dingtalk".equalsIgnoreCase(source);
        }
        if (!imSource) {
            // WEB/Loop 等来源没有 IM 定向目标：清掉上一轮残留的路由即可。
            // 回复仍会广播到所有绑定该会话的 IM 通道（见 WebStreamBuilder.replyToBoundChannel），
            // 以实现多终端（web/im）内容同步，各通道退回到绑定用户（binding.openId）。
            session.attrs().remove("session.replyRoute");
            return;
        }
        Map<String, String> route = new HashMap<>();
        route.put("source", source);
        route.put("sourceUserId", sourceUserId);
        route.put("replyTarget", replyTarget);
        route.put("messageId", messageId);
        session.attrs().put("session.replyRoute", route);
    }
}
