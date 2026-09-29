package org.noear.solon.codecli.portal.web.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.talents.mount.source.FileMountSource;
import org.noear.solon.ai.talents.mount.Mount;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.util.MountPathUtil;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.codecli.workspace.WorkspaceMeta;
import org.noear.solon.core.handle.Result;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MountSettingsControllerTest {
    @TempDir
    Path tempDir;

    private String oldUserHome;
    private String oldUserDir;
    private MountSettingsController controller;

    @BeforeEach
    void setUp() throws Exception {
        oldUserHome = System.getProperty("user.home");
        oldUserDir = System.getProperty("user.dir");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        System.setProperty("user.dir", tempDir.resolve("workspace").toString());

        // 预建挂载目录：realPath 解析依赖目录存在，否则去重键退化为别名
        Files.createDirectories(tempDir.resolve("home/.soloncode/skills"));
        Files.createDirectories(tempDir.resolve("workspace/.soloncode/skills"));

        String workspacePath = tempDir.resolve("workspace").toString();

        HarnessEngine engine = HarnessEngine.of(workspacePath, ".soloncode/")
                .sessionProvider(new org.noear.solon.codecli.session.SessionManager(workspacePath))
                .mountAdd(Mount.builder().alias("@global-skills").type(MountType.SKILLS)
                        .source(FileMountSource.of(MountPathUtil.resolve("~/.soloncode/skills/", workspacePath))).build())
                .mountAdd(Mount.builder().alias("@user-skills").type(MountType.SKILLS).primary(true)
                        .source(FileMountSource.of(MountPathUtil.resolve("~/.soloncode/skills/", workspacePath))).build())
                .mountAdd(Mount.builder().alias("@workspace-skills").type(MountType.SKILLS).primary(true)
                        .source(FileMountSource.of(MountPathUtil.resolve("./.soloncode/skills/", workspacePath))).build())
                .build();

        // 直接构建工作区上下文并覆写 getOrCreate，绕开需要 Solon 运行时的完整启动链
        WorkspaceContext workspaceContext = new WorkspaceContext(
                new WorkspaceMeta("default", "workspace",
                        tempDir.resolve("workspace").toString(), System.currentTimeMillis(), true),
                engine, null, null, null, null, null, null, new AgentSettings());

        WorkspaceManager workspaceManager = new WorkspaceManager(new AgentSettings()) {
            @Override
            public WorkspaceContext getOrCreate(String workspaceIdOrPath) {
                return workspaceContext;
            }
        };

        controller = new MountSettingsController(workspaceManager);
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", oldUserHome);
        System.setProperty("user.dir", oldUserDir);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsOneInstallTargetPerDirectoryAndMarksWorkspaceScope() {
        Result result = controller.mountsList(null);
        assertEquals(200, result.getCode());

        List<Map<String, Object>> mounts = (List<Map<String, Object>>) result.getData();
        // @harness-agents（内置 agents 挂载，4.1.1 起 visible=true）与两个 SKILLS 目录
        assertEquals(3, mounts.size());
        assertTrue(mounts.stream().anyMatch(item -> "@user-skills".equals(item.get("alias"))
                && "user".equals(item.get("scope"))
                && "file".equals(item.get("scheme"))
                && ((Map<?, ?>) item.get("actions")).get("openLocal").equals(Boolean.TRUE)));
        assertTrue(mounts.stream().anyMatch(item -> "@workspace-skills".equals(item.get("alias"))
                && "workspace".equals(item.get("scope"))));
        // 内置挂载可见但非文件目录，无真实路径
        assertTrue(mounts.stream().anyMatch(item -> "@harness-agents".equals(item.get("alias"))
                && Boolean.FALSE.equals(item.get("writeable"))
                && "classpath".equals(item.get("scheme"))
                && Boolean.FALSE.equals(((Map<?, ?>) item.get("actions")).get("openLocal"))));
        assertFalse(mounts.stream().anyMatch(item -> "@global-skills".equals(item.get("alias"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsOnlyFirstLevelFilesAndLimitsPreviewToFiftyItems() throws Exception {
        Path root = tempDir.resolve("workspace/files");
        Files.createDirectories(root.resolve("aaa-dir"));
        Files.write(root.resolve("README.md"), "read me".getBytes("UTF-8"));
        for (int i = 0; i < 51; i++) {
            Files.write(root.resolve(String.format("file-%02d.txt", i)), new byte[]{1});
        }

        controller.engine().addMount(Mount.builder()
                .alias("@workspace-files")
                .type(MountType.FILES)
                .source(FileMountSource.of(root))
                .build());

        Result result = controller.mountsContent("@workspace-files", "SKILLS");
        assertEquals(200, result.getCode());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        assertEquals(50, items.size());
        assertEquals(53, data.get("total"));
        assertEquals(Boolean.TRUE, data.get("truncated"));
        assertEquals(50, data.get("limit"));
        assertTrue(items.stream().anyMatch(item -> "aaa-dir".equals(item.get("name"))
                && Boolean.TRUE.equals(item.get("directory"))));
        assertTrue(items.stream().anyMatch(item -> "README.md".equals(item.get("name"))
                && Boolean.FALSE.equals(item.get("directory"))));
        assertTrue(items.stream().allMatch(item -> !String.valueOf(item.get("path")).contains("/")));
    }
}
