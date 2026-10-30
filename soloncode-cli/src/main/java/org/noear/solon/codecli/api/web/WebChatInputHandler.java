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

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.input.BufferedCommandContextFactory;
import org.noear.solon.ai.harness.input.CommandContextFactory;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.input.ParsedCommand;
import org.noear.solon.ai.util.CmdUtil;
import org.noear.solon.codecli.channel.ImMessages;
import org.noear.solon.codecli.channel.ImStatus;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.session.SessionActivity;
import org.noear.solon.codecli.session.SessionMeta;
import org.noear.solon.ai.harness.session.queue.SessionQueue;
import org.noear.solon.ai.harness.session.queue.SessionQueueItem;
import org.noear.solon.ai.harness.session.steer.SteerInterceptor;
import org.noear.solon.codecli.util.TraceUtil;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * 聊天输入的受理、分发与中断：忙态插话（降级排队）、斜杠命令、HITL 审批、Loop 同步捕获与 interrupt。
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
            synchronized (session.attrs()) {
                boolean dispatch = Thread.currentThread().equals(session.attrs().get(WebQueueDispatcher.ATTR_QUEUE_DISPATCH));
                boolean busyCommand = false;
                if (input != null && input.startsWith("/")) {
                    List<String> parts = CmdUtil.parseArguments(input.trim().substring(1));
                    if (!parts.isEmpty()) {
                        Command command = wsContext.getEngine().getCommandRegistry().find(parts.get(0).toLowerCase());
                        busyCommand = command != null && !command.terminalOnly() && command.runnableWhenBusy();
                    }
                }
                if ((SessionActivity.isBusy(session) || SessionQueue.pendingSize(session) > 0)
                        && !dispatch && !busyCommand) {
                    if (attachments != null && attachments.length > 0) {
                        throw new IllegalStateException("Attachments cannot be queued while session is busy");
                    }
                    int position = SessionQueue.enqueue(session, input,
                            InputOrigin.of(source, sourceUserId, replyTarget, messageId),
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
                        setReplyRoute(session, queued.getOrigin());
                    } else {
                        setReplyRoute(session, InputOrigin.of(source, sourceUserId, replyTarget, messageId));
                    }
                }
            }

            // 写入会话级模型 / 推理（后续 StreamBuilder 与旁路任务均可读取）
            if (Assert.isNotEmpty(selectedModel)) {
                session.getContext().put(HarnessEngine.CTX_MODEL_SELECTED, selectedModel);
            }
            // 写入会话级子代理选择（仅在显式指定时）。
            // IM 等未携带该字段的输入传 null，不能把 web 端已选的子代理清空。
            if (selectedAgent != null) {
                session.getContext().put(HarnessEngine.CTX_AGENT_SELECTED, selectedAgent);
            }
            boolean effortProvided = reasoningEffort != null;
            org.noear.solon.codecli.util.ReasoningSupportUtil.putSessionEffort(session, reasoningEffort, effortProvided);
            boolean modeProvided = thinkingMode != null;
            org.noear.solon.codecli.util.ReasoningSupportUtil.putSessionThinkingMode(session, thinkingMode, modeProvided);

            // HITL approve/reject handling：决策应用与续流时机判定统一交给引擎（含 callId 精确定位与工具返回改写）。
            //必须放在子代理解析之前：审批请求通常不带文本，mention 解析对它无意义。
            if (Assert.isNotEmpty(hitlAction)) {
                // callId 通过 session context 透传（前端 POST hitlCallId），避免全链路改签名
                String hitlCallId = session.getContext().getAs(WebGate.CTX_HITL_CALL_ID);
                session.getContext().remove(WebGate.CTX_HITL_CALL_ID);

                // 一次调用完成「写决策 + 必要时的续流装配」，宿主只 switch kind：
                //   TASK —— 决策已写入且本批已齐备，引擎已装配续流请求，直接执行；
                //   HITL —— 未续流：过期审批卡（isHitlApplied()==false）或本批尚有未决卡，
                //           两者都不能续流（前者会把上一轮问题重跑，后者会带走未决任务）。
                // 续流用的子代理必须取「首轮实际生效值」（WebStreamBuilder 每轮写入 _agent_selected_tmp），
                // 而不是审批请求自带的 selectedAgent：审批请求通常不带该字段，
                // 会回落主代理而取不到子代理挂起的 trace（续跑空转）。
                String effectiveAgent = (String) session.attrs().get(WebStreamBuilder.ATTR_EFFECTIVE_AGENT);
                InputOutcome hitlOutcome = wsContext.getEngine().handle(
                        HarnessInput.of(session, null)
                                .selectedAgent(effectiveAgent)
                                .selectedModel(selectedModel)
                                .hitl(HITLAction.of(hitlAction), hitlCallId) // 协议字符串在边界解析，脏值这里报错而非静默当拒绝
                                .resume(true));

                if (hitlOutcome.getKind() == InputOutcome.Kind.TASK) {
                    gate.getSessionRunner()
                            .performAgentTaskAsync(wsContext, session, sessionCwd, hitlOutcome);
                }
                return;
            }

            // @子代理 解析口径与 CLI/桌面端统一（harness AgentMentions）：
            // 输入开头的有效 @子代理 优先；否则使用选择器传入的有效子代理。
            // 解析、附件落盘、命令识别、Prompt 构建、请求装配全部下沉到引擎（input 唯一入口）。
            // Web 只保留：命令回执的信封渲染、请求级上下文写入、流的订阅。
            // 历史前端兼容：旧版会以文本 "/resume" 触发续流，转为显式 resume 语义
            // （等价于原 buildStreamFlux 里的 /resume 剥离，语义前移到入口处）。
            boolean resumeInput = "/resume".equals(input != null ? input.trim() : null);
            InputOutcome outcome = wsContext.getEngine().handle(
                    HarnessInput.of(session, resumeInput ? null : input)
                            .resume(resumeInput)
                            .selectedAgent(selectedAgent)
                            .selectedModel(selectedModel)
                            .origin(InputOrigin.of(source, sourceUserId, replyTarget, messageId))
                            .attachments(WebAttachmentStore.toAttachments(
                                    wsContext.getEngine().getWorkspace(), attachments, attachmentTypes))
                            .commandContextFactory(webCommandFactory()));

            switch (outcome.getKind()) {
                case TASK:
                    if (outcome.isCommand()) {
                        // 命令触发的任务：回执照发、不抢发 done，收尾交给任务流
                        startCommandTaskTurn(wsContext, session, sessionCwd, outcome);
                    } else {
                        // 流式处理：输出通过 WebSocket 推送（模型/子代理一律用引擎生效值）
                        gate.getSessionRunner().performAgentTaskAsync(wsContext, session, sessionCwd, outcome);
                    }
                    break;
                case REPLY:
                    // 命令已执行，或命令在本渠道不可用：回执自成一轮
                    startReplyTurn(wsContext, session, outcome, source, sourceUserId, replyTarget, messageId);
                    break;
                default:
                    // HITL / IGNORED：决策已写入或无事可做
                    break;
            }
        } catch (Exception e) {
            LOG.error("Task fail: {}", e.getMessage(), e);
            if (session != null && Thread.currentThread().equals(session.attrs().get(WebQueueDispatcher.ATTR_QUEUE_DISPATCH))) {
                session.attrs().remove("session.queue.executing");
                throw new IllegalStateException("Queued task could not start", e);
            }
            gate.emitToClient(wsContext, sessionId, WebEvent.ofError(e));
            // 失败终态同步到 IM（与 web 一致）
            gate.getStreamBuilder().replyErrorToBoundChannels(wsContext, session, e);
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
     * 命令触发的 Agent 任务：回执照发、不抢发 done，收尾交给随后发起的任务流。
     *
     * <p>模型/子代理一律用引擎生效值（命令声明 → 会话选择 → 默认），与普通消息同口径。</p>
     */
    private void startCommandTaskTurn(WorkspaceContext wsContext, AgentSession session, String sessionCwd,
                                      InputOutcome outcome) {
        // 防重入（原先在命令回调里）：会话已有流在跑（含上一轮 doFinally 尚未归位槽位的瞬时窗口）时，
        // 再开一条流会与在跑流共享同一 ReActTrace —— 旧流把 route 置 END 后新流立即空转结束，
        // 表现为「点了没反应、马上结束」。此处已不再是输入受理占位持有者（占位在本轮 finally 才释放），
        // 只能用真实流句柄判断，不能用 isBusy，否则 /continue、/rerun 会被自身拦截。
        if (SessionActivity.hasActiveStream(session)) {
            LOG.warn("[WebGate] Session {} agent task via /{} skipped: another task in progress",
                    session.getSessionId(), outcome.getCommandName());
            gate.emitToClient(wsContext, session.getSessionId(), WebEvent.ofCommand("当前有任务正在执行，本次触发已忽略"));
            return;
        }

        // 开流前后端流门复位必须早于回执：上一轮 done 后前端已置 _streamClosed 并丢弃后续 chunk，
        // 若先发回执，回执会被前端静默丢掉（命令事件不带 runId，自愈判据用不上）。
        // 与普通消息同构（performAgentTaskAsync → beginStreamTurn 会再复位一次，幂等无害）。
        gate.getSessionRunner().beginStreamTurn(wsContext, session);

        // 命令回执先发（命令自己已写入缓冲），任务流由本方法发起、并由那条流负责收尾。
        // 不能在这里发 done：否则前端提前收尾并丢掉任务流的后续输出。
        // IM 回执必须按「非终态」发送：终态会封闭 ATTR_IM_TERMINAL_SENT 去重门，
        // 随后任务流的最终答复会被该门吞掉，IM 端只能看到命令回执、看不到任务结果。
        if (outcome.hasReply()) {
            gate.emitToClient(wsContext, session.getSessionId(), WebEvent.ofCommand(outcome.getCommandOutput()));
            gate.getStreamBuilder().replyToBoundChannel(wsContext, session.getSessionId(),
                    outcome.getCommandOutput(), false);
        }

        gate.getSessionRunner().performAgentTaskAsync(wsContext, session, sessionCwd, outcome);
    }

    /**
     * 仅有回执的一轮：命令已执行，或命令在本渠道不可用（未执行）。
     *
     * <p>二者宿主动作相同，因此合并为一个分支。</p>
     */
    private void startReplyTurn(WorkspaceContext wsContext, AgentSession session, InputOutcome outcome,
                                String source, String sourceUserId, String replyTarget, String messageId) {
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
        if ("rewind".equals(outcome.getCommandName())) {
            ParsedCommand cmd = outcome.getCommand();
            int rewindCount = 1;
            if (cmd != null && cmd.argCount() > 0) {
                try {
                    rewindCount = Integer.parseInt(cmd.argAt(0));
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
            if (outcome.hasReply()) {
                text = outcome.getCommandOutput();
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

    /**
     * Web/IM 命令上下文工厂：终端能力为桌面/Web 默认值（无终端），
     * 来源身份与终端能力均由引擎按输入统一读取，无需在此注入。
     * <p>
     * 命令内部触发的任务只记录意图（分发器交给引擎二段装配），
     * 流由 {@link #handleCommandTurn} 在主路径统一发起。
     */
    private CommandContextFactory webCommandFactory() {
        return new BufferedCommandContextFactory();
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
                    if (command != null && !command.terminalOnly() && command.runnableWhenBusy()) {
                        gate.emitToClient(wsContext, sessionId, WebEvent.ofUserInput(input, source));
                        onChatInput(wsContext, sessionId, null, input, null, null, null, null, source,
                                null, null, null, sourceUserId, replyTarget, messageId);
                        return true;
                    }
                }

                // 普通消息忙态默认「插话」（与 web 前端流式中 Enter 的默认一致）：把补充注入正在运行的任务，
                // 由 SteerInterceptor 在下一个采样边界消费。插不进去（任务恰在切换/尚未进入推理边界、邮箱已满等）
                // 或输入是斜杠命令时，再降级为统一持久化排队，确保消息不丢。
                if (input != null && !input.startsWith("/")) {
                    SteerInterceptor.SteerResult steerResult = SteerInterceptor.steer(session, null, null, input,
                            InputOrigin.of(source, sourceUserId, replyTarget, messageId),
                            () -> SessionActivity.isBusy(session));
                    if (steerResult.getStatus() == SteerInterceptor.SteerStatus.STEERED) {
                        // 不回放 ofUserInput：web 端由 steer_applied 就地渲染 steer-note（与 web 发起的插话同一路径），
                        // 提前推 user_input 会与 steer-note 重复上屏。
                        gate.getStreamBuilder().signalOriginChannel(wsContext, sessionId, ImStatus.STEERED,
                                null, source, sourceUserId, replyTarget, messageId);
                        return true;
                    }
                }

                // 降级：繁忙时进入统一持久化队列，而不是只让调用方重试。
                int position = SessionQueue.enqueue(session, input,
                        InputOrigin.of(source, sourceUserId, replyTarget, messageId));
                if (position >= 0) {
                    gate.emitToClient(wsContext, sessionId, WebEvent.ofUserInput(input, source));
                    // IM 侧感知补齐：web 能看到排队状态，IM 看不到；把位次主动推给来源端。
                    // position 为入队后的队列长度，本项位居队尾，故前面还有 position-1 条。
                    // 命令引导（/steer、/interrupt）同一轮忙态只教学一次，避免连发多条时刷屏。
                    boolean withCommandHint = gate.getStreamBuilder().claimCommandHint(session);
                    gate.getStreamBuilder().signalOriginChannel(wsContext, sessionId, ImStatus.QUEUED,
                            ImMessages.queued(position - 1, withCommandHint), source, sourceUserId, replyTarget, messageId);
                    gate.getQueueDispatcher().drainSessionQueue(wsContext, session);
                    return true;
                }
                LOG.warn("[WebGate] {} event could not be queued for busy session {}", source, sessionId);
                // 入队失败（队列满/落盘失败）：明确告知来源端本轮未被接收，不能让其以为发出去了。
                gate.getStreamBuilder().signalOriginChannel(wsContext, sessionId, ImStatus.REJECTED,
                        null, source, sourceUserId, replyTarget, messageId);
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

        // IM 侧感知补齐：空闲受理后给来源端一条「已收到」，否则 IM 用户只能盲等。
        // 斜杠命令自带回执（isCommand 内部经 replyToBoundChannel 投递），不重复提示。
        if (input == null || !input.startsWith("/")) {
            gate.getStreamBuilder().signalOriginChannel(wsContext, sessionId, ImStatus.ACCEPTED,
                    null, source, sourceUserId, replyTarget, messageId);
        }

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
        // Loop 发起：清掉上一轮残留的 IM 定向路由（Loop 不是 IM 来源，过程消息不外发）
        setReplyRoute(session, InputOrigin.channel(source));
        gate.emitToClient(wsContext, sessionId, WebEvent.ofUserInput(input, source));

        // Loop 的提示词是「已受理的文本」，即使以 / 开头也不应被当命令执行；
        // 命令识别、提及解析、Prompt 构建统一交给引擎（与 CLI/桌面端同一套口径）
        InputOutcome outcome = wsContext.getEngine().handle(HarnessInput.of(session, input)
                .commandAware(false)
                .origin(InputOrigin.channel(source)));

        if (outcome.hasRequest() == false) {
            return null;
        }

        return gate.getSessionRunner().performAgentTaskSync(wsContext, session, null, outcome);
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

            try {
                // 在任何持久化/事件投递前领取取消终态，与 RunEnd 使用同一临界区。
                // 投递延后到持久化之后，但只能使用此刻捕获的本轮路由。
                Runnable replyCanceled = gate.getStreamBuilder().prepareCanceledReply(wsContext, session);

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
                // 取消终态同步到 IM（与 web 一致），避免 IM 端停在「已读不回」
                replyCanceled.run();

                if (trace != null) {
                    Long totalTokens = (trace.getMetrics() != null) ? trace.getMetrics().getTotalTokens() : 0L;
                    long elapsedSeconds = 0L;
                    if (trace.getBeginTimeMs() > 0) {
                        elapsedSeconds = (System.currentTimeMillis() - trace.getBeginTimeMs()) / 1000;
                    }
                    gate.emitToClient(wsContext, sessionId, WebEvent.ofTrace(null, totalTokens, elapsedSeconds, "用户已取消任务."));
                }

                // 2) 正常路径同线程先发 done，再 dispose；doFinally 中 emitDoneOnce 因 CAS 跳过
                gate.getEventPublisher().emitDoneOnce(wsContext, session);
            } finally {
                // 句柄已经移出会话，持久化或回执失败也不能留下无法再次取消的活跃流。
                composite.dispose();
            }
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

    private static void setReplyRoute(AgentSession session, InputOrigin origin) {
        if (session == null) return;
        // 新一轮输入受理：重置 IM 终态去重门（final/error/canceled 共用），保证本轮终态仍能落 IM。
        session.attrs().remove(WebStreamBuilder.ATTR_IM_TERMINAL_SENT);
        if (origin == null || origin.isIm() == false) {
            // WEB/Loop 等来源没有 IM 定向目标：清掉上一轮残留的路由即可。
            // 终态仍会广播到所有绑定该会话的 IM 通道（见 WebStreamBuilder.replyToBoundChannel），
            // 以实现多终端（web/im）内容同步，各通道退回到绑定用户（binding.openId）。
            session.attrs().remove("session.replyRoute");
            return;
        }
        session.attrs().put("session.replyRoute", origin.toMap());
    }
}
