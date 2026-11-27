/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.workspace.filer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.mount.Mount;
import org.noear.solon.ai.mount.MountType;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.core.handle.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FileService.create 单元测试（文件面板右键「新建文件 / 新建文件夹」）。
 *
 * <p>覆盖：根目录与子目录新建、新建后可直接 write、新建文件夹只建一层、同名 409 且不覆盖、
 * 名称带路径分隔符或非法字符 400、目录越界、目录不存在 404、只读挂载 403、类型非法 400。</p>
 */
public class FileServiceCreateTest {

    private Path tempRoot;
    private Path workspace;
    private FileService fileService;

    @BeforeEach
    public void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("filer-create-");
        workspace = Files.createDirectories(tempRoot.resolve("ws"));
        // 默认工作区不依赖 engine（resolveRoot 仅在挂载别名时访问 engine）
        fileService = new FileService(workspace.toString(), null);
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

    /** 构造一个带 FILES 挂载的服务。 */
    private FileService serviceWithMount(String alias, Path root, boolean writable) {
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), ".soloncode/")
                .sessionProvider(new SessionManager(workspace.toString()))
                .build();
        engine.addMount(Mount.builder()
                .alias(alias)
                .type(MountType.FILES)
                .source(FileMountSource.of(root, true))
                .writable(writable)
                .build());
        return new FileService(workspace.toString(), engine);
    }

    @Test
    public void create_fileInRootIsEmptyAndWritable() throws Exception {
        Result<Map> res = fileService.create("workspace", "", "a.md", FileService.CREATE_TYPE_FILE);
        assertEquals(200, res.getCode(), res.getDescription());
        assertEquals("a.md", res.getData().get("path"));
        assertEquals("a.md", res.getData().get("name"));
        assertEquals("file", res.getData().get("type"));

        Path created = workspace.resolve("a.md");
        assertTrue(Files.isRegularFile(created));
        assertEquals(0, Files.size(created));

        // 新建后的空文件马上能走现有的编辑保存流程
        Result<Map> write = fileService.write("workspace", "a.md", "hello");
        assertEquals(200, write.getCode(), write.getDescription());
        assertEquals("hello", new String(Files.readAllBytes(created), StandardCharsets.UTF_8));
    }

    @Test
    public void create_fileInSubDirectoryReturnsRelativePath() throws Exception {
        Files.createDirectories(workspace.resolve("src/main"));

        Result<Map> res = fileService.create("workspace", "src/main", "App.java", FileService.CREATE_TYPE_FILE);
        assertEquals(200, res.getCode(), res.getDescription());
        assertEquals("src/main/App.java", res.getData().get("path"));
        assertTrue(Files.isRegularFile(workspace.resolve("src/main/App.java")));
    }

    @Test
    public void create_directoryCreatesSingleLevel() throws Exception {
        Result<Map> res = fileService.create("workspace", "", "docs", FileService.CREATE_TYPE_DIRECTORY);
        assertEquals(200, res.getCode(), res.getDescription());
        assertEquals("directory", res.getData().get("type"));
        assertTrue(Files.isDirectory(workspace.resolve("docs")));
    }

    @Test
    public void create_existingNameIsConflictAndKeepsContent() throws Exception {
        Files.write(workspace.resolve("a.txt"), "keep".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(workspace.resolve("dir"));

        assertEquals(409, fileService.create("workspace", "", "a.txt", FileService.CREATE_TYPE_FILE).getCode());
        assertEquals(409, fileService.create("workspace", "", "a.txt", FileService.CREATE_TYPE_DIRECTORY).getCode());
        assertEquals(409, fileService.create("workspace", "", "dir", FileService.CREATE_TYPE_FILE).getCode());
        assertEquals(409, fileService.create("workspace", "", "dir", FileService.CREATE_TYPE_DIRECTORY).getCode());

        assertEquals("keep", new String(Files.readAllBytes(workspace.resolve("a.txt")), StandardCharsets.UTF_8));
    }

    @Test
    public void create_nameWithSubDirectoryIsRejected() throws Exception {
        Files.createDirectories(workspace.resolve("a"));

        assertEquals(400, fileService.create("workspace", "", "b/c.txt", FileService.CREATE_TYPE_FILE).getCode());
        assertEquals(400, fileService.create("workspace", "", "a/c.txt", FileService.CREATE_TYPE_FILE).getCode());
        assertEquals(400, fileService.create("workspace", "", "x\\y", FileService.CREATE_TYPE_DIRECTORY).getCode());

        assertFalse(Files.exists(workspace.resolve("b")), "不得顺带创建中间目录");
        assertFalse(Files.exists(workspace.resolve("a/c.txt")));
    }

    @Test
    public void create_invalidNamesAreRejected() throws Exception {
        String[] names = {"", "   ", ".", "..", "a..b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "a\nb", "a\u0000b"};
        for (String name : names) {
            assertEquals(400, fileService.create("workspace", "", name, FileService.CREATE_TYPE_FILE).getCode(),
                    "名称应被拒绝: [" + name + "]");
        }
        assertEquals(400, fileService.create("workspace", "", null, FileService.CREATE_TYPE_FILE).getCode());
    }

    @Test
    public void create_nameIsTrimmed() throws Exception {
        Result<Map> res = fileService.create("workspace", "", "  b.txt  ", FileService.CREATE_TYPE_FILE);
        assertEquals(200, res.getCode(), res.getDescription());
        assertTrue(Files.isRegularFile(workspace.resolve("b.txt")));
    }

    @Test
    public void create_parentTraversalIsRejected() throws Exception {
        assertEquals(400, fileService.create("workspace", "..", "evil.txt", FileService.CREATE_TYPE_FILE).getCode());
        assertEquals(400, fileService.create("workspace", "a/../..", "evil.txt", FileService.CREATE_TYPE_FILE).getCode());
        assertFalse(Files.exists(tempRoot.resolve("evil.txt")));
    }

    @Test
    public void create_absoluteDirectoryOutsideRootIsDenied() throws Exception {
        Result<Map> res = fileService.create("workspace", tempRoot.toString(), "evil.txt", FileService.CREATE_TYPE_FILE);
        assertEquals(403, res.getCode());
        assertFalse(Files.exists(tempRoot.resolve("evil.txt")));
    }

    @Test
    public void create_missingOrFileParentIsNotFound() throws Exception {
        Files.write(workspace.resolve("f.txt"), "x".getBytes(StandardCharsets.UTF_8));

        assertEquals(404, fileService.create("workspace", "missing", "a.txt", FileService.CREATE_TYPE_FILE).getCode());
        assertEquals(404, fileService.create("workspace", "f.txt", "a.txt", FileService.CREATE_TYPE_FILE).getCode());
        assertFalse(Files.exists(workspace.resolve("missing")));
    }

    @Test
    public void create_invalidTypeIsRejected() throws Exception {
        assertEquals(400, fileService.create("workspace", "", "a.txt", null).getCode());
        assertEquals(400, fileService.create("workspace", "", "a.txt", "symlink").getCode());
        assertFalse(Files.exists(workspace.resolve("a.txt")));
    }

    @Test
    public void create_inWritableMount() throws Exception {
        Path mountRoot = Files.createDirectories(tempRoot.resolve("mount-rw"));
        Files.createDirectories(mountRoot.resolve("sub"));
        FileService service = serviceWithMount("@rw", mountRoot, true);

        Result<Map> res = service.create("@rw", "sub", "n.md", FileService.CREATE_TYPE_FILE);
        assertEquals(200, res.getCode(), res.getDescription());
        assertEquals("sub/n.md", res.getData().get("path"));
        assertTrue(Files.isRegularFile(mountRoot.resolve("sub/n.md")));
        assertFalse(Files.exists(workspace.resolve("sub/n.md")), "必须建在挂载里，而不是默认工作区");

        // 不传 workspaceId 时可从 @alias/ 前缀解析
        Result<Map> prefixed = service.create(null, "@rw/sub", "d", FileService.CREATE_TYPE_DIRECTORY);
        assertEquals(200, prefixed.getCode(), prefixed.getDescription());
        assertTrue(Files.isDirectory(mountRoot.resolve("sub/d")));
    }

    @Test
    public void create_inReadonlyMountIsForbidden() throws Exception {
        Path mountRoot = Files.createDirectories(tempRoot.resolve("mount-ro"));
        FileService service = serviceWithMount("@ro", mountRoot, false);

        assertEquals(403, service.create("@ro", "", "n.md", FileService.CREATE_TYPE_FILE).getCode());
        assertEquals(403, service.create("@ro", "", "d", FileService.CREATE_TYPE_DIRECTORY).getCode());
        assertFalse(Files.exists(mountRoot.resolve("n.md")));
        assertFalse(Files.exists(mountRoot.resolve("d")));
    }
}
