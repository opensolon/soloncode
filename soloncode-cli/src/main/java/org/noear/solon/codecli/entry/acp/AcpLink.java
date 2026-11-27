package org.noear.solon.codecli.entry.acp;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.agent.PromptContext;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.AbsReActEvent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.intercept.ContextSizeEvent;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.react.task.PlanEvent;
import org.noear.solon.ai.agent.react.task.ReasonDeltaEvent;
import org.noear.solon.ai.agent.react.task.ReasonEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallStartEvent;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.InterruptMode;
import org.noear.solon.ai.harness.agent.TaskTalent;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.MarkdownCommand;
import org.noear.solon.ai.harness.command.ParsedCommand;
import org.noear.solon.ai.harness.command.builtin.ContinueCommand;
import org.noear.solon.ai.harness.command.builtin.HelpCommand;
import org.noear.solon.ai.harness.command.builtin.ModelCommand;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.host.HarnessHost;
import org.noear.solon.ai.harness.host.HostSignal;
import org.noear.solon.ai.harness.host.RunOutcome;
import org.noear.solon.ai.harness.host.RunPrep;
import org.noear.solon.ai.harness.host.RunSink;
import org.noear.solon.ai.harness.i18n.HarnessI18n;
import org.noear.solon.ai.harness.i18n.HarnessI18nKeys;
import org.noear.solon.ai.harness.input.BusyPolicy;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputAttachments;
import org.noear.solon.ai.harness.input.RejectReason;
import org.noear.solon.ai.harness.session.HarnessAttrs;
import org.noear.solon.ai.harness.util.TraceUtil;
import org.noear.solon.ai.talents.memory.MemoryTalent;
import org.noear.solon.ai.harness.extension.goal.GoalTalent;
import org.noear.solon.codecli.api.web.service.LastTraceService;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.codecli.util.ReasoningSupportUtil;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AcpLink implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(AcpLink.class);
    /** 宿主骨架的租户键（ACP 只有一个引擎） */
    private static final String TENANT = "acp";

    /**
     * session/new、session/load、session/resume 响应里 configOptions 的载体键。
     *
     * <p>SDK 的建立会话结果 DTO 没有 configOptions 字段，处理器把它放在 {@code _meta}
     * 里带出来，由 {@link AcpSetupTransport} 在序列化前提升到 result 顶层（ACP v1 标准字段），
     * 属于本端兼容缝而不是私有协议扩展。</p>
     */
    static final String CONFIG_OPTIONS = "configOptions";

    /**
     * 本端发放的会话 ID 形态：{@code acp-} + 全量 UUID，兼容早期 {@code acp-} + 8 位十六进制。
     * 带前缀保证归属校验只占本端命名空间——裸 UUID 可能来自其它宿主，不能据此认领。
     */
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile(
            "acp-(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    /**
     * 可发布为 ACP 斜杠命令的命令名形态：字母 / 数字 / `_` `.` `:` `-`，且必须全小写——
     * 名字不规则的注册项不进补全清单，客户端无从输入的命令没有意义。
     */
    private static final Pattern SLASH_COMMAND_NAME = Pattern.compile("[\\p{L}\\p{N}_.:-]+");

    private final HarnessEngine agentRuntime;
    private final AcpAgentTransport agentTransport;
    private final AgentSettings agentSettings;
    /** 会话级配置选项（configOptions：模型 / 推理强度 / 思考模式 / 审批模式）的装配与写入 */
    private final AcpSessionConfig sessionConfig;
    private final LastTraceService lastTraceService = new LastTraceService();
    /** 宿主骨架：受理、开流、终态、续发、停流由框架代办，本入口只把信号映射成 ACP 协议 */
    private final HarnessHost host;

    public AcpLink(HarnessEngine agentRuntime, AcpAgentTransport agentTransport, AgentSettings agentSettings) {
        this.agentRuntime = agentRuntime;
        this.agentTransport = agentTransport;
        this.agentSettings = agentSettings;
        this.sessionConfig = new AcpSessionConfig(agentRuntime, agentSettings);
        this.host = HarnessHost.builder()
                .engines(tenant -> agentRuntime)
                // ACP 是一问一答：goal / loop 的后台轮次在 ACP 侧没有出口，也不接管积压续发
                .programRuns(false)
                .build();
    }

    private final Map<String, AcpSessionContext> sessionStates = new ConcurrentHashMap<>();

    /** sessionId -> 进行中的轮次：取消 / 关闭 / 断连时截断审批等待并阻止迟到的决策提交 */
    private final Map<String, AcpTurn> activeTurns = new ConcurrentHashMap<>();

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
        // 会话建立/恢复响应要在结果之前回放历史、广播可用命令，都走 agent 自身的 session/update 通道
        final AtomicReference<AcpAsyncAgent> agentRef = new AtomicReference<>();

        AcpAsyncAgent agent = AcpAgent.async(new AcpSetupTransport(transport))
                .requestTimeout(Duration.ofSeconds(60))
                .initializeHandler(req -> {
                    // SessionCapabilities: 参数为 Object，非 null 表示"支持"，null 表示"不支持"。
                    // 声明 list=false(null), close={}, resume={}。
                    // close / resume 用空对象 {} 而非 Boolean.TRUE，因为部分客户端（如 IDEA）
                    // 的 ACP 模型将它们定义为对象类型，传入 true 会序列化为 JSON 字面量导致反序列化失败。
                    AcpSchema.SessionCapabilities sessionCaps =
                            new AcpSchema.SessionCapabilities(null, Collections.emptyMap(), Collections.emptyMap());

                    // 能力只报真实可用的：audio 未接、客户端注入的 MCP（http/sse 传输）
                    // 并不真正生效，故不广告；embeddedContext / image 有完整处理链路
                    return Mono.just(new AcpSchema.InitializeResponse(
                            1,
                            new AcpSchema.AgentCapabilities(true,
                                    sessionCaps,
                                    new AcpSchema.McpCapabilities(false, false),
                                    new AcpSchema.PromptCapabilities(false, true, true),
                                    null),
                            Arrays.asList(),
                            new AcpSchema.Implementation("soloncode", AgentFlags.getVersion(), "SolonCode"),
                            null
                    ));
                })
                .newSessionHandler(req -> {
                    // 先验 cwd：非法路径按参数错误拒绝，不允许静默回落到进程目录/引擎工作区
                    String cwd = requireCwd(req.cwd());
                    String sessionId = "acp-" + UUID.randomUUID().toString();

                    // 先开后端会话：prompt 之前的 set_config_option 要能落到持久快照
                    AgentSession session = host.openSession(TENANT, sessionId, null);
                    cwd = bindCwd(session, cwd);

                    sessionStates.put(sessionId, new AcpSessionContext(cwd, req.mcpServers()));

                    // 先广播可用斜杠命令（available_commands_update），再给响应
                    return announceCommands(agentRef.get(), sessionId, cwd)
                            .thenReturn(new AcpSchema.NewSessionResponse(sessionId, null, null,
                                    Collections.singletonMap(CONFIG_OPTIONS, (Object) sessionConfig.options(session))));
                })
                .loadSessionHandler(req -> {
                    // load 恢复已存在的会话：校验身份与归属、绑定工作区，再把历史回放给客户端
                    AgentSession session = attachSession(req.sessionId(), req.cwd(), req.mcpServers());

                    Map<String, Object> meta =
                            Collections.singletonMap(CONFIG_OPTIONS, (Object) sessionConfig.options(session));
                    AcpAsyncAgent acpAgent = agentRef.get();
                    // 先回放（保持消息序），再广播可用命令，最后给结果
                    return replayHistory(acpAgent, session)
                            .then(Mono.defer(() -> announceCommands(acpAgent, req.sessionId(),
                                    agentRuntime.getSessionCwd(session))))
                            .thenReturn(new AcpSchema.LoadSessionResponse(null, null, meta));
                })
                .resumeSessionHandler(req -> {
                    // resume 与 load 同校验，只是不回放（本地入口自己持有对话上下文）
                    AgentSession session = attachSession(req.sessionId(), req.cwd(), req.mcpServers());
                    return announceCommands(agentRef.get(), req.sessionId(), agentRuntime.getSessionCwd(session))
                            .thenReturn(new AcpSchema.ResumeSessionResponse(null, null,
                                    Collections.singletonMap(CONFIG_OPTIONS, (Object) sessionConfig.options(session))));
                })
                .setSessionConfigOptionHandler(req -> {
                    String sessionId = req.sessionId();
                    if (sessionStates.get(sessionId) == null) {
                        return Mono.error(new AcpProtocolException(
                                AcpErrorCodes.SESSION_NOT_FOUND,
                                "Session not found: " + sessionId));
                    }

                    // 只发布 select 选项；类型省略或 "select" 之外的请求一律按未知值拒绝
                    if (req.type() != null && !"select".equals(req.type())) {
                        return Mono.error(new AcpProtocolException(
                                AcpErrorCodes.INVALID_PARAMS,
                                "Unknown config option or value: " + req.configId()));
                    }

                    AgentSession session = requireExistingSession(sessionId);

                    // 进行中不改选择：本轮生效值已在受理时快照，运行中写入没有意义
                    if (activeTurns.containsKey(sessionId) || agentRuntime.status(session).isOccupied()) {
                        return Mono.error(new AcpProtocolException(
                                AcpErrorCodes.CONCURRENT_PROMPT,
                                "Session is busy: " + sessionId));
                    }

                    return Mono.fromSupplier(() -> new AcpSchema.SetSessionConfigOptionResponse(
                            sessionConfig.set(session, req.configId(), req.value())));
                })
                .cancelHandler(req -> {
                    // 停止就是停止：停流由骨架代办，本轮不再往客户端投递事件，终态一定是 CANCELLED。
                    // 未知会话的取消是纯空操作——还没轮到 harness 就得先返回，避免对陌生会话停流
                    String sessionId = req.sessionId();
                    if (sessionStates.get(sessionId) == null) {
                        return Mono.empty();
                    }
                    stopTurn(sessionId, () -> host.interrupt(TENANT, sessionId));
                    return Mono.empty();
                })
                .closeSessionHandler(req -> {
                    // 客户端显式关闭 session：正确的清理时机
                    String sessionId = req.sessionId();
                    if (sessionStates.get(sessionId) == null) {
                        return Mono.error(new AcpProtocolException(
                                AcpErrorCodes.SESSION_NOT_FOUND,
                                "Session not found: " + sessionId));
                    }
                    sessionStates.remove(sessionId);
                    // 若还有在跑的一轮（含审批等待），先停掉（不再向已关闭的会话投递）
                    stopTurn(sessionId, () -> host.admin(TENANT).interrupt(sessionId, InterruptMode.CANCEL_STREAM));
                    // Keep the session in memory if a cancelled run is still settling; it still owns the snapshot.
                    if (!host.closeSession(TENANT, sessionId, () -> removeBackendSession(sessionId))) {
                        LOG.debug("[ACP] session still occupied on close, keep loaded: {}", sessionId);
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

                    AgentSession session = requireExistingSession(sessionId);

                    // Read the saved selection; HarnessHost binds the input to the session by ID.
                    AcpSessionConfig.Snapshot snapshot = sessionConfig.snapshot(session);

                    HarnessInput input = toInput(request, context.getCwd());
                    // Only a text-only prompt can invoke a command, never text assembled from attachments.
                    ParsedCommand command = request.prompt().stream().allMatch(AcpSchema.TextContent.class::isInstance)
                            ? ParsedCommand.parse(input.getText()) : null;
                    List<Command> available = command == null ? Collections.emptyList() : availableCommands(context.getCwd());
                    Command selected = command == null ? null : available.stream()
                            .filter(item -> item.name().equals(command.getName()))
                            .findFirst().orElse(null);
                    Supplier<Mono<Void>> localReply = null;
                    if (selected instanceof HelpCommand) {
                        localReply = () -> acpContext.sendMessage(
                                HelpCommand.formatHelp(available, command.argAt(0), false, agentRuntime.getLocale()));
                    } else if (selected instanceof ModelCommand) {
                        localReply = () -> modelCommand(agentRef.get(), acpContext, session, command);
                    } else if (selected instanceof ContinueCommand) {
                        if (command.argCount() != 0) {
                            localReply = () -> acpContext.sendMessage(
                                    HelpCommand.formatHelp(available, "continue", false, agentRuntime.getLocale()));
                        } else {
                            ReActTrace trace = TraceUtil.getCurrentTrace(session);
                            if (trace == null || Prompt.isEmpty(trace.getOriginalPrompt()) || Agent.ID_END.equals(trace.getRoute())) {
                                localReply = () -> acpContext.sendMessage(
                                        HarnessI18n.get(agentRuntime.getLocale(), HarnessI18nKeys.COMMAND_CONTINUE_NONE));
                            } else {
                                // 只续未完成的轨迹，不调用会撤掉末尾消息的 CLI /continue。
                                input = HarnessInput.of((String) null).resume(true);
                            }
                        }
                    }
                    // 忙时直接拒绝：一问一答，排队的那一轮没有出口。
                    return prompt(acpContext, sessionId, input
                            .commandAware(selected instanceof MarkdownCommand)
                            .agentAware(false)
                            .whenBusy(BusyPolicy.REJECT)
                            .selectedModel(snapshot.model), context, snapshot, localReply);
                })
                .build();

        agentRef.set(agent);
        return agent;
    }

    /**
     * 本会话可发布 / 可执行的斜杠命令：{@link HelpCommand}、会话级的 {@link ContinueCommand}
     * 与 {@link ModelCommand}，外加（工作区即引擎工作区时）已启用的 {@link MarkdownCommand}。
     * 终端专属、改写历史、排队 / 插话 / 后台调度等 CLI 命令在 ACP 侧没有出口，一律不发布。
     */
    private List<Command> availableCommands(String cwd) {
        if (!"/".equals(agentRuntime.getCommandPrefix())) {
            return Collections.emptyList();
        }
        boolean includeMarkdown = isEngineWorkspace(cwd);
        // ACP does not share the terminal or support commands that rewrite history or schedule background turns.
        return agentRuntime.getCommandRegistry().all().stream()
                .filter(command -> !command.terminalOnly())
                .filter(command -> command.getClass() == HelpCommand.class
                        || command.getClass() == ContinueCommand.class
                        || command.getClass() == ModelCommand.class
                        || (includeMarkdown && command.getClass() == MarkdownCommand.class))
                .filter(command -> SLASH_COMMAND_NAME.matcher(command.name()).matches()
                        && command.name().equals(command.name().toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
    }

    /**
     * 会话工作区是否就是引擎工作区：只有一致时才发布该工作区加载的 Markdown 自定义命令，
     * 其它目录的会话不能借用引擎工作区的私有命令。
     */
    private boolean isEngineWorkspace(String cwd) {
        String workspace = agentRuntime.getWorkspace();
        if (workspace == null || workspace.isEmpty()) {
            return false;
        }
        try {
            return Files.isSameFile(Paths.get(cwd), Paths.get(workspace));
        } catch (IOException | InvalidPathException e) {
            return false;
        }
    }

    /** 会话建立（new / load / resume）响应之前，以标准 available_commands_update 广播可用命令 */
    private Mono<Void> announceCommands(AcpAsyncAgent agent, String sessionId, String cwd) {
        Locale locale = agentRuntime.getLocale();
        List<AcpSchema.AvailableCommand> commands = availableCommands(cwd).stream()
                .map(command -> new AcpSchema.AvailableCommand(command.name(), command.description(locale),
                        command instanceof HelpCommand ? new AcpSchema.AvailableCommandInput("[command]")
                                : command instanceof ModelCommand ? new AcpSchema.AvailableCommandInput("[ls | help | model]")
                                : null))
                .collect(Collectors.toList());
        return agent.sendSessionUpdate(sessionId,
                new AcpSchema.AvailableCommandsUpdate("available_commands_update", commands));
    }

    /**
     * {@code /model} 的 ACP 版实现：列模型、帮助与切换都只对当前会话生效。
     *
     * <p>切换经 {@link AcpSessionConfig#set} 持久化会话选择并回广播全量 configOptions
     * （标准 config_option_update），与 set_config_option 同一条写路径；不触碰全局模型。</p>
     */
    private Mono<Void> modelCommand(AcpAsyncAgent agent, PromptContext context, AgentSession session,
                                    ParsedCommand command) {
        Locale locale = agentRuntime.getLocale();
        String value = command.getArgsJoined();
        if ("help".equals(value)) {
            return context.sendMessage(HelpCommand.formatHelp(Collections.singletonList(new ModelCommand()),
                    "model", false, locale));
        }
        if (value.isEmpty() || "ls".equals(value)) {
            String list = String.join("\n", ModelCommand.formatModels(agentRuntime.getModels(),
                    sessionConfig.snapshot(session).model, locale)).replaceAll("\\x1b\\[[0-9;]*m", "");
            return context.sendMessage(list);
        }
        if (agentRuntime.status(session).isOccupied()) {
            return Mono.error(new AcpProtocolException(AcpErrorCodes.CONCURRENT_PROMPT,
                    "Session is busy: " + context.getSessionId()));
        }
        List<AcpSchema.SessionConfigOption> options;
        try {
            options = sessionConfig.set(session, AcpSessionConfig.MODEL, value);
        } catch (AcpProtocolException e) {
            if (e.getCode() != AcpErrorCodes.INVALID_PARAMS) {
                return Mono.error(e);
            }
            return context.sendMessage(HarnessI18n.fmt(locale, HarnessI18nKeys.COMMAND_MODEL_NOT_FOUND, "name", value));
        }
        return agent.sendSessionUpdate(context.getSessionId(), new AcpSchema.ConfigOptionUpdate("config_option_update", options))
                .then(context.sendMessage(HarnessI18n.fmt(locale, HarnessI18nKeys.COMMAND_MODEL_SWITCHED, "name", value)));
    }

    /**
     * 跑一轮：受理、开流、终态、停流都交给 {@link HarnessHost}，本方法只把 {@link HostSignal}
     * 映射成 ACP 协议（流式更新 → 最终停止原因）。
     *
     * <p>与直连 {@code engine.handle} 相比，这里不再自己判忙、自己 takeWhile 停流、自己撤回积压：
     * 忙由 {@link BusyPolicy#REJECT} 直接拒绝，停止由客户端 cancel 走 {@code admin.interrupt}，
     * 中断之后框架保证本轮不再投递事件、终态一定是 CANCELLED。</p>
     *
     * <p>审批等待期间客户端取消 / 断开时，{@code turn.stopped} 抢先完成，外层工作流被取消，
     * 已收齐的批准也不会再写回会话。</p>
     */
    private Mono<AcpSchema.PromptResponse> prompt(PromptContext acpContext, String sessionId,
                                                  HarnessInput input, AcpSessionContext context,
                                                  AcpSessionConfig.Snapshot snapshot,
                                                  Supplier<Mono<Void>> localReply) {
        final AtomicInteger toolCallCounter = new AtomicInteger(0);
        final AcpTurn turn = new AcpTurn();

        if (activeTurns.putIfAbsent(sessionId, turn) != null) {
            // 一问一答：本会话还有在跑（含等审批）的一轮
            return Mono.error(new AcpProtocolException(
                    AcpErrorCodes.CONCURRENT_PROMPT,
                    "Session already has an active turn: " + sessionId));
        }

        Mono<AcpSchema.PromptResponse> work = localReply != null
                ? Mono.defer(localReply)
                        .thenReturn(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN))
                : host.stream(TENANT, sessionId, input, prepare(context, snapshot))
                .concatMap(signal -> toAcp(signal, acpContext, sessionId, toolCallCounter,
                        context, snapshot, turn))
                .onErrorResume(e -> {
                    // 协议异常透传，保留原错误码；其余异常以消息形式反馈
                    if (e instanceof AcpProtocolException) {
                        return Mono.error(e);
                    }
                    return acpContext.sendMessage("Error: " + e.getMessage()).then(Mono.empty());
                })
                // 只有终态会产出一个 PromptResponse；已取消时等 stopTurn 清理完再放行终态
                .next()
                .switchIfEmpty(Mono.defer(() -> turn.cancelled.get()
                        ? turn.stopped.asMono()
                        : Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN))));

        return Mono.firstWithSignal(turn.stopped.asMono(), work)
                .doFinally(signal -> {
                    // 取消路径的移除与清理由 stopTurn 统一收尾；正常结束在这里收尾。
                    // 先清工具映射再摘活动表：顺序反过来时，摘表后新轮次注册的旧映射
                    // 会被这里的按会话清理误删。
                    if (turn.cancelled.get() == false) {
                        cleanupToolCalls(sessionId);
                        activeTurns.remove(sessionId, turn);
                    }
                });
    }

    /**
     * 本轮准备工作区与推理参数（受理快照里的值，续流决策沿用同一份）。
     */
    private RunSink prepare(AcpSessionContext context, AcpSessionConfig.Snapshot snapshot) {
        final String cwd = context.getCwd();
        return new RunSink() {
            @Override
            public void prepare(RunPrep prep) {
                prep.request().options(o -> {
                    if (Assert.isNotEmpty(cwd)) {
                        o.toolContextPut(HarnessEngine.ATTR_CWD, cwd);
                    }
                    ReasoningSupportUtil.applyToOptions(o, snapshot.thinking, snapshot.effort);
                });
            }
        };
    }

    /** 一个宿主信号 → ACP 输出（只有终态产生 PromptResponse） */
    private Mono<AcpSchema.PromptResponse> toAcp(HostSignal signal, PromptContext acpContext,
                                                 String sessionId,
                                                 AtomicInteger toolCallCounter,
                                                 AcpSessionContext context,
                                                 AcpSessionConfig.Snapshot snapshot,
                                                 AcpTurn turn) {
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
                return toAcpEvent(signal.getEvent(), acpContext, sessionId, toolCallCounter, snapshot, turn)
                        .then(Mono.empty());
            case REJECTED:
                return acpContext.sendMessage(rejectText(signal))
                        .then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.REFUSAL)));
            case DEFERRED:
                // 忙时直接拒绝（BusyPolicy.REJECT），不会进积压；留此分支只为兜底提示
                return acpContext.sendMessage("当前会话正在执行任务，请稍后再发")
                        .then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.REFUSAL)));
            case HITL:
                return resolveHitl(signal.getCards(), acpContext, sessionId,
                        toolCallCounter, context, snapshot, turn);
            case FINISHED:
                if (signal.getOutcome() != null && signal.getOutcome().getHitlCards().isEmpty() == false) {
                    // 终态里还带着挂起卡：与前面的 HITL 信号同一批时不会重复提问
                    // （决策已写或已问过），没有新卡可问就按正常终态收尾
                    return resolveHitl(signal.getOutcome().getHitlCards(), acpContext, sessionId,
                            toolCallCounter, context, snapshot, turn)
                            .switchIfEmpty(finish(signal.getOutcome(), acpContext, sessionId, turn));
                }
                return finish(signal.getOutcome(), acpContext, sessionId, turn);
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
     * 一批挂起卡的审批流程：按本轮快照的审批模式处理。
     *
     * <ul>
     *   <li>{@code reject}：不经客户端，全批按拒绝写入并续流（不绕过权限，方向是拒绝）；</li>
     *   <li>{@code ask}：逐张发 {@code session/request_permission}，收齐整批答复后才统一写决策，
     *       保证「先收到的批准」不会先于整批提交；</li>
     *   <li>答复 cancelled：整轮停止，本批按「拒绝、不续流」落决策，不留挂起屏障；</li>
     *   <li>审批 RPC 出错 / 超时 / 空答复 / 未知选项：一律失败关闭按拒绝处理。</li>
     * </ul>
     *
     * <p>同一轮里已问过（或已决策）的卡不再重复提问：决策未命中（卡过期）时返回 empty 让
     * FINISHED 兜底收尾，避免对同一批卡无限续问。</p>
     */
    private Mono<AcpSchema.PromptResponse> resolveHitl(List<HitlCard> cards, PromptContext acpContext,
                                                       String sessionId,
                                                       AtomicInteger toolCallCounter,
                                                       AcpSessionContext context,
                                                       AcpSessionConfig.Snapshot snapshot,
                                                       AcpTurn turn) {
        List<HitlCard> pending = new ArrayList<>();
        if (cards != null) {
            for (HitlCard card : cards) {
                // 同一轮同一张卡只问一次；重复到达（HITL + FINISHED，或决策未命中）不再提问
                if (card != null && card.isDecided() == false && turn.askedCallIds.add(card.getCallId())) {
                    pending.add(card);
                }
            }
        }
        if (pending.isEmpty()) {
            return Mono.empty();
        }

        if (AcpSessionConfig.MODE_REJECT.equals(snapshot.permissionMode)) {
            List<PermissionOutcome> rejected = new ArrayList<>();
            for (int i = 0; i < pending.size(); i++) {
                rejected.add(PermissionOutcome.REJECT);
            }
            return submitDecisions(pending, rejected, acpContext, sessionId,
                    toolCallCounter, context, snapshot, turn);
        }

        return Flux.fromIterable(pending)
                .concatMap(card -> askPermission(card, acpContext, sessionId, toolCallCounter))
                // 客户端放弃一张卡后不再为同批后续卡提问——整轮在批处理里统一收尾
                .takeUntil(outcome -> outcome == PermissionOutcome.CANCELLED)
                .collectList()
                .flatMap(outcomes -> {
                    if (turn.cancelled.get()) {
                        // 等待 / 收齐答复期间轮次已被取消：已收集的批准一律不落
                        return Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED));
                    }
                    if (outcomes.contains(PermissionOutcome.CANCELLED)) {
                        // 客户端放弃审批：整轮停止；本批按「拒绝、不续流」写决策，不让任何被请求的工具运行
                        return rejectWithoutResume(pending, sessionId, context, snapshot, turn)
                                .then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED)));
                    }
                    return submitDecisions(pending, outcomes, acpContext, sessionId,
                            toolCallCounter, context, snapshot, turn);
                });
    }

    /**
     * 单张挂起卡 → 一次 {@code session/request_permission}。
     *
     * <p>只有 {@code allow-once} 精确命中才批准；reject-once、出错、超时、空答复、
     * 未知选项一律失败关闭按拒绝；{@code cancelled} 单独标记交给批处理停掉整轮。</p>
     */
    private Mono<PermissionOutcome> askPermission(HitlCard card, PromptContext acpContext,
                                                  String sessionId, AtomicInteger toolCallCounter) {
        // 与工具卡 start/end 同一个 toolCallId：callId 映射已登记则复用，否则按同一前缀规则生成
        String key = sessionId + "#" + card.getCallId();
        String toolCallId = callIdToToolCallId.computeIfAbsent(key,
                k -> resolveToolCallId(card.getCallId(), toolCallCounter));

        Map<String, Object> args = card.getArgs();
        AcpSchema.RequestPermissionRequest request = new AcpSchema.RequestPermissionRequest(
                sessionId,
                new AcpSchema.ToolCallUpdate(
                        toolCallId,
                        buildToolTitle(card.getToolName(), null, args, null, true),
                        resolveToolKind(card.getToolName()),
                        AcpSchema.ToolCallStatus.PENDING,
                        Collections.emptyList(),
                        buildLocations(args),
                        args,
                        null),
                Arrays.asList(
                        new AcpSchema.PermissionOption("allow-once", "Allow once",
                                AcpSchema.PermissionOptionKind.ALLOW_ONCE),
                        new AcpSchema.PermissionOption("reject-once", "Reject",
                                AcpSchema.PermissionOptionKind.REJECT_ONCE)));

        return acpContext.requestPermission(request)
                .map(response -> {
                    if (response == null || response.outcome() == null) {
                        return PermissionOutcome.REJECT;
                    }
                    if (response.outcome() instanceof AcpSchema.PermissionCancelled) {
                        return PermissionOutcome.CANCELLED;
                    }
                    if (response.outcome() instanceof AcpSchema.PermissionSelected
                            && "allow-once".equals(((AcpSchema.PermissionSelected) response.outcome()).optionId())) {
                        return PermissionOutcome.APPROVE;
                    }
                    return PermissionOutcome.REJECT;
                })
                .defaultIfEmpty(PermissionOutcome.REJECT)
                .onErrorResume(e -> {
                    LOG.debug("[ACP] permission request failed, fail closed to reject: {} #{}",
                            sessionId, card.getCallId(), e);
                    return Mono.just(PermissionOutcome.REJECT);
                });
    }

    /**
     * 整批决策按序写入：前面的决策只写不续（其流内信号与本批无关，消费完即弃），
     * 最后一个决策带 {@code resume(true)} 尝试续流——续出的那一轮走同一套 ACP 信号映射，
     * 可能再次挂审批，届时递归回本方法。
     */
    private Mono<AcpSchema.PromptResponse> submitDecisions(List<HitlCard> pending,
                                                           List<PermissionOutcome> outcomes,
                                                           PromptContext acpContext, String sessionId,
                                                           AtomicInteger toolCallCounter,
                                                           AcpSessionContext context,
                                                           AcpSessionConfig.Snapshot snapshot,
                                                           AcpTurn turn) {
        // 前面的决策只写不续：逐个按序提交，流内信号与本批无关，消费完即弃
        Mono<Void> applied = Mono.empty();
        for (int i = 0; i < pending.size() - 1; i++) {
            final HitlCard card = pending.get(i);
            final HITLAction action = outcomes.get(i) == PermissionOutcome.APPROVE
                    ? HITLAction.APPROVE : HITLAction.REJECT;
            HarnessInput decision = HarnessInput.of((String) null)
                    .commandAware(false)
                    .agentAware(false)
                    .whenBusy(BusyPolicy.REJECT)
                    .selectedModel(snapshot.model)
                    .hitl(action, card.getCallId())
                    .resume(false);
            // 每条决策订阅时再查取消位：取消后不再往 harness 写任何决策（含批准）
            applied = applied.then(Mono.defer(() -> turn.cancelled.get()
                    ? Mono.<Void>empty()
                    : host.stream(TENANT, sessionId, decision, prepare(context, snapshot)).then()));
        }

        // 最后一个决策带 resume(true) 尝试续流：续出的那一轮走同一套 ACP 信号映射
        final HitlCard lastCard = pending.get(pending.size() - 1);
        final HITLAction lastAction = outcomes.get(pending.size() - 1) == PermissionOutcome.APPROVE
                ? HITLAction.APPROVE : HITLAction.REJECT;
        HarnessInput lastDecision = HarnessInput.of((String) null)
                .commandAware(false)
                .agentAware(false)
                .whenBusy(BusyPolicy.REJECT)
                .selectedModel(snapshot.model)
                .hitl(lastAction, lastCard.getCallId())
                .resume(true);

        Mono<AcpSchema.PromptResponse> tail = Mono.defer(() -> {
            if (turn.cancelled.get()) {
                // 取消后绝不能续流：等 stopTurn 清理完成后的终态信号收口
                return turn.stopped.asMono();
            }
            return host.stream(TENANT, sessionId, lastDecision, prepare(context, snapshot))
                    .concatMap(signal -> toAcp(signal, acpContext, sessionId,
                            toolCallCounter, context, snapshot, turn))
                    .onErrorResume(e -> {
                        if (e instanceof AcpProtocolException) {
                            return Mono.error(e);
                        }
                        return acpContext.sendMessage("Error: " + e.getMessage())
                                .then(Mono.empty());
                    })
                    .next()
                    .switchIfEmpty(Mono.defer(() -> turn.cancelled.get()
                            ? turn.stopped.asMono()
                            : Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN))));
        });

        return applied.then(tail);
    }

    /**
     * 整批按「拒绝、不续流」写入（审批被客户端放弃时收尾用）：决策落在挂起卡上，
     * 不触发续流、也不借助清除手段——屏障的解除由决策本身完成。
     */
    private Mono<Void> rejectWithoutResume(List<HitlCard> pending, String sessionId,
                                             AcpSessionContext context, AcpSessionConfig.Snapshot snapshot,
                                             AcpTurn turn) {
        Mono<Void> chain = Mono.empty();
        for (HitlCard card : pending) {
            HarnessInput decision = HarnessInput.of((String) null)
                    .commandAware(false)
                    .agentAware(false)
                    .whenBusy(BusyPolicy.REJECT)
                    .selectedModel(snapshot.model)
                    .hitl(HITLAction.REJECT, card.getCallId())
                    .resume(false);
            // 取消后不补写决策：剩余挂起卡由 stopTurn 的 rejectAll 清理
            chain = chain.then(Mono.defer(() -> turn.cancelled.get()
                    ? Mono.<Void>empty()
                    : host.stream(TENANT, sessionId, decision, prepare(context, snapshot)).then()));
        }
        return chain;
    }

    /**
     * 客户端取消 / 会话关闭：原子地标记轮次、停流并就地了结全部仍挂在会话上的 HITL 任务
     * （含已写决策但未被消费的部分——取消时不能给下一次输入留屏障），清完才发出终态
     * 并从活动表移除；此后到达的迟到答复找不到轮次，自然失效。
     *
     * @param interrupt 实际的停流动作（会话级或仅停流式，由调用方决定）
     */
    private void stopTurn(String sessionId, Runnable interrupt) {
        AcpTurn turn = activeTurns.get(sessionId);
        if (turn == null || !turn.cancelled.compareAndSet(false, true)) {
            // 已认领过的取消（cancel 后又 close、并发 cancel）不再重复清理
            return;
        }
        try {
            interrupt.run();

            AgentSession session = agentRuntime.getSessionOrNull(sessionId);
            if (session != null && HITL.getPendingTasks(session).isEmpty() == false) {
                try {
                    HITL.rejectAll(session, "Turn cancelled by client");
                } catch (Throwable e) {
                    LOG.warn("[ACP] cleanup pending hitl cards on cancel failed: {}", sessionId, e);
                }
            }
            cleanupToolCalls(sessionId);
        } finally {
            turn.stopped.tryEmitValue(new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED));
            activeTurns.remove(sessionId, turn);
        }
    }

    /** 清掉本轮残留的 callId 映射（正常路径下 end 阶段已 remove，工具 start 后被中断会残留） */
    private void cleanupToolCalls(String sessionId) {
        callIdToToolCallId.keySet().removeIf(k -> k.startsWith(sessionId + "#"));
    }

    /** 本轮终态：正常结束 / 出错 / 被中断（客户端据此区分「答完了」与「被取消」） */
    private Mono<AcpSchema.PromptResponse> finish(RunOutcome outcome, PromptContext acpContext,
                                                  String sessionId, AcpTurn turn) {
        if (turn.cancelled.get()) {
            // 客户端取消统一由 stopTurn 收口：即使 harness 随后按完成/失败收场，
            // 客户端看到的终态也必须是取消清理完成后的 CANCELLED
            return turn.stopped.asMono();
        }

        if (outcome == null) {
            // 骨架保证 FINISHED 带终态；真没有时不能把异常当正常结束
            LOG.warn("[ACP] finished signal without outcome: {}", sessionId);
            return Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
        }
        if (outcome.isCancelled()) {
            // 非客户端取消（harness 自身中断）：直接按取消终态答复
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
                                  String sessionId, AtomicInteger toolCallCounter,
                                  AcpSessionConfig.Snapshot snapshot, AcpTurn turn) {
        // === 上下文占用（usage_update） ===
        if (event instanceof ContextSizeEvent) {
            ContextSizeEvent usage = (ContextSizeEvent) event;
            if (usage.getTokenCount() < 0 || usage.getContextLength() <= 0) {
                return Mono.empty();
            }
            Map<String, Object> meta = Assert.isEmpty(snapshot.model)
                    ? null : Collections.singletonMap("model", snapshot.model);
            return acpContext.sendUpdate(sessionId, new AcpSchema.UsageUpdate(
                    "usage_update", (long) usage.getTokenCount(), usage.getContextLength(), null, meta));
        }
        // === 规划阶段：映射到 ACP Plan 结构化输出 ===
        else if (event instanceof PlanEvent) {
            String content = event.getText();
            AcpSchema.PlanEntry entry = new AcpSchema.PlanEntry(
                    content != null ? content : "Planning...",
                    AcpSchema.PlanEntryPriority.HIGH,
                    AcpSchema.PlanEntryStatus.IN_PROGRESS
            );
            AcpSchema.Plan plan = new AcpSchema.Plan("plan", Collections.singletonList(entry));
            return acpContext.sendUpdate(sessionId, plan);
        }
        // === 推理增量：正文走 message，思考走 thought ===
        else if (event instanceof ReasonDeltaEvent) {
            ReasonDeltaEvent delta = (ReasonDeltaEvent) event;
            ChatEvent chunk = delta.getChatEvent();
            if (chunk == null || !delta.hasText()) {
                return Mono.empty();
            }
            if (chunk.is(ChatEventType.TEXT_DELTA)) {
                turn.selectReason(delta);
                turn.sentText.append(delta.getText());
                return acpContext.sendMessage(delta.getText());
            }
            if (chunk.is(ChatEventType.THINKING_DELTA) && !"off".equals(snapshot.thinking)) {
                turn.selectReason(delta);
                turn.sentThought = true;
                return acpContext.sendThought(delta.getText());
            }
        }
        // === 推理回合结束：未流出过的全量内容兜底 ===
        else if (event instanceof ReasonEndEvent) {
            ReasonEndEvent end = (ReasonEndEvent) event;
            turn.selectReason(end);
            // Full replies are a fallback for providers that emitted no matching deltas.
            Mono<Void> updates = Mono.empty();
            if (!turn.sentThought && !"off".equals(snapshot.thinking) && Assert.isNotEmpty(end.getThinking())) {
                turn.sentThought = true;
                updates = acpContext.sendThought(end.getThinking());
            }
            if (turn.sentText.length() == 0 && end.hasText()) {
                turn.sentText.append(end.getText());
                updates = updates.then(acpContext.sendMessage(end.getText()));
            }
            return updates;
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

            boolean failed = observationChunk.isError();
            Throwable error = observationChunk.getError();
            String content = error == null ? event.getText() : String.valueOf(error.getMessage());
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
        else if (event instanceof RunEndEvent && event.hasText()) {
            RunEndEvent end = (RunEndEvent) event;
            turn.selectReason(end);
            String text = end.getText();
            String sent = turn.sentText.toString();
            if (end.isAbnormal()) {
                // ReAct emits a direct tool result or error as a final delta after any earlier text.
                if (sent.endsWith(text)) {
                    return Mono.empty();
                }
            } else if (text.startsWith(sent)) {
                text = text.substring(sent.length());
            }
            if (Assert.isNotEmpty(text)) {
                return acpContext.sendMessage(text);
            }
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
     * 任务分发工具（task / task_start / task_wait / task_stop）、记忆工具、目标工具。
     */
    private boolean isInternalTool(String toolName) {
        return TaskTalent.isTaskTool(toolName)
                || MemoryTalent.isMemoryTool(toolName)
                || GoalTalent.isGoalTool(toolName);
    }

    /**
     * load / resume 共用的会话认领：只认本端发放的 ID 形态、只取已存在的会话、忙时拒绝。
     *
     * <p>不做 {@code getSession(id)} 式认领：不存在（或已删除）的 ID 不能借恢复路径
     * 凭空建出一个会话来。</p>
     */
    private AgentSession attachSession(String sessionId, String requestedCwd,
                                         List<AcpSchema.McpServer> mcpServers) {
        if (Assert.isEmpty(sessionId) || SESSION_ID_PATTERN.matcher(sessionId).matches() == false) {
            throw new AcpProtocolException(AcpErrorCodes.SESSION_NOT_FOUND,
                    "Session not found: " + sessionId);
        }

        // 只取已存在的（内存或磁盘，不新建）
        AgentSession session = requireExistingSession(sessionId);

        if (activeTurns.containsKey(sessionId) || agentRuntime.status(session).isOccupied()) {
            throw new AcpProtocolException(AcpErrorCodes.CONCURRENT_PROMPT,
                    "Session is busy: " + sessionId);
        }

        String cwd = bindCwd(session, requireCwd(requestedCwd));
        sessionStates.put(sessionId, new AcpSessionContext(cwd, mcpServers));
        return session;
    }

    private AgentSession requireExistingSession(String sessionId) {
        AgentSession session = agentRuntime.getSessionOrNull(sessionId);
        if (session == null) {
            throw new AcpProtocolException(AcpErrorCodes.SESSION_NOT_FOUND,
                    "Session not found: " + sessionId);
        }
        return session;
    }

    /**
     * 会话工作区绑定：已有绑定（本会话此前设过的 cwd）必须与本次请求的规范化目录一致——
     * 不一致按参数错误拒绝，防同一活会话被改挂到另一个目录；没有绑定时才采纳请求里
     * 校验过的目录并写入会话（同步落快照）。
     *
     * @param requestedCwd 已经过 {@link #requireCwd} 规范化的目录（必传）
     * @return 本会话生效的工作区
     */
    private String bindCwd(AgentSession session, String requestedCwd) {
        Object bound = session.attrs().get(HarnessEngine.ATTR_CWD);
        if (bound == null) {
            bound = session.getContext().get(HarnessAttrs.SESSION_CWD);
        }
        if (bound instanceof String && Assert.isNotEmpty((String) bound)) {
            if (!bound.equals(requestedCwd)) {
                throw new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS,
                        "session is bound to a different workspace");
            }
            return (String) bound;
        }

        try {
            agentRuntime.setSessionCwd(session, requestedCwd);
        } catch (SecurityException e) {
            // 引擎工作区策略拒绝：同样是参数错误，不回落
            throw new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS,
                    "cwd rejected by workspace policy: " + requestedCwd);
        }
        // 绑定是会话事实的一部分：随快照持久，重启后仍认得这个工作区
        session.updateSnapshot();
        return requestedCwd;
    }

    /**
     * 客户端工作区校验：cwd 是 ACP 契约里的必传参数，且必须是存在的绝对目录
     * （file:// URI 与 Windows 原生绝对路径同样合法），规范化到真实路径。
     * 缺省 / 非法 / 相对 / 不存在的路径一律按参数错误拒绝——绝不静默回落。
     */
    private String requireCwd(String cwd) {
        if (cwd == null || cwd.trim().isEmpty()) {
            throw new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS,
                    "cwd must be an existing absolute directory");
        }

        try {
            Path p = cwd.startsWith("file:") ? Paths.get(URI.create(cwd)) : Paths.get(cwd);
            if (p.isAbsolute() == false || Files.isDirectory(p) == false) {
                throw new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS,
                        "cwd must be an existing absolute directory");
            }
            return p.toRealPath().toString();
        } catch (AcpProtocolException e) {
            throw e;
        } catch (Exception e) {
            throw new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS,
                    "cwd must be an existing absolute directory");
        }
    }

    private Mono<Void> replayHistory(AcpAsyncAgent agent, AgentSession session) {
        String sessionId = session.getSessionId();
        List<ChatMessage> messages = new ArrayList<>(session.getMessages());
        List<?> steps = latestReplaySteps(session, messages);
        return Flux.range(0, messages.size())
                .concatMap(i -> {
                    Mono<Void> message = replayMessage(agent, sessionId, messages.get(i));
                    return i == messages.size() - 1 && !steps.isEmpty()
                            ? Flux.fromIterable(steps).concatMap(step -> replayTraceStep(agent, sessionId, (Map<?, ?>) step))
                                    .then(message)
                            : message;
                }).then();
    }

    private List<?> latestReplaySteps(AgentSession session, List<ChatMessage> messages) {
        if (messages.size() < 2 || !(messages.get(messages.size() - 1) instanceof AssistantMessage)) {
            return Collections.emptyList();
        }
        AssistantMessage answer = (AssistantMessage) messages.get(messages.size() - 1);
        Object runId = answer.getMetadataAs(AgentTrace.META_RUN_ID);
        if (runId == null || answer.isToolCalls() || Assert.isNotEmpty(answer.getThinking())) {
            return Collections.emptyList();
        }
        // Only augment the plain transcript projection of the latest complete run.
        ChatMessage user = messages.get(messages.size() - 2);
        if (!(user instanceof UserMessage) || !runId.equals(user.getMetadataAs(AgentTrace.META_RUN_ID))) {
            return Collections.emptyList();
        }
        try {
            Map<String, Object> trace = lastTraceService.buildLastTrace(session, false, user.getContent());
            return runId.equals(trace.get("runId")) ? (List<?>) trace.get("events") : Collections.emptyList();
        } catch (RuntimeException e) {
            LOG.debug("[ACP] last-trace replay unavailable for session {}", session.getSessionId(), e);
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private Mono<Void> replayTraceStep(AcpAsyncAgent agent, String sessionId, Map<?, ?> step) {
        String kind = (String) step.get("kind");
        if (LastTraceService.KIND_THINKING.equals(kind)) {
            return agent.sendSessionUpdate(sessionId, new AcpSchema.AgentThoughtChunk(
                    "agent_thought_chunk", new AcpSchema.TextContent((String) step.get("text"))));
        }
        if (LastTraceService.KIND_NOTE.equals(kind)) {
            return agent.sendSessionUpdate(sessionId, new AcpSchema.AgentMessageChunk(
                    "agent_message_chunk", new AcpSchema.TextContent((String) step.get("text"))));
        }
        if (!LastTraceService.KIND_TOOL.equals(kind) || !Boolean.TRUE.equals(step.get("done"))) {
            return Mono.empty();
        }
        String name = (String) step.get("name");
        String result = (String) step.get("result");
        Map<String, Object> args = (Map<String, Object>) step.get("args");
        List<AcpSchema.ToolCallContent> content = Assert.isEmpty(result) ? Collections.emptyList()
                : Collections.singletonList(new AcpSchema.ToolCallContentBlock("content", new AcpSchema.TextContent(result)));
        AcpSchema.ToolCallStatus status = Boolean.TRUE.equals(step.get("isError"))
                ? AcpSchema.ToolCallStatus.FAILED : AcpSchema.ToolCallStatus.COMPLETED;
        return agent.sendSessionUpdate(sessionId, new AcpSchema.ToolCall("tool_call", "tc-" + step.get("callId"),
                (String) step.get("title"), resolveToolKind(name), status, content, buildLocations(args), args, result, null));
    }

    /**
     * load 回放：把存储的历史消息按原序投影成 session/update。
     *
     * <p>用户文本 → user_message_chunk；助手思考 → agent_thought_chunk（不是可见答复）；
     * 助手正文 → agent_message_chunk；工具调用与结果按 ToolCall.uuid 稳定配对（与直播的
     * {@code tc-} 前缀同一口径）；system 消息不回放。</p>
     */
    private Mono<Void> replayMessage(AcpAsyncAgent agent, String sessionId, ChatMessage msg) {
        if (msg instanceof UserMessage) {
            String text = msg.getContent();
            if (Assert.isEmpty(text)) {
                return Mono.empty();
            }
            return agent.sendSessionUpdate(sessionId,
                    new AcpSchema.UserMessageChunk("user_message_chunk", new AcpSchema.TextContent(text)));
        }

        if (msg instanceof AssistantMessage) {
            AssistantMessage am = (AssistantMessage) msg;
            Mono<Void> chain = Mono.empty();
            if (Assert.isNotEmpty(am.getThinking())) {
                chain = chain.then(agent.sendSessionUpdate(sessionId,
                        new AcpSchema.AgentThoughtChunk("agent_thought_chunk",
                                new AcpSchema.TextContent(am.getThinking()))));
            }
            if (Assert.isNotEmpty(am.getText())) {
                chain = chain.then(agent.sendSessionUpdate(sessionId,
                        new AcpSchema.AgentMessageChunk("agent_message_chunk",
                                new AcpSchema.TextContent(am.getText()))));
            }
            if (am.isToolCalls() && am.getToolCalls() != null) {
                for (ToolCall tc : am.getToolCalls()) {
                    if (tc == null || isInternalTool(tc.getName())) {
                        continue;
                    }
                    Map<String, Object> args = tc.getArguments();
                    chain = chain.then(agent.sendSessionUpdate(sessionId, new AcpSchema.ToolCall(
                            "tool_call",
                            "tc-" + tc.getUuid(),
                            buildToolTitle(tc.getName(), null, args, null, false),
                            resolveToolKind(tc.getName()),
                            AcpSchema.ToolCallStatus.COMPLETED,
                            Collections.emptyList(),
                            buildLocations(args),
                            args,
                            null,
                            null)));
                }
            }
            return chain;
        }

        if (msg instanceof ToolMessage) {
            ToolMessage tm = (ToolMessage) msg;
            if (isInternalTool(tm.getName())) {
                return Mono.empty();
            }
            String content = tm.getContent();
            List<AcpSchema.ToolCallContent> blocks = Assert.isEmpty(content)
                    ? Collections.<AcpSchema.ToolCallContent>emptyList()
                    : Collections.singletonList(new AcpSchema.ToolCallContentBlock(
                            "content", new AcpSchema.TextContent(content)));
            return agent.sendSessionUpdate(sessionId, new AcpSchema.ToolCallUpdateNotification(
                    "tool_call_update",
                    "tc-" + tm.getToolCallId(),
                    tm.getName(),
                    resolveToolKind(tm.getName()),
                    tm.isError() ? AcpSchema.ToolCallStatus.FAILED : AcpSchema.ToolCallStatus.COMPLETED,
                    blocks,
                    Collections.emptyList(),
                    null,
                    content,
                    null));
        }

        // system 及其余角色不回放
        return Mono.empty();
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
     */
    HarnessInput toInput(AcpSchema.PromptRequest promptRequest, String cwd) {
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
        return HarnessInput.of(text).attachments(media);
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
                // 协议侧统一用 '/'：Windows 上 relativize 会产出 '\'，客户端按 URI 段解析
                return cwdPath.relativize(fullPath).toString().replace('\\', '/');
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

    /** 一轮的客户端句柄：停止信号 + 防重复审批标记，随轮次结束从 activeTurns 按身份移除 */
    private static final class AcpTurn {
        /** 取消 / 关闭 / 断连时抢先完成的终态（firstWithSignal 的另一路） */
        final Sinks.One<AcpSchema.PromptResponse> stopped = Sinks.one();
        /** 已立起后不再写任何已收集的批准（审批答复可能迟到于取消） */
        final AtomicBoolean cancelled = new AtomicBoolean();
        /** 本轮已提问过的 callId：HITL 信号与 FINISHED 里的同批卡不重复提问 */
        final java.util.Set<String> askedCallIds = ConcurrentHashMap.newKeySet();
        // Accessed only by the serialized event mapper, including approval continuations.
        private String reasonRunId;
        private String reasonTurnId;
        private final StringBuilder sentText = new StringBuilder();
        private boolean sentThought;

        private void selectReason(AbsReActEvent event) {
            if (!Objects.equals(reasonRunId, event.getRunId()) || !Objects.equals(reasonTurnId, event.getTurnId())) {
                reasonRunId = event.getRunId();
                reasonTurnId = event.getTurnId();
                sentText.setLength(0);
                sentThought = false;
            }
        }
    }

    /** 单张挂起卡的审批结论（cancelled 单独成档，交给批处理停掉整轮） */
    private enum PermissionOutcome {
        APPROVE, REJECT, CANCELLED
    }

    /**
     * SDK 建立会话结果 DTO（new / load / resume）没有 configOptions 字段，这里包一层传输：
     * 处理器把选项放进 {@code _meta} 载体键，本类在写出前把它提升为 result 顶层的
     * {@code configOptions}（ACP v1 标准字段）。其余消息原样透传。
     */
    static final class AcpSetupTransport implements AcpAgentTransport {
        private final AcpAgentTransport delegate;

        AcpSetupTransport(AcpAgentTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public Mono<Void> start(Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler) {
            return delegate.start(in -> handler.apply(in).map(AcpSetupTransport::withSetupOptions));
        }

        @Override
        public Mono<Void> closeGracefully() {
            return delegate.closeGracefully();
        }

        @Override
        public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
            return delegate.sendMessage(message);
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return delegate.unmarshalFrom(data, typeRef);
        }

        @Override
        public Mono<Void> awaitTermination() {
            return delegate.awaitTermination();
        }

        @Override
        public void setExceptionHandler(Consumer<Throwable> handler) {
            delegate.setExceptionHandler(handler);
        }

        @Override
        public List<Integer> protocolVersions() {
            return delegate.protocolVersions();
        }

        /**
         * 只改写三种建立会话响应：载体键存在时才提升，其余消息（含同方法名的错误响应）原样放行。
         */
        static AcpSchema.JSONRPCMessage withSetupOptions(AcpSchema.JSONRPCMessage message) {
            if ((message instanceof AcpSchema.JSONRPCResponse) == false) {
                return message;
            }
            AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) message;
            Object result = response.result();

            Map<String, Object> meta;
            Map<String, Object> rewritten = new LinkedHashMap<>();
            if (result instanceof AcpSchema.NewSessionResponse) {
                AcpSchema.NewSessionResponse r = (AcpSchema.NewSessionResponse) result;
                meta = r.meta();
                rewritten.put("sessionId", r.sessionId());
                if (r.modes() != null) {
                    rewritten.put("modes", r.modes());
                }
                if (r.models() != null) {
                    rewritten.put("models", r.models());
                }
            } else if (result instanceof AcpSchema.LoadSessionResponse) {
                AcpSchema.LoadSessionResponse r = (AcpSchema.LoadSessionResponse) result;
                meta = r.meta();
                if (r.modes() != null) {
                    rewritten.put("modes", r.modes());
                }
                if (r.models() != null) {
                    rewritten.put("models", r.models());
                }
            } else if (result instanceof AcpSchema.ResumeSessionResponse) {
                AcpSchema.ResumeSessionResponse r = (AcpSchema.ResumeSessionResponse) result;
                meta = r.meta();
                if (r.modes() != null) {
                    rewritten.put("modes", r.modes());
                }
                if (r.models() != null) {
                    rewritten.put("models", r.models());
                }
            } else {
                return message;
            }

            if (meta == null || meta.containsKey(CONFIG_OPTIONS) == false) {
                return message;
            }

            Map<String, Object> rest = new LinkedHashMap<>(meta);
            rewritten.put(CONFIG_OPTIONS, rest.remove(CONFIG_OPTIONS));
            if (rest.isEmpty() == false) {
                rewritten.put("_meta", rest);
            }

            return new AcpSchema.JSONRPCResponse(response.jsonrpc(), response.id(), rewritten, response.error());
        }
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
