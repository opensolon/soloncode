package org.noear.solon.codecli.portal.web.pipeline;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.codecli.portal.web.event.WebEvent;
import org.noear.solon.codecli.portal.web.event.WebEventNames;
import org.noear.solon.codecli.portal.web.event.payload.SystemTracePayload;

/**
 * 负责记录会话级别的 Token 消耗与状态审计
 *
 * @author noear
 */
public class SessionMetricsRecorder {

    private final AgentSession session;

    public SessionMetricsRecorder(AgentSession session) {
        this.session = session;
    }

    public void record(WebEvent<?> event) {
        if (event == null || session == null) {
            return;
        }

        if (WebEventNames.SYSTEM_TRACE.equals(event.getEvent()) && event.getPayload() instanceof SystemTracePayload) {
            SystemTracePayload payload = (SystemTracePayload) event.getPayload();
            if (payload.getTotalTokens() != null) {
                session.attrs().put("_loop_last_total_tokens", payload.getTotalTokens());
            }
        }
    }
}
