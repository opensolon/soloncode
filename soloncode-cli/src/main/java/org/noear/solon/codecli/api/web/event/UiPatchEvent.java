package org.noear.solon.codecli.api.web.event;

import org.noear.solon.ai.agent.AbsAgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.codecli.api.web.event.payload.UiPatchPayload;

/**
 * ui.patch 智能体事件（SAEP 2.0 UI 扩展块增量更新）。
 *
 * <p>由 WebEventMapper 映射为 {@code WebEvent#ofUiPatch} 下发；patch 语义上为端侧
 * 按 JSON Pointer 应用，不做服务端清洗（见 UiRenderFilter 的透传分支）。</p>
 */
public class UiPatchEvent extends AbsAgentEvent {
    private final UiPatchPayload payload;
    private final String reasonId;

    public UiPatchEvent(String runId, String agentName, AgentSession session,
                        UiPatchPayload payload, String reasonId) {
        super(runId, agentName, session);
        this.payload = payload;
        this.reasonId = reasonId;
    }

    public UiPatchPayload getPayload() {
        return payload;
    }

    public String getReasonId() {
        return reasonId;
    }
}
