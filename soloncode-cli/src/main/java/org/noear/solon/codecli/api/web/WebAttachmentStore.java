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

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.harness.input.InputAttachments;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.core.util.IoUtil;
import org.noear.solon.lang.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Set;

/**
 * Web 端附件落盘与装配。
 *
 * <p>把上传附件保存到工作区 {@code .uploads} 目录（含路径穿越校验），
 * 图片转 {@link ImageBlock} 作为多模态输入，其余作为文件引用交给 Agent 自行读取。</p>
 *
 * <p>落盘职责在宿主侧：各端校验规则不同（桌面端另有符号链接与总量上限），
 * 引擎只接收装配结果，不触碰文件系统。</p>
 *
 * @author noear 2026/5/8 created
 */
public final class WebAttachmentStore {
    /** 支持的图片扩展名集合 */
    private static final Set<String> IMAGE_EXTENSIONS = Utils.asSet(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg");

    private static final String TYPE_IMAGE = "image";

    private WebAttachmentStore() {
    }

    /**
     * 保存附件并装配为引擎输入结果。
     *
     * <p>声明类型为 image 且扩展名属于图片集合时转内容块（二者缺一即按文件处理，与既有口径一致）；
     * 其余按文件引用处理，以 {@code [附件: path]} 前缀告知模型。</p>
     *
     * @param workspaceRoot   工作区根目录（{@code .uploads} 的父目录）
     * @param attachments     上传的文件数组（可为 null）
     * @param attachmentTypes 附件类型数组，与 attachments 一一对应（可为 null）
     */
    public static InputAttachments toAttachments(String workspaceRoot,
                                                 @Nullable UploadedFile[] attachments,
                                                 @Nullable String[] attachmentTypes) {
        InputAttachments result = InputAttachments.of();
        if (attachments == null || attachments.length == 0) {
            return result;
        }

        Path root;
        try {
            root = Paths.get(workspaceRoot).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new IllegalStateException("Invalid workspace root: " + workspaceRoot, e);
        }

        for (int i = 0; i < attachments.length; i++) {
            UploadedFile attachment = attachments[i];
            if (attachment == null) {
                continue;
            }

            String fileName = attachment.getName();
            //双重校验：文件名不得含路径分隔与上跳，落盘路径必须仍在根目录内
            if (fileName == null || fileName.contains("..")
                    || fileName.contains("/") || fileName.contains("\\")) {
                continue;
            }

            String ext = "";
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0) {
                ext = fileName.substring(dot).toLowerCase();
            }

            String declaredType = (attachmentTypes != null && i < attachmentTypes.length)
                    ? attachmentTypes[i] : null;

            try {
                Path uploadDir = root.resolve(".uploads").normalize();
                Files.createDirectories(uploadDir);
                Path savePath = uploadDir.resolve(fileName).normalize();
                if (!savePath.startsWith(root)) {
                    continue;
                }

                byte[] bytes = IoUtil.transferToBytes(attachment.getContent());
                Files.write(savePath, bytes);

                String relative = ".uploads/" + fileName;
                if (isImageAttachment(ext, declaredType)) {
                    result.media(ImageBlock.ofBase64(Base64.getEncoder().encodeToString(bytes),
                            extensionToMime(ext)).filePath(relative));
                } else {
                    result.reference(relative);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to save attachment: " + fileName, e);
            }
        }

        return result;
    }

    /**
     * 判断附件是否为图片类型（声明类型与扩展名需同时匹配）
     *
     * @param ext          文件扩展名（含点号，如 {@code .png}）
     * @param declaredType 前端声明的附件类型（如 image）
     */
    static boolean isImageAttachment(String ext, @Nullable String declaredType) {
        return TYPE_IMAGE.equals(declaredType) && IMAGE_EXTENSIONS.contains(ext);
    }

    /**
     * 将图片扩展名映射为 MIME 类型
     */
    static String extensionToMime(String ext) {
        switch (ext) {
            case ".jpg":
            case ".jpeg":
                return "image/jpeg";
            case ".gif":
                return "image/gif";
            case ".webp":
                return "image/webp";
            case ".bmp":
                return "image/bmp";
            case ".svg":
                return "image/svg+xml";
            case ".png":
            default:
                return "image/png";
        }
    }
}
