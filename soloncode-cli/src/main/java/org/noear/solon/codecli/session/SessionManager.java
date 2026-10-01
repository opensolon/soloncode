package org.noear.solon.codecli.session;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.workspace.WorkspaceDataUtil;
import org.noear.solon.lang.NonNull;
import org.noear.solon.lang.Nullable;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器，所有会话存储在传统路径 ~/.soloncode/workspaces/&lt;工作区标识&gt;/sessions/&lt;sessionId&gt;/ 下。
 * 用户隔离通过会话元数据（_meta.json 中的 ownerUserId）实现，由 API 层过滤。
 *
 * @author noear 2026/7/12 created
 */
public class SessionManager implements AgentSessionProvider {
    private final String workspace;
    private final Map<String, AgentSession> sessionMap = new ConcurrentHashMap<>();

    public SessionManager() {
        this.workspace = AgentFlags.getUserDir();
    }

    public SessionManager(String workspace) {
        this.workspace = workspace;
    }

    @Override
    public @NonNull AgentSession getSession(String sessionId) {
        // 会话数据存到 ~/.soloncode/workspaces/<工作区标识>/sessions/<sessionId>/
        return sessionMap.computeIfAbsent(sessionId, key ->
                new FileAgentSession(key, resolveSessionPath(key).toString()));
    }

    /** 带用户隔离的会话获取：已有 owner 不匹配时拒绝，认领失败不继续执行。 */
    public @NonNull AgentSession getSession(String sessionId, @Nullable String userId) {
        AgentSession session = getSession(sessionId);
        if (userId != null && !userId.isEmpty()) {
            synchronized (session.attrs()) {
                Path sessionDir = resolveSessionPath(sessionId);
                SessionMeta meta = SessionMeta.load(sessionDir);
                String owner = meta.getOwnerUserId();
                if (owner != null && !owner.isEmpty() && !userId.equals(owner)) {
                    throw new IllegalStateException("Session not found");
                }
                if (owner == null || owner.isEmpty()) {
                    meta.setOwnerUserId(userId);
                    try {
                        meta.save(sessionDir);
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException("Cannot save session owner", e);
                    }
                }
            }
        }
        return session;
    }

    /**
     * 获取「已在内存中」的会话实例，不存在时返回 null（不创建）。
     *
     * <p>{@link #getSession(String)} 是 computeIfAbsent 语义：一调用就会为该 sessionId 建出
     * {@link FileAgentSession} 并常驻 sessionMap（含快照反序列化 + 全量历史消息装载）。
     * 只读场景（如拉取历史消息）若走它，用户随手点开的每个会话都会被永久钉在内存里。</p>
     *
     * <p>因此只读方只用本方法探测：命中则以内存为准（与写侧同源，不会读到过期数据），
     * 未命中则由调用方回落到磁盘文件，既不引入常驻开销，也不改变可见结果。</p>
     */
    public @Nullable AgentSession getSessionIfPresent(String sessionId) {
        return sessionMap.get(sessionId);
    }

    /**
     * 解析会话存储路径
     */
    private Path resolveSessionPath(String sessionId) {
        return WorkspaceDataUtil.sessionsDir(workspace).toPath().resolve(sessionId).normalize();
    }

    /** 标记自动任务专用会话。专用会话保留在 sessions 下，但不再依赖名称前缀判断用途。 */
    public void markAutomationSession(String sessionId, String automationId) {
        AgentSession session = getSession(sessionId);
        Path sessionDir = resolveSessionPath(sessionId);
        synchronized (session.attrs()) {
            SessionMeta meta = SessionMeta.load(sessionDir);
            meta.setSessionType("AUTOMATION");
            meta.setOwnerAutomationId(automationId);
            meta.setArchived(false);
            try {
                meta.save(sessionDir);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot save automation session metadata", e);
            }
        }
    }

    /** 删除任务时归档专用会话，保留历史消息，避免误删上下文。 */
    public void archiveAutomationSession(String sessionId, String automationId) {
        if (sessionId == null || sessionId.trim().isEmpty()) return;
        AgentSession session = getSession(sessionId);
        Path sessionDir = resolveSessionPath(sessionId);
        synchronized (session.attrs()) {
            SessionMeta meta = SessionMeta.load(sessionDir);
            meta.setSessionType("AUTOMATION");
            meta.setOwnerAutomationId(automationId);
            meta.setArchived(true);
            try {
                meta.save(sessionDir);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot archive automation session", e);
            }
        }
    }

    public @Nullable AgentSession removeSession(String sessionId) {
        return sessionMap.remove(sessionId);
    }
}