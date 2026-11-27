package org.noear.solon.codecli.api.web.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.codecli.workspace.filer.FileService;
import org.noear.solon.core.handle.Result;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * /web/chat/filer/create 传参契约：前端把所在目录、名称、类型放在请求体，
 * 各字段缺省时回退同名查询串（与 write 一致）。
 */
public class FilerWebControllerCreateTest {

    private Path root;
    private FilerWebController controller;

    @BeforeEach
    public void setUp() throws Exception {
        root = Files.createTempDirectory("filer-ctl-create-");
        Files.createDirectories(root.resolve("src"));

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
    public void createFileFromBody() throws Exception {
        Result<Map> res = controller.fileCreate("{\"path\":\"src\",\"name\":\"a.md\",\"type\":\"file\"}",
                null, null, null, null);
        assertEquals(200, res.getCode(), res.getDescription());
        assertEquals("src/a.md", res.getData().get("path"));
        assertTrue(Files.isRegularFile(root.resolve("src/a.md")));
    }

    @Test
    public void createDirectoryAtRootFromBody() throws Exception {
        Result<Map> res = controller.fileCreate("{\"path\":\"\",\"name\":\"docs\",\"type\":\"directory\"}",
                null, null, null, null);
        assertEquals(200, res.getCode(), res.getDescription());
        assertTrue(Files.isDirectory(root.resolve("docs")));
    }

    @Test
    public void fieldsFallBackToQueryString() throws Exception {
        Result<Map> res = controller.fileCreate("", null, "src", "q.txt", "file");
        assertEquals(200, res.getCode(), res.getDescription());
        assertTrue(Files.isRegularFile(root.resolve("src/q.txt")));
    }

    @Test
    public void bodyTakesPrecedenceOverQueryString() throws Exception {
        Result<Map> res = controller.fileCreate("{\"path\":\"src\",\"name\":\"body.txt\",\"type\":\"file\"}",
                null, "", "query.txt", "directory");
        assertEquals(200, res.getCode(), res.getDescription());
        assertTrue(Files.isRegularFile(root.resolve("src/body.txt")));
        assertFalse(Files.exists(root.resolve("query.txt")));
    }

    @Test
    public void invalidTypeIsRejected() throws Exception {
        Result<Map> res = controller.fileCreate("{\"path\":\"src\",\"name\":\"a.md\",\"type\":\"link\"}",
                null, null, null, null);
        assertEquals(400, res.getCode());
        assertFalse(Files.exists(root.resolve("src/a.md")));
    }
}
