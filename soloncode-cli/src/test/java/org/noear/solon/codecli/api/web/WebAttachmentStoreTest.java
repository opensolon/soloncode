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
package org.noear.solon.codecli.api.web;

import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.harness.input.InputAttachments;
import org.noear.solon.core.handle.UploadedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Web 端附件落盘与装配。
 *
 * <p>落盘自引擎下沉到宿主后，原先由 {@code InputAttachmentsTest} 覆盖的两条口径
 * （声明类型与扩展名双校验、路径穿越拦截）在此接管，防止重构中静默丢失。</p>
 */
class WebAttachmentStoreTest {
    @TempDir
    Path workspace;

    @Test
    void fileIsSavedAndBecomesReference() throws Exception {
        InputAttachments result = WebAttachmentStore.toAttachments(workspace.toString(),
                files(upload("notes.txt", "hello")), types(null));

        assertFalse(result.hasMedia());
        assertEquals(1, result.getReferences().size());
        assertEquals(".uploads/notes.txt", result.getReferences().get(0));
        assertTrue(Files.exists(workspace.resolve(".uploads/notes.txt")));
        assertEquals("[附件: .uploads/notes.txt]\n看下这个", result.withFilePrefix("看下这个"));
    }

    @Test
    void declaredImageWithImageExtensionBecomesMediaBlock() throws Exception {
        InputAttachments result = WebAttachmentStore.toAttachments(workspace.toString(),
                files(upload("pic.png", "png-bytes")), types("image"));

        assertEquals(1, result.getMediaBlocks().size());
        assertTrue(result.getMediaBlocks().get(0) instanceof ImageBlock);
        assertEquals("image/png", result.getMediaBlocks().get(0).getMimeType());
        //已落盘：带上工作区相对路径供历史标签使用
        assertEquals(".uploads/pic.png", result.getMediaBlocks().get(0).getFilePath());
        assertTrue(result.getReferences().isEmpty());
        assertTrue(result.isAllImages());
        //图片不进文本前缀
        assertEquals("看图", result.withFilePrefix("看图"));
    }

    @Test
    void imageExtensionWithoutDeclaredTypeFallsBackToReference() throws Exception {
        //扩展名是图片但未声明 image 类型，按文件处理（与既有口径一致）
        InputAttachments result = WebAttachmentStore.toAttachments(workspace.toString(),
                files(upload("pic.png", "not-image")), types(null));

        assertFalse(result.hasMedia());
        assertEquals(1, result.getReferences().size());
    }

    @Test
    void audioExtensionDeclaredAsFileFallsBackToReference() throws Exception {
        //前端默认值就是 'file'：mp3 被标成 file 时不得变成音频块
        InputAttachments result = WebAttachmentStore.toAttachments(workspace.toString(),
                files(upload("voice.mp3", "x")), types("file"));

        assertFalse(result.hasMedia());
        assertEquals(1, result.getReferences().size());
        assertEquals(".uploads/voice.mp3", result.getReferences().get(0));
    }

    @Test
    void mergedMimeFollowsExtension() throws Exception {
        InputAttachments jpg = WebAttachmentStore.toAttachments(workspace.toString(),
                files(upload("p.jpg", "x")), types("image"));

        assertEquals("image/jpeg", jpg.getMediaBlocks().get(0).getMimeType());
        assertEquals(".uploads/p.jpg", jpg.getMediaBlocks().get(0).getFilePath());
    }

    @Test
    void pathTraversalNamesAreSkipped() throws Exception {
        InputAttachments result = WebAttachmentStore.toAttachments(workspace.toString(),
                files(upload("../evil.txt", "x"), upload("a/b.txt", "x"), upload("ok.txt", "x")),
                types(null, null, null));

        assertEquals(1, result.getReferences().size());
        assertEquals(".uploads/ok.txt", result.getReferences().get(0));
        assertFalse(Files.exists(workspace.getParent().resolve("evil.txt")));
    }

    @Test
    void nullOrEmptyInputsYieldEmptyResult() {
        assertTrue(WebAttachmentStore.toAttachments(workspace.toString(), null, null).hasAny() == false);
        assertTrue(WebAttachmentStore.toAttachments(workspace.toString(), new UploadedFile[0], null).hasAny() == false);
    }

    @Test
    void typesShorterThanFilesDoesNotThrow() throws Exception {
        InputAttachments result = WebAttachmentStore.toAttachments(workspace.toString(),
                files(upload("a.png", "x"), upload("b.png", "x")), types("image"));

        //第二个附件没有类型声明，按文件处理
        assertEquals(1, result.getMediaBlocks().size());
        assertEquals(1, result.getReferences().size());
    }

    private static UploadedFile upload(String name, String content) {
        return new UploadedFile("application/octet-stream",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), name);
    }

    private static UploadedFile[] files(UploadedFile... files) {
        return files;
    }

    private static String[] types(String... types) {
        return types;
    }
}
