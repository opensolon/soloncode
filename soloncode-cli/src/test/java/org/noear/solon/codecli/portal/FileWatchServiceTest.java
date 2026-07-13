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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
     * 测试：buildFrontendJson 生成正确的 JSON 结构
     */
    @Test
    public void testBuildFrontendJson() {
        List<ChangeEntry> changes = java.util.Arrays.asList(
                new ChangeEntry("workspace", "src/Foo.java", "create", "file"),
                new ChangeEntry("@mount1", "bar.txt", "delete", "file")
        );

        String json = FileWatchService.buildFrontendJson(changes);

        assertNotNull(json);
        assertTrue(json.contains("\"type\":\"filer_change\""), "JSON should contain type field");
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
}
