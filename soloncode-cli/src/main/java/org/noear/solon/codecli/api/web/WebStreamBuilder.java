package org.noear.solon.codecli.api.web;

import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.channel.ImStatus;
import org.noear.solon.codecli.config.entity.GeneralGroupDo;
import org.noear.solon.codecli.api.web.event.WebEvent;
import org.noear.solon.codecli.api.web.pipeline.SessionMetricsRecorder;
import org.noear.solon.ai.talents.lsp.LspCheckState;
import org.noear.solon.codecli.api.web.pipeline.ToolPresentationFilter;
import org.noear.solon.codecli.api.web.pipeline.UiRenderFilter;
import org.noear.solon.codecli.api.web.pipeline.WebEventMapper;
import org.noear.solon.codecli.session.steer.SteerInterceptor;
import org.noear.solon.codecli.util.ReasoningSupportUtil;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RunUtil;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 响应式 Web 流构建器 (SAEP 2.0 管道装配入口)
 *
 * @author noear
 */
@Slf4j
public class WebStreamBuilder {
    private final WebGate webGate;

    public WebStreamBuilder(WebGate webGate) {
        this.webGate = webGate;
    }

    public WebStreamBuilder() {
        this(null);
    }

    /** IM 非流式终态去重标记：一轮只允许一条终态消息（final/error/canceled）落 IM。 */
    public static final String ATTR_IM_TERMINAL_SENT = "session.im.terminal.sent";

    /** 长任务心跳定时器句柄（终态时取消）。 */
    private static final String ATTR_IM_HEARTBEAT = "session.im.heartbeat";

    /** 本轮忙态命令引导是否已提示过：同一轮只教学一次，避免连发多条时重复刷屏。 */
    private static final String ATTR_IM_CMD_HINT_SHOWN = "session.im.cmdHint.shown";

    /** 受理后多久仍无终态，就向来源端补一条「仍在处理」。 */
    private static final long LONG_RUNNING_DELAY_MS = 60_000L;

