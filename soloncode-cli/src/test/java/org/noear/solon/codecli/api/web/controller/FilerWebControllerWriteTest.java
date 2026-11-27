package org.noear.solon.codecli.api.web.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.codecli.workspace.filer.FileService;
import org.noear.solon.core.handle.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * /web/chat/filer/write 传参契约：前端编辑保存把 path 放在查询串（与 read 一致），
 * 也可放在请求体；两种方式都必须能写入。
 */
public class FilerWebControllerWriteTest {

    private Path root;
    private FilerWebController controller;

    @BeforeEach
    public void setUp() throws Exception {
        root = Files.createTempDirectory("filer-ctl-write-");
        Files.write(root.resolve("a.txt"), "old".getBytes(StandardCharsets.UTF_8));

        WorkspaceContext ctx = mock(WorkspaceContext.class);
        when(ctx.getFileService()).thenReturn(new FileService(root.toString(), null));
        WorkspaceManager manager = mock(WorkspaceManager.class);
        when(manager.currentContext()).thenReturn(ctx);
        controller = new FilerWebController(manager);
    }

    @AfterEach
    public void tearDown() throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    public void pathFromQueryString() throws Exception {
        Result<Map> res = controller.fileWrite("{\"content\":\"new\"}", null, "a.txt");
        assertEquals(200, res.getCode(), res.getDescription());
        assertEquals("new", new String(Files.readAllBytes(root.resolve("a.txt")), StandardCharsets.UTF_8));
    }

    @Test
    public void pathFromBodyTakesPrecedence() throws Exception {
        Result<Map> res = controller.fileWrite("{\"path\":\"a.txt\",\"content\":\"body\"}", null, "missing.txt");
        assertEquals(200, res.getCode(), res.getDescription());
        assertEquals("body", new String(Files.readAllBytes(root.resolve("a.txt")), StandardCharsets.UTF_8));
    }

    @Test
    public void missingPathIsRejected() throws Exception {
        Result<Map> res = controller.fileWrite("{\"content\":\"x\"}", null, null);
        assertEquals(400, res.getCode());
    }
}
