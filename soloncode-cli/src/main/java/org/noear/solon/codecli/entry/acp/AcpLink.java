package org.noear.solon.codecli.entry.acp;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.agent.PromptContext;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.react.task.PlanEvent;
import org.noear.solon.ai.agent.react.task.ReasonDeltaEvent;
import org.noear.solon.ai.agent.react.task.ReasonEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallStartEvent;
import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.InterruptMode;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.host.HarnessHost;
import org.noear.solon.ai.harness.host.HostSignal;
import org.noear.solon.ai.harness.host.RunOutcome;
import org.noear.solon.ai.harness.host.RunPrep;
import org.noear.solon.ai.harness.host.RunSink;
import org.noear.solon.ai.harness.input.BusyPolicy;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputAttachments;
import org.noear.solon.ai.harness.input.RejectReason;
import org.noear.solon.ai.talents.memory.MemoryTalent;
import org.noear.solon.ai.harness.extension.goal.GoalTalent;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AcpLink implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(AcpLink.class);
    /** 宿主骨架的租户键（ACP 只有一个引擎） */
    private static final String TENANT = "acp";

    /** 挂起卡无人可批时的回执（同时作为拒绝理由写入工具结果） */
    private static final String HITL_UNSUPPORTED =
            "ACP 客户端没有审批通道：本次工具调用已全部拒绝，本轮已停止。"
            + "需要逐次确认请关闭 HITL，或改用 Web / 桌面端。";

    private final HarnessEngine agentRuntime;
    private final AcpAgentTransport agentTransport;
    private final AgentSettings agentSettings;
    /** 宿主骨架：受理、开流、终态、续发、停流由框架代办，本入口只把信号映射成 ACP 协议 */
    private final HarnessHost host;

    public AcpLink(HarnessEngine agentRuntime, AcpAgentTransport agentTransport, AgentSettings agentSettings) {
        this.agentRuntime = agentRuntime;
        this.agentTransport = agentTransport;
        this.agentSettings = agentSettings;
        this.host = HarnessHost.builder()
                .engines(tenant -> agentRuntime)
                // ACP 是一问一答：goal / loop 的后台轮次在 ACP 侧没有出口，也不接管积压续发
                .programRuns(false)
                .build();

        if (agentRuntime.isHitlEnabled()) {
            // 启动就说清楚：ACP 协议侧有审批通道（session/request_permission），但本入口尚未使用，
            // 落到挂起卡的轮次会被自动拒绝并停掉（见 denyHitl），而不是停在审批上等一个点不了的头。
            LOG.warn("[ACP] hitlEnabled=true but ACP approval is not wired: pending cards are auto-denied, "
                    + "turn is stopped. Disable hitl or use the web / desktop entry");
        }
    }

    private final Map<String, AcpSessionContext> sessionStates = new ConcurrentHashMap<>();

    /**
     * callId -> toolCallId 映射，用于 ToolCallStart 与 ToolCallEnd 之间精确配对同一张工具卡。
     * key = sessionId + "#" + callId；end 阶段消费后移除，避免累积。
     */
    private final Map<String, String> callIdToToolCallId = new ConcurrentHashMap<>();

    public void run() {
        AcpAsyncAgent acpAgent = createAgent(agentTransport);
        acpAgent.start().subscribe();
    }

    public AcpAsyncAgent createAgent(AcpAgentTransport transport) {
        return AcpAgent.async(transport)
                .requestTimeout(Duration.ofSeconds(60))
                .initializeHandler(req -> {
                    // SessionCapabilities: 参数为 Object，非 null 表示"支持"，null 表示"不支持"。
                    // 声明 list=false(null), close={}, resume=false(null)。
                    // 只有声明了 close 能力，客户端才会主动调用 session/close。
                    // close 用空对象 {} 而非 Boolean.TRUE，因为部分客户端（如 IDEA）
                    // 的 ACP 模型将 close 定义为 SessionCloseCapabilities 对象类型，
                    // 传入 true 会序列化为 JSON 字面量导致反序列化失败。
                    AcpSchema.SessionCapabilities sessionCaps =
                            new AcpSchema.SessionCapabilities(null, Collections.emptyMap(), null);

                    return Mono.just(new AcpSchema.InitializeResponse(
                            1,
                            new AcpSchema.AgentCapabilities(true,
                                    sessionCaps,
                                    new AcpSchema.McpCapabilities(true, true),
                                    new AcpSchema.PromptCapabilities(true, true, true),
                                    null),
                            Arrays.asList()
                    ));
                })
                .newSessionHandler(req -> {
                    String sessionId = "acp-" + UUID.randomUUID().toString().substring(0, 8);
                    String cwd = req.cwd();

                    sessionStates.put(sessionId, new AcpSessionContext(cwd, req.mcpServers()));

                    return Mono.just(new AcpSchema.NewSessionResponse(sessionId, null, null));
                })
                .loadSessionHandler(req -> {
                    String sessionId = req.sessionId();
                    String cwd = req.cwd();

                    sessionStates.put(sessionId, new AcpSessionContext(cwd, req.mcpServers()));

                    return Mono.just(new AcpSchema.LoadSessionResponse(null, null));
                })
                .cancelHandler(req -> {
                    // 停止就是停止：停流由骨架代办，本轮不再往客户端投递事件，终态一定是 CANCELLED。
                    // 只停当前一轮（一问一答，没有积压可保留）
                    host.admin(TENANT).interrupt(req.sessionId(), InterruptMode.CANCEL_STREAM);
                    return Mono.empty();
                })
                .closeSessionHandler(req -> {
                    // 客户端显式关闭 session：正确的清理时机
                    String sessionId = req.sessionId();
                    sessionStates.remove(sessionId);
                    // 若还有在跑的一轮，先停掉（不再向已关闭的会话投递）
                    host.admin(TENANT).interrupt(sessionId, InterruptMode.CANCEL_STREAM);
                    // 规范路径：判忙 → 通知扩展（撤 goal / loop 的定时任务）→ 删存储；仅从内存移除，
                    // 磁盘历史保留，供后续 loadSession 恢复。
                    // 停流是异步的：刚 interrupt 的那一轮可能还在收尾（仍算占用），此时 closeSession 会拒绝，
                    // 退一步只清会话实例（否则会漏掉，下次 prompt 沿用旧实例）。
                    if (host.closeSession(TENANT, sessionId, () -> removeBackendSession(sessionId)) == false) {
                        LOG.debug("[ACP] session still occupied on close, evict only: {}", sessionId);
                        removeBackendSession(sessionId);
                    }
                    return Mono.just(new AcpSchema.CloseSessionResponse());
                })
                .promptHandler((request, acpContext) -> {
                    String sessionId = acpContext.getSessionId();
                    final AcpSessionContext context = sessionStates.get(sessionId);

                    // session 不存在时抛出协议异常，SDK 会保留 SESSION_NOT_FOUND(-32002) 错误码，
                    // 客户端收到语义明确的错误而非 NPE 兵底的 -32603。
                    if (context == null) {
                        return Mono.error(new AcpProtocolException(
                                AcpErrorCodes.SESSION_NOT_FOUND,
                                "Session not found: " + sessionId));
                    }

                    // 会话必须先解析再构造 input：受理依赖 input.getSession() 绑定会话，
                    // 传 null 会让 ReActRequest 回落到临时 InMemoryAgentSession（多轮上下文丢失、快照不落盘）。
                    // 经骨架开通（引擎要求作用域时会在这里拦下，而不是跑一半失败），ACP 没有登录身份故 principal 为空。
                    AgentSession session = host.openSession(TENANT, sessionId, null);

                    // ACP 客户端文本不构成命令语义，与既有直通行为保持一致。
                    // 忙时直接拒绝：一问一答，排队的那一轮没有出口（本次响应返回后没人接续发）
                    return prompt(acpContext, sessionId, toInput(request, session, context.getCwd())
                            .commandAware(false)
                            .agentAware(false)
                            .whenBusy(BusyPolicy.REJECT), context.getCwd());
                })
                .build();
    }

    /**
     * 跑一轮：受理、开流、终态、停流都交给 {@link HarnessHost}，本方法只把 {@link HostSignal}
     * 映射成 ACP 协议（流式更新 → 最终停止原因）。
     *
     * <p>与直连 {@code engine.handle} 相比，这里不再自己判忙、自己 takeWhile 停流、自己撤回积压：
     * 忙由 {@link BusyPolicy#REJECT} 直接拒绝，停止由客户端 cancel 走 {@code admin.interrupt}，
     * 中断之后框架保证本轮不再投递事件、终态一定是 CANCELLED。</p>
     */
    private Mono<AcpSchema.PromptResponse> prompt(PromptContext acpContext, String sessionId,
                                                  HarnessInput input, String cwd) {
        final long startTime = System.currentTimeMillis();
        final AtomicInteger toolCallCounter = new AtomicInteger(0);

        // 本轮工作区在开流前补进请求（框架只暴露「改请求」这一个准备动作）
        RunSink prepare = new RunSink() {
            @Override
            public void prepare(RunPrep prep) {
                if (Assert.isNotEmpty(cwd)) {
                    prep.request().options(o -> o.toolContextPut(HarnessEngine.ATTR_CWD, cwd));
                }
            }
        };

        return host.stream(TENANT, sessionId, input, prepare)
                .concatMap(signal -> toAcp(signal, acpContext, sessionId, startTime, toolCallCounter))
                .onErrorResume(e -> {
                    // 协议异常透传，保留原错误码；其余异常以消息形式反馈
                    if (e instanceof AcpProtocolException) {
                        return Mono.error(e);
                    }
                    return acpContext.sendMessage("Error: " + e.getMessage()).then(Mono.empty());
                })
                // 只有终态会产出一个 PromptResponse
                .next()
                .switchIfEmpty(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)));
    }

    /** 一个宿主信号 → ACP 输出（只有终态产生 PromptResponse） */
    private Mono<AcpSchema.PromptResponse> toAcp(HostSignal signal, PromptContext acpContext,
                                                 String sessionId, long startTime,
                                                 AtomicInteger toolCallCounter) {
        switch (signal.getType()) {
            case REPLY:
                if (Assert.isEmpty(signal.getText())) {
                    return Mono.empty();
                }
                return acpContext.sendMessage(signal.getText()).then(Mono.empty());
            case EVENT:
                if (signal.getEvent() == null) {
                    return Mono.empty();
                }
                return toAcpEvent(signal.getEvent(), acpContext, sessionId, startTime, toolCallCounter)
                        .then(Mono.empty());
            case REJECTED:
                return acpContext.sendMessage(rejectText(signal))
                        .then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.REFUSAL)));
            case DEFERRED:
                // 忙时直接拒绝（BusyPolicy.REJECT），不会进积压；留此分支只为兜底提示
                return acpContext.sendMessage("当前会话正在执行任务，请稍后再发")
                        .then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.REFUSAL)));
            case HITL:
                return denyHitl(signal.getCards(), acpContext, sessionId);
            case FINISHED:
                if (signal.getOutcome() != null && signal.getOutcome().getHitlCards().isEmpty() == false) {
                    // 终态里还带着挂起卡（HITL 信号在同一轮里已经处理过；这里兜底「只收到终态」的路径）
                    return denyHitl(signal.getOutcome().getHitlCards(), acpContext, sessionId);
                }
                return finish(signal.getOutcome(), acpContext, sessionId);
            default:
                // IGNORED / STEERED：无事可做，正常结束本轮
                return Mono.empty();
        }
    }

    /** 未受理的原因文案（引擎给的详情优先） */
    private String rejectText(HostSignal signal) {
        if (Assert.isNotEmpty(signal.getText())) {
            return signal.getText();
        }
        if (signal.getRejectReason() == RejectReason.BUSY) {
            return "当前会话正在执行任务，请稍后再发";
        }
        return "本次输入未被受理";
    }

    /**
     * ACP 侧没有使用审批通道时，把挂起卡就地了结（安全方向：拒绝），再停掉本轮。
     *
     * <p>不能只是「告警后结束」：挂起卡留在会话里会让后续输入一律按「待审批」拒收，
     * 会话从此不可用（重启才恢复）。拒绝后会触发续流，而那一轮在 ACP 侧没有出口，
     * 所以立即停掉，终态按 REFUSAL 回报给客户端。</p>
     */
    private Mono<AcpSchema.PromptResponse> denyHitl(List<HitlCard> cards, PromptContext acpContext, String sessionId) {
        int denied = 0;
        if (cards != null) {
            for (HitlCard card : cards) {
                if (card.isDecided()) {
                    continue; // 已决策的残留项：不重复写，等引擎自己消费
                }
                try {
                    host.decide(TENANT, sessionId, card.getCallId(), HITLAction.REJECT,
                            HITL_UNSUPPORTED, null);
                    denied++;
                } catch (Throwable e) {
                    LOG.warn("[ACP] deny hitl card failed: {} #{}", sessionId, card.getCallId(), e);
                }
            }
        }

        // 拒绝会触发续流：ACP 没有出口，也没有人能看，直接停掉
        host.admin(TENANT).interrupt(sessionId, InterruptMode.CANCEL_STREAM);
        cleanupToolCalls(sessionId);
        LOG.warn("[ACP] hitl pending but ACP has no approval channel: {} (cards={}, denied={})",
                sessionId, cards == null ? 0 : cards.size(), denied);

        return acpContext.sendMessage(HITL_UNSUPPORTED)
                .then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.REFUSAL)));
    }

    /** 清掉本轮残留的 callId 映射（正常路径下 end 阶段已 remove，工具 start 后被中断会残留） */
    private void cleanupToolCalls(String sessionId) {
        callIdToToolCallId.keySet().removeIf(k -> k.startsWith(sessionId + "#"));
    }

    /** 本轮终态：正常结束 / 出错 / 被中断（客户端据此区分「答完了」与「被取消」） */
    private Mono<AcpSchema.PromptResponse> finish(RunOutcome outcome, PromptContext acpContext, String sessionId) {
        cleanupToolCalls(sessionId);

        if (outcome == null) {
            // 骨架保证 FINISHED 带终态；真没有时不能把异常当正常结束
            LOG.warn("[ACP] finished signal without outcome: {}", sessionId);
            return Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
        }
        if (outcome.isCancelled()) {
            return Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED));
        }
        if (outcome.isFailed()) {
            Throwable error = outcome.getError();
            String message = error == null ? "unknown error" : String.valueOf(error.getMessage());
            return acpContext.sendMessage("Error: " + message)
                    .then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)));
        }
        return Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
    }

    /** 单个 ReAct 事件 → ACP 流式更新（Plan / 思考 / 工具卡 / 最终回复） */
    private Mono<Void> toAcpEvent(AgentEvent event, PromptContext acpContext,
                                  String sessionId, long startTime, AtomicInteger toolCallCounter) {
        // === 规划阶段：映射到 ACP Plan 结构化输出 ===
        if (event instanceof PlanEvent) {
            String content = event.getText();
            AcpSchema.PlanEntry entry = new AcpSchema.PlanEntry(
                    content != null ? content : "Planning...",
                    AcpSchema.PlanEntryPriority.HIGH,
                    AcpSchema.PlanEntryStatus.IN_PROGRESS
            );
            AcpSchema.Plan plan = new AcpSchema.Plan("plan", Collections.singletonList(entry));
            return acpContext.sendUpdate(sessionId, plan);
        }
        // === 思考阶段 ===
        else if (event instanceof ReasonDeltaEvent) {
            if (event.hasText() && agentSettings.getGeneral().isCliThinkPrinted()) {
                return acpContext.sendThought(event.getText());
            }
        }
        // === ThoughtChunk（多任务并行） ===
        else if (event instanceof ReasonEndEvent) {
            ReasonEndEvent thoughtChunk = (ReasonEndEvent) event;
            if (thoughtChunk.hasMeta(TaskTalent.TOOL_MULTITASK)) {
                String content = thoughtChunk.getText();
                if (Assert.isNotEmpty(content)) {
                    return acpContext.sendThought(content);
                }
            }
        }
        // === 工具执行开始阶段：发 pending ToolCall 骨架卡 ===
        else if (event instanceof ToolCallStartEvent) {
            ToolCallStartEvent startChunk = (ToolCallStartEvent) event;
            String toolName = startChunk.getToolName();

            if (isInternalTool(toolName)) {
                return Mono.empty();
            }

            // 以 callId 作为 toolCallId 的稳定来源，保证 start/end 精确配对；
            // callId 缺失时退化为自增序号。
            String toolCallId = resolveToolCallId(startChunk.getCallId(), toolCallCounter);
            callIdToToolCallId.put(sessionId + "#" + startChunk.getCallId(), toolCallId);

            Map<String, Object> args = startChunk.getArgs();
            AcpSchema.ToolCall toolCall = new AcpSchema.ToolCall(
                    "tool_call",
                    toolCallId,
                    buildToolTitle(toolName, startChunk.getAgentName(), args, null, true),
                    resolveToolKind(toolName),
                    AcpSchema.ToolCallStatus.IN_PROGRESS,
                    Collections.emptyList(),
                    buildLocations(args),
                    args,          // rawInput
                    null,          // rawOutput
                    null           // meta
            );
            return acpContext.sendUpdate(sessionId, toolCall);
        }
        // === 工具执行结束阶段：发 ToolCallUpdate 填充完成/失败态 ===
        else if (event instanceof ToolCallEndEvent) {
            ToolCallEndEvent observationChunk = (ToolCallEndEvent) event;
            String toolName = observationChunk.getToolName();

            if (isInternalTool(toolName)) {
                return Mono.empty();
            }

            // 复用 start 阶段登记的 toolCallId；未登记（无 start 事件）时新建
            String key = sessionId + "#" + observationChunk.getCallId();
            String toolCallId = callIdToToolCallId.remove(key);
            if (toolCallId == null) {
                toolCallId = resolveToolCallId(observationChunk.getCallId(), toolCallCounter);
            }

            boolean failed = observationChunk.getError() != null;
            String content = failed
                    ? String.valueOf(observationChunk.getError().getMessage())
                    : event.getText();
            Map<String, Object> args = observationChunk.getArgs();

            AcpSchema.ToolCallUpdateNotification update = new AcpSchema.ToolCallUpdateNotification(
                    "tool_call_update",
                    toolCallId,
                    buildToolTitle(toolName, observationChunk.getAgentName(), args, content, false),
                    resolveToolKind(toolName),
                    failed ? AcpSchema.ToolCallStatus.FAILED : AcpSchema.ToolCallStatus.COMPLETED,
                    buildToolContent(toolName, args, content),
                    buildLocations(args),
                    args,          // rawInput
                    content,       // rawOutput
                    null           // meta
            );
            return acpContext.sendUpdate(sessionId, update);
        }
        // === 最终回复阶段 ===
        else if (event instanceof RunEndEvent) {
            String traceInfo = buildTraceInfo(((RunEndEvent) event).getTrace(), startTime);

            // 发送最终文本内容
            return acpContext.sendMessage(event.getText() + traceInfo);
        }

        return Mono.empty();
    }

    /**
     * 同步移除后端 AgentSession（仅内存 map，不删除磁盘历史）。
     *
     * <p>HarnessEngine.getSessionProvider() 返回 AgentSessionProvider 接口，
     * evictSession 仅定义在具体实现 SessionManager 上，故需 instanceof 判断。</p>
     */
    private void removeBackendSession(String sessionId) {
        AgentSessionProvider provider = agentRuntime.getSessionProvider();
        if (provider instanceof SessionManager) {
            ((SessionManager) provider).evictSession(sessionId);
        }
    }

    /**
     * 内部工具（不向 ACP 客户端展示）判定，与 WebStreamBuilder 保持一致：
     * 任务分发工具（task/multitask）、记忆工具、目标工具。
     */
    private boolean isInternalTool(String toolName) {
        return TaskTalent.TOOL_MULTITASK.equals(toolName)
                || TaskTalent.TOOL_TASK.equals(toolName)
                || MemoryTalent.isMemoryTool(toolName)
                || GoalTalent.isGoalTool(toolName);
    }

    /**
     * 生成稳定的 toolCallId：优先用引擎 callId（保证 start/end 配对），缺失时用自增序号兜底。
     */
    private String resolveToolCallId(String callId, AtomicInteger counter) {
        if (Assert.isNotEmpty(callId)) {
            return "tc-" + callId;
        }
        return "tc-" + counter.incrementAndGet();
    }

    /**
     * 按工具名映射 ACP ToolKind，让客户端按类型渲染（读/改/执行/搜索/抓取等）。
     */
    private AcpSchema.ToolKind resolveToolKind(String toolName) {
        if (Assert.isEmpty(toolName)) {
            return AcpSchema.ToolKind.OTHER;
        }
        switch (toolName) {
            case "read":
                return AcpSchema.ToolKind.READ;
            case TerminalTalent.TOOL_WRITE:
            case TerminalTalent.TOOL_EDIT:
                return AcpSchema.ToolKind.EDIT;
            case "grep":
            case "glob":
            case "ls":
                return AcpSchema.ToolKind.SEARCH;
            case "bash":
                return AcpSchema.ToolKind.EXECUTE;
            case "webfetch":
            case "websearch":
                return AcpSchema.ToolKind.FETCH;
            default:
                return AcpSchema.ToolKind.EXECUTE;
        }
    }

    /**
     * 构建结构化 ToolCallContent：
     * <ul>
     *   <li>write：以 ToolCallDiff 呈现新建文件内容（oldText=null, newText=content 参数）</li>
     *   <li>edit：以 ToolCallDiff 逐条呈现 old_str → new_str 改动</li>
     *   <li>其余工具：以 ToolCallContentBlock 包裹文本输出</li>
     * </ul>
     */
    private List<AcpSchema.ToolCallContent> buildToolContent(String toolName, Map<String, Object> args, String content) {
        // write：整文件新增/覆盖，用 diff 展示写入内容
        if (TerminalTalent.TOOL_WRITE.equals(toolName) && args != null) {
            String path = firstNonEmpty(args, "file_path", "path");
            Object body = args.get(TerminalTalent.PARAM_CONTENT);
            if (path != null && body != null) {
                return Collections.singletonList(new AcpSchema.ToolCallDiff(
                        "diff", path, null, String.valueOf(body)));
            }
        }

        // edit：结构化 edits 列表，逐条生成 diff
        if (TerminalTalent.TOOL_EDIT.equals(toolName) && args != null
                && args.get(TerminalTalent.PARAM_EDITS) instanceof List) {
            String path = firstNonEmpty(args, "file_path", "path");
            List<AcpSchema.ToolCallContent> diffs = new ArrayList<>();
            for (Object item : (List<?>) args.get(TerminalTalent.PARAM_EDITS)) {
                if (item instanceof Map) {
                    Map<?, ?> edit = (Map<?, ?>) item;
                    Object oldStr = edit.get("old_str");
                    Object newStr = edit.get("new_str");
                    diffs.add(new AcpSchema.ToolCallDiff(
                            "diff", path,
                            oldStr == null ? null : String.valueOf(oldStr),
                            newStr == null ? null : String.valueOf(newStr)));
                }
            }
            if (!diffs.isEmpty()) {
                return diffs;
            }
        }

        // 其余工具：文本输出
        if (Assert.isNotEmpty(content)) {
            return Collections.singletonList(new AcpSchema.ToolCallContentBlock(
                    "content", new AcpSchema.TextContent(content)));
        }

        return Collections.emptyList();
    }

    /**
     * 从工具参数提取文件位置，生成 ToolCallLocation，供客户端跳转/高亮。
     */
    private List<AcpSchema.ToolCallLocation> buildLocations(Map<String, Object> args) {
        if (args == null) {
            return Collections.emptyList();
        }
        String path = firstNonEmpty(args, "file_path", "path");
        if (path == null) {
            return Collections.emptyList();
        }
        Integer line = null;
        Object off = args.get("offset");
        if (off instanceof Number) {
            line = ((Number) off).intValue();
        }
        return Collections.singletonList(new AcpSchema.ToolCallLocation(path, line));
    }

    private String firstNonEmpty(Map<String, Object> args, String... keys) {
        for (String k : keys) {
            Object v = args.get(k);
            if (v != null && Assert.isNotEmpty(String.valueOf(v))) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    /**
     * 构建工具调用的显示标题（title）。
     *
     * <p>title 用于客户端卡片头部展示；子代理执行时加 agentName/ 前缀（对齐 WebStreamBuilder）。</p>
     *
     * @param running true=start 阶段（执行中，无结果摘要），false=end 阶段（已完成，可带结果摘要）。
     *                避免 start 阶段 content 为空时错误地显示“read: completed”（状态却是 IN_PROGRESS）。
     */
    private String buildToolTitle(String toolName, String agentName, Map<String, Object> args, String content, boolean running) {
        if (Assert.isEmpty(toolName)) {
            return content;
        }

        // 子代理前缀：非主引擎执行时标注归属
        String displayName = toolName;
        if (Assert.isNotEmpty(agentName) && !agentRuntime.getName().equals(agentName)) {
            displayName = agentName + "/" + toolName;
        }

        String argsStr = buildArgsStr(args);

        if (agentSettings.getGeneral().isCliPrintSimplified()) {
            // 简化模式：只显示工具名 + 状态/结果摘要
            String summary;
            if (running) {
                // start 阶段：优先展示参数（如 file_path），无参数时用 running 占位
                summary = Assert.isEmpty(argsStr) ? "running" : summary(argsStr);
            } else if (Assert.isEmpty(content)) {
                summary = "completed";
            } else {
                String[] lines = content.split("\n");
                if (lines.length > 1) {
                    summary = "returned " + lines.length + " lines";
                } else {
                    summary = summary(content);
                }
            }
            return displayName + ": " + summary;
        } else {
            // 全量模式：显示工具名 + 参数
            if (argsStr.length() > 100) {
                return displayName + "(" + argsStr.substring(0, 97) + "...)";
            }
            return displayName + "(" + argsStr + ")";
        }
    }

    /**
     * 构建 trace 统计信息（参考 WebStreamBuilder.getTraceInfo）
     */
    private String buildTraceInfo(ReActTrace trace, long startTime) {
        StringBuilder buf = new StringBuilder();
        buf.append("(");

        if (trace != null) {
            if (trace.getOptions() != null && trace.getOptions().getChatModel() != null) {
                buf.append(trace.getOptions().getChatModel().getNameOrModel());
            }
            if (trace.getMetrics() != null) {
                if (buf.length() > 1) buf.append(", ");
                buf.append(trace.getMetrics().getTotalTokens()).append("tk");
            }
        }

        long seconds = Duration.ofMillis(System.currentTimeMillis() - startTime).getSeconds();
        if (buf.length() > 1) buf.append(", ");
        buf.append(seconds).append("s");

        buf.append(")");
        return buf.toString();
    }

    /** 截断过长文本作为摘要（超过 40 字符截断并加省略号）。 */
    private String summary(String text) {
        return text.length() > 40 ? text.substring(0, 37) + "..." : text;
    }

    private String buildArgsStr(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        args.forEach((k, v) -> {
            if (sb.length() > 0) sb.append(" ");
            sb.append(k).append("=").append(v);
        });
        return sb.toString().replace("\n", " ");
    }

    /**
     * 把 ACP PromptRequest 转为引擎输入：文本与媒资分流。
     *
     * <p>文本块与 ResourceLink 的 [path] 内联拼入正文本保持既有格式；
     * 图片（含内嵌 blob）走 {@code InputAttachments.media}；其他内嵌资源
     * 按原逻辑回退为文本块。</p>
     *
     * @param session 会话（必须非空：其为 handle 绑定会话的唯一依据）
     */
    public HarnessInput toInput(AcpSchema.PromptRequest promptRequest, AgentSession session, String cwd) {
        InputAttachments media = InputAttachments.of();
        StringBuilder textBuilder = new StringBuilder();

        for (AcpSchema.ContentBlock cp : promptRequest.prompt()) {
            if (cp instanceof AcpSchema.TextContent) {
                AcpSchema.TextContent text = (AcpSchema.TextContent) cp;
                if (textBuilder.length() > 0) textBuilder.append(" ");
                textBuilder.append(text.text());
            } else if (cp instanceof AcpSchema.ImageContent) {
                AcpSchema.ImageContent image = (AcpSchema.ImageContent) cp;
                if (Assert.isEmpty(image.uri())) {
                    media.media(ImageBlock.ofBase64(image.data(), image.mimeType()));
                } else {
                    media.media(ImageBlock.ofUrl(image.uri(), image.mimeType()));
                }
            } else if (cp instanceof AcpSchema.ResourceLink) {
                // 文件拖拽引用：不读取内容，只将 URI 转为工作区相对路径，
                // 以 [path] 形式追加到用户文本后面
                String relativePath = toRelativePath(((AcpSchema.ResourceLink) cp).uri(), cwd);
                if (Assert.isNotEmpty(relativePath)) {
                    if (textBuilder.length() > 0) textBuilder.append(" ");
                    textBuilder.append("[").append(relativePath).append("]");
                }
            } else if (cp instanceof AcpSchema.Resource) {
                handleEmbeddedResource(media, textBuilder, (AcpSchema.Resource) cp);
            }
        }

        String text = textBuilder.length() > 0 ? textBuilder.toString() : null;
        return HarnessInput.of(session, text).attachments(media);
    }

    /**
     * 将 ResourceLink 的 URI 转为工作区相对路径。
     * <p>去除 file:// 前缀，若路径以 cwd 开头则转为相对路径，否则返回原路径。</p>
     */
    private String toRelativePath(String uri, String cwd) {
        if (Assert.isEmpty(uri)) {
            return null;
        }

        // 去除 file:// 前缀
        String filePath = uri;
        if (filePath.startsWith("file://")) {
            filePath = filePath.substring(7);
            // Windows: file:///C:/... → C:/...
            if (filePath.startsWith("/") && filePath.length() > 2 && filePath.charAt(2) == ':') {
                filePath = filePath.substring(1);
            }
        }

        // 转为工作区相对路径
        if (Assert.isNotEmpty(cwd)) {
            Path fullPath = Paths.get(filePath);
            Path cwdPath = Paths.get(cwd);
            if (fullPath.startsWith(cwdPath)) {
                return cwdPath.relativize(fullPath).toString();
            }
        }

        return filePath;
    }



    /**
     * 处理 Resource：客户端内联嵌入的资源内容。
     * <ul>
     *   <li>TextResourceContents：text 字段已有内容，拼入正文（保留 ``` 包裹格式）</li>
     *   <li>BlobResourceContents：base64 编码，图片转 media，其余尝试解码为文本拼入正文</li>
     * </ul>
     */
    private void handleEmbeddedResource(InputAttachments media, StringBuilder textBuilder, AcpSchema.Resource resource) {
        AcpSchema.EmbeddedResourceResource res = resource.resource();
        if (res == null) {
            return;
        }

        if (res instanceof AcpSchema.TextResourceContents) {
            AcpSchema.TextResourceContents textRes = (AcpSchema.TextResourceContents) res;
            String text = textRes.text();
            if (Assert.isNotEmpty(text)) {
                String uri = textRes.uri();
                String label = Assert.isNotEmpty(uri) ? "File: " + uri + "\n" : "";
                appendText(textBuilder, label + "```\n" + text + "\n```");
            }
        } else if (res instanceof AcpSchema.BlobResourceContents) {
            AcpSchema.BlobResourceContents blobRes = (AcpSchema.BlobResourceContents) res;
            String blob = blobRes.blob();
            String mimeType = blobRes.mimeType();
            if (Assert.isEmpty(blob)) {
                return;
            }

            if (mimeType != null && mimeType.startsWith("image/")) {
                media.media(ImageBlock.ofBase64(blob, mimeType));
            } else {
                // 尝试解码为文本
                try {
                    byte[] decoded = Base64.getDecoder().decode(blob);
                    String text = new String(decoded, StandardCharsets.UTF_8);
                    String uri = blobRes.uri();
                    String label = Assert.isNotEmpty(uri) ? "File: " + uri + "\n" : "";
                    appendText(textBuilder, label + "```\n" + text + "\n```");
                } catch (Exception e) {
                    // 二进制内容无法解码为文本
                    String uri = blobRes.uri();
                    String name = Assert.isNotEmpty(uri) ? uri : "binary resource";
                    appendText(textBuilder, "[Binary resource: " + name + " ("
                            + (mimeType != null ? mimeType : "unknown") + ")]");
                }
            }
        }
    }

    /** 追加一段独立文本（带前导空格分隔，与原多块拼接格式一致）。 */
    private void appendText(StringBuilder textBuilder, String text) {
        if (textBuilder.length() > 0) textBuilder.append(" ");
        textBuilder.append(text);
    }

    public static class AcpSessionContext {
        private final String cwd;
        private final List<AcpSchema.McpServer> mcpServers;
        private final Instant createdAt;

        public AcpSessionContext(String cwd, List<AcpSchema.McpServer> mcpServers) {
            this.cwd = cwd;
            this.mcpServers = mcpServers;
            this.createdAt = Instant.now();
        }

        public String getCwd() {
            return cwd;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public List<AcpSchema.McpServer> getMcpServers() {
            return mcpServers;
        }
    }
}
