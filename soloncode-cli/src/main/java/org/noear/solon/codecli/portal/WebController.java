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
package org.noear.solon.codecli.portal;

import org.noear.snack4.ONode;
import org.noear.solon.Solon;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessFlags;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.command.builtin.LoopScheduler;
import org.noear.solon.codecli.channel.dingtalk.DingTalkLink;
import org.noear.solon.codecli.channel.feishu.FeishuLink;
import org.noear.solon.codecli.channel.wechat.WeChatClient;
import org.noear.solon.codecli.channel.wechat.WeChatLink;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Web Chat Controller
 *
 * <p>输入通过 HTTP POST 接收，AI 处理结果通过 WebGate 的 WebSocket 推送到前端。</p>
 *
 * @author oisin 2026-3-13
 * @author noear 2026-4-18
 */
public class WebController {
    private static final Logger LOG = LoggerFactory.getLogger(WebController.class);

    private final HarnessEngine engine;
    private final WebGate webGate;
    private final LoopScheduler loopScheduler;

    private static final Set<String> EXCLUDED_DIRS = new HashSet<>(Arrays.asList(
            ".git", ".idea", ".soloncode", "node_modules", "target", "__pycache__", ".gradle", ".mvn", "build"
    ));

    public WebController(HarnessEngine engine, WebGate webGate, LoopScheduler loopScheduler) {
        this.engine = engine;
        this.webGate = webGate;
        this.loopScheduler = loopScheduler;

        // 注入 Web 端 Loop 任务执行器：异步执行 AI 任务，通过 WebGate WebSocket 推送到前端
        if (loopScheduler != null) {
            loopScheduler.addTaskExecutor((sessionId, prompt) -> {
                if (sessionId.startsWith("web-") == false) {
                    return;
                }

                webGate.safeChatInput(sessionId, prompt, "Loop");
            });
        }
    }

    /**
     * 入口：重定向到静态首页 index.html。
     */
    @Get
    @Mapping("/")
    public void index(Context ctx) throws Throwable {
        ctx.forward("/web.html");
    }

