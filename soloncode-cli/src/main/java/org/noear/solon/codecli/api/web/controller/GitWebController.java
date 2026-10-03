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
import org.noear.solon.annotation.Post;
import org.noear.solon.annotation.Param;
import org.noear.solon.codecli.api.web.AbstractWebController;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.codecli.workspace.git.GitService;
import org.noear.solon.core.handle.Result;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Git 集成 Controller（原 WebController 的 git 域）。
 *
 * <p>职责：仓库状态检测、初始化、Diff 查看、暂存/撤销、文件内容与历史、提交、变更摘要
 * （委派给 {@link GitService}）。同一工作区内挂载切换由 withGitWorkspace 串行化处理。</p>
 */
public class GitWebController extends AbstractWebController {

    public GitWebController(WorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    private Result<Map> withGitWorkspace(String mount, GitOperation op) throws Exception {
        GitService currentGitService = gitService(); // 已按当前物理工作区隔离（WorkspaceContext 持有独立实例）
        String targetWsId = (mount == null || mount.isEmpty()) ? "workspace" : mount;
        File originalDir = currentGitService.getDefaultWorkspaceDir();
        // 同一工作区内挂载切换存在共享 workspaceDir 的并发风险，用服务实例锁串行化
        synchronized (currentGitService) {
            if (!"workspace".equals(targetWsId)) {
                File targetDir = currentGitService.resolveGitDir(targetWsId);
                currentGitService.setWorkspaceDir(targetDir);
            }
            try {
                return op.execute();
            } finally {
                currentGitService.setWorkspaceDir(originalDir);
            }
        }
    }

    @FunctionalInterface
    private interface GitOperation {
        Result<Map> execute() throws Exception;
    }

    @Get
    @Mapping("/web/chat/git/status")
    public Result<Map> gitStatus(@Param(value = "mount", required = false) String mount) throws Exception {
        String wsId = (mount != null && !mount.isEmpty()) ? mount : null;
        return withGitWorkspace(wsId, () -> gitService().status());
    }

    @Post
    @Mapping("/web/chat/git/init")
    public Result<Map> gitInit(@Param(value = "mount", required = false) String mount,
                               @Param(value = "initialCommit", required = false) Boolean initialCommit) throws Exception {
        String wsId = (mount != null && !mount.isEmpty()) ? mount : null;
        return withGitWorkspace(wsId, () -> gitService().init(initialCommit));
    }

    @Get
    @Mapping("/web/chat/git/diff")
    public Result<Map> gitDiff(@Param(value = "mount", required = false) String mount,
                               @Param(value = "path", required = false) String path) throws Exception {
        String wsId = (mount != null && !mount.isEmpty()) ? mount : null;
        return withGitWorkspace(wsId, () -> gitService().diff(path));
    }

    @Post
    @Mapping("/web/chat/git/stage")
    public Result<Map> gitStage(@Body String body,
                                @Param(value = "mount", required = false) String mount) throws Exception {
        String path = parseJsonPath(body);
        String wsId = (mount != null && !mount.isEmpty()) ? mount : null;
        return withGitWorkspace(wsId, () -> gitService().stage(path));
    }

    @Post
    @Mapping("/web/chat/git/unstage")
    public Result<Map> gitUnstage(@Body String body,
                                  @Param(value = "mount", required = false) String mount) throws Exception {
        String path = parseJsonPath(body);
        String wsId = (mount != null && !mount.isEmpty()) ? mount : null;
        return withGitWorkspace(wsId, () -> gitService().unstage(path));
    }

    @Post
    @Mapping("/web/chat/git/discard")
    public Result<Map> gitDiscard(@Body String body,
                                  @Param(value = "mount", required = false) String mount) throws Exception {
        String path = parseJsonPath(body);
        String wsId = (mount != null && !mount.isEmpty()) ? mount : null;
        return withGitWorkspace(wsId, () -> gitService().discard(path));
    }

    @Get
    @Mapping("/web/chat/git/file-content")
    public Result<Map> gitFileContent(@Param(value = "mount", required = false) String mount,
                                      @Param("path") String path,
                                      @Param(value = "ref", required = false) String ref) throws Exception {
        String wsId = (mount != null && !mount.isEmpty()) ? mount : null;
        return withGitWorkspace(wsId, () -> gitService().fileContent(path, ref));
    }

    @Get
    @Mapping("/web/chat/git/history")
    public Result<Map> gitHistory(@Param(value = "mount", required = false) String mount,
                                  @Param(value = "limit", required = false) Integer limit) throws Exception {
        String wsId = (mount != null && !mount.isEmpty()) ? mount : null;
        final int count = limit == null ? 20 : Math.max(1, Math.min(limit, 50));
        return withGitWorkspace(wsId, () -> gitService().history(count));
    }

    @Post
    @Mapping("/web/chat/git/commit")
    public Result<Map> gitCommit(@Body String body,
                                 @Param(value = "mount", required = false) String mount) throws Exception {
        String message = null;
        List<String> files = null;
        if (body != null && !body.trim().isEmpty()) {
            try {
                ONode json = ONode.ofJson(body);
                if (json != null && json.isObject()) {
                    ONode msgNode = json.get("message");
                    if (msgNode != null && msgNode.isString()) {
                        message = msgNode.getString();
                    }
                    ONode filesNode = json.get("files");
                    if (filesNode != null && filesNode.isArray()) {
                        files = new ArrayList<>();
                        for (ONode f : filesNode.getArray()) {
                            files.add(f.getString());
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        final String finalMsg = message;
        final List<String> finalFiles = files;
        String wsId = (mount != null && !mount.isEmpty()) ? mount : null;
        return withGitWorkspace(wsId, () -> gitService().commit(finalMsg, finalFiles));
    }

    @Post
    @Mapping("/web/chat/git/summary")
    public Result<Map> gitSummary(@Param(value = "mount", required = false) String mount,
                                  @Param("sessionId") String sessionId,
                                  @Param("paths") String paths) throws Exception {
        if (sessionId == null || sessionId.isEmpty()) {
            return Result.failure(400, "sessionId is required");
        }
        if (!isValidSessionId(sessionId)) {
            return Result.failure(400, "Invalid sessionId");
        }

        // 解析文件路径列表
        List<String> files = new ArrayList<>();
        if (paths != null && !paths.trim().isEmpty()) {
            try {
                ONode json = ONode.ofJson(paths);
                if (json != null && json.isArray()) {
                    for (ONode f : json.getArray()) {
                        String p = f.getString();
                        if (p != null && !p.isEmpty()) {
                            files.add(p);
                        }
                    }
                }
            } catch (Exception e) {
                return Result.failure(400, "Invalid paths format, expected JSON array");
            }
        }

        String wsId = (mount != null && !mount.isEmpty()) ? mount : null;
        return withGitWorkspace(wsId, () -> gitService().summary(sessionId, files));
    }

    /**
     * 从 JSON 请求体中解析 path 字段。
     *
     * @param body JSON 字符串，如 { "path": "src/App.java" }
     * @return path 值，解析失败返回 null
     */
    private String parseJsonPath(String body) {
        if (body != null && !body.trim().isEmpty()) {
            try {
                ONode json = ONode.ofJson(body);
                if (json != null && json.isObject()) {
                    ONode pathNode = json.get("path");
                    if (pathNode != null && pathNode.isString()) {
                        return pathNode.getString();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
