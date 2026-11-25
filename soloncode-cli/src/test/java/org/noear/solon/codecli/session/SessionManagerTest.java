package org.noear.solon.codecli.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.codecli.workspace.WorkspaceDataUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
    void subtaskSessionsAreNeverCached() {
        SessionManager manager = new SessionManager(workspace.toString());
        String id = "task-0123456789abcdef0123456789abcdef";

        manager.getSession("web-p1");
        assertNull(manager.getSession(id, "web-p1", false), "不存在时只取返回 null");
        assertFalse(Files.exists(sessionsRoot().resolve("web-p1").resolve(SessionManager.SUBTASKS_DIR)), "只取不得建出目录");

        AgentSession created = manager.getSession(id, "web-p1", true);
        assertNull(manager.getLoadedSession(id), "子任务会话不常驻内存");

        AgentSession reopened = manager.getSession(id, "web-p1", false);
        assertNotNull(reopened, "目录存在时从磁盘重开");
        assertNotSame(created, reopened);
        assertNull(manager.getLoadedSession(id));

        // 不合完整格式的 ID 按主会话校验：越界新建抛 IAE、只取返回 null
        assertThrows(IllegalArgumentException.class, () -> manager.getSession("task-../x", true));
        assertNull(manager.getSession("task-../x", false));

        // 只是前缀相同、不合完整格式的按主会话对待（正常缓存）
        AgentSession lookalike = manager.getSession("task-board", true);
        assertSame(lookalike, manager.getLoadedSession("task-board"));
    }

    @Test
    void subtaskSessionsWithParentAreNestedUnderParent() throws IOException {
        SessionManager manager = new SessionManager(workspace.toString());
        String id = "task-0123456789abcdef0123456789abcdef";
        Path nested = sessionsRoot().resolve("web-p1").resolve(SessionManager.SUBTASKS_DIR).resolve(id);

        // 父会话不存在：只取返回 null，新建拒绝（不为已删除的父会话建空壳）
        assertNull(manager.getSession(id, "web-p1", false));
        assertThrows(IllegalStateException.class, () -> manager.getSession(id, "web-p1", true));
        assertFalse(Files.exists(sessionsRoot().resolve("web-p1")), "不得把父会话目录建出来");

        manager.getSession("web-p1");
        AgentSession created = manager.getSession(id, "web-p1", true);
        assertNotNull(created);
        assertTrue(Files.isDirectory(nested), "子任务会话存在父会话目录之下");
        assertFalse(Files.exists(sessionsRoot().resolve(id)), "不再平铺在 sessions 下");
        assertNull(manager.getLoadedSession(id), "子任务会话不常驻内存");
        assertNotNull(manager.getSession(id, "web-p1", false), "同一父会话可取回");

        // 别的父会话取不到，且不留空壳
        manager.getSession("web-p2");
        assertNull(manager.getSession(id, "web-p2", false));
        assertFalse(Files.exists(sessionsRoot().resolve("web-p2").resolve(SessionManager.SUBTASKS_DIR)));

        // 非法父会话 ID（含越界、子任务 ID）：只取 null，新建抛 IAE
        for (String bad : new String[]{"../x", "a/b", "", id}) {
            assertNull(manager.getSession(id, bad, false), bad);
            assertThrows(IllegalArgumentException.class, () -> manager.getSession(id, bad, true), bad);
        }

        // 删父会话目录即连带删除子会话
        try (Stream<Path> paths = Files.walk(sessionsRoot().resolve("web-p1"))) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        assertNull(manager.getSession(id, "web-p1", false));
    }

    @Test
    void legacyFlatSubtaskSessionIsStillReadableWithParent() throws IOException {
        SessionManager manager = new SessionManager(workspace.toString());
        String id = "task-fedcba9876543210fedcba9876543210";
        manager.getSession("web-p1");
        Files.createDirectories(sessionsRoot().resolve(id)); // 旧版本留下的平铺子会话

        assertNotNull(manager.getSession(id, "web-p1", false), "平铺的旧子会话仍可取回（回放、续跑）");
        assertNotNull(manager.getSession(id, "web-p1", true), "已有旧数据时不再另建嵌套目录");
        assertFalse(Files.exists(sessionsRoot().resolve("web-p1").resolve(SessionManager.SUBTASKS_DIR)),
                "只读兼容，不迁移");
        assertNotNull(manager.getSession(id, false), "不带父会话标识也能只读取到旧数据");
        assertNull(manager.getLoadedSession(id), "子任务会话不常驻内存");
    }

    /** 不带父会话标识新建子任务会话一律拒绝：不再产生新的平铺数据 */
    @Test
    void subtaskSessionCannotBeCreatedWithoutParent() {
        SessionManager manager = new SessionManager(workspace.toString());
        String id = "task-00112233445566778899aabbccddeeff";

        assertThrows(IllegalArgumentException.class, () -> manager.getSession(id, true));
        assertThrows(IllegalArgumentException.class, () -> manager.getSession(id));
        assertThrows(IllegalArgumentException.class, () -> manager.getSession(id, null, true));
        assertNull(manager.getSession(id, false));
        assertFalse(Files.exists(sessionsRoot().resolve(id)), "不得建出平铺目录");
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

    @Test
    void clearAlsoRemovesSubtasksOfThatSessionOnly() throws IOException {
        SessionManager manager = new SessionManager(workspace.toString());
        String id = "task-0123456789abcdef0123456789abcdef";
        AgentSession p1 = manager.getSession("web-p1");
        Path parent = sessionsRoot().resolve("web-p1");
        Path sub = parent.resolve(SessionManager.SUBTASKS_DIR).resolve(id);
        Files.createDirectories(sub);
        Files.write(sub.resolve(id + ".snapshot.json"), "{}".getBytes());
        Files.write(parent.resolve("_meta.json"), "{}".getBytes());
        Path otherSub = sessionsRoot().resolve("web-p2").resolve(SessionManager.SUBTASKS_DIR).resolve(id);
        Files.createDirectories(otherSub);

        p1.clear();
        assertFalse(Files.exists(parent.resolve(SessionManager.SUBTASKS_DIR)), "清空会话连带删除 subtasks/");
        assertTrue(Files.isRegularFile(parent.resolve("_meta.json")), "父会话其它文件不动");
        assertTrue(Files.isDirectory(otherSub), "别的会话不受影响");

        p1.clear(); // 没有子任务时清空不出错、不建目录
        assertFalse(Files.exists(parent.resolve(SessionManager.SUBTASKS_DIR)));

        // 从磁盘加载回来的会话同样连带删除
        Files.createDirectories(sub);
        manager.evictSession("web-p1");
        manager.getSessionOrNull("web-p1").clear();
        assertFalse(Files.exists(parent.resolve(SessionManager.SUBTASKS_DIR)));
    }

    @Test
    void clearOfSubtaskSessionsDoesNotCascade() throws IOException {
        SessionManager manager = new SessionManager(workspace.toString());
        String id = "task-0123456789abcdef0123456789abcdef";
        // 旧版平铺的子会话目录下即使有 subtasks/ 也不动（只有普通会话才连带删除）
        Path flat = sessionsRoot().resolve(id).resolve(SessionManager.SUBTASKS_DIR);
        Files.createDirectories(flat);

        manager.getSession(id, false).clear();
        assertTrue(Files.isDirectory(flat));
    }

    @Test
    void clearDoesNotFollowSymlinks() throws IOException {
        SessionManager manager = new SessionManager(workspace.toString());
        AgentSession p1 = manager.getSession("web-p1");
        Path outside = workspace.resolve("outside");
        Files.createDirectories(outside);
        Path keep = Files.write(outside.resolve("keep.txt"), "x".getBytes());

        // subtasks/ 里有指向外部目录的链接：只删链接
        Path subtasks = sessionsRoot().resolve("web-p1").resolve(SessionManager.SUBTASKS_DIR);
        Files.createDirectories(subtasks);
        Files.createSymbolicLink(subtasks.resolve("task-0123456789abcdef0123456789abcdef"), outside);
        p1.clear();
        assertFalse(Files.exists(subtasks, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(keep), "链接指向的内容不得被删");

        // subtasks 本身是链接：同样只删链接
        Files.createSymbolicLink(subtasks, outside);
        p1.clear();
        assertFalse(Files.exists(subtasks, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(keep), "链接指向的内容不得被删");
    }
}
