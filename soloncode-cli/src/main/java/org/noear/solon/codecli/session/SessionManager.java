package org.noear.solon.codecli.session;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.lang.NonNull;
import org.noear.solon.lang.Nullable;

import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author noear 2026/7/12 created
 *
 */
public class SessionManager implements AgentSessionProvider {
    private String workspace = AgentFlags.getUserDir();
    private Map<String, AgentSession> sessionMap = new ConcurrentHashMap<>();

    @Override
    public @NonNull AgentSession getSession(String sessionId) {
        // 会话数据存到全局目录 ~/.soloncode/sessions/<sessionId>/

        return sessionMap.computeIfAbsent(sessionId, key ->
                new FileAgentSession(key, Paths.get(workspace, AgentFlags.getHarnessSessions()).resolve(key).normalize().toFile().toString()));
    }

    public @Nullable AgentSession removeSession(String sessionId) {
        return sessionMap.remove(sessionId);
    }
}
