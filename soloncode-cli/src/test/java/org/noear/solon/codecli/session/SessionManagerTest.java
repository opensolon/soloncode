package org.noear.solon.codecli.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.codecli.workspace.WorkspaceDataUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionManager 获取契约：
 * <ul>
 *   <li>getSession(id, false) / getSessionOrNull：不存在时返回 null 且不建目录、不进缓存；目录存在时加载并缓存</li>
 *   <li>getLoadedSession：只看内存</li>
 *   <li>ID 为空或越界：新建类方法抛 IAE 且不在 sessions 外建目录，只取类方法返回 null</li>
 *   <li>getOwnedSession：认领归属，不同用户拒绝</li>
 * </ul>
 */
class SessionManagerTest {
    @TempDir
    Path workspace;

    private Path sessionsRoot() {
        return WorkspaceDataUtil.sessionsPath(workspace.toString());
    }

    @AfterEach
    void cleanDataDir() throws IOException {
        // 会话数据落在 ~/.soloncode/workspaces/<工作区标识>/ 下，用完清掉，避免污染用户目录
        Path dataDir = sessionsRoot().getParent();
        if (dataDir != null && Files.exists(dataDir)) {
            try (Stream<Path> paths = Files.walk(dataDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void getSessionOrNullReturnsNullAndCreatesNothingWhenAbsent() {
        SessionManager manager = new SessionManager(workspace.toString());

        assertNull(manager.getSessionOrNull("absent"));
        assertNull(manager.getSession("absent", false));
        assertFalse(Files.exists(sessionsRoot().resolve("absent")), "不得在磁盘上建出会话目录");
        assertNull(manager.getLoadedSession("absent"), "不得放入缓存");
    }

    @Test
    void getSessionOrNullLoadsExistingDirectoryAndCaches() throws IOException {
        Files.createDirectories(sessionsRoot().resolve("s1"));
        SessionManager manager = new SessionManager(workspace.toString());

        assertNull(manager.getLoadedSession("s1"), "目录存在但未加载时，getLoadedSession 不读磁盘");
        AgentSession loaded = manager.getSessionOrNull("s1");
        assertNotNull(loaded, "目录存在时应加载");
        assertSame(loaded, manager.getLoadedSession("s1"), "加载后应缓存");
        assertSame(loaded, manager.getSession("s1"), "已在内存时返回同一实例");
    }

    @Test
    void getSessionOrNullReturnsCachedInstanceEvenBeforeDirectoryExists() {
        SessionManager manager = new SessionManager(workspace.toString());
        AgentSession created = manager.getSession("s2");

        assertSame(created, manager.getSessionOrNull("s2"));
    }

    @Test
    void lookupMethodsReturnNullForBlankAndTraversalIds() {
        SessionManager manager = new SessionManager(workspace.toString());

        assertNull(manager.getSessionOrNull(null));
        assertNull(manager.getSessionOrNull(""));
        assertNull(manager.getSessionOrNull("../outside"));
        assertNull(manager.getSessionOrNull("a/b"));
        assertNull(manager.getLoadedSession(null));
        assertNull(manager.evictSession(null));
        manager.archiveAutomationSession("../outside", "auto-1"); // 不抛、不建
        assertFalse(Files.exists(sessionsRoot().resolveSibling("outside")));
    }

    @Test
    void createMethodsRejectBlankAndTraversalIdsWithoutTouchingDisk() {
        SessionManager manager = new SessionManager(workspace.toString());

        assertThrows(IllegalArgumentException.class, () -> manager.getSession(null));
        assertThrows(IllegalArgumentException.class, () -> manager.getSession(""));
        assertThrows(IllegalArgumentException.class, () -> manager.getSession("../outside"));
        assertThrows(IllegalArgumentException.class, () -> manager.getSession("a/b"));
        assertThrows(IllegalArgumentException.class, () -> manager.getOwnedSession("../outside", "u1"));
        assertThrows(IllegalArgumentException.class, () -> manager.markAutomationSession("../outside", "auto-1"));

        assertFalse(Files.exists(sessionsRoot().resolveSibling("outside")), "不得在 sessions 目录外建目录");
        assertFalse(Files.exists(sessionsRoot().resolve("a")), "不得建出嵌套目录");
    }

    @Test
    void getOwnedSessionClaimsOwnerAndRejectsOtherUsers() {
        SessionManager manager = new SessionManager(workspace.toString());

        AgentSession session = manager.getOwnedSession("s4", "u1");
        assertEquals("u1", SessionMeta.load(sessionsRoot().resolve("s4")).getOwnerUserId(), "首次访问认领归属");
        assertSame(session, manager.getOwnedSession("s4", "u1"));
        assertSame(session, manager.getOwnedSession("s4", null), "userId 为空时不做隔离");
        assertThrows(IllegalStateException.class, () -> manager.getOwnedSession("s4", "u2"));
    }

    @Test
    void automationMarkAndArchiveUpdateMeta() {
        SessionManager manager = new SessionManager(workspace.toString());

        manager.markAutomationSession("auto-s5", "a1");
        SessionMeta meta = SessionMeta.load(sessionsRoot().resolve("auto-s5"));
        assertEquals("AUTOMATION", meta.getSessionType());
        assertEquals("a1", meta.getOwnerAutomationId());
        assertFalse(meta.isArchived());

        manager.archiveAutomationSession("auto-s5", "a1");
        assertTrue(SessionMeta.load(sessionsRoot().resolve("auto-s5")).isArchived());

        manager.archiveAutomationSession("absent", "a1");
        assertFalse(Files.exists(sessionsRoot().resolve("absent")), "归档不存在的会话不得把它建出来");
    }

    @Test
    void evictSessionOnlyDropsMemory() {
        SessionManager manager = new SessionManager(workspace.toString());
        AgentSession created = manager.getSession("s3");

        assertSame(created, manager.evictSession("s3"));
        assertNull(manager.getLoadedSession("s3"));
        assertTrue(Files.isDirectory(sessionsRoot().resolve("s3")), "evict 不删磁盘");
        assertNotNull(manager.getSessionOrNull("s3"), "磁盘仍在，可重新加载");
    }
}
