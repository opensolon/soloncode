package org.noear.solon.codecli.portal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FilerIgnoreRules 单元测试
 *
 * <p>核心不变式：<b>文件树里能展示的节点，必须也在文件监听范围内</b>。
 * 两侧规则一旦分叉，就会出现「树里看得见、但改动永远不推送」的静默不刷新
 * （历史上 venv/vendor 与 .uploads 就是这么漏的）。</p>
 */
public class FilerIgnoreRulesTest {

    private Path tempRoot;

    @BeforeEach
    public void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("filer-rules-");
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

    @Test
    public void testExcludedNames() {
        String[] excluded = {
                ".git", ".idea", ".vscode", ".settings", ".soloncode", ".claude", ".opencode",
                ".gradle", ".mvn", ".pytest_cache", "__pycache__", ".DS_Store",
                "node_modules", "venv", "vendor", "target", "build"
        };
        for (String name : excluded) {
            assertTrue(FilerIgnoreRules.isIgnoredName(name), name + " should be ignored");
        }
    }

    @Test
    public void testHiddenWhitelistAndNormalNames() {
        assertFalse(FilerIgnoreRules.isIgnoredName(".uploads"), ".uploads is whitelisted for directories");
        assertTrue(FilerIgnoreRules.isIgnoredName(".env"), "other dot-prefixed entries stay ignored");

        assertFalse(FilerIgnoreRules.isIgnoredName("src"));
        assertFalse(FilerIgnoreRules.isIgnoredName("README.md"));
        assertFalse(FilerIgnoreRules.isIgnoredName(""), "empty name should not be ignored");
        assertFalse(FilerIgnoreRules.isIgnoredName(null), "null name should not be ignored");

        // 白名单只对目录生效：同名普通文件仍按隐藏文件处理
        assertTrue(FilerIgnoreRules.isIgnoredName(".uploads", false));
    }

    @Test
    public void testIgnoredPathChecksEverySegment() {
        assertTrue(FilerIgnoreRules.isIgnoredPath(Paths.get("src/node_modules/lib/index.js")));
        assertTrue(FilerIgnoreRules.isIgnoredPath(Paths.get("a/.git/config")));
        assertTrue(FilerIgnoreRules.isIgnoredPath(Paths.get("web/vendor/jquery.js")));

        assertFalse(FilerIgnoreRules.isIgnoredPath(Paths.get("src/main/java/App.java")));
        assertFalse(FilerIgnoreRules.isIgnoredPath(Paths.get(".uploads/photo.png")),
                ".uploads is shown in the tree, so it must be watched as well");
        assertFalse(FilerIgnoreRules.isIgnoredPath(null));
    }

    /**
     * 锁死不变式：展示侧（FileService）与本规则同源，两者判定必须一致。
     */
    @Test
    public void testFileServiceSharesTheSameRules() throws Exception {
        Method isSkippedName = Class
                .forName("org.noear.solon.codecli.portal.web.service.FileService")
                .getDeclaredMethod("isSkippedName", File.class);
        isSkippedName.setAccessible(true);

        String[] dirs = {"venv", "vendor", "node_modules", "target", ".git", ".uploads", "src"};
        for (String name : dirs) {
            Path dir = tempRoot.resolve(name);
            Files.createDirectories(dir);
            boolean hiddenByTree = (Boolean) isSkippedName.invoke(null, dir.toFile());
            boolean ignoredByWatcher = FilerIgnoreRules.isIgnoredName(name, true);
            assertEquals(ignoredByWatcher, hiddenByTree,
                    "tree visibility and watch coverage must agree on directory: " + name);
        }

        String[] files = {"a.txt", ".env", ".DS_Store"};
        for (String name : files) {
            Path file = tempRoot.resolve(name);
            Files.write(file, "x".getBytes());
            boolean hiddenByTree = (Boolean) isSkippedName.invoke(null, file.toFile());
            boolean ignoredByWatcher = FilerIgnoreRules.isIgnoredName(name, false);
            assertEquals(ignoredByWatcher, hiddenByTree,
                    "tree visibility and watch coverage must agree on file: " + name);
        }
    }

    /**
     * allow 配置：把依赖目录从默认排除表里放行（放行后既展示也监听，不破坏不变式）
     */
    @Test
    public void testAllowReleasesDefaultExcludedDirs() {
        Set<String> allow = FilerIgnoreRules.parseNames(" venv , vendor ");
        Set<String> excluded = FilerIgnoreRules.resolveExcluded(allow, Collections.emptySet());

        assertFalse(excluded.contains("venv"), "venv should be released by allow");
        assertFalse(excluded.contains("vendor"), "vendor should be released by allow");
        assertTrue(excluded.contains("node_modules"), "unrelated defaults stay excluded");
    }

    /**
     * allow 中点号开头的项需同时进隐藏白名单 ——
     * 否则仅从排除表移除仍会被「点号开头一律忽略」规则拦下，配置看似生效实际无效。
     */
    @Test
    public void testAllowDotPrefixedDirEntersVisibleWhitelist() {
        Set<String> allow = FilerIgnoreRules.parseNames(".venv");
        Set<String> excluded = FilerIgnoreRules.resolveExcluded(allow, Collections.emptySet());
        Set<String> visibleHidden = FilerIgnoreRules.resolveVisibleHidden(allow, excluded);

        assertTrue(visibleHidden.contains(".venv"), ".venv must be whitelisted to be actually visible");
        assertTrue(visibleHidden.contains(".uploads"), "default whitelist entries are kept");
    }

    /**
     * extra 优先于 allow：同时出现时以「排除」为准，且不能漏到隐藏白名单里
     */
    @Test
    public void testExtraExcludesAndWinsOverAllow() {
        Set<String> allow = FilerIgnoreRules.parseNames(".uploads,venv");
        Set<String> extra = FilerIgnoreRules.parseNames("dist,.uploads");
        Set<String> excluded = FilerIgnoreRules.resolveExcluded(allow, extra);
        Set<String> visibleHidden = FilerIgnoreRules.resolveVisibleHidden(allow, excluded);

        assertTrue(excluded.contains("dist"), "extra should be excluded");
        assertTrue(excluded.contains(".uploads"), "extra wins over allow");
        assertFalse(visibleHidden.contains(".uploads"), "excluded dirs must never stay in the visible whitelist");
        assertFalse(excluded.contains("venv"));
    }

    @Test
    public void testParseNamesIgnoresBlankAndNull() {
        assertTrue(FilerIgnoreRules.parseNames(null).isEmpty());
        assertTrue(FilerIgnoreRules.parseNames("   ").isEmpty());
        assertTrue(FilerIgnoreRules.parseNames(" , ,").isEmpty());
        assertEquals(1, FilerIgnoreRules.parseNames(" a , a ").size());
    }
}
