package org.noear.solon.codecli.workspace.filer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.talents.mount.Mount;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.ai.talents.mount.source.FileMountSource;
import org.noear.solon.core.handle.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件面板与挂载层的链接策略一致性。
 *
 * <p>前置缺陷：挂载层允许跟随指向根外的符号链接 / Windows Junction（技能目录整体搬到 D 盘
 * 再链回是常见做法），但文件面板仍按最后一段是不是链接一律 403，形成
 * 「树里能看见、点开读不了」的半开半闭状态。</p>
 *
 * <p>本用例锁定三条链路（树 / 读 / 原始读）在两个方向上都必须与来源策略一致：</p>
 * <ul>
 *   <li>来源开启跟随：链接目录可列出、链接文件可读取</li>
 *   <li>来源保持严格：链接仍按越界拦截</li>
 * </ul>
 */
public class FileServiceSymlinkTest {

    private Path tempRoot;
    private Path outside;
    private Path workspace;

    @BeforeEach
    public void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("filer-symlink-");
        workspace = Files.createDirectories(tempRoot.resolve("ws"));
        outside = Files.createDirectories(tempRoot.resolve("outside"));

        // 挂载根之外的真实内容：链接指向它
        Files.createDirectories(outside.resolve("skill-dir"));
        Files.write(outside.resolve("skill-dir/SKILL.md"), "linked skill".getBytes(StandardCharsets.UTF_8));
        Files.write(outside.resolve("note.txt"), "linked note".getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (tempRoot != null) {
            Files.walk(tempRoot)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    private void link(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            // Windows 非管理员无建链接权限时跳过（junction 行为已由挂载层单测覆盖）
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic link unsupported: " + e.getMessage());
        }
    }

    /** 构造一个带指定链接策略的 FILES 挂载。 */
    private FileService serviceWithMount(String alias, Path root, boolean follow) throws Exception {
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), ".soloncode/")
                .sessionProvider(new org.noear.solon.codecli.session.SessionManager(workspace.toString()))
                .build();
        engine.addMount(Mount.builder()
                .alias(alias)
                .type(MountType.FILES)
                .source(FileMountSource.of(root, follow))
                .writeable(true)
                .build());
        return new FileService(workspace.toString(), engine);
    }

    private boolean hasPath(List<Map> tree, String suffix) {
        return tree.stream().anyMatch(m -> String.valueOf(m.get("path")).endsWith(suffix));
    }

    @Test
    public void followEnabled_mountShouldListAndReadThroughSymlink() throws Exception {
        Path mountRoot = Files.createDirectories(tempRoot.resolve("mount-follow"));
        link(mountRoot.resolve("skill-dir"), outside.resolve("skill-dir"));
        link(mountRoot.resolve("note.txt"), outside.resolve("note.txt"));

        FileService fileService = serviceWithMount("@follow", mountRoot, true);

        // 树里能看到链接目录与链接文件
        Result<List<Map>> tree = fileService.tree("@follow", "", 1);
        assertEquals(200, tree.getCode());
        assertTrue(hasPath(tree.getData(), "skill-dir"), "链接目录必须出现在文件树里: " + tree.getData());
        assertTrue(hasPath(tree.getData(), "note.txt"), "链接文件必须出现在文件树里");

        // 链接目录可以继续展开
        Result<List<Map>> sub = fileService.tree("@follow", "skill-dir", 1);
        assertEquals(200, sub.getCode());
        assertTrue(hasPath(sub.getData(), "SKILL.md"), "链接目录内文件必须可列出: " + sub.getData());

        // 文本读与原始读都拿到链接目标的真实内容
        Result<Map> read = fileService.read("@follow", "note.txt");
        assertEquals(200, read.getCode());
        assertEquals("linked note", read.getData().get("content"));

        Path raw = fileService.resolveFilePath("@follow", "note.txt");
        assertEquals("linked note", new String(Files.readAllBytes(raw), StandardCharsets.UTF_8));
    }

    @Test
    public void followDisabled_mountShouldStillRejectSymlink() throws Exception {
        Path mountRoot = Files.createDirectories(tempRoot.resolve("mount-strict"));
        link(mountRoot.resolve("skill-dir"), outside.resolve("skill-dir"));
        link(mountRoot.resolve("note.txt"), outside.resolve("note.txt"));

        FileService fileService = serviceWithMount("@strict", mountRoot, false);

        // 严格来源：链接一律不出现在树里、也不可读
        Result<List<Map>> tree = fileService.tree("@strict", "", 1);
        assertEquals(200, tree.getCode());
        assertTrue(!hasPath(tree.getData(), "skill-dir"), "严格来源不得列出链接目录");
        assertTrue(!hasPath(tree.getData(), "note.txt"), "严格来源不得列出链接文件");

        assertEquals(403, fileService.read("@strict", "note.txt").getCode());
        // 链接目录不得被当作展开根，否则会绕开列表过滤直接读到根外内容
        assertEquals(403, fileService.tree("@strict", "skill-dir", 1).getCode());
    }

    @Test
    public void followEnabled_shouldStillRejectParentTraversal() throws Exception {
        Path mountRoot = Files.createDirectories(tempRoot.resolve("mount-escape"));
        FileService fileService = serviceWithMount("@escape", mountRoot, true);

        // 语法级越界与链接无关，跟随模式下照常拒绝
        assertEquals(400, fileService.read("@escape", "../note.txt").getCode());
    }

    @Test
    public void writeThroughSymlinkShouldUpdateTargetNotReplaceLink() throws Exception {
        Path mountRoot = Files.createDirectories(tempRoot.resolve("mount-write"));
        Path target = outside.resolve("editable.txt");
        Files.write(target, "before".getBytes(StandardCharsets.UTF_8));
        link(mountRoot.resolve("editable.txt"), target);

        FileService fileService = serviceWithMount("@write", mountRoot, true);
        Result<Map> res = fileService.write("@write", "editable.txt", "after");
        assertEquals(200, res.getCode());

        // 写入必须落到链接目标，且链接本身不能被原子写替换成普通文件
        assertEquals("after", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertTrue(Files.isSymbolicLink(mountRoot.resolve("editable.txt")), "链接必须保留");
    }
}
