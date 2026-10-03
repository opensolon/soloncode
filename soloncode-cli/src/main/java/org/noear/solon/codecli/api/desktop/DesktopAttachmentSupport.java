/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.api.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Desktop chat attachment validation and workspace storage. */
final class DesktopAttachmentSupport {
    static final int MAX_ATTACHMENTS = 10;
    static final int MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024;
    static final int MAX_TOTAL_ATTACHMENT_BYTES = 50 * 1024 * 1024;
    private static final int MAX_BASE64_CHARS = ((MAX_ATTACHMENT_BYTES + 2) / 3) * 4 + 128;
    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg"));
    private static final Set<String> IMAGE_MIME_TYPES = new HashSet<>(Arrays.asList(
            "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "image/svg+xml"));

    private DesktopAttachmentSupport() {
    }

    static byte[] decode(WsMessage.WsAttachment attachment) {
        String data = attachment == null ? null : attachment.getData();
        if (data == null) {
            throw new IllegalArgumentException("附件内容不能为空");
        }

        if ("text".equals(attachment.getEncoding())) {
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            requireAllowedSize(bytes.length);
            return bytes;
        }

        int commaIndex = data.indexOf(',');
        if (data.startsWith("data:") && commaIndex > 0) {
            data = data.substring(commaIndex + 1);
        }
        if (data.length() > MAX_BASE64_CHARS) {
            throw new IllegalArgumentException("附件不能超过 20 MB");
        }

        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("附件数据格式无效");
        }
        requireAllowedSize(bytes.length);
        return bytes;
    }

    static String save(Path workspace, String fileName, byte[] bytes) throws IOException {
        String safeName = validateFileName(fileName);
        Path workspaceRoot = workspace.toAbsolutePath().normalize();
        Files.createDirectories(workspaceRoot);
        Path realWorkspace = workspaceRoot.toRealPath();
        Path uploadDir = realWorkspace.resolve(".uploads");
        if (Files.exists(uploadDir) && Files.isSymbolicLink(uploadDir)) {
            throw new IllegalArgumentException("附件目录不能是符号链接");
        }
        Files.createDirectories(uploadDir);
        Path realUploadDir = uploadDir.toRealPath();
        if (!realUploadDir.startsWith(realWorkspace)) {
            throw new IllegalArgumentException("附件目录不在工作区内");
        }

        Path destination = realUploadDir.resolve(safeName).normalize();
        if (!destination.startsWith(realUploadDir)) {
            throw new IllegalArgumentException("附件文件名无效");
        }
        if (Files.exists(destination) && Files.isSymbolicLink(destination)) {
            throw new IllegalArgumentException("附件目标不能是符号链接");
        }
        Files.write(destination, bytes);
        return ".uploads/" + safeName;
    }

    static boolean isMultimodalImage(WsMessage.WsAttachment attachment) {
        if (attachment == null || !"image".equals(attachment.getType())) {
            return false;
        }
        String fileName = attachment.getName();
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        String extension = dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        String mimeType = attachment.getMimeType() == null
                ? ""
                : attachment.getMimeType().toLowerCase(Locale.ROOT);
        return IMAGE_EXTENSIONS.contains(extension) && IMAGE_MIME_TYPES.contains(mimeType);
    }

    private static String validateFileName(String fileName) {
        if (fileName == null) {
            throw new IllegalArgumentException("附件文件名不能为空");
        }
        String value = fileName.trim();
        if (value.length() == 0 || value.length() > 255 || ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException("附件文件名无效");
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 32 || "<>:\"/\\|?*".indexOf(ch) >= 0) {
                throw new IllegalArgumentException("附件文件名无效");
            }
        }
        Path parsed = Paths.get(value);
        if (parsed.getNameCount() != 1 || !value.equals(parsed.getFileName().toString())) {
            throw new IllegalArgumentException("附件文件名无效");
        }
        return value;
    }

    private static void requireAllowedSize(int size) {
        if (size > MAX_ATTACHMENT_BYTES) {
            throw new IllegalArgumentException("附件不能超过 20 MB");
        }
    }
}
