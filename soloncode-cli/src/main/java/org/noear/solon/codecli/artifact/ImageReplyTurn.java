package org.noear.solon.codecli.artifact;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.codecli.channel.ChannelReply;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** 请求级图片许可与路由快照；解析 Markdown 和读取图片不占用会话锁。 */
public final class ImageReplyTurn implements ReActInterceptor {
    public static final String ATTR = ImageReplyTurn.class.getName();
    public static final String ATTR_IM_TERMINAL_SENT = "session.im.terminal.sent";
    private final AgentSession session;
    private final String root;
    private final String cwd;
    private final Map<String, String> route;
    private final Object originalRoute;
    private String runId;
    // 终态领取与图片许可分离：领取后的正常回复仍可发送准备好的图片。
    private boolean terminal;
    private boolean imagesAllowed = true;

    @SuppressWarnings("unchecked")
    public ImageReplyTurn(AgentSession session, String root, String cwd) {
        this.session = session;
        this.root = root;
        this.cwd = cwd;
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
            if (isCurrent() && !terminal && trace.getSession() == session && runId == null) {
                String traceRunId = trace.getRunId();
                if (traceRunId != null && !traceRunId.trim().isEmpty()) {
                    runId = traceRunId;
                }
            }
        }
    }

    /** 只在锁内获取许可快照；解析、路径检查和图片读取全部在锁外执行。 */
    public ChannelReply prepare(String text, String runId) {
        final boolean allowed;
        synchronized (session.attrs()) {
            allowed = imagesAllowed && canClaim(runId);
        }
        return ChannelReply.fromMarkdown(text, true, runId, root, cwd, allowed);
    }

    /** HITL 只禁用图片，仍允许后续文本终态安全降级。 */
    public void discardImages() {
        synchronized (session.attrs()) {
            imagesAllowed = false;
        }
    }

    /** 取消不仅禁用图片，也封闭迟到 RunEnd 的终态领取。 */
    public void closeTerminal() {
        synchronized (session.attrs()) {
            terminal = true;
            imagesAllowed = false;
        }
    }

    public Map<String, String> getRoute() { return route; }

    public boolean isCurrent() {
        synchronized (session.attrs()) {
            return session.attrs().get(ATTR) == this;
        }
    }

    /** 不以 terminal 判断许可：claim 之后发送正常图片仍需再次检查此状态。 */
    public boolean isImagesAllowed() {
        synchronized (session.attrs()) {
            return isCurrent() && imagesAllowed;
        }
    }

    /** 锁内预检；准备完成后 claim 仍必须重新检查，不能依赖此结果。 */
    public boolean canClaim(String runId) {
        synchronized (session.attrs()) {
            return isCurrent() && !terminal && runId != null && runId.equals(this.runId);
        }
    }

    /** RunEnd 必须匹配本请求真正绑定的 runId，且最多领取一次。 */
    public boolean claim(String runId) {
        synchronized (session.attrs()) {
            if (!canClaim(runId)) return false;
            terminal = true;
            return true;
        }
    }

    public void clearRoute() {
        synchronized (session.attrs()) {
            if (isCurrent() && session.attrs().get("session.replyRoute") == originalRoute) {
                session.attrs().remove("session.replyRoute");
            }
        }
    }

    /** doFinally 只禁用与清理，绝不将流完成误判为正常 RunEnd。 */
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
