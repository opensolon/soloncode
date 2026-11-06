package org.noear.solon.codecli.session;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.workspace.WorkspaceDataUtil;
import org.noear.solon.lang.NonNull;
import org.noear.solon.lang.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器，所有会话存储在传统路径 ~/.soloncode/workspaces/&lt;工作区标识&gt;/sessions/&lt;sessionId&gt;/ 下。
 * 用户隔离通过会话元数据（_meta.json 中的 ownerUserId）实现，由 API 层过滤。
 *
 * <p>获取方法一览（按副作用由大到小）：</p>
 * <ul>
 *   <li>{@link #getSession(String)}：不存在则新建（建目录、常驻内存）</li>
 *   <li>{@link #getOwnedSession(String, String)}：同上，并校验/认领归属用户</li>
 *   <li>{@link #getSessionOrNull(String)}：只取已存在的（内存或磁盘），磁盘有则加载进内存，不新建</li>
 *   <li>{@link #getLoadedSession(String)}：只取已加载进内存的，不读磁盘、不加载</li>
 * </ul>
 * <p>{@link #evictSession(String)} 与 {@link #getLoadedSession(String)} 对应：把已加载的会话移出内存。</p>
 *
 * <p>会话 ID 必须非空，且解析后是 sessions 目录的直接子目录（防 {@code ../} 越界）。
 * 新建类方法遇到非法 ID 抛 {@link IllegalArgumentException}；只取类方法返回 null。</p>
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

    /**
     * 获取会话。
     *
     * <p>{@code createIfAbsent=true}：不存在则新建（{@link FileAgentSession} 构造即建目录），非法 ID 抛
     * {@link IllegalArgumentException}。{@code createIfAbsent=false}：只取已存在的——内存命中直接返回，
     * 否则仅当会话目录已存在时加载并缓存，目录不存在或 ID 非法返回 null（不建目录、不放缓存）。
     * 判存与加载在同一个 {@code computeIfAbsent} 内，是原子的。</p>
     */
    @Override
    public @Nullable AgentSession getSession(String sessionId, boolean createIfAbsent) {
        if (createIfAbsent) {
            Path dir = resolveSessionPath(sessionId);
            return sessionMap.computeIfAbsent(sessionId, key -> new FileAgentSession(key, dir.toString()));
        }

        Path dir = sessionPathOrNull(sessionId);
        if (dir == null) {
            return null;
        }
        return sessionMap.computeIfAbsent(sessionId, key ->
                Files.isDirectory(dir) ? new FileAgentSession(key, dir.toString()) : null); // 返回 null 时不写入
    }

    /** 获取会话，不存在则新建（= {@code getSession(sessionId, true)}）。 */
    public @NonNull AgentSession getSession(String sessionId) {
        return getSession(sessionId, true);
    }

    /** 只取已存在的会话（内存或磁盘），不存在返回 null、不新建（= {@code getSession(sessionId, false)}）。 */
    public @Nullable AgentSession getSessionOrNull(String sessionId) {
        return getSession(sessionId, false);
    }

    /**
     * 获取会话（不存在则新建），并做用户隔离：已有 owner 且不匹配时拒绝；尚无 owner 时认领为 userId，认领失败抛异常。
     * userId 为空时等同 {@link #getSession(String)}。
     */
    public @NonNull AgentSession getOwnedSession(String sessionId, @Nullable String userId) {
        AgentSession session = getSession(sessionId);
        if (userId != null && !userId.isEmpty()) {
            updateMeta(session, sessionId, meta -> {
                String owner = meta.getOwnerUserId();
                if (owner != null && !owner.isEmpty() && !userId.equals(owner)) {
                    throw new IllegalStateException("Session not found");
                }
                if (owner == null || owner.isEmpty()) {
                    meta.setOwnerUserId(userId);
                    return true;
                }
                return false;
            }, "Cannot save session owner");
        }
        return session;
    }

    /**
     * 只取「已加载进内存」的会话，未加载返回 null（不读磁盘、不加载、不新建）。
     *
     * <p>与 {@link #getSessionOrNull(String)} 的区别：后者磁盘有就加载进内存（含快照反序列化 + 全量历史消息），
     * 加载后常驻。批量扫描全部会话目录的场景（如启动时探测队列、恢复目标）必须用本方法，
     * 否则每个会话都会被加载并钉在内存里；未命中时由调用方自行读磁盘投影。</p>
     */
    public @Nullable AgentSession getLoadedSession(String sessionId) {
        return sessionId == null ? null : sessionMap.get(sessionId);
    }

    /** 标记自动任务专用会话（不存在则新建）。专用会话保留在 sessions 下，但不再依赖名称前缀判断用途。 */
    public void markAutomationSession(String sessionId, String automationId) {
        updateMeta(getSession(sessionId), sessionId, meta -> {
            meta.setSessionType("AUTOMATION");
            meta.setOwnerAutomationId(automationId);
            meta.setArchived(false);
            return true;
        }, "Cannot save automation session metadata");
    }

    /** 删除任务时归档专用会话，保留历史消息，避免误删上下文。会话已不存在时什么也不做（不把它建出来）。 */
    public void archiveAutomationSession(String sessionId, String automationId) {
        AgentSession session = getSessionOrNull(sessionId);
        if (session == null) return;
        updateMeta(session, sessionId, meta -> {
            meta.setSessionType("AUTOMATION");
            meta.setOwnerAutomationId(automationId);
            meta.setArchived(true);
            return true;
        }, "Cannot archive automation session");
    }

    /**
     * 把会话移出内存（不删磁盘、不通知扩展；下次获取时从磁盘重建）。
     *
     * <p>永久删除会话须先调 {@code engine.removeSession(id)}（撤掉定时任务），再调本方法，最后删目录。</p>
     */
    public @Nullable AgentSession evictSession(String sessionId) {
        return sessionId == null ? null : sessionMap.remove(sessionId);
    }

    // ==================== 内部工具 ====================

    /** 元数据修改器：返回 true 表示有改动需要保存。 */
    private interface MetaUpdater {
        boolean apply(SessionMeta meta);
    }

    /** 在会话锁内读-改-存 _meta.json；保存失败包成 {@link IllegalStateException}。 */
    private void updateMeta(AgentSession session, String sessionId, MetaUpdater updater, String errMsg) {
        Path sessionDir = resolveSessionPath(sessionId);
        synchronized (session.attrs()) {
            SessionMeta meta = SessionMeta.load(sessionDir);
            if (updater.apply(meta)) {
                try {
                    meta.save(sessionDir);
                } catch (IOException e) {
                    throw new IllegalStateException(errMsg, e);
                }
            }
        }
    }

    /** 解析会话存储路径；ID 为空或越界抛 {@link IllegalArgumentException}。 */
    private Path resolveSessionPath(String sessionId) {
        Path dir = sessionPathOrNull(sessionId);
        if (dir == null) {
            throw new IllegalArgumentException("Invalid sessionId: " + sessionId);
        }
        return dir;
    }

    /** 解析会话存储路径；ID 为空、含非法字符或不是 sessions 的直接子目录（防 "../" 越界）时返回 null。 */
    private @Nullable Path sessionPathOrNull(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        Path root = WorkspaceDataUtil.sessionsPath(workspace).normalize();
        Path dir;
        try {
            dir = root.resolve(sessionId).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        return root.equals(dir.getParent()) ? dir : null;
    }
}
