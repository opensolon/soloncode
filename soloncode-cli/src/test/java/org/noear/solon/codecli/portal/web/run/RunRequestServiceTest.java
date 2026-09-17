package org.noear.solon.codecli.portal.web.run;

import org.noear.snack4.ONode;
import org.noear.solon.codecli.workspace.WorkspaceMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RunRequestService 请求规范化测试
 *
 * @author noear 2026/8/28 created
 */
class RunRequestServiceTest {

    private RunRequestService service;

    @BeforeEach
    void setUp() {
        service = new RunRequestService(null) {
            @Override
            protected List<WorkspaceMeta> listKnownWorkspaces() {
                return Arrays.asList(
                        new WorkspaceMeta("default-id", "launch", "/tmp/ws-default", 0L, true),
                        new WorkspaceMeta("ws-1", "proj-a", "/tmp/proj-a", 0L, false));
            }
        };
    }

    private RunRequestService.NormalizedRequest normalize(String json) throws Exception {
        return service.normalize(ONode.ofJson(json));
    }

    // ===== 基础校验 =====

    @Test
    void promptIsRequired() {
        assertThrows(RunRequestService.BadRequestException.class, () -> normalize("{}"));
        assertThrows(RunRequestService.BadRequestException.class, () -> normalize("{\"prompt\":\"  \"}"));
    }

    @Test
    void minimalRequestUsesDefaultWorkspace() throws Exception {
        RunRequestService.NormalizedRequest req = normalize("{\"prompt\":\"hi\"}");
        assertEquals("hi", req.prompt);
        assertEquals("/tmp/ws-default", req.workspacePath);
        assertNull(req.sessionId); // 无会话维度不锁
        assertTrue(req.argv.isEmpty());
    }

    // ===== options 字段映射 =====

    @Test
    void mapsAllScalarOptions() throws Exception {
        RunRequestService.NormalizedRequest req = normalize("{\"prompt\":\"p\",\"options\":{"
                + "\"output_format\":\"stream-json\",\"model\":\"sonnet\",\"max_turns\":15,"
                + "\"session_id\":\"s1\",\"resume\":\"s2\",\"fallback_model\":\"haiku\","
                + "\"json_schema\":{\"type\":\"object\"},\"max_budget_usd\":2.5}}");
        assertTrue(req.argv.contains("--output-format=stream-json"));
        assertTrue(req.argv.contains("--model=sonnet"));
        assertTrue(req.argv.contains("--max-turns=15"));
        assertTrue(req.argv.contains("--session-id=s1"));
        assertTrue(req.argv.contains("--resume=s2"));
        assertTrue(req.argv.contains("--fallback-model=haiku"));
        assertTrue(req.argv.stream().anyMatch(f -> f.startsWith("--json-schema=")));
        assertTrue(req.argv.contains("--max-budget-usd=2.5"));
    }

    @Test
    void mapsBooleanOptions() throws Exception {
        RunRequestService.NormalizedRequest req = normalize("{\"prompt\":\"p\",\"options\":{"
                + "\"continue\":true,\"bare\":true}}");
        assertTrue(req.argv.contains("--continue"));
        assertTrue(req.argv.contains("--bare"));
    }

    @Test
    void falseBooleansAreOmitted() throws Exception {
        RunRequestService.NormalizedRequest req = normalize("{\"prompt\":\"p\",\"options\":{"
                + "\"continue\":false,\"bare\":false}}");
        assertFalse(req.argv.contains("--continue"));
        assertFalse(req.argv.contains("--bare"));
    }

    @Test
    void mapsToolListsWithPatternSyntax() throws Exception {
        RunRequestService.NormalizedRequest req = normalize("{\"prompt\":\"p\",\"options\":{"
                + "\"allowed_tools\":[\"Read\",\"Bash(git log *)\"],"
                + "\"disallowed_tools\":[\"Bash(rm *)\"]}}");
        assertTrue(req.argv.contains("--allowedTools=Read,Bash(git log *)"));
        assertTrue(req.argv.contains("--disallowedTools=Bash(rm *)"));
    }

