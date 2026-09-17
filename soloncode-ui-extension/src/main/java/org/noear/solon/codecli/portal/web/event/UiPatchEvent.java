package org.noear.solon.codecli.portal.web.event;

import org.noear.solon.ai.agent.AbsAgentEvent;
import org.noear.solon.ai.agent.react.AbsReActEvent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.codecli.portal.web.event.payload.UiPatchPayload;

/**
 * UI 增量更新事件（Agent 侧）：Plugin/Talent 通过 {@code ReActTrace.pushAgentEvent} 发射，
 * 由 {@code WebEventMapper} 映射为 SAEP 2.0 的 {@code ui.patch} WebEvent。
 */
public class UiPatchEvent extends AbsReActEvent {
    private final UiPatchPayload payload;

    public UiPatchEvent(ReActTrace trace, UiPatchPayload payload) {
        super(trace);
        this.payload = payload;
    }

    public UiPatchPayload getPayload() {
        return payload;
    }
}
