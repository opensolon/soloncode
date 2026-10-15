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
import org.noear.solon.core.handle.Result;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileService.write 单元测试。
 *
 * <p>覆盖：正常写入（原子覆盖）、read/write 往返、路径围栏（".." 拒绝）、
 * 只写已存在文件、UTF-8 编码防护、2MB 上限、lastModified 返回。</p>
 */
public class FileServiceWriteTest {

    private Path tempRoot;
    private FileService fileService;

    @BeforeEach
    public void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("filer-write-");
        // 默认工作区不依赖 engine（resolveRoot 仅在挂载别名时访问 engine）
        fileService = new FileService(tempRoot.toString(), null);
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

    private File given(String relPath, String content) throws Exception {
        File f = tempRoot.resolve(relPath).toFile();
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    public void write_overwritesExistingFile() throws Exception {
        given("src/App.java", "old");

        Result res = fileService.write("src/App.java", "new content");
        assertEquals(200, res.getCode());

        assertEquals("new content",
                new String(Files.readAllBytes(tempRoot.resolve("src/App.java")), StandardCharsets.UTF_8));
    }

    @Test
    public void write_thenReadRoundTrips() throws Exception {
        given("notes.md", "a");

        fileService.write("notes.md", "中文内容 line2\n");

        Result read = fileService.read("notes.md");
        assertEquals(200, read.getCode());
        java.util.Map data = (java.util.Map) read.getData();
        assertEquals("中文内容 line2\n", data.get("content"));
        assertNotNull(data.get("lastModified"), "read must expose lastModified");
    }

    @Test
    public void write_rejectsParentTraversal() throws Exception {
        given("a.txt", "x");

        Result res = fileService.write("../a.txt", "evil");
        assertEquals(400, res.getCode());
    }

    @Test
    public void write_rejectsMissingFile() throws Exception {
        Result res = fileService.write("not-exists.txt", "x");
        assertEquals(404, res.getCode());
    }

    @Test
    public void write_rejectsDirectory() throws Exception {
        tempRoot.resolve("dir").toFile().mkdirs();

        Result res = fileService.write("dir", "x");
        assertEquals(404, res.getCode());
    }

    @Test
    public void write_rejectsNonUtf8Original() throws Exception {
        // GBK 编码字节（中文在 UTF-8 下非法），read 的 UTF-8 解码会失败或产生替换字符
        File f = tempRoot.resolve("gbk.txt").toFile();
        Files.write(f.toPath(), "中文".getBytes("GBK"));

        Result res = fileService.write("gbk.txt", "替换后");
        assertEquals(415, res.getCode());
        // 原文件保持不变
        assertArrayEquals("中文".getBytes("GBK"),
                Files.readAllBytes(f.toPath()));
    }

    @Test
    public void write_rejectsContentOver2mb() throws Exception {
        given("big.txt", "x");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2 * 1024 * 1024 + 10; i++) {
            sb.append('a');
        }
        Result res = fileService.write("big.txt", sb.toString());
        assertEquals(413, res.getCode());
    }

    @Test
    public void write_nullContentClearsFile() throws Exception {
        given("empty.txt", "something");

        Result res = fileService.write("empty.txt", null);
        assertEquals(200, res.getCode());
        assertEquals(0, Files.readAllBytes(tempRoot.resolve("empty.txt")).length);
    }

    @Test
    public void write_returnsFileInfo() throws Exception {
        given("info.txt", "12345");

        Result res = fileService.write("info.txt", "hello");
        assertEquals(200, res.getCode());
        java.util.Map data = (java.util.Map) res.getData();
        assertEquals("info.txt", data.get("path"));
        assertEquals("info.txt", data.get("name"));
        assertEquals(5, ((Number) data.get("size")).intValue());
        assertNotNull(data.get("lastModified"));
    }

    @Test
    public void write_leavesNoTmpFileOnSuccess() throws Exception {
        given("clean.txt", "x");

        fileService.write("clean.txt", "y");

        String[] children = tempRoot.toFile().list();
        for (String c : children) {
            assertFalse(c.contains("soloncode-tmp"), "临时文件必须被 move 掉: " + c);
        }
    }
}
