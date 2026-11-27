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
package org.noear.solon.codecli.api.web.controller;

import org.noear.snack4.ONode;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Post;
import org.noear.solon.codecli.api.web.AbstractWebController;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文件浏览 Controller（原 WebController 的 filer 域，委派给 FileService）。
 *
 * <p>职责：文件挂载工作区列表、目录树浏览、文件搜索、文本读取与原始二进制输出（图片/视频渲染）、
 * 文本编辑写入。</p>
 */
public class FilerWebController extends AbstractWebController {

    public FilerWebController(WorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    @Get
    @Mapping("/web/chat/filer/workspaces")
    public Result<List<Map>> fileWorkspaces() throws Exception {
        return fileService().listWorkspaces();
    }

    /**
     * 工作区文件树浏览接口。
     */
    @Get
    @Mapping("/web/chat/filer/tree")
    public Result<List<Map>> fileTree(@Param(value = "mount", required = false) String workspace,
                                      @Param(value = "path", required = false) String path,
                                      @Param(value = "depth", required = false) Integer depth) throws Exception {
        return fileService().tree(workspace, path, depth);
    }

    /**
     * 工作区文件搜索接口。
     */
    @Get
    @Mapping("/web/chat/filer/search")
    public Result<List<Map>> fileSearch(@Param(value = "mount", required = false) String workspace,
                                        @Param("keyword") String keyword) throws Exception {
        return fileService().search(workspace, keyword);
    }

    /**
     * 读取工作区文件内容接口。
     */
    @Get
    @Mapping("/web/chat/filer/read")
    public Result<Map> fileRead(@Param(value = "mount", required = false) String workspace,
                                @Param("path") String path) throws Exception {
        return fileService().read(workspace, path);
    }

    /**
     * 写入工作区文件内容接口（编辑保存）。
     *
     * <p>安全校验与读取一致（路径围栏、只读挂载拦截、编码防护），仅支持覆盖已存在的文本文件。</p>
     */
    @Post
    @Mapping("/web/chat/filer/write")
    public Result<Map> fileWrite(@Body String body,
                                 @Param(value = "mount", required = false) String workspace,
                                 @Param(value = "path", required = false) String queryPath) throws Exception {
        ONode json = parseJsonBody(body);
        ONode pathNode = json == null ? null : json.get("path");
        // 路径优先取请求体，缺省时回退查询串（与 /web/chat/filer/read 传参方式一致）
        String path = (pathNode != null && pathNode.isString()) ? pathNode.getString() : queryPath;
        ONode contentNode = json == null ? null : json.get("content");
        String content = (contentNode != null && contentNode.isString()) ? contentNode.getString() : null;
        return fileService().write(workspace, path, content);
    }

    /**
     * 在目录下新建空文件或文件夹接口（文件面板右键菜单）。
     *
     * <p>请求体 {@code {"path": "所在目录", "name": "新名称", "type": "file"|"directory"}}，
     * 挂载走查询串 {@code mount}。各字段优先取请求体，缺省时回退同名查询串（与 write 一致）。</p>
     */
    @Post
    @Mapping("/web/chat/filer/create")
    public Result<Map> fileCreate(@Body String body,
                                  @Param(value = "mount", required = false) String workspace,
                                  @Param(value = "path", required = false) String queryPath,
                                  @Param(value = "name", required = false) String queryName,
                                  @Param(value = "type", required = false) String queryType) throws Exception {
        ONode json = parseJsonBody(body);
        String dir = bodyString(json, "path", queryPath);
        String name = bodyString(json, "name", queryName);
        String type = bodyString(json, "type", queryType);
        return fileService().create(workspace, dir, name, type);
    }

    /**
     * 取请求体中的字符串字段，没有时回退给定值。
     */
    private String bodyString(ONode json, String key, String fallback) {
        ONode node = json == null ? null : json.get(key);
        return (node != null && node.isString()) ? node.getString() : fallback;
    }

    /**
     * 从 JSON 请求体中解析出 ONode（解析失败返回 null）。
     */
    private ONode parseJsonBody(String body) {
        if (body != null && !body.trim().isEmpty()) {
            try {
                ONode json = ONode.ofJson(body);
                if (json != null && json.isObject()) {
                    return json;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 读取工作区文件原始二进制内容（用于图片、视频等媒体文件展示）。
     *
     * <p>直接以原始字节流输出文件内容，并设置正确的 Content-Type，
     * 以便浏览器直接渲染图片或视频。</p>
     */
    @Get
    @Mapping("/web/chat/filer/read-raw")
    public void fileReadRaw(Context ctx,
                            @Param(value = "mount", required = false) String mount,
                            @Param("path") String path) throws Exception {
        if (path == null || path.trim().isEmpty()) {
            ctx.status(400);
            ctx.output("Path is required");
            return;
        }
        try {
            Path targetPath = fileService().resolveFilePath(mount, path);
            byte[] bytes = Files.readAllBytes(targetPath);
            String contentType = guessContentType(path);
            ctx.contentType(contentType);
            ctx.headerSet("Cache-Control", "private, max-age=3600");
            ctx.output(bytes);
        } catch (IllegalArgumentException e) {
            ctx.status(404);
            ctx.output(e.getMessage());
        } catch (SecurityException e) {
            ctx.status(403);
            ctx.output(e.getMessage());
        } catch (Exception e) {
            ctx.status(500);
            ctx.output("Failed to read file: " + e.getMessage());
        }
    }

    /**
     * 根据文件扩展名推测 MIME 类型（用于原始文件输出）。
     */
    private String guessContentType(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".ogg")) return "video/ogg";
        return "application/octet-stream";
    }
}
