package org.noear.solon.codecli.portal.web;

import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.portal.web.event.WebEvent;
import org.noear.solon.codecli.portal.web.pipeline.ChannelBroadcastSink;
import org.noear.solon.codecli.portal.web.pipeline.SessionMetricsRecorder;
import org.noear.solon.ai.talents.lsp.LspCheckState;
import org.noear.solon.codecli.portal.web.pipeline.ToolPresentationFilter;
import org.noear.solon.codecli.portal.web.pipeline.UiRenderFilter;
import org.noear.solon.codecli.portal.web.pipeline.WebEventMapper;
import org.noear.solon.codecli.util.ReasoningSupportUtil;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.core.util.Assert;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 响应式 Web 流构建器 (SAEP 2.0 管道装配入口)
 *
 * @author noear
 */
@Slf4j
public class WebStreamBuilder {
    private final WebGate webGate;
    private final ChannelBroadcastSink broadcastSink = new ChannelBroadcastSink();

    public WebStreamBuilder(WebGate webGate) {
        this.webGate = webGate;
    }

    public WebStreamBuilder() {
        this(null);
    }

    public void replyToBoundChannel(WorkspaceContext wsContext, String sessionId, String text, boolean isFinal) {
        broadcastSink.replyToBoundChannel(wsContext.getChannelHub(), sessionId, text, isFinal);
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

        String sessionEffort = ReasoningSupportUtil.getSessionEffort(session);
        String sessionThinkingMode = ReasoningSupportUtil.getSessionThinkingMode(session);
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
        final String effectiveEffort = ReasoningSupportUtil.resolveEffectiveEffort(
                null, sessionEffort, cap, false);
        ReasoningSupportUtil.applyToPrompt(prompt, sessionThinkingMode, effectiveEffort);

        WebEventMapper mapper = new WebEventMapper(this, wsContext, session, chatModel);
        ToolPresentationFilter toolFilter = new ToolPresentationFilter(buildLspState(wsContext));
        UiRenderFilter uiRenderFilter = new UiRenderFilter();
        SessionMetricsRecorder metricsRecorder = new SessionMetricsRecorder(session);
        // 运行中插话（steer）拦截器：请求级挂载，LinkedHashMap 插入序保证排在默认拦截器（含上下文压缩）之后
        SteerInterceptor steerInterceptor = new SteerInterceptor(webGate, wsContext);

        return agent.prompt(prompt)
                .session(session)
                .options(o -> {
                    o.chatModel(chatModel);
                    ReasoningSupportUtil.applyToOptions(o, sessionThinkingMode, effectiveEffort);
                    o.interceptorAdd(steerInterceptor);

                    if (Assert.isNotEmpty(sessionCwd)) {
                        o.toolContextPut(HarnessEngine.ATTR_CWD, sessionCwd);
                    }
                })
                .stream()
                .flatMap(event -> Flux.fromIterable(mapper.mapEvent(event)))
                .filter(WebEvent::isNotEmpty)
                .map(toolFilter::apply)
                .map(uiRenderFilter::apply)
                .doOnNext(event -> broadcastSink.broadcast(wsContext.getChannelHub(), event))
                .doOnNext(metricsRecorder::record)
                .onErrorResume(e -> {
                    log.error("Stream execution error", e);
                    //只发 error：done 统一由订阅侧 doFinally 走 emitDoneOnce 去重门发出。
                    //此处再拼一个 ofDone 会绕过去重门直推给前端，造成同一轮双 done。
                    return Flux.just(WebEvent.ofError(e));
                });
    }
}
