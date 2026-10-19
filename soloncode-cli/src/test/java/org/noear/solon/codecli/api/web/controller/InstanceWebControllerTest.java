package org.noear.solon.codecli.api.web.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.codecli.util.WebInstanceRegistry;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.codecli.workspace.WorkspaceMeta;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.snack4.ONode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InstanceWebControllerTest {
    @TempDir
    Path directory;

    @Test
    void notReadyDoesNotAdvertiseOrAcceptAttach() {
        WorkspaceManager manager = mock(WorkspaceManager.class);
        InstanceWebController controller = new InstanceWebController(manager, "memory-token");
        assertEquals(false, controller.info().getData().get("attachSupported"));
        assertEquals(Result.FAILURE_CODE, controller.attach(context("memory-token"), body(directory)).getCode());
        verifyNoInteractions(manager);

        InstanceWebController withoutToken = new InstanceWebController(manager);
        withoutToken.setAttachSupported(true);
        assertEquals(false, withoutToken.info().getData().get("attachSupported"));
    }

    @Test
    void diskReplacementCannotChangeInstanceAuthentication() throws Exception {
        int port = 52000 + (int) Math.floorMod(System.nanoTime(), 1000L);
        String memoryToken = WebInstanceRegistry.generateToken();
        WorkspaceManager manager = mock(WorkspaceManager.class);
        WorkspaceContext workspace = mock(WorkspaceContext.class);
        WorkspaceMeta meta = new WorkspaceMeta("workspace-id", "test", directory.toString(), 0L, false);
        when(workspace.getMeta()).thenReturn(meta);
        when(manager.getOrCreate(directory.toRealPath().toString())).thenReturn(workspace);
        InstanceWebController controller = new InstanceWebController(manager, memoryToken);
        WebInstanceRegistry.register(port, memoryToken);
        controller.setAttachSupported(true);
        String replacement = WebInstanceRegistry.register(port);
        try {
            assertEquals(true, controller.info().getData().get("attachSupported"));
            assertEquals(Result.FAILURE_CODE, controller.attach(context(replacement), body(directory)).getCode());
            verifyNoInteractions(manager);
            Result<Map<String, Object>> result = controller.attach(context(memoryToken), body(directory));
            assertEquals(Result.SUCCEED_CODE, result.getCode());
            assertEquals("workspace-id", result.getData().get("workspaceId"));
            WebInstanceRegistry.remove(port, replacement);
            assertEquals(Result.SUCCEED_CODE, controller.attach(context(memoryToken), body(directory)).getCode());
        } finally {
            WebInstanceRegistry.remove(port, replacement);
        }
    }

    @Test
    void relativeMissingAndFilePathsAreRejectedBeforeWorkspaceCreation() throws Exception {
        WorkspaceManager manager = mock(WorkspaceManager.class);
        InstanceWebController controller = new InstanceWebController(manager, "token");
        controller.setAttachSupported(true);
        Path file = Files.createFile(directory.resolve("file"));
        for (String path : new String[]{".", "relative/path", "", directory.resolve("missing").toString(), file.toString()}) {
            Result<?> result = controller.attach(context("token"), new ONode().set("path", path).toJson());
            assertEquals(Result.FAILURE_CODE, result.getCode(), path);
        }
        assertEquals("Path must be absolute", controller.attach(context("token"), body(java.nio.file.Paths.get("."))).getDescription());
        verifyNoInteractions(manager);
    }

    @Test
    void remotePeerAndMissingOrWrongTokensAreRejected() {
        WorkspaceManager manager = mock(WorkspaceManager.class);
        InstanceWebController controller = new InstanceWebController(manager, "token");
        controller.setAttachSupported(true);
        Context remote = context("token");
        when(remote.remoteIp()).thenReturn("192.0.2.1");
        when(remote.header("X-Forwarded-For")).thenReturn("127.0.0.1");
        assertEquals(Result.FAILURE_CODE, controller.attach(remote, body(directory)).getCode());
        assertEquals(Result.FAILURE_CODE, controller.attach(context(null), body(directory)).getCode());
        assertEquals(Result.FAILURE_CODE, controller.attach(context("wrong"), body(directory)).getCode());
        verifyNoInteractions(manager);
    }

    private static Context context(String token) {
        Context ctx = mock(Context.class);
        when(ctx.remoteIp()).thenReturn("127.0.0.1");
        when(ctx.header("X-Soloncode-Attach-Token")).thenReturn(token);
        return ctx;
    }

    private static String body(Path path) {
        return new ONode().set("path", path.toString()).toJson();
    }
}
