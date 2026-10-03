package org.noear.solon.codecli.api.web.event;

import org.noear.solon.ai.agent.AbsAgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.codecli.api.web.event.payload.UiRenderPayload;

/**
 * ui.render 智能体事件（SAEP 2.0 UI 扩展块渲染）。
 *
 * <p>由 WebEventMapper 映射为 {@code WebEvent#ofUiRender} 下发，发前经
 * UiRenderFilter 做 schema 校验、超长截断与样式白名单清洗。</p>
 */
public class UiRenderEvent extends AbsAgentEvent {
    private final UiRenderPayload payload;
    private final String reasonId;

    public UiRenderEvent(String runId, String agentName, AgentSession session,
                         UiRenderPayload payload, String reasonId) {
        super(runId, agentName, session);
        this.payload = payload;
        this.reasonId = reasonId;
    }

    public UiRenderPayload getPayload() {
        return payload;
    }

    public String getReasonId() {
        return reasonId;
    }
}
