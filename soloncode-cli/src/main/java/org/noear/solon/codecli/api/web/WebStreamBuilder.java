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
import org.noear.solon.codecli.channel.ChannelReply;
import org.noear.solon.codecli.channel.feishu.FeishuLink;
import org.noear.solon.codecli.channel.dingtalk.DingTalkLink;

import org.noear.solon.codecli.artifact.ImageReplyTurn;
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
    public static final String ATTR_IM_TERMINAL_SENT = ImageReplyTurn.ATTR_IM_TERMINAL_SENT;

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
        prepareTextReply(wsContext, sessionId, text, isFinal, session, null).run();
    }

    /** 锁内领取终态并冻结路由；返回的动作只能在锁外执行。expected 隔离旧请求的错误。 */
    @SuppressWarnings("unchecked")
    private Runnable prepareTextReply(WorkspaceContext context, String sessionId, String text,
                                      boolean isFinal, AgentSession session, ImageReplyTurn expected) {
        String imText = stripAnsi(text);
        if (session == null) {
            return () -> sendTextReply(context, sessionId, imText, isFinal, null);
        }
        synchronized (session.attrs()) {
            if (expected != null && !expected.isCurrent()) return () -> { };
            ImageReplyTurn turn = (ImageReplyTurn) session.attrs().get(ImageReplyTurn.ATTR);
            Object originalRoute = session.attrs().get("session.replyRoute");
            Map<String, String> route = turn != null ? turn.getRoute()
                    : originalRoute instanceof Map ? java.util.Collections.unmodifiableMap(
                            new java.util.HashMap<>((Map<String, String>) originalRoute))
                    : java.util.Collections.emptyMap();
            if (isFinal) {
                // 文本终态立即封闭本轮图片和迟到 RunEnd，与成功终态共用去重门。
                if (turn != null) turn.closeTerminal();
                if (Boolean.TRUE.equals(session.attrs().put(ATTR_IM_TERMINAL_SENT, Boolean.TRUE))) {
                    return () -> { };
                }
                cancelLongRunningWatch(session);
                resetCommandHint(session);
            }
            Runnable send = () -> sendTextReply(context, sessionId, imText, isFinal, route);
            return isFinal ? prepareDelivery(session, turn, originalRoute, send) : send;
        }
    }

    /** 延迟发送只消费已领取的快照，不再读写终态门；清理时按身份保护新请求。 */
    private Runnable prepareDelivery(AgentSession session, ImageReplyTurn turn, Object originalRoute,
                                     Runnable send) {
        java.util.concurrent.atomic.AtomicBoolean sent = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (!sent.compareAndSet(false, true)) return;
            try {
                send.run();
            } finally {
                synchronized (session.attrs()) {
                    if (turn != null) turn.clearRoute();
                    else if (session.attrs().get("session.replyRoute") == originalRoute) {
                        session.attrs().remove("session.replyRoute");
                    }
                }
            }
        };
    }

    /** 仅由顶层 RunEnd 调用；成功与 isFinal 是两个不同的条件。 */
    public void replyRunEnd(WorkspaceContext context, AgentSession session, ImageReplyTurn turn,
                            String runId, String text, boolean abnormal) {
        synchronized (session.attrs()) {
            if (!turn.canClaim(runId)
                    || Boolean.TRUE.equals(session.attrs().get(ATTR_IM_TERMINAL_SENT))) return;
        }
        // 能力查询、Markdown 解析、路径验证、读取和解码均不得持有 attrs 锁。
        // 纯 Web、未绑定或仅绑定文本 IM 时，只生成安全占位，不准备本地图片。
        ChannelReply prepared = abnormal
                ? ChannelReply.fromMarkdown("任务执行异常结束。", true, runId, null, null, false)
                : hasBoundImageChannel(context, session.getSessionId())
                    ? prepareRunEndReply(turn, stripAnsi(text), runId)
                    : ChannelReply.fromMarkdown(stripAnsi(text), true, runId, null, null, false);
        Runnable delivery;
        synchronized (session.attrs()) {
            // 准备期间可能取消、异常、HITL 或切换请求；只有当前轮能领取终态。
            if (!turn.canClaim(runId)
                    || Boolean.TRUE.equals(session.attrs().get(ATTR_IM_TERMINAL_SENT))
                    || !turn.claim(runId)) return;
            ChannelReply reply = turn.isImagesAllowed() ? prepared : prepared.withoutImages();
            // 成功终态在此提交交付；后续取消不撤销已领取的快照或渠道排队图片。
            // 取消仍按 Web 既有流程处理，本轮不引入可撤销发送队列。
            session.attrs().put(ATTR_IM_TERMINAL_SENT, Boolean.TRUE);
            cancelLongRunningWatch(session);
            resetCommandHint(session);
            Map<String, String> route = turn.getRoute();
            String sessionId = session.getSessionId();
            delivery = prepareDelivery(session, turn, null, () -> {
                for (Channel link : context.getChannelHub().getImLinks()) {
                    if (!link.isBound(sessionId)) continue;
                    boolean origin = link.getChannelName().equalsIgnoreCase(route.get("source"));
                    try {
                        link.sendReply(sessionId, reply,
                                origin ? route.get("sourceUserId") : null,
                                origin ? route.get("replyTarget") : null,
                                origin ? route.get("messageId") : null);
                    } catch (RuntimeException e) {
                        // 一个渠道失败不能阻止其它绑定端收尾，且不重新投递已成功渠道。
                        log.warn("Channel final reply failed: {}", link.getChannelName(), e);
                    }
                }
            });
        }
        delivery.run();
    }

    /** 只读查询实际绑定的图片接收端；未绑定渠道的能力不构成读取本地文件的许可。 */
    private boolean hasBoundImageChannel(WorkspaceContext context, String sessionId) {
        for (Channel link : context.getChannelHub().getImLinks()) {
            // 仅纳入现有支持本地图片的实现；未来有统一能力接口后再替换此有限判断。
            if (link.isBound(sessionId) && (link instanceof FeishuLink || link instanceof DingTalkLink)) return true;
        }
        return false;
    }

    /** 仅有绑定图片 IM 时在锁外准备最终 Markdown 快照，与终态领取分离。 */
    protected ChannelReply prepareRunEndReply(ImageReplyTurn turn, String text, String runId) {
        return turn.prepare(text, runId);
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
        prepareErrorReply(wsContext, session, null, e).run();
    }

    private Runnable prepareErrorReply(WorkspaceContext context, AgentSession session,
                                       ImageReplyTurn expected, Throwable e) {
        String msg = e == null ? null : e.getMessage();
        if (Assert.isEmpty(msg)) {
            msg = e == null ? "未知错误" : e.getClass().getSimpleName();
        }
        msg = stripAnsi(msg).replaceAll("[\\r\\n]+", " ").trim();
        if (msg.length() > 200) {
            msg = msg.substring(0, 200) + "...";
        }
        return prepareTextReply(context, session.getSessionId(), "任务执行失败：" + msg,
                true, session, expected);
    }

    /**
     * 锁内先领取取消终态，持久化后在锁外投递。
     * 回执只保留本轮路由快照，不会在延迟执行时读取或改写新一轮的终态门。
     */
    Runnable prepareCanceledReply(WorkspaceContext context, AgentSession session) {
        return prepareTextReply(context, session.getSessionId(), "用户已取消任务。", true, session, null);
    }

    private void sendTextReply(WorkspaceContext context, String sessionId, String text, boolean isFinal,
                               Map<String, String> route) {
        String source = route == null ? null : route.get("source");
        String imText = stripAnsi(text);
        for (Channel link : context.getChannelHub().getImLinks()) {
            try {
                if (!link.isBound(sessionId)) continue;
                if (source != null && source.equalsIgnoreCase(link.getChannelName())) {
                    link.sendReply(sessionId, imText, isFinal,
                            route.get("sourceUserId"), route.get("replyTarget"), route.get("messageId"));
                } else {
                    link.sendReply(sessionId, imText, isFinal);
                }
            } catch (RuntimeException e) {
                // 单个渠道的失败不能阻断其它绑定端的文本终态。
                log.warn("Channel text reply failed", e);
            }
        }
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
    public Flux<WebEvent<?>> buildStreamFlux(WorkspaceContext wsContext, AgentSession session, ReActAgent targetAgent, ChatModel targetModel, String sessionCwd, Prompt prompt) {
        if (prompt == null) {
            prompt = Prompt.of();
        }

        if ("/resume".equals(prompt.getUserContent())) {
            prompt = Prompt.of();
        }

        session.attrs().put("_agent_selected_tmp", targetAgent.name());

        HarnessEngine engine = wsContext.getEngine();
        GeneralGroupDo general = wsContext.getSettings() == null ? null : wsContext.getSettings().getGeneral();
        String globalThinking = general == null ? null : general.getDefaultThinkingMode();
        String globalEffort = general == null ? null : general.getDefaultReasoningEffort();

        //会话显式（含手动选 auto）> 全局默认（设置→通用）> 模型 defaultOptions > 供应商
        String sessionThinkingMode = ReasoningSupportUtil.resolveSessionThinkingOrDefault(session, globalThinking);
        ReasoningSupportUtil.ModelCapability cap = null;
        try {
            ChatConfig fullConfig = null;
            if (targetModel != null && targetModel.getConfig() != null) {
                String key = targetModel.getConfig().getNameOrModel();
                if (Assert.isNotEmpty(key)) {
                    fullConfig = engine.getModelOrNil(key);
                    if (fullConfig == null) {
                        fullConfig = ReasoningSupportUtil.findEngineConfig(engine.getModels(), key);
                    }
                }
                if (fullConfig == null && Assert.isNotEmpty(targetModel.getConfig().getModel())) {
                    fullConfig = engine.getModelOrNil(targetModel.getConfig().getModel());
                    if (fullConfig == null) {
                        fullConfig = ReasoningSupportUtil.findEngineConfig(
                                engine.getModels(), targetModel.getConfig().getModel());
                    }
                }
                if (fullConfig == null && Assert.isNotEmpty(targetModel.getConfig().getName())) {
                    fullConfig = engine.getModelOrNil(targetModel.getConfig().getName());
                }
            }
            if (fullConfig != null) {
                cap = ReasoningSupportUtil.resolveCapability(fullConfig);
            } else if (targetModel != null && targetModel.getConfig() != null) {
                cap = ReasoningSupportUtil.resolveCapability(
                        targetModel.getConfig().getName(),
                        targetModel.getConfig().getModel(),
                        targetModel.getConfig().getStandardOrProvider(),
                        null);
            }
        } catch (Throwable ignored) {
        }

        final String effectiveEffort = ReasoningSupportUtil.resolveSessionEffortOrDefault(
                session, globalEffort, cap);
        ReasoningSupportUtil.applyToPrompt(prompt, sessionThinkingMode, effectiveEffort);

        ImageReplyTurn imageTurn = new ImageReplyTurn(session, wsContext.getMeta().getPath(), sessionCwd);
        try {
            WebEventMapper mapper = new WebEventMapper(this, wsContext, session, targetModel, imageTurn);
            ToolPresentationFilter toolFilter = new ToolPresentationFilter(buildLspState(wsContext));
            UiRenderFilter uiRenderFilter = new UiRenderFilter();
            SessionMetricsRecorder metricsRecorder = new SessionMetricsRecorder(session);
            // 运行中插话（steer）拦截器：请求级挂载，LinkedHashMap 插入序保证排在默认拦截器（含上下文压缩）之后
            SteerInterceptor steerInterceptor = new SteerInterceptor(webGate, wsContext);

            final Flux<AgentEvent> stream = targetAgent.prompt(prompt)
                    .session(session)
                    .options(o -> {
                        if (targetModel != null) {
                            o.chatModel(targetModel);
                        }

                        ReasoningSupportUtil.applyToOptions(o, sessionThinkingMode, effectiveEffort);
                        o.interceptorAdd(steerInterceptor);
                        o.interceptorAdd(imageTurn);


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
                        prepareErrorReply(wsContext, session, imageTurn, e).run();
                        //只发 error：done 统一由订阅侧 doFinally 走 emitDoneOnce 去重门发出。
                        //此处再拼一个 ofDone 会绕过去重门直推给前端，造成同一轮双 done。
                        return Flux.just(WebEvent.ofError(e));
                    })
                    .doFinally(signal -> imageTurn.cleanup());
        } catch (RuntimeException | Error e) {
            // stream() 装配期也可能同步失败，此时尚无 doFinally。
            try {
                prepareErrorReply(wsContext, session, imageTurn, e).run();
            } finally {
                imageTurn.cleanup();
            }
            throw e;
        }
    }
}
