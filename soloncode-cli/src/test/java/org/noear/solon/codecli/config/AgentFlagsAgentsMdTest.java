package org.noear.solon.codecli.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工作区上下文的 AGENTS.md 解析契约测试：
 *
 * <p>多工作区场景下按工作区路径解析（{@link AgentFlags#getAgentsMd(String)}），
 * 优先级必须是「工作区 .soloncode/AGENTS.md &gt; ~/.soloncode/AGENTS.md &gt; 无」，
 * 且不受进程启动目录（user.dir）影响——否则通过 web/API 打开非启动目录的项目时
 * 会错误读到启动目录的配置。</p>
 */
public class AgentFlagsAgentsMdTest {

    private Path tempHome;
    private Path workspaceA;
    private Path workspaceB;
    private Path startupDir;
    private String originalUserHome;
    private String originalUserDir;

    @BeforeEach
    void setUp() throws Exception {
        tempHome = Files.createTempDirectory("soloncode-agents-home-");
        workspaceA = Files.createTempDirectory("soloncode-agents-ws-a-");
        workspaceB = Files.createTempDirectory("soloncode-agents-ws-b-");
        startupDir = Files.createTempDirectory("soloncode-agents-startup-");
        originalUserHome = System.getProperty("user.home");
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.home", tempHome.toString());
        // 进程启动目录指向一个无关目录，用于验证工作区解析不依赖 user.dir
        System.setProperty("user.dir", startupDir.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
        if (originalUserDir != null) {
            System.setProperty("user.dir", originalUserDir);
        }
        deleteRecursively(tempHome);
        deleteRecursively(workspaceA);
        deleteRecursively(workspaceB);
        deleteRecursively(startupDir);
    }

    @Test
    void workspaceAgentsMdWinsOverUserHome() throws Exception {
        writeAgentsMd(workspaceA, "# workspace A agents");
        writeAgentsMd(tempHome, "# user home agents");

        assertEquals("# workspace A agents", AgentFlags.getAgentsMd(workspaceA.toString()));
    }

    @Test
    void fallsBackToUserHomeWhenWorkspaceHasNone() throws Exception {
        // workspaceA 无 AGENTS.md，用户目录有
        writeAgentsMd(tempHome, "# user home agents");

        assertEquals("# user home agents", AgentFlags.getAgentsMd(workspaceA.toString()));
    }

    @Test
    void returnsNullWhenNeitherWorkspaceNorUserHomeHasIt() throws Exception {
        // 用户目录只有 .soloncode 目录本身，没有 AGENTS.md
        Files.createDirectories(tempHome.resolve(AgentFlags.getHarnessHome()));

        assertNull(AgentFlags.getAgentsMd(workspaceA.toString()));
    }

    @Test
    void ignoresStartupDirWhenWorkspaceGiven() throws Exception {
        // 启动目录有 AGENTS.md，但工作区解析不应读它
        writeAgentsMd(startupDir, "# startup agents");
        Files.createDirectories(tempHome.resolve(AgentFlags.getHarnessHome()));

        assertNull(AgentFlags.getAgentsMd(workspaceB.toString()));
    }

    @Test
    void differentWorkspacesLoadTheirOwnAgentsMd() throws Exception {
        writeAgentsMd(workspaceA, "# workspace A agents");
        writeAgentsMd(workspaceB, "# workspace B agents");

        assertEquals("# workspace A agents", AgentFlags.getAgentsMd(workspaceA.toString()));
        assertEquals("# workspace B agents", AgentFlags.getAgentsMd(workspaceB.toString()));
    }

    @Test
    void emptyWorkspacePathDegradesToStartupDirResolution() throws Exception {
        // 空串退化为按启动目录解析（与无参版本同语义）
        writeAgentsMd(startupDir, "# startup agents");

        assertEquals("# startup agents", AgentFlags.getAgentsMd(""));
    }

    private static void writeAgentsMd(Path baseDir, String content) throws Exception {
        Path dir = Paths.get(baseDir.toString(), AgentFlags.getHarnessHome());
        Files.createDirectories(dir);
        Path file = dir.resolve(AgentFlags.NAME_AGENTS_MD);
        try (InputStream in = new java.io.ByteArrayInputStream(content.getBytes("utf-8"))) {
            Files.copy(in, file);
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walk(root)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (Exception ignore) {
                        // 清理失败不影响断言
                    }
                });
    }
}