    @Test
    void mapsAddDirsAsRepeatedFlags() throws Exception {
        RunRequestService.NormalizedRequest req = normalize("{\"prompt\":\"p\",\"options\":{"
                + "\"add_dirs\":[\"/a\",\"/b\"]}}");
        assertTrue(req.argv.contains("--add-dir=/a"));
        assertTrue(req.argv.contains("--add-dir=/b"));
    }

    // ===== 未识别字段拒绝 =====

    @Test
    void unknownOptionFieldRejected() {
        assertThrows(RunRequestService.BadRequestException.class, () ->
                normalize("{\"prompt\":\"p\",\"options\":{\"typo_field\":1}}"));
    }

    @Test
    void invalidOutputFormatRejected() {
        assertThrows(RunRequestService.BadRequestException.class, () ->
                normalize("{\"prompt\":\"p\",\"options\":{\"output_format\":\"yaml\"}}"));
    }

    @Test
    void invalidPermissionModeRejected() {
        assertThrows(RunRequestService.BadRequestException.class, () ->
                normalize("{\"prompt\":\"p\",\"options\":{\"permission_mode\":\"yolo\"}}"));
    }

    @Test
    void nonPositiveMaxTurnsRejected() {
        assertThrows(RunRequestService.BadRequestException.class, () ->
                normalize("{\"prompt\":\"p\",\"options\":{\"max_turns\":0}}"));
    }

    // ===== permission-mode 收口 =====

    @Test
    void bypassPermissionsRejected() {
        assertThrows(RunRequestService.ForbiddenException.class, () ->
                normalize("{\"prompt\":\"p\",\"options\":{\"permission_mode\":\"bypassPermissions\"}}"));
    }

    @Test
    void dontAskAllowed() throws Exception {
        RunRequestService.NormalizedRequest req = normalize(
                "{\"prompt\":\"p\",\"options\":{\"permission_mode\":\"dontAsk\"}}");
        assertTrue(req.argv.contains("--permission-mode=dontAsk"));
    }

    // ===== workspace 白名单 =====

    @Test
    void workspaceByNameOrIdOrPath() throws Exception {
        assertEquals("/tmp/proj-a", normalize(
                "{\"prompt\":\"p\",\"workspace\":\"proj-a\"}").workspacePath);
        assertEquals("/tmp/proj-a", normalize(
                "{\"prompt\":\"p\",\"workspace\":\"ws-1\"}").workspacePath);
        assertEquals("/tmp/proj-a", normalize(
                "{\"prompt\":\"p\",\"workspace\":\"/tmp/proj-a\"}").workspacePath);
    }

    @Test
    void unknownWorkspaceRejected() {
        assertThrows(RunRequestService.WorkspaceNotFoundException.class, () ->
                normalize("{\"prompt\":\"p\",\"workspace\":\"/etc\"}"));
    }

    // ===== 会话锁标识 =====

    @Test
    void sessionLockPriority() throws Exception {
        // session_id 优先
        RunRequestService.NormalizedRequest a = normalize(
                "{\"prompt\":\"p\",\"options\":{\"session_id\":\"s1\",\"resume\":\"s2\"}}");
        assertEquals("s1", a.sessionId);
        // 次选 resume
        RunRequestService.NormalizedRequest b = normalize(
                "{\"prompt\":\"p\",\"options\":{\"resume\":\"s2\"}}");
        assertEquals("s2", b.sessionId);
        // continue → 固定 "cli"
        RunRequestService.NormalizedRequest c = normalize(
                "{\"prompt\":\"p\",\"options\":{\"continue\":true}}");
        assertEquals("cli", c.sessionId);
    }

    // ===== metadata 透传 =====

    @Test
    void metadataPassedThrough() throws Exception {
        RunRequestService.NormalizedRequest req = normalize(
                "{\"prompt\":\"p\",\"metadata\":{\"request_id\":\"ci-001\"}}");
        assertEquals("ci-001", req.metadata.get("request_id").getString());
        assertNull(normalize("{\"prompt\":\"p\"}").metadata);
    }
}
