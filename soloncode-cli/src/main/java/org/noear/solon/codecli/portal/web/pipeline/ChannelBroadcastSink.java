package org.noear.solon.codecli.portal.web.pipeline;

import org.noear.solon.codecli.channel.Channel;
import org.noear.solon.codecli.channel.ChannelHub;
import org.noear.solon.codecli.portal.web.event.WebEvent;
import org.noear.solon.codecli.portal.web.event.WebEventNames;
import org.noear.solon.codecli.portal.web.event.payload.MessagePayload;

/**
 * 将文本片段异步/同步广播到绑定的微信/飞书等 IM 渠道
 *
 * @author noear
 */
public class ChannelBroadcastSink {
    public void broadcast(ChannelHub channelHub, WebEvent<?> event) {
        if (event == null) {
            return;
        }

        if (!WebEventNames.MESSAGE_DELTA.equals(event.getEvent())) {
            return;
        }

        if (!(event.getPayload() instanceof MessagePayload)) {
            return;
        }

        MessagePayload payload = (MessagePayload) event.getPayload();
        String delta = payload.getDelta();
        String sessionId = event.getSessionId();

        if (delta != null && !delta.isEmpty() && sessionId != null) {
            for (Channel link : channelHub.getImLinks()) {
                if (link.isBound(sessionId)) {
                    link.sendReply(sessionId, delta, false);
                }
            }
        }
    }

    public void replyToBoundChannel(ChannelHub channelHub, String sessionId, String text, boolean isFinal) {
        if (sessionId == null) {
            return;
        }
        for (Channel link : channelHub.getImLinks()) {
            if (link.isBound(sessionId)) {
                link.sendReply(sessionId, text, isFinal);
            }
        }
    }
}