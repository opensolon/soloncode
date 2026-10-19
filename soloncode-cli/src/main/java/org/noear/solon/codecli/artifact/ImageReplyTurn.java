package org.noear.solon.codecli.artifact;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 请求级图片生命周期与路由快照；session 仅保存本对象，旧请求只能操作自己的登记簿。 */
public final class ImageReplyTurn implements ReActInterceptor {
    public static final String ATTR = ImageReplyTurn.class.getName();
    public static final String ATTR_IM_TERMINAL_SENT = "session.im.terminal.sent";
    private final AgentSession session;
    private final ImagePublishTurn images;
    private final Map<String, String> route;
    private final Object originalRoute;
    // 与图片登记簿的 finished 不同：HITL 只封闭图片，仍允许后续文本终态。
    private boolean terminal;
    private final String token = UUID.randomUUID().toString();

    @SuppressWarnings("unchecked")
    public ImageReplyTurn(AgentSession session, String root, String cwd) {
        this.session = session;
        this.images = new ImagePublishTurn(root, cwd);
        synchronized (session.attrs()) {
            originalRoute = session.attrs().get("session.replyRoute");
            route = originalRoute instanceof Map
                    ? Collections.unmodifiableMap(new HashMap<>((Map<String, String>) originalRoute))
                    : Collections.emptyMap();
            Object old = session.attrs().put(ATTR, this);
            if (old instanceof ImageReplyTurn) {
                ((ImageReplyTurn) old).closeTerminal();
            }
            session.attrs().remove(ATTR_IM_TERMINAL_SENT);
        }
    }

    @Override
    public void onReasonStart(ReActTrace trace, StringBuilder systemPromptBuf) {
        synchronized (session.attrs()) {
            if (isCurrent() && !terminal && trace.getSession() == session && images.getRunId() == null) {
                images.bindRunId(trace.getRunId());
            }
        }
    }

    public String getToken() { return token; }
    public ImagePublishTurn getImages() { return images; }

    /** HITL 后立即封闭登记簿，之后即便出现正常 RunEnd 也没有图片可发。 */
    public void discardImages() {
        synchronized (session.attrs()) {
            images.discard();
        }
    }

    /** 取消不仅丢弃图片，也封闭迟到 RunEnd 的终态领取。 */
    public void closeTerminal() {
        synchronized (session.attrs()) {
            terminal = true;
            images.discard();
        }
    }

    public Map<String, String> getRoute() { return route; }
    public boolean isCurrent() { return session.attrs().get(ATTR) == this; }

    /** 调用方持有 attrs 锁。RunEnd 必须匹配本请求真正绑定的 runId。 */
    public boolean claim(String runId) {
        if (!isCurrent() || terminal || runId == null || !runId.equals(images.getRunId())) return false;
        terminal = true;
        return true;
    }

    public void clearRoute() {
        if (isCurrent() && session.attrs().get("session.replyRoute") == originalRoute) {
            session.attrs().remove("session.replyRoute");
        }
    }

    /** doFinally 只丢弃，绝不将流完成误判为正常 RunEnd。 */
    public void cleanup() {
        synchronized (session.attrs()) {
            closeTerminal();
            if (isCurrent()) {
                clearRoute();
                session.attrs().remove(ATTR, this);
            }
        }
    }
}
