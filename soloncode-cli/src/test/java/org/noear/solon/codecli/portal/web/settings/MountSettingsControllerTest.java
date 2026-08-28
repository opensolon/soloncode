package org.noear.solon.codecli.portal.web.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.core.handle.Result;

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
    void setUp() {
        oldUserHome = System.getProperty("user.home");
        oldUserDir = System.getProperty("user.dir");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        System.setProperty("user.dir", tempDir.resolve("workspace").toString());

        HarnessEngine engine = HarnessEngine.of(tempDir.resolve("workspace").toString(), ".soloncode/")
                .mountAdd(MountDir.builder().alias("@global-skills").type(MountType.SKILLS).path("~/.soloncode/skills/").build())
                .mountAdd(MountDir.builder().alias("@user-skills").type(MountType.SKILLS).path("~/.soloncode/skills/").primary(true).build())
                .mountAdd(MountDir.builder().alias("@workspace-skills").type(MountType.SKILLS).path("./.soloncode/skills/").primary(true).build())
                .build();
        controller = new MountSettingsController(engine, new AgentSettings(), null, null);
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
        assertEquals(2, mounts.size());
        assertTrue(mounts.stream().anyMatch(item -> "@user-skills".equals(item.get("alias"))
                && "user".equals(item.get("scope"))));
        assertTrue(mounts.stream().anyMatch(item -> "@workspace-skills".equals(item.get("alias"))
                && "workspace".equals(item.get("scope"))));
        assertFalse(mounts.stream().anyMatch(item -> "@global-skills".equals(item.get("alias"))));
    }
}
