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
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.core.util.Assert;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * 聊天输入附件的落盘与提示词装配。
 *
 * <p>负责把上传附件保存到工作区 {@code .uploads} 目录（含路径穿越校验）、
 * 识别图片附件并编码为 Base64 内容块、构建附件元数据与子代理元数据。
 * 这些是无状态的纯函数，与网关、会话生命周期无关。</p>
 *
 * @author noear 2026/5/8 created
 */
final class WebAttachments {
    /** 支持的图片扩展名集合 */
    private static final Set<String> IMAGE_EXTENSIONS = org.noear.solon.Utils.asSet(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg");

    private WebAttachments() {
    }

    /**
     * 保存附件并拆分为图片内容块与文件附件列表。
     *
     * @param workspaceRoot   引擎工作区根目录（.uploads 的父目录）
     * @param attachments     上传的文件附件数组（可为 null）
     * @param attachmentTypes 附件类型数组，与 attachments 一一对应（如 "image"）
     * @param imageBlocks     出参：图片内容块集合
     * @param imageFileNames  出参：图片相对路径列表
     * @param fileAttachments 出参：非图片附件的相对路径列表
     * @throws Exception 落盘或读取失败
     */
    static void saveAttachments(String workspaceRoot,
                                UploadedFile[] attachments, String[] attachmentTypes,
                                List<ImageBlock> imageBlocks,
                                List<String> imageFileNames,
                                List<String> fileAttachments) throws Exception {
        if (attachments == null) {
            return;
        }
        for (int i = 0; i < attachments.length; i++) {
            UploadedFile attachment = attachments[i];
            String fileName = attachment.getName();
            if (fileName != null && !fileName.contains("..") && !fileName.contains("/") && !fileName.contains("\\")) {
                String ext = "." + attachment.getExtension();
                Path uploadDir = Paths.get(workspaceRoot, ".uploads").toAbsolutePath().normalize();
                Files.createDirectories(uploadDir);
                Path savePath = uploadDir.resolve(fileName).toAbsolutePath().normalize();
                fileName = ".uploads/" + fileName;

                if (savePath.startsWith(Paths.get(workspaceRoot).toAbsolutePath().normalize())) {
                    Files.copy(attachment.getContent(), savePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                    if (isImageAttachment(ext, attachmentTypes != null && i < attachmentTypes.length ? attachmentTypes[i] : null)) {
                        byte[] bytes = Files.readAllBytes(savePath);
                        String base64 = Base64.getEncoder().encodeToString(bytes);
                        String mime = extensionToMime(ext);
                        imageBlocks.add(ImageBlock.ofBase64(base64, mime));
                        imageFileNames.add(fileName);
                    } else {
                        fileAttachments.add(fileName);
                    }
                }
            }
        }
    }

    /**
     * 构建带附件前缀的输入文本（文件附件写入 {@code [附件: xxx]} 前缀）。
     *
     * @param currentInput    原始输入（可为 null）
     * @param fileAttachments 文件附件相对路径列表
     * @return 前缀拼接后的文本；无附件时原样返回
     */
    static String withFilePrefix(String currentInput, List<String> fileAttachments) {
        if (fileAttachments == null || fileAttachments.isEmpty()) {
            return currentInput;
        }
        String filePrefix = fileAttachments.stream()
                .map(f -> "[附件: " + f + "]")
                .collect(java.util.stream.Collectors.joining("\n"));
        if (currentInput == null || currentInput.isEmpty()) {
            return filePrefix + "\n请帮我处理这些附件";
        }
        return filePrefix + "\n" + currentInput;
    }

    /**
     * 判断附件是否为图片类型。
     *
     * @param ext             文件扩展名（含点号，如 ".png"）
     * @param attachmentsType 前端传递的附件类型标识（如 "image"）
     * @return true 表示该附件应作为图片处理
     */
    static boolean isImageAttachment(String ext, String attachmentsType) {
        return "image".equals(attachmentsType) && IMAGE_EXTENSIONS.contains(ext);
    }

    /**
     * 将文件扩展名映射为 MIME 类型。
     *
     * @param ext 文件扩展名（含点号，如 ".jpg"）
     * @return 对应的 MIME 类型字符串，未匹配时默认返回 "image/png"
     */
    static String extensionToMime(String ext) {
        switch (ext) {
            case ".jpg":
            case ".jpeg":
                return "image/jpeg";
            case ".png":
                return "image/png";
            case ".gif":
                return "image/gif";
            case ".webp":
                return "image/webp";
            case ".bmp":
                return "image/bmp";
            case ".svg":
                return "image/svg+xml";
            default:
                return "image/png";
        }
    }

    /**
     * 构建附件元数据 JSON 数组字符串（用于存入 ndjson metadata.attachments）。
     *
     * @param imageFileNames 图片文件名列表（已校验安全的文件名）
     * @return JSON 数组字符串，如 [{"name":"photo.jpg","type":"image"}]；列表为空则返回 null
     */
    static String buildAttachmentMeta(List<String> imageFileNames) {
        if (imageFileNames == null || imageFileNames.isEmpty()) {
            return null;
        }
        // 手动构建 JSON 避免依赖 ONode 序列化细节
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < imageFileNames.size(); i++) {
            if (i > 0) sb.append(",");
            String name = imageFileNames.get(i);
            // 简单转义双引号和反斜杠（文件名已校验无 / \ ..）
            String escaped = name.replace("\\", "\\\\").replace("\"", "\\\"");
            sb.append("{\"name\":\"").append(escaped).append("\",\"type\":\"image\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 写入用户消息的子代理元数据（存入 ndjson metadata.agent）。
     *
     * @param userMsg   用户消息
     * @param agentName 最终生效的子代理名（可为 null/空，表示主 Agent）
     */
    static void addAgentMeta(UserMessage userMsg, String agentName) {
        if (userMsg != null && Assert.isNotEmpty(agentName)) {
            userMsg.addMetadata("agent", agentName);
        }
    }
}