    /** 去掉终端 ANSI 转义序列（IM 无法渲染，命令回执如 /model 会带）。 */
    public static String stripAnsi(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "");
    }

    /**
     * 终态消息（最终答复 / 失败 / 取消）：广播到所有绑定该会话的 IM 通道。
     *
     * <p>web 与 IM 都是同一 session 的输出端，终态必须全端一致。入站来源通道带上定向参数
     * （群聊 / 消息线程回复），其它通道（含 WEB/Loop 发起时 route 为空）退回到绑定用户
     * （binding.openId）。一轮只投递一条终态，避免 final 与 error 重复刷屏。</p>
     */
    public void replyToBoundChannel(WorkspaceContext wsContext, String sessionId, String text, boolean isFinal) {
        if (sessionId == null) return;
        AgentSession session = wsContext.getEngine().getSession(sessionId);
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> route = session == null ? null
                : (java.util.Map<String, String>) session.attrs().get("session.replyRoute");

        // 多终端（web/im）同步：无论本轮由谁发起（IM / WEB / Loop），都把终态广播到
        // 所有绑定该会话的 IM 通道。入站来源通道带上定向参数（群聊/消息线程回复），
        // 其它通道（含 WEB/Loop 发起时 route 为空的情况）退回到绑定用户（binding.openId）。
        String routeSource = route == null ? null : route.get("source");
        String sourceUserId = route == null ? null : route.get("sourceUserId");
        String replyTarget = route == null ? null : route.get("replyTarget");
        String messageId = route == null ? null : route.get("messageId");

        boolean allowSend = true;
        if (isFinal && session != null) {
            // 终态去重门：final / error / canceled 共用，保证 IM 每轮只收一条终态。
            Object prev = session.attrs().put(ATTR_IM_TERMINAL_SENT, Boolean.TRUE);
            allowSend = !Boolean.TRUE.equals(prev);
            // 本轮已收尾，停掉长任务心跳（若尚未触发）。
            cancelLongRunningWatch(session);
            // 本轮已收尾，忙态命令引导标记复位：下一轮忙态重新教学一次。
            resetCommandHint(session);
        }

        if (allowSend) {
            String imText = stripAnsi(text);
            for (Channel link : wsContext.getChannelHub().getImLinks()) {
                if (!link.isBound(sessionId)) {
                    continue;
                }
                if (routeSource != null && routeSource.equalsIgnoreCase(link.getChannelName())) {
                    link.sendReply(sessionId, imText, isFinal, sourceUserId, replyTarget, messageId);
                } else {
                    link.sendReply(sessionId, imText, isFinal);
                }
            }
        }

        if (isFinal && route != null && session != null) {
            session.attrs().remove("session.replyRoute", route);
        }
    }

    /**
     * 过程消息（ReasonEndEvent）：只投递给发起本轮的那个 IM 通道。
     *
     * <p>IM 不接收流式分片，过程消息是它唯一能拿到的中间产物；但它属于「噪音」，
     * 只回来源端，避免 web / 其它 IM 端被同一段过程刷屏。WEB/Loop 发起时 route 为空，
     * 过程消息不下发任何 IM。</p>
     */
    public void replyPartialToOriginChannel(WorkspaceContext wsContext, String sessionId, String text) {
        if (sessionId == null || Assert.isEmpty(text)) return;
        AgentSession session = wsContext.getEngine().getSession(sessionId);
        @SuppressWarnings("unchecked")
        Map<String, String> route = session == null ? null
                : (Map<String, String>) session.attrs().get("session.replyRoute");
        if (route == null) {
            return;
        }
        String routeSource = route.get("source");
        if (routeSource == null) {
            return;
        }
        String imText = stripAnsi(text);
        for (Channel link : wsContext.getChannelHub().getImLinks()) {
            if (link.isBound(sessionId) && routeSource.equalsIgnoreCase(link.getChannelName())) {
                link.sendReply(sessionId, imText, false,
                        route.get("sourceUserId"), route.get("replyTarget"), route.get("messageId"));
                return;
            }
        }
    }

    /** 失败终态：以非流式终态语义同步到 IM（与 final 共用去重门与投递通道）。 */
    public void replyErrorToBoundChannels(WorkspaceContext wsContext, AgentSession session, Throwable e) {
        if (session == null) return;
        String msg = e == null ? null : e.getMessage();
        if (Assert.isEmpty(msg)) {
            msg = e == null ? "未知错误" : e.getClass().getSimpleName();
        }
        msg = stripAnsi(msg).replaceAll("[\\r\\n]+", " ").trim();
        if (msg.length() > 200) {
            msg = msg.substring(0, 200) + "...";
        }
        replyToBoundChannel(wsContext, session.getSessionId(), "任务执行失败：" + msg, true);
    }

    /** 取消终态：与失败同路，保证 IM 端不会停在「已读不回」。 */
    public void replyCanceledToBoundChannels(WorkspaceContext wsContext, AgentSession session) {
        if (session == null) return;
        replyToBoundChannel(wsContext, session.getSessionId(), "用户已取消任务。", true);
    }

    /** 命令在已有任务运行时使用自己的回复目标，不覆盖运行任务的路由。 */
    public void replyToBoundChannel(WorkspaceContext wsContext, String sessionId, String text, boolean isFinal,
                                    String source, String sourceUserId, String replyTarget, String messageId) {
        if (sessionId == null || source == null) return;
        if (isFinal) {
            cancelLongRunningWatch(wsContext.getEngine() == null ? null : wsContext.getEngine().getSession(sessionId));
        }
        String imText = stripAnsi(text);
        for (Channel link : wsContext.getChannelHub().getImLinks()) {
            if (link.isBound(sessionId) && source.equalsIgnoreCase(link.getChannelName())) {
                link.sendReply(sessionId, imText, isFinal, sourceUserId, replyTarget, messageId);
            }
        }
    }

    /**
     * 交互状态信号：只投递给发起本轮的那个 IM 通道。
     *
     * <p>IM 的感知远不如 web（看不到排队、loading）。这些信号属于「状态」而非「会话内容」，
     * 不参与历史、不走流式（IM 只收非流式的 ReasonEndEvent / RunEndEvent）。
     * WEB/Loop 发起时没有匹配通道，自然不下发任何 IM。</p>
     */
    public void signalOriginChannel(WorkspaceContext wsContext, String sessionId, ImStatus status, String detail,
                                    String source, String sourceUserId, String replyTarget, String messageId) {
        if (sessionId == null || source == null || status == null || wsContext == null) return;
        if (wsContext.getChannelHub() == null) return;
        for (Channel link : wsContext.getChannelHub().getImLinks()) {
            if (link.isBound(sessionId) && source.equalsIgnoreCase(link.getChannelName())) {
                link.sendStatus(sessionId, status, detail, sourceUserId, replyTarget, messageId);
                if (status == ImStatus.ACCEPTED) {
                    AgentSession session = wsContext.getEngine() == null ? null
                            : wsContext.getEngine().getSession(sessionId);
                    // 新一轮从空闲开始：重置引导标记，下轮忙态重新教学一次。
                    resetCommandHint(session);
                    // 受理即挂长任务心跳；终态投递时取消。
                    startLongRunningWatch(wsContext, sessionId, source, sourceUserId, replyTarget, messageId);
                }
                return;
            }
        }
    }

    /**
     * 取用「本轮忙态命令引导」的提示机会：首次调用返回 true 并置位，其后返回 false。
     *
     * <p>同一轮忙态里用户可能连发多条，命令引导（/steer、/interrupt）只随第一条下发，
     * 后续只报位次，避免把回执刷成教学广告。标记在本轮终态或下一轮受理时复位。</p>
     */
    public boolean claimCommandHint(AgentSession session) {
        if (session == null) {
            return true;
        }
        Object prev = session.attrs().put(ATTR_IM_CMD_HINT_SHOWN, Boolean.TRUE);
        return !Boolean.TRUE.equals(prev);
    }

    private void resetCommandHint(AgentSession session) {
        if (session == null) return;
        session.attrs().remove(ATTR_IM_CMD_HINT_SHOWN);
    }

    /**
     * 长任务心跳：受理后超过 {@link #LONG_RUNNING_DELAY_MS} 仍无终态，补一条「仍在处理」提醒，
     * 避免用户在长任务期间以为消息丢了。只触发一次，终态投递时取消。
     */
    private void startLongRunningWatch(WorkspaceContext wsContext, String sessionId, String source,
                                       String sourceUserId, String replyTarget, String messageId) {
        AgentSession session = wsContext.getEngine() == null ? null : wsContext.getEngine().getSession(sessionId);
        if (session == null) return;
        cancelLongRunningWatch(session);
        try {
            ScheduledFuture<?> future = RunUtil.timer().schedule(
                    () -> signalOriginChannel(wsContext, sessionId, ImStatus.LONG_RUNNING, null,
                            source, sourceUserId, replyTarget, messageId),
                    LONG_RUNNING_DELAY_MS, TimeUnit.MILLISECONDS);
            session.attrs().put(ATTR_IM_HEARTBEAT, future);
        } catch (Throwable e) {
            log.debug("IM long-running watch schedule failed: {}", e.getMessage());
        }
    }

    private void cancelLongRunningWatch(AgentSession session) {
        if (session == null) return;
        Object value = session.attrs().remove(ATTR_IM_HEARTBEAT);
        if (value instanceof Future) {
            ((Future<?>) value).cancel(false);
        }
    }

    /**
     * LSP 检查状态查询：纯查表（最近一次写入后的结论 + 启用状态/扩展名匹配），不起进程。
     *
     * <p>让 Web UI 能区分三件不同的事：已检查且无错误、已请求但未拿到结论（冷启动索引中）、
     * 以及根本没有语言服务器覆盖。把后两者混为一谈会让界面给出比实际更强的确定性保证。
     */
    private Function<String, LspCheckState> buildLspState(WorkspaceContext wsContext) {
        return filePath -> {
            try {
                HarnessEngine engine = wsContext.getEngine();
                if (engine == null || engine.getLspTalent() == null) {
                    return LspCheckState.NONE;
                }
                if (engine.getLspTalent().isEnabled() == false) {
                    return LspCheckState.NONE;
                }

                LspCheckState state = engine.getLspTalent().getFileCheckState(filePath);
                if (state != LspCheckState.NONE) {
                    return state;
                }

                //没有记录（如诊断钩子未走到）：退回到覆盖判定，有服务器但无结论就是 PENDING
                boolean covered = engine.getLspTalent().getLspManager().hasClientFor(filePath);
                return covered ? LspCheckState.PENDING : LspCheckState.NONE;
            } catch (Throwable e) {
                return LspCheckState.NONE;
            }
        };
    }

    /**
     * 构建 SAEP 2.0 响应式事件流
     */
    public Flux<WebEvent<?>> buildStreamFlux(WorkspaceContext wsContext, AgentSession session, ReActAgent agent, ChatModel chatModel, String sessionCwd, Prompt prompt) {
        if (prompt == null) {
            prompt = Prompt.of();
        }

        if ("/resume".equals(prompt.getUserContent())) {
            prompt = Prompt.of();
        }

        session.attrs().put("_agent_selected_tmp", agent.name());

        GeneralGroupDo general = wsContext.getSettings() == null ? null : wsContext.getSettings().getGeneral();
        String globalThinking = general == null ? null : general.getDefaultThinkingMode();
        String globalEffort = general == null ? null : general.getDefaultReasoningEffort();

        //会话显式（含手动选 auto）> 全局默认（设置→通用）> 模型 defaultOptions > 供应商
        String sessionThinkingMode = ReasoningSupportUtil.resolveSessionThinkingOrDefault(session, globalThinking);
        ReasoningSupportUtil.ModelCapability cap = null;
        try {
            ChatConfig fullConfig = null;
            if (chatModel != null && chatModel.getConfig() != null) {
                String key = chatModel.getConfig().getNameOrModel();
                if (Assert.isNotEmpty(key)) {
                    fullConfig = wsContext.getEngine().getModelOrNil(key);
                    if (fullConfig == null) {
                        fullConfig = ReasoningSupportUtil.findEngineConfig(wsContext.getEngine().getModels(), key);
                    }
                }
                if (fullConfig == null && Assert.isNotEmpty(chatModel.getConfig().getModel())) {
                    fullConfig = wsContext.getEngine().getModelOrNil(chatModel.getConfig().getModel());
                    if (fullConfig == null) {
                        fullConfig = ReasoningSupportUtil.findEngineConfig(
                                wsContext.getEngine().getModels(), chatModel.getConfig().getModel());
                    }
                }
                if (fullConfig == null && Assert.isNotEmpty(chatModel.getConfig().getName())) {
                    fullConfig = wsContext.getEngine().getModelOrNil(chatModel.getConfig().getName());
                }
            }
            if (fullConfig != null) {
                cap = ReasoningSupportUtil.resolveCapability(fullConfig);
            } else if (chatModel != null && chatModel.getConfig() != null) {
                cap = ReasoningSupportUtil.resolveCapability(
                        chatModel.getConfig().getName(),
                        chatModel.getConfig().getModel(),
                        chatModel.getConfig().getStandardOrProvider(),
                        null);
            }
        } catch (Throwable ignored) {
        }
        final String effectiveEffort = ReasoningSupportUtil.resolveSessionEffortOrDefault(
                session, globalEffort, cap);
        ReasoningSupportUtil.applyToPrompt(prompt, sessionThinkingMode, effectiveEffort);

        WebEventMapper mapper = new WebEventMapper(this, wsContext, session, chatModel);
        ToolPresentationFilter toolFilter = new ToolPresentationFilter(buildLspState(wsContext));
        UiRenderFilter uiRenderFilter = new UiRenderFilter();
        SessionMetricsRecorder metricsRecorder = new SessionMetricsRecorder(session);
        // 运行中插话（steer）拦截器：请求级挂载，LinkedHashMap 插入序保证排在默认拦截器（含上下文压缩）之后
        SteerInterceptor steerInterceptor = new SteerInterceptor(webGate, wsContext);

        final Flux<AgentEvent> stream = agent.prompt(prompt)
                .session(session)
                .options(o -> {
                    o.chatModel(chatModel);
                    ReasoningSupportUtil.applyToOptions(o, sessionThinkingMode, effectiveEffort);
                    o.interceptorAdd(steerInterceptor);

                    if (Assert.isNotEmpty(sessionCwd)) {
                        o.toolContextPut(HarnessEngine.ATTR_CWD, sessionCwd);
                    }
                })
                .stream();

        return   stream
                .flatMap(event -> Flux.fromIterable(mapper.mapEvent(event)))
                .filter(WebEvent::isNotEmpty)
                .map(toolFilter::apply)
                .map(uiRenderFilter::apply)
                .doOnNext(metricsRecorder::record)
                .onErrorResume(e -> {
                    log.error("Stream execution error", e);
                    //IM 与 web 是同一 session 的输出端：流式 error 仅 web 可见，
                    //这里再补一条非流式终态给 IM，否则 IM 端会永久停在「已读不回」。
                    replyErrorToBoundChannels(wsContext, session, e);
                    //只发 error：done 统一由订阅侧 doFinally 走 emitDoneOnce 去重门发出。
                    //此处再拼一个 ofDone 会绕过去重门直推给前端，造成同一轮双 done。
                    return Flux.just(WebEvent.ofError(e));
                });
    }
}
