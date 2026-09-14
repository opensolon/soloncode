package org.noear.solon.codecli.portal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.portal.FileWatchService.ChangeEntry;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileWatchService 单元测试
 *
 * <p>通过临时文件夹模拟文件变更，验证 WatchService 的事件捕获、
 * 去重防抖、按根分发等核心能力。</p>
 *
 * <p><b>macOS 注意</b>：JDK 8 在 macOS 上使用 PollingWatchService（轮询模式），
 * 默认轮询间隔约 10 秒。测试前通过 {@code sun.nio.fs.pollInterval} 系统属性
 * 缩短为 1 秒，加速测试。</p>
 */
public class FileWatchServiceTest {

    private FileWatchService service;
    private Path tempRoot;

    @BeforeAll
    public static void setUpClass() {
        // macOS JDK 8 使用 PollingWatchService，默认 10 秒轮询一次，缩短为 1 秒加速测试
        System.setProperty("sun.nio.fs.pollInterval", "1");
    }

    @BeforeEach
    public void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("fws-test-");
        service = new FileWatchService();
    }

    @AfterEach
    public void tearDown() {
        if (service != null) {
            service.stop();
        }
        if (tempRoot != null && Files.exists(tempRoot)) {
            walkAndDelete(tempRoot);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 启动服务并等待目录树注册完成
     */
    private void startAndWait() throws Exception {
        service.start();
        Thread.sleep(2000);
    }

    /**
     * 递归删除目录
     */
    private void walkAndDelete(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    /** 等待超时（秒），考虑 PollingWatchService 轮询延迟 */
    private static final int AWAIT_SEC = 15;

    /** 轮询等待条件成立（用于同一 handler 需要多次断言的场景，CountDownLatch 只能用一次） */
    private static boolean waitUntil(BooleanSupplier condition, int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }

    // ═══════════════════════════════════════════════════════════════
    //  测试用例
    // ═══════════════════════════════════════════════════════════════

    /**
     * 测试：在根目录新建文件，handler 应收到变更
     */
    @Test
    public void testCreateFile() throws Exception {
        List<ChangeEntry> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        service.addRoot("test-ws", tempRoot)
                .addHandler(changes -> {
                    received.addAll(changes);
                    latch.countDown();
                });

        startAndWait();

        Path newFile = tempRoot.resolve("aaa.md");
        Files.write(newFile, "hello".getBytes());

        assertTrue(latch.await(AWAIT_SEC, TimeUnit.SECONDS),
                "handler should receive create event within " + AWAIT_SEC + "s");

        assertFalse(received.isEmpty(), "received changes should not be empty");
        ChangeEntry entry = received.get(0);
        assertEquals("test-ws", entry.wsId);
        assertEquals("aaa.md", entry.path);
    }

    /**
     * 测试：修改已有文件，handler 应收到变更
     */
    @Test
    public void testModifyFile() throws Exception {
        Path existFile = tempRoot.resolve("exist.txt");
        Files.write(existFile, "old".getBytes());

        List<ChangeEntry> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        service.addRoot("test-ws", tempRoot)
                .addHandler(changes -> {
                    received.addAll(changes);
                    latch.countDown();
                });

        startAndWait();

        Files.write(existFile, "new content".getBytes());

        assertTrue(latch.await(AWAIT_SEC, TimeUnit.SECONDS),
                "handler should receive modify event");

        assertFalse(received.isEmpty());
        ChangeEntry entry = received.get(0);
        assertEquals("test-ws", entry.wsId);
        assertEquals("exist.txt", entry.path);
    }

    /**
     * 测试：删除文件，handler 应收到变更
     */
    @Test
    public void testDeleteFile() throws Exception {
        Path file = tempRoot.resolve("delete-me.txt");
        Files.write(file, "bye".getBytes());

        List<ChangeEntry> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        service.addRoot("test-ws", tempRoot)
                .addHandler(changes -> {
                    received.addAll(changes);
                    latch.countDown();
                });

        startAndWait();

        Files.delete(file);

        assertTrue(latch.await(AWAIT_SEC, TimeUnit.SECONDS),
                "handler should receive delete event");

        assertFalse(received.isEmpty());
        ChangeEntry entry = received.get(0);
        assertEquals("test-ws", entry.wsId);
        assertEquals("delete-me.txt", entry.path);
    }

    /**
     * 测试：在子目录中创建文件，handler 应收到变更
     */
    @Test
    public void testCreateFileInSubDir() throws Exception {
        Path subDir = tempRoot.resolve("sub");
        Files.createDirectories(subDir);

        List<ChangeEntry> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        service.addRoot("test-ws", tempRoot)
                .addHandler(changes -> {
                    received.addAll(changes);
                    for (ChangeEntry e : changes) {
                        if ("sub/child.md".equals(e.path)) {
                            latch.countDown();
                            break;
                        }
                    }
                });

        startAndWait();

        Path newFile = subDir.resolve("child.md");
        Files.write(newFile, "child".getBytes());

        assertTrue(latch.await(AWAIT_SEC, TimeUnit.SECONDS),
                "handler should receive sub-dir create event");

        // 在收到的变更中找到 sub/child.md
        boolean found = false;
        for (ChangeEntry e : received) {
            if ("sub/child.md".equals(e.path)) {
                assertEquals("test-ws", e.wsId);
                found = true;
                break;
            }
        }
        assertTrue(found, "should find sub/child.md in received changes");
    }

    /**
     * 测试：新建目录后再在其中创建文件，handler 应收到变更
     * （验证新目录自动注册监听）
     */
    @Test
    public void testNewDirAutoRegister() throws Exception {
        List<ChangeEntry> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        service.addRoot("test-ws", tempRoot)
                .addHandler(changes -> {
                    received.addAll(changes);
                    for (ChangeEntry e : changes) {
                        if ("deep/nested/file.md".equals(e.path)) {
                            latch.countDown();
                            break;
                        }
                    }
                });

        startAndWait();

        Path newDir = tempRoot.resolve("deep/nested");
        Files.createDirectories(newDir);
        // 等待 PollingWatchService 检测到新目录 + registerTree 注册新目录
        Thread.sleep(12000);

        Files.write(newDir.resolve("file.md"), "found".getBytes());

        assertTrue(latch.await(AWAIT_SEC, TimeUnit.SECONDS),
                "handler should receive event from newly created directory");
    }

    /**
     * 测试：浅监听（shallow）只挂根目录自身
     *
     * <p>对应「在 ~ 下启动」的默认工作区场景：前端不展示文件树，后端也不应递归注册整棵树。</p>
     * <p>预期：根目录下的直接变更仍能捕获（便于未来解锁时可用），但预存的子目录不入监听，
     * 其内部变更不被推送；在该子目录下新建目录也不会触发递归注册。</p>
     */
    @Test
    public void testShallowRootSkipsSubtree() throws Exception {
        // 预先存在的子目录：浅监听下不应注册
        Path existingSub = tempRoot.resolve("bigsub");
        Files.createDirectories(existingSub);

        List<ChangeEntry> received = new CopyOnWriteArrayList<>();
        service.addRoot("test-ws", tempRoot)
                .shallow(true)
                .addHandler(received::addAll);

        startAndWait();

        // 1) 子目录内部的文件变更不应被推送
        Files.write(existingSub.resolve("inside.txt"), "x".getBytes());
        // 2) 子目录内新建目录也不应触发递归注册（shallow 下不深入）
        Path innerDir = existingSub.resolve("inner");
        Files.createDirectories(innerDir);
        Files.write(innerDir.resolve("deep.txt"), "y".getBytes());
        Thread.sleep(5000);

        assertTrue(received.stream().noneMatch(e -> e.path.startsWith("bigsub/")),
                "shallow root should not receive changes from subtree, but got: " + received);

        // 3) 根目录下的直接变更仍应被捕获
        received.clear();
        Files.write(tempRoot.resolve("atRoot.txt"), "z".getBytes());
        assertTrue(waitUntil(() -> received.stream().anyMatch(e -> "atRoot.txt".equals(e.path)), AWAIT_SEC),
                "shallow root should still receive changes of its own direct children");
    }

    /**
     * 测试：非浅监听（默认）仍会递归注册子树（与浅监听形成对照）
     */
    @Test
    public void testDefaultRootRecursesSubtree() throws Exception {
        Path existingSub = tempRoot.resolve("sub");
        Files.createDirectories(existingSub);

        CountDownLatch latch = new CountDownLatch(1);
        service.addRoot("test-ws", tempRoot)
                .addHandler(changes -> {
                    for (ChangeEntry e : changes) {
                        if ("sub/inside.txt".equals(e.path)) {
                            latch.countDown();
                            break;
                        }
                    }
                });

        startAndWait();
        Files.write(existingSub.resolve("inside.txt"), "x".getBytes());

        assertTrue(latch.await(AWAIT_SEC, TimeUnit.SECONDS),
                "default (non-shallow) root should receive changes from pre-existing subtree");
    }

    /**
     * 测试：排除目录下的文件变更不应被捕获
     */
    @Test
    public void testExcludedDir() throws Exception {
        Path gitDir = tempRoot.resolve(".git");
        Files.createDirectories(gitDir);

        List<ChangeEntry> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        service.addRoot("test-ws", tempRoot)
                .addHandler(changes -> {
                    received.addAll(changes);
                    latch.countDown();
                });

        startAndWait();

        Files.write(gitDir.resolve("config"), "test".getBytes());

        // 等待 5 秒（pollInterval=1s，5 个轮询周期），不应该有事件
        assertFalse(latch.await(5, TimeUnit.SECONDS),
                "changes in .git should be ignored");
        assertTrue(received.isEmpty(), "no changes should be received from excluded dir");
    }

    /**
     * 测试：多根目录，变更只分发到对应根的 handler
     */
    @Test
    public void testMultipleRootsIsolation() throws Exception {
        Path rootA = Files.createTempDirectory("fws-rootA-");
        Path rootB = Files.createTempDirectory("fws-rootB-");

        try {
            List<ChangeEntry> receivedA = new CopyOnWriteArrayList<>();
            List<ChangeEntry> receivedB = new CopyOnWriteArrayList<>();
            CountDownLatch latchA = new CountDownLatch(1);
            CountDownLatch latchB = new CountDownLatch(1);

            service.addRoot("rootA", rootA)
                    .addHandler(changes -> {
                        receivedA.addAll(changes);
                        latchA.countDown();
                    });

            service.addRoot("rootB", rootB)
                    .addHandler(changes -> {
                        receivedB.addAll(changes);
                        latchB.countDown();
                    });

            startAndWait();

            Files.write(rootA.resolve("a.txt"), "a".getBytes());

            assertTrue(latchA.await(AWAIT_SEC, TimeUnit.SECONDS),
                    "rootA handler should receive event");
            assertFalse(receivedA.isEmpty());
            assertEquals("rootA", receivedA.get(0).wsId);
            assertEquals("a.txt", receivedA.get(0).path);

            // rootB 不应收到 rootA 的事件
            assertFalse(latchB.await(2, TimeUnit.SECONDS),
                    "rootB handler should NOT receive rootA's event");
            assertTrue(receivedB.isEmpty(), "rootB should have no changes");

            Files.write(rootB.resolve("b.txt"), "b".getBytes());

            assertTrue(latchB.await(AWAIT_SEC, TimeUnit.SECONDS),
                    "rootB handler should receive event");
            assertFalse(receivedB.isEmpty());
            assertEquals("rootB", receivedB.get(0).wsId);
            assertEquals("b.txt", receivedB.get(0).path);
        } finally {
            walkAndDelete(rootA);
            walkAndDelete(rootB);
        }
    }

    /**
     * 测试：同一根注册多个 handler，都应被调用
     */
    @Test
    public void testMultipleHandlers() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);

        service.addRoot("test-ws", tempRoot)
                .addHandler(changes -> {
                    callCount.incrementAndGet();
                    latch.countDown();
                })
                .addHandler(changes -> {
                    callCount.incrementAndGet();
                    latch.countDown();
                });

        startAndWait();

        Files.write(tempRoot.resolve("multi.md"), "test".getBytes());

        assertTrue(latch.await(AWAIT_SEC, TimeUnit.SECONDS),
                "both handlers should be called");
        assertEquals(2, callCount.get());
    }

    /**
     * 测试：buildFrontendJson 生成正确的 SAEP 2.0 信封结构
     */
    @Test
    public void testBuildFrontendJson() {
        List<ChangeEntry> changes = java.util.Arrays.asList(
                new ChangeEntry("workspace", "src/Foo.java", "create", "file"),
                new ChangeEntry("@mount1", "bar.txt", "delete", "file")
        );

        String json = FileWatchService.buildFrontendJson(changes);

        assertNotNull(json);
        // 信封必须带 event：前端 AgentEventDispatcher.toWebEvent 据此识别，缺失会被判为 null 丢弃
        assertTrue(json.contains("\"event\":\"system.filer_change\""), "JSON should carry the SAEP event name");
        assertTrue(json.contains("\"payload\""), "changes must be wrapped in payload");
        assertTrue(json.contains("\"timestamp\""), "JSON should contain envelope timestamp");
        assertTrue(json.contains("\"wsId\":\"workspace\""), "JSON should contain first wsId");
        assertTrue(json.contains("\"path\":\"src/Foo.java\""), "JSON should contain first path");
        assertTrue(json.contains("\"kind\":\"create\""), "JSON should contain create kind");
        assertTrue(json.contains("\"wsId\":\"@mount1\""), "JSON should contain second wsId");
        assertTrue(json.contains("\"path\":\"bar.txt\""), "JSON should contain second path");
        assertTrue(json.contains("\"kind\":\"delete\""), "JSON should contain delete kind");
        assertTrue(json.contains("\"createdAt\""), "JSON should contain createdAt");
    }

    /**
     * 测试：同路径变更合并为净效果
     */
    @Test
    public void testMergeChange() {
        ChangeEntry create = new ChangeEntry("ws", "a.txt", "create", "file");
        ChangeEntry modify = new ChangeEntry("ws", "a.txt", "modify", "file");
        ChangeEntry delete = new ChangeEntry("ws", "a.txt", "delete", "file");

        ChangeEntry createThenModify = FileWatchService.mergeChange(create, modify);
        assertNotNull(createThenModify);
        assertEquals("create", createThenModify.kind);

        assertNull(FileWatchService.mergeChange(create, delete), "create+delete should cancel");

        ChangeEntry deleteThenCreate = FileWatchService.mergeChange(delete, create);
        assertNotNull(deleteThenCreate);
        assertEquals("create", deleteThenCreate.kind);

        ChangeEntry modifyThenDelete = FileWatchService.mergeChange(modify, delete);
        assertNotNull(modifyThenDelete);
        assertEquals("delete", modifyThenDelete.kind);
    }

    /**
     * 测试：ChangeEntry 的 equals 和 hashCode
     */
    @Test
    public void testChangeEntryEquals() {
        ChangeEntry a = new ChangeEntry("ws", "path/to/file", "modify", "file");
        ChangeEntry b = new ChangeEntry("ws", "path/to/file", "modify", "file");
        ChangeEntry c = new ChangeEntry("ws", "other/file", "modify", "file");
        ChangeEntry d = new ChangeEntry("other", "path/to/file", "modify", "file");
        ChangeEntry e = new ChangeEntry("ws", "path/to/file", "create", "file");

        assertEquals(a, b, "same fields should be equal");
        assertEquals(a.hashCode(), b.hashCode(), "hashCode should match");
        assertNotEquals(a, c, "different path should not be equal");
        assertNotEquals(a, d, "different wsId should not be equal");
        assertNotEquals(a, e, "different kind should not be equal");
    }

    /**
     * 测试：整棵目录树被 move 进监听范围时，子项也要补齐 create 事件
     *
     * <p>move 进来只会产生顶层目录的一条 ENTRY_CREATE，子项没有任何事件；
     * 同理「创建目录 → 注册监听」之间的窗口内落盘的文件也收不到事件。
     * 两者都只能靠注册完成后的补扫兜住。</p>
     */
    @Test
    public void testMovedDirTreeIsScanned() throws Exception {
        Path staging = Files.createTempDirectory("fws-staging-");
        try {
            Path pending = staging.resolve("moved");
            Files.createDirectories(pending.resolve("sub"));
            Files.write(pending.resolve("a.txt"), "a".getBytes());
            Files.write(pending.resolve("sub/b.txt"), "b".getBytes());

            Set<String> paths = ConcurrentHashMap.newKeySet();
            service.addRoot("test-ws", tempRoot)
                    .addHandler(changes -> {
                        for (ChangeEntry e : changes) {
                            paths.add(e.path);
                        }
                    });

            startAndWait();

            Files.move(pending, tempRoot.resolve("moved"));

            assertTrue(waitUntil(() -> paths.contains("moved/sub/b.txt"), AWAIT_SEC),
                    "nested children of a moved-in directory should be pushed, got: " + paths);
            assertTrue(paths.contains("moved"), "top dir should be pushed, got: " + paths);
            assertTrue(paths.contains("moved/a.txt"), "direct child should be pushed, got: " + paths);
            assertTrue(paths.contains("moved/sub"), "nested dir should be pushed, got: " + paths);
        } finally {
            walkAndDelete(staging);
        }
    }

    /**
     * 测试：目录被多个根覆盖时（如 FILES 挂载指向工作区子目录）两个根都要收到事件，
     * 且移除其中一个根不能连带掐掉另一个根的监听（同一目录共享同一个 WatchKey）
     */
    @Test
    public void testOverlappingRoots() throws Exception {
        Path shared = tempRoot.resolve("shared");
        Files.createDirectories(shared);

        Set<String> parentPaths = ConcurrentHashMap.newKeySet();
        Set<String> childPaths = ConcurrentHashMap.newKeySet();

        service.addRoot("parent-ws", tempRoot)
                .addHandler(changes -> {
                    for (ChangeEntry e : changes) parentPaths.add(e.path);
                });
        service.addRoot("@child", shared)
                .addHandler(changes -> {
                    for (ChangeEntry e : changes) childPaths.add(e.path);
                });

        startAndWait();

        Files.write(shared.resolve("x.txt"), "x".getBytes());

        assertTrue(waitUntil(() -> parentPaths.contains("shared/x.txt"), AWAIT_SEC),
                "outer root should receive nested change, got: " + parentPaths);
        assertTrue(waitUntil(() -> childPaths.contains("x.txt"), AWAIT_SEC),
                "overlapping inner root should receive the same change, got: " + childPaths);

        // 移除内层根：外层根必须仍然监听该目录
        service.removeRoot("@child");
        childPaths.clear();
        Files.write(shared.resolve("y.txt"), "y".getBytes());

        assertTrue(waitUntil(() -> parentPaths.contains("shared/y.txt"), AWAIT_SEC),
                "outer root must keep watching after the overlapping root was removed, got: " + parentPaths);
        assertTrue(childPaths.isEmpty(), "removed root should no longer receive changes");
    }

    /**
     * 测试：同一实例 stop() 后再 start() 仍能推送变更
     *
     * <p>stop() 的 shutdownNow() 会取消挂起的防抖任务，而「防抖在飞标记」的复位
     * 正是该任务负责的。若 stop() 不主动复位，重启后 CAS 将永久失败，
     * 新 scheduler 又未 shutdown（同步兼底分支进不了）—— 变更只堆积、永不推送。</p>
     */
    @Test
    public void testRestartAfterStopStillPushes() throws Exception {
        List<String> paths = new CopyOnWriteArrayList<>();

        service.addRoot("test-ws", tempRoot)
                .addHandler(changes -> changes.forEach(c -> paths.add(c.path)));

        startAndWait();
        Files.write(tempRoot.resolve("before.md"), "1".getBytes());
        assertTrue(waitUntil(() -> paths.contains("before.md"), AWAIT_SEC),
                "first run should push, got: " + paths);

        // 制造「防抖任务在飞时被 stop 掉」的时序：写入后立即停，不给 flush 窗口
        Files.write(tempRoot.resolve("during.md"), "2".getBytes());
        service.stop();

        paths.clear();
        startAndWait();

        Files.write(tempRoot.resolve("after.md"), "3".getBytes());
        assertTrue(waitUntil(() -> paths.contains("after.md"), AWAIT_SEC),
                "restarted instance must still push changes, got: " + paths);
    }
}
