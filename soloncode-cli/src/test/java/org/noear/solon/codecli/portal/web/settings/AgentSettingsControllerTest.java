package org.noear.solon.codecli.portal.web.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.core.handle.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentSettingsControllerTest {
    @TempDir
    Path tempDir;

    private String oldUserHome;
    private String oldUserDir;
    private HarnessEngine engine;
    private AgentSettingsController controller;

    @BeforeEach
    void setUp() {
        oldUserHome = System.getProperty("user.home");
        oldUserDir = System.getProperty("user.dir");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        System.setProperty("user.dir", tempDir.resolve("workspace").toString());

        engine = HarnessEngine.of(tempDir.resolve("workspace").toString(), ".soloncode/")
                .mountAdd(MountDir.builder().alias(AgentSettingsController.USER_ALIAS).type(MountType.AGENTS).path("~/.soloncode/agents/").primary(true).build())
                .mountAdd(MountDir.builder().alias(AgentSettingsController.WORKSPACE_ALIAS).type(MountType.AGENTS).path("./.soloncode/agents/").primary(true).build())
                .build();
        controller = new AgentSettingsController(engine, new AgentSettings(), null, null);
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", oldUserHome);
        System.setProperty("user.dir", oldUserDir);
    }

    @Test
    void addGetAndRemoveWorkspaceAgent() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "backend-dev");
        body.put("scope", "workspace");
        body.put("description", "backend expert");
        body.put("tools", Arrays.asList("read", "edit"));
        body.put("systemPrompt", "Do backend work.");

        Result added = controller.agentsAdd(ONode.ofBean(body).toJson());
        assertEquals(200, added.getCode());

        Path file = java.nio.file.Paths.get(engine.getWorkspace(), ".soloncode/agents/backend-dev.md").toAbsolutePath().normalize();
        assertTrue(Files.exists(file));
        String markdown = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("name: \"backend-dev\""));
        assertTrue(markdown.contains("description: \"backend expert\""));
        assertTrue(markdown.contains("tools: [\"read\",\"edit\"]"));
        assertFalse(markdown.contains("disallowedTools"));
        assertFalse(markdown.contains("maxTurns"));
        assertTrue(markdown.endsWith("Do backend work.\n"));

        Result detail = controller.agentsGet("backend-dev", "workspace");
        assertEquals(200, detail.getCode());
        String detailJson = ONode.ofBean(detail.getData()).toJson();
        assertTrue(detailJson.contains("backend expert"));
        assertTrue(detailJson.contains("Do backend work."));
        assertFalse(detailJson.contains("markdown"));

        Result removed = controller.agentsRemove(json("name", "backend-dev", "scope", "workspace"));
        assertEquals(200, removed.getCode());
        assertFalse(Files.exists(file));
    }

    @Test
    void rejectInvalidNameAndMissingFormFields() {
        Result invalidName = controller.agentsAdd(formJson("../bad", "user", "bad", "bad"));
        assertNotEquals(200, invalidName.getCode());

        Result missingDescription = controller.agentsAdd(formJson("good", "user", "", "prompt"));
        assertNotEquals(200, missingDescription.getCode());

        Result missingPrompt = controller.agentsAdd(formJson("good", "user", "description", ""));
        assertNotEquals(200, missingPrompt.getCode());
    }

    @Test
    void updatePreservesAdvancedFrontMatter() throws Exception {
        Path root = java.nio.file.Paths.get(engine.getWorkspace(), ".soloncode/agents").toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path file = root.resolve("advanced.md");
        String original = "---\n"
                + "name: \"advanced\"\n"
                + "description: \"old\"\n"
                + "tools: [\"read\"]\n"
                + "model: \"coder\"\n"
                + "hidden: true\n"
                + "hooks:\n  before: \"check\"\n"
                + "---\n\nOld prompt.\n";
        Files.write(file, original.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "advanced");
        body.put("scope", "workspace");
        body.put("originalName", "advanced");
        body.put("originalScope", "workspace");
        body.put("description", "new description");
        body.put("tools", Arrays.asList("read", "grep"));
        body.put("systemPrompt", "New prompt.");

        Result updated = controller.agentsUpdate(ONode.ofBean(body).toJson());
        assertEquals(200, updated.getCode());
        String markdown = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("description: \"new description\""));
        assertTrue(markdown.contains("tools: [\"read\",\"grep\"]"));
        assertTrue(markdown.contains("model: \"coder\""));
        assertTrue(markdown.contains("hidden: true"));
        assertTrue(markdown.contains("hooks:\n  before: \"check\""));
        assertTrue(markdown.endsWith("New prompt.\n"));
    }

    @Test
    void emptyToolsMeansNoToolPermission() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "text-only");
        body.put("scope", "workspace");
        body.put("description", "text only");
        body.put("tools", java.util.Collections.emptyList());
        body.put("systemPrompt", "Answer with text.");

        assertEquals(200, controller.agentsAdd(ONode.ofBean(body).toJson()).getCode());
        Path file = java.nio.file.Paths.get(engine.getWorkspace(), ".soloncode/agents/text-only.md").toAbsolutePath().normalize();
        String markdown = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("tools: []"));
    }

    @Test
    void invalidAgentCanBeOpenedAndRepaired() throws Exception {
        Path root = java.nio.file.Paths.get(engine.getWorkspace(), ".soloncode/agents").toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path file = root.resolve("broken.md");
        Files.write(file, "---\nname: other\n---\n\nRecover this prompt.\n".getBytes(StandardCharsets.UTF_8));

        Result detail = controller.agentsGet("broken", "workspace");
        assertEquals(200, detail.getCode());
        String detailJson = ONode.ofBean(detail.getData()).toJson();
        assertTrue(detailJson.contains("\"valid\":false"));
        assertTrue(detailJson.contains("Recover this prompt."));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "broken");
        body.put("scope", "workspace");
        body.put("originalName", "broken");
        body.put("originalScope", "workspace");
        body.put("description", "repaired");
        body.put("tools", Arrays.asList("read"));
        body.put("systemPrompt", "Repaired prompt.");
        assertEquals(200, controller.agentsUpdate(ONode.ofBean(body).toJson()).getCode());
        assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains("name: \"broken\""));
    }

    @Test
    void listMarksWorkspaceOverrideAsEffective() throws Exception {
        Path userRoot = tempDir.resolve("home/.soloncode/agents");
        Path workspaceRoot = tempDir.resolve("workspace/.soloncode/agents");
        Files.createDirectories(userRoot);
        Files.createDirectories(workspaceRoot);
        String user = "---\nname: same\ndescription: user\ntools: [\"read\"]\n---\n\nUser.\n";
        String workspace = "---\nname: same\ndescription: workspace\ntools: [\"grep\"]\n---\n\nWorkspace.\n";
        Files.write(userRoot.resolve("same.md"), user.getBytes(StandardCharsets.UTF_8));
        Files.write(workspaceRoot.resolve("same.md"), workspace.getBytes(StandardCharsets.UTF_8));

        String json = ONode.ofBean(controller.agentsList().getData()).toJson();
        assertFalse(json.contains("\"overriddenBy\""));
        assertTrue(json.contains("\"overridesUser\":true"));
    }

    @Test
    void listShowsOnlyEffectiveDefinitionAndWorkspaceBadgeSource() throws Exception {
        Path userRoot = tempDir.resolve("home/.soloncode/agents");
        Path workspaceRoot = tempDir.resolve("workspace/.soloncode/agents");
        Files.createDirectories(userRoot);
        Files.createDirectories(workspaceRoot);
        String user = "---\nname: general\ndescription: user override\ntools: [\"read\"]\n---\n\nUser.\n";
        String workspace = "---\nname: workspace-only\ndescription: workspace\ntools: [\"grep\"]\n---\n\nWorkspace.\n";
        Files.write(userRoot.resolve("general.md"), user.getBytes(StandardCharsets.UTF_8));
        Files.write(workspaceRoot.resolve("workspace-only.md"), workspace.getBytes(StandardCharsets.UTF_8));

        String json = ONode.ofBean(controller.agentsList().getData()).toJson();
        assertTrue(json.contains("user override"));
        assertTrue(json.contains("workspace-only"));
        assertFalse(json.contains("\"scope\":\"builtin\""));
        assertFalse(json.contains("当前生效"));
    }

    @Test
    void editingBuiltinCreatesUserOverride() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "general");
        body.put("scope", "user");
        body.put("sourceName", "general");
        body.put("sourceScope", "builtin");
        body.put("description", "custom general");
        body.put("tools", Arrays.asList("read"));
        body.put("systemPrompt", "Custom general prompt.");

        assertEquals(200, controller.agentsAdd(ONode.ofBean(body).toJson()).getCode());
        Path file = tempDir.resolve("home/.soloncode/agents/general.md");
        assertTrue(Files.exists(file));
        assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains("custom general"));
    }

    @Test
    void editingWorkspaceAgentCanMoveToUserScope() throws Exception {
        Map<String, Object> add = new LinkedHashMap<>();
        add.put("name", "move-agent");
        add.put("scope", "workspace");
        add.put("description", "old");
        add.put("tools", Arrays.asList("read"));
        add.put("systemPrompt", "old prompt");
        assertEquals(200, controller.agentsAdd(ONode.ofBean(add).toJson()).getCode());

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("name", "move-agent");
        update.put("scope", "user");
        update.put("originalName", "move-agent");
        update.put("originalScope", "workspace");
        update.put("description", "new");
        update.put("tools", Arrays.asList("grep"));
        update.put("systemPrompt", "new prompt");
        assertEquals(200, controller.agentsUpdate(ONode.ofBean(update).toJson()).getCode());
        assertTrue(Files.exists(tempDir.resolve("home/.soloncode/agents/move-agent.md")));
        assertFalse(Files.exists(tempDir.resolve("workspace/.soloncode/agents/move-agent.md")));
    }
    @Test
    void builtinStillReadableAfterOverrideFileExists() throws Exception {
        Path root = java.nio.file.Paths.get(engine.getWorkspace(), ".soloncode/agents").toAbsolutePath().normalize();
        Files.createDirectories(root);
        Files.write(root.resolve("general.md"), "---\nname: general\ndescription: override\ntools: [\"read\"]\n---\n\nOverride.\n".getBytes(StandardCharsets.UTF_8));
        engine.getAgentManager().refreshByMountAlias(AgentSettingsController.WORKSPACE_ALIAS);

        Result builtin = controller.agentsGet("general", "builtin");
        assertEquals(200, builtin.getCode());
        assertFalse(ONode.ofBean(builtin.getData()).toJson().contains("Override."));
    }


    @Test
    void rejectOversizedDescription() {
        char[] chars = new char[1001];
        Arrays.fill(chars, 'a');
        Result result = controller.agentsAdd(formJson("large", "workspace", new String(chars), "prompt"));
        assertNotEquals(200, result.getCode());
    }

    @Test
    void builtinAgentCanBeReadButNotRemovedAsBuiltin() {
        Result detail = controller.agentsGet("general", "builtin");
        assertEquals(200, detail.getCode());
        String detailJson = ONode.ofBean(detail.getData()).toJson();
        assertTrue(detailJson.contains("general"));
        assertTrue(detailJson.contains("systemPrompt"));

        Result removed = controller.agentsRemove(json("name", "general", "scope", "builtin"));
        assertNotEquals(200, removed.getCode());
    }

    private String formJson(String name, String scope, String description, String systemPrompt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("scope", scope);
        data.put("description", description);
        data.put("tools", Arrays.asList("read", "grep"));
        data.put("systemPrompt", systemPrompt);
        return ONode.ofBean(data).toJson();
    }

    private String json(String... pairs) {
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            data.put(pairs[i], pairs[i + 1]);
        }
        return ONode.ofBean(data).toJson();
    }
}
