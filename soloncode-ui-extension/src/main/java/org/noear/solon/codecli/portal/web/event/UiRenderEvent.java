package org.noear.solon.codecli.portal.web.event;

import org.noear.solon.ai.agent.AbsAgentEvent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.codecli.portal.web.event.payload.UiRenderPayload;

/**
 * UI 渲染事件（Agent 侧）：Plugin/Talent 通过 {@code ReActTrace.pushAgentEvent} 发射，
 * 由 {@code WebEventMapper} 映射为 SAEP 2.0 的 {@code ui.render} WebEvent。
 *
 * <p>构造时复用 trace 的 runId / agentName / session / reasonId，保持与 tool.* 等同级归属。</p>
 */
public class UiRenderEvent extends AbsAgentEvent {
    private final UiRenderPayload payload;
    private final String reasonId;

    public UiRenderEvent(ReActTrace trace, UiRenderPayload payload) {
        this(trace, payload, trace.getLastReasonMessage());
    }

    public UiRenderEvent(ReActTrace trace, UiRenderPayload payload, AssistantMessage message) {
        super(trace.getRunId(), trace.getAgentName(), trace.getSession(), message);
        this.payload = payload;
        this.reasonId = trace.getCurrentReasonId();
    }

    public UiRenderPayload getPayload() {
        return payload;
    }

    public String getReasonId() {
        return reasonId;
    }
}