    /**
     * 页面元信息：由静态首页（/index.html）启动时 fetch 一次，用于回填标题与侧栏。
     */
    @Get
    @Mapping("/chat/meta")
    public Result<Map> meta() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appTitle", Solon.cfg().appTitle());
        data.put("appVersion", AgentFlags.getVersion());
        data.put("workspace", engine.getProps().getWorkspace());
        data.put("workname", getLastSegment(engine.getProps().getWorkspace()));
        return Result.succeed(data);
    }

    private static String getLastSegment(String pathStr) {
        Path path = Paths.get(pathStr);
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    //---------------

    /**
     * 加载用户消息历史记录
     */
    @Get
    @Mapping("/chat/sessions")
    public Result<List<Map>> sessions() throws Exception {
        Path sessionsPath = Paths.get(engine.getProps().getWorkspace(), ".soloncode", "sessions").toAbsolutePath().normalize();
        File sessionsDir = sessionsPath.toFile();
        List<Map> data = new ArrayList<>();

        if (sessionsDir.exists() && sessionsDir.isDirectory()) {
            File[] dirs = sessionsDir.listFiles(f -> f.isDirectory() && f.getName().startsWith("web-"));
            if (dirs != null) {
                Arrays.sort(dirs, Comparator.comparingLong(File::lastModified).reversed());

                for (File dir : dirs) {
                    String sid = dir.getName();
                    File msgFile = new File(dir, sid + ".messages.ndjson");
                    if (!msgFile.exists()) continue;

                    String label = extractFirstUserMessage(msgFile);
                    if (label == null || label.isEmpty()) continue;

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("sessionId", sid);
                    item.put("label", label.length() > 30 ? label.substring(0, 30) + "..." : label);
                    item.put("time", dir.lastModified());
                    data.add(item);

                    //恢复定时任务
                    loopScheduler.restore(sid, engine.getProps().getWorkspace(), engine.getProps().getHarnessSessions());
                }
            }
        }

        return Result.succeed(data);
    }

    /**
     * 删除消息记录
     */
    @Post
    @Mapping("/chat/sessions/delete")
    public Result deleteSession(@Param("sessionId") String sessionId) throws Exception {
        if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure();
        }

        Path sessionPath = Paths.get(engine.getProps().getWorkspace(), ".soloncode", "sessions", sessionId).toAbsolutePath().normalize();
        File sessionDir = sessionPath.toFile();

        if (sessionDir.exists() && sessionDir.isDirectory()) {
            deleteDirectory(sessionDir);
        }

        return Result.succeed();
    }

    @Get
    @Mapping("/chat/models")
    public Result<Map> models(@Param(value = "sessionId", required = false) String sessionId) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map> list = new ArrayList<>();

        for (ChatConfig config : engine.getProps().getModels()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("model", config.getNameOrModel());
            item.put("description", config.getDescriptionOrModel());
            list.add(item);
        }
        data.put("list", list);

        if (Assert.isNotEmpty(sessionId)) {
            AgentSession session = engine.getSession(sessionId);
            String selected = session.getContext().getOrDefault(HarnessFlags.VAR_MODEL_SELECTED,
                    engine.getMainModel().getNameOrModel());

            data.put("selected", selected);
        } else {
            data.put("selected", engine.getMainModel().getNameOrModel());
        }

        return Result.succeed(data);
    }

    @Post
    @Mapping("/chat/models/select")
    public Result models_select(@Param("sessionId") String sessionId, @Param("modelName") String modelName) throws Exception {
        AgentSession session = engine.getSession(sessionId);

        session.getContext().put(HarnessFlags.VAR_MODEL_SELECTED, modelName);

        session.updateSnapshot();

        return Result.succeed();
    }


    /**
     * 获取消息详细记录信息
     */
    @Get
    @Mapping("/chat/messages")
    public Result<List<Map>> messages(@Param("sessionId") String sessionId) throws Exception {
        List<Map> data = new ArrayList<>();
        Path sessionsPath = Paths.get(engine.getProps().getWorkspace(), ".soloncode", "sessions", sessionId).toAbsolutePath().normalize();
        File msgFile = new File(sessionsPath.toFile(), sessionId + ".messages.ndjson");

        if (msgFile.exists()) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(msgFile), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    ONode node = ONode.ofJson(line);
                    String role = node.get("role").getString();
                    String content = node.get("content").getString();

                    if (role != null && content != null) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("role", role);
                        item.put("content", content);
                        item.put("createdAt", node.get("createdAt").getString());
                        data.add(item);
                    }
                }
            }
        }

        return Result.succeed(data);
    }

    @Post
    @Mapping("/chat/interrupt")
    public Result interruptSession(@Param("sessionId") String sessionId) {
        if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            return Result.failure();
        }

        webGate.interruptSession(sessionId);

        return Result.succeed();
    }

    /**
     * 获取可用命令列表
     */
    @Get
    @Mapping("/chat/commands")
    public Result<List<Map>> commands() {
        List<Map> data = new ArrayList<>();
        for (Command cmd : engine.getCommandRegistry().all()) {
            if (cmd.cliOnly()) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", cmd.name());
            item.put("description", cmd.description());
            item.put("type", "command");
            data.add(item);
        }

        for (AgentDefinition definition : engine.getAgentManager().getAgents()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", definition.getName());
            item.put("description", definition.getDescription());
            item.put("type", "subagent");
            data.add(item);
        }

        return Result.succeed(data);
    }

    /**
     * 聊天输入：解析参数后路由到 WebGate 处理
     */
    @Mapping("/chat/input")
    public Result chat_input(Context ctx, String input, UploadedFile[] attachments, String attachmentTypes[], String model, String sessionId) {
        try {
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = ctx.headerOrDefault("X-Session-Id", "web");
            }
            String sessionCwd = ctx.header("X-Session-Cwd");

            if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
                ctx.status(400);
                ctx.output("Invalid Session ID");
                return null;
            }

            if (Assert.isNotEmpty(sessionCwd)) {
                if (sessionCwd.contains("..")) {
                    ctx.status(400);
                    ctx.output("Invalid Session Cwd");
                    return null;
                }
            }

            String hitlAction = ctx.param("hitlAction");

            // 路由到 WebGate 处理（AI 结果通过 WebSocket 推送到前端）
            webGate.onChatInput(sessionId, sessionCwd, input, model, attachments, attachmentTypes, hitlAction);

            // 返回简单 JSON，前端通过 WebSocket 接收 AI 结果
            return Result.succeed();
        } catch (Throwable e) {
            LOG.error("[Web] chat_input error: {}", e.getMessage());
            return Result.failure(500, e.getMessage());
        }
    }



    // ==================== Git Diff API ====================

    private static class ProcessResult {
        int exitCode;
        String stdout;
        String stderr;
    }

    private ProcessResult runGitCommand(File workDir, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir);
        pb.redirectErrorStream(false);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");

        Process proc = pb.start();
        String stdout = readGitStream(proc.getInputStream());
        String stderr = readGitStream(proc.getErrorStream());

        boolean finished = proc.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            ProcessResult result = new ProcessResult();
            result.exitCode = -1;
            result.stdout = "";
            result.stderr = "Command timed out after 10 seconds";
            return result;
        }

        ProcessResult result = new ProcessResult();
        result.exitCode = proc.exitValue();
        result.stdout = stdout;
        result.stderr = stderr;
        return result;
    }

    private String readGitStream(java.io.InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Git 状态检测：检测 Git 可用性、仓库初始化状态、分支名、变更文件列表
     */
    @Get
    @Mapping("/chat/git/status")
    public Result<Map> gitStatus() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        File workspaceDir = new File(engine.getProps().getWorkspace());

        // 1. 检测 git 是否可用
        try {
            ProcessResult checkGit = runGitCommand(workspaceDir, "git", "--version");
            if (checkGit.exitCode != 0) {
                data.put("gitAvailable", false);
                data.put("initialized", false);
                return Result.succeed(data);
            }
        } catch (Exception e) {
            data.put("gitAvailable", false);
            data.put("initialized", false);
            return Result.succeed(data);
        }
        data.put("gitAvailable", true);

        // 2. 检测是否是 git 仓库
        ProcessResult checkRepo = runGitCommand(workspaceDir, "git", "rev-parse", "--is-inside-work-tree");
        if (checkRepo.exitCode != 0) {
            data.put("initialized", false);
            data.put("workspacePath", workspaceDir.getAbsolutePath());
            return Result.succeed(data);
        }
        data.put("initialized", true);

        // 3. 获取分支名
        ProcessResult branchResult = runGitCommand(workspaceDir, "git", "branch", "--show-current");
        String branch = branchResult.stdout.trim();
        data.put("branch", branch.isEmpty() ? "master" : branch);

        // 4. 解析 git status --porcelain=v1
        ProcessResult statusResult = runGitCommand(workspaceDir, "git", "status", "--porcelain=v1");
        List<String> changed = new ArrayList<>();
        List<String> staged = new ArrayList<>();
        List<String> untracked = new ArrayList<>();

        for (String line : statusResult.stdout.split("\n")) {
            if (line.length() < 4) continue;
            String x = line.substring(0, 1);
            String y = line.substring(1, 2);
            String filePath = line.substring(3);

            if ("?".equals(x) && "?".equals(y)) {
                untracked.add(filePath);
            } else {
                if (!" ".equals(x) && "?".equals(x) == false) staged.add(filePath);
                if (!" ".equals(y) && "?".equals(y) == false) changed.add(filePath);
            }
        }
        data.put("changed", changed);
        data.put("staged", staged);
        data.put("untracked", untracked);

        return Result.succeed(data);
    }

    /**
     * 初始化 Git 仓库
     */
    @Post
    @Mapping("/chat/git/init")
    public Result<Map> gitInit(@Param(value = "initialCommit", required = false) Boolean initialCommit) throws Exception {
        File workspaceDir = new File(engine.getProps().getWorkspace());

        // 安全校验：确认不是已有仓库
        ProcessResult check = runGitCommand(workspaceDir, "git", "rev-parse", "--is-inside-work-tree");
        if (check.exitCode == 0) {
            return Result.failure(400, "Already a git repository");
        }

        // 执行 git init
        ProcessResult initResult = runGitCommand(workspaceDir, "git", "init");
        if (initResult.exitCode != 0) {
            return Result.failure(500, "git init failed: " + initResult.stderr);
        }

        // 自动生成 .gitignore（仅当文件不存在时）
        File gitignore = new File(workspaceDir, ".gitignore");
        if (!gitignore.exists()) {
            String content = String.join("\n",
                    "# Auto-generated by SolonCode",
                    ".git/",
                    ".idea/",
                    ".soloncode/",
                    ".gradle/",
                    ".mvn/",
                    "node_modules/",
                    "target/",
                    "build/",
                    "__pycache__/",
                    "*.class",
                    "*.jar",
                    "*.log"
            );
            java.nio.file.Files.write(gitignore.toPath(), content.getBytes("UTF-8"));
        }

        // 可选：执行 initial commit
        if (Boolean.TRUE.equals(initialCommit)) {
            runGitCommand(workspaceDir, "git", "add", "-A");
            runGitCommand(workspaceDir, "git", "-c", "user.name=SolonCode",
                    "-c", "user.email=soloncode@local",
                    "commit", "-m", "Initial commit");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("initialized", true);

        ProcessResult branchResult = runGitCommand(workspaceDir, "git", "branch", "--show-current");
        String branch = branchResult.stdout.trim();
        data.put("branch", branch.isEmpty() ? "master" : branch);

        return Result.succeed(data);
    }

    /**
     * 获取 Git diff 内容
     */
    @Get
    @Mapping("/chat/git/diff")
    public Result<Map> gitDiff(@Param(value = "path", required = false) String path) throws Exception {
        File workspaceDir = new File(engine.getProps().getWorkspace());

        // 安全校验：防止路径穿越
        if (path != null && (path.contains("..") || path.startsWith("/"))) {
            return Result.failure(400, "Invalid path");
        }

        boolean hasPath = path != null && !path.trim().isEmpty();

        // 未暂存的变更
        List<String> unstagedCmd = new ArrayList<>(Arrays.asList("git", "diff"));
        if (hasPath) { unstagedCmd.add("--"); unstagedCmd.add(path); }
        ProcessResult unstagedResult = runGitCommand(workspaceDir, unstagedCmd.toArray(new String[0]));

        // 已暂存的变更
        List<String> stagedCmd = new ArrayList<>(Arrays.asList("git", "diff", "--cached"));
        if (hasPath) { stagedCmd.add("--"); stagedCmd.add(path); }
        ProcessResult stagedResult = runGitCommand(workspaceDir, stagedCmd.toArray(new String[0]));

        // 合并 diff 输出
        String fullDiff = unstagedResult.stdout;
        if (!stagedResult.stdout.isEmpty()) {
            fullDiff += "\n" + stagedResult.stdout;
        }

        // 截断保护：单文件 diff 限制 2000 行
        if (hasPath) {
            String[] lines = fullDiff.split("\n");
            if (lines.length > 2000) {
                fullDiff = String.join("\n", Arrays.copyOf(lines, 2000))
                        + "\n\n... (差异过大，仅显示前 2000 行，请在终端查看完整 diff)";
            }
        }

        // stat 摘要
        List<String> statCmd = new ArrayList<>(Arrays.asList("git", "diff", "--stat"));
        if (hasPath) { statCmd.add("--"); statCmd.add(path); }
        ProcessResult statResult = runGitCommand(workspaceDir, statCmd.toArray(new String[0]));

        List<String> statCachedCmd = new ArrayList<>(Arrays.asList("git", "diff", "--cached", "--stat"));
        if (hasPath) { statCachedCmd.add("--"); statCachedCmd.add(path); }
        ProcessResult statCachedResult = runGitCommand(workspaceDir, statCachedCmd.toArray(new String[0]));

        String stat = statResult.stdout;
        if (!statCachedResult.stdout.isEmpty()) {
            stat += (stat.isEmpty() ? "" : "\n") + statCachedResult.stdout;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("diff", fullDiff);
        data.put("stat", stat);

        return Result.succeed(data);
    }

    /**
     * 获取指定版本的文件内容
     */
    @Get
    @Mapping("/chat/git/file-content")
    public Result<Map> gitFileContent(@Param("path") String path,
                                     @Param(value = "ref", required = false) String ref) throws Exception {
        File workspaceDir = new File(engine.getProps().getWorkspace());

        if (path == null || path.contains("..") || path.startsWith("/")) {
            return Result.failure(400, "Invalid path");
        }
        if (ref == null || ref.isEmpty()) ref = "HEAD";

        ProcessResult result = runGitCommand(workspaceDir, "git", "show", ref + ":" + path);

        if (result.exitCode != 0) {
            return Result.failure(404, "File not found: " + result.stderr);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", result.stdout);
        return Result.succeed(data);
    }

    // ==================== 工具方法 ====================

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }

    private String extractFirstUserMessage(File msgFile) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(msgFile), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                ONode node = ONode.ofJson(line);
                String role = node.get("role").getString();
                if ("USER".equals(role)) {
                    return node.get("content").getString();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * 工作区文件树
     */
    @Get
    @Mapping("/chat/filer/tree")
    public Result<List<Map>> fileTree(@Param(value = "path", required = false) String path,
                                      @Param(value = "depth", required = false) Integer depth) throws Exception {
        if (depth == null || depth < 1) depth = 1;
        if (path == null) path = "";
        if (path.contains("..")) {
            return Result.failure(400, "Invalid path");
        }

        java.nio.file.Path workspace = java.nio.file.Paths.get(engine.getProps().getWorkspace()).toAbsolutePath().normalize();
        java.nio.file.Path target = workspace.resolve(path).toAbsolutePath().normalize();

        if (!target.startsWith(workspace)) {
            return Result.failure(403, "Access denied");
        }
        if (!target.toFile().exists() || !target.toFile().isDirectory()) {
            return Result.failure(404, "Directory not found");
        }

        List<Map> tree = buildTree(target, workspace, depth, 1);
        return Result.succeed(tree);
    }

    private List<Map> buildTree(java.nio.file.Path dir, java.nio.file.Path workspace, int maxDepth, int currentDepth) {
        File[] files = dir.toFile().listFiles();
        if (files == null) return Collections.emptyList();

        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        List<Map> result = new ArrayList<>();
        for (File f : files) {
            if (f.getName().startsWith(".") || EXCLUDED_DIRS.contains(f.getName())) continue;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", f.getName());
            item.put("path", workspace.relativize(f.toPath().toAbsolutePath().normalize()).toString().replace('\\', '/'));
            item.put("type", f.isDirectory() ? "directory" : "file");

            if (f.isDirectory() && currentDepth < maxDepth) {
                item.put("expanded", true);
                item.put("children", buildTree(f.toPath(), workspace, maxDepth, currentDepth + 1));
            } else if (f.isDirectory()) {
                item.put("expanded", false);
                item.put("children", null);
            }
            result.add(item);
        }
        return result;
    }
}