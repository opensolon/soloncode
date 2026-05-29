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
package org.noear.solon.codecli.portal.web;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessFlags;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.core.handle.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Git 服务 —— 封装工作区 Git 操作的核心业务逻辑。
 *
 * <p>提供 Git 仓库状态检测、初始化、Diff 查看、暂存/取消暂存、
 * 文件内容获取、提交以及 AI 提交摘要生成等能力。</p>
 *
 * <h3>设计说明</h3>
 * <ul>
 *   <li>通过 workspace 路径构造，所有 Git 命令均在此路径下执行</li>
 *   <li>内部封装 {@link ProcessResult} 和 {@link #runGitCommand} 统一进程调用</li>
 *   <li>供 WebController 或其他模块直接调用，无需关心 Git 命令细节</li>
 * </ul>
 *
 * @author noear 2026-5-30
 * @see WebController
 */
public class GitService {
    private static final Logger LOG = LoggerFactory.getLogger(GitService.class);

    /** 工作区目录 */
    private final File workspaceDir;

    /** AI Agent 执行引擎，用于 gitSummary 时获取模型和 Agent */
    private final HarnessEngine engine;

    /**
     * 构造函数。
     *
     * @param workspace 工作区根目录路径
     * @param engine    AI Agent 执行引擎
     */
    public GitService(String workspace, HarnessEngine engine) {
        this.workspaceDir = new File(workspace);
        this.engine = engine;
    }

    // ==================== 内部基础设施 ====================

    /**
     * 进程执行结果封装，用于承载 Git 命令的标准输出、标准错误和退出码。
     */
    static class ProcessResult {
        /** 进程退出码，0 表示成功 */
        int exitCode;
        /** 标准输出内容 */
        String stdout;
        /** 标准错误内容 */
        String stderr;
    }

    /**
     * 在工作区目录下执行 Git 命令。
     * <p>禁用 Git 终端提示（GIT_TERMINAL_PROMPT=0），设置 10 秒超时保护，
     * 超时后强制终止进程并返回退出码 -1。</p>
     *
     * @param command 完整的命令及参数（如 "git", "status", "--porcelain=v1"）
     * @return 进程执行结果
     * @throws Exception 进程启动或流读取异常
     */
    private ProcessResult runGitCommand(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workspaceDir);
        pb.redirectErrorStream(false);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");

        Process proc = pb.start();
        String stdout = readStream(proc.getInputStream());
        String stderr = readStream(proc.getErrorStream());

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

    /**
     * 读取输入流的所有行并拼接为字符串。
     *
     * @param is 输入流
     * @return 拼接后的字符串内容
     * @throws Exception 流读取异常
     */
    private String readStream(java.io.InputStream is) throws Exception {
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
     * 校验当前工作区是否为 Git 仓库。
     *
     * @return true 表示是 Git 仓库
     */
    private boolean isGitRepo() throws Exception {
        ProcessResult check = runGitCommand("git", "rev-parse", "--is-inside-work-tree");
        return check.exitCode == 0;
    }

    // ==================== 公开业务方法 ====================

    /**
     * Git 状态检测：返回 Git 可用性、仓库初始化状态、当前分支名及变更文件列表。
     *
     * @return 包含 gitAvailable、initialized、branch、changed、staged、untracked 的结果对象
     * @throws Exception Git 命令执行异常
     */
    public Result<Map> status() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();

        // 1. 检测 git 是否可用
        try {
            ProcessResult checkGit = runGitCommand("git", "--version");
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
        ProcessResult checkRepo = runGitCommand("git", "rev-parse", "--is-inside-work-tree");
        if (checkRepo.exitCode != 0) {
            data.put("initialized", false);
            data.put("workspacePath", workspaceDir.getAbsolutePath());
            return Result.succeed(data);
        }
        data.put("initialized", true);

        // 3. 获取分支名
        ProcessResult branchResult = runGitCommand("git", "branch", "--show-current");
        String branch = branchResult.stdout.trim();
        data.put("branch", branch.isEmpty() ? "master" : branch);

        // 4. 解析 git status --porcelain=v1
        ProcessResult statusResult = runGitCommand("git", "status", "--porcelain=v1");
        List<String> changed = new ArrayList<>();
        List<String> staged = new ArrayList<>();
        List<String> untracked = new ArrayList<>();

        for (String line : statusResult.stdout.split("\n")) {
            if (line.length() < 4) continue;
            String x = line.substring(0, 1);
            String y = line.substring(1, 2);
            String filePath = line.substring(3);

            // 规范化：去除尾部斜杠
            if (filePath.endsWith("/")) {
                filePath = filePath.substring(0, filePath.length() - 1);
            }

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
     * 初始化 Git 仓库。
     * <p>在工作区执行 git init，自动生成 .gitignore 文件（仅当文件不存在时），
     * 可选执行初始提交。</p>
     *
     * @param initialCommit 是否执行初始提交
     * @return 包含 initialized、branch 的结果对象
     * @throws Exception Git 命令执行异常
     */
    public Result<Map> init(Boolean initialCommit) throws Exception {
        // 安全校验：确认不是已有仓库
        if (isGitRepo()) {
            return Result.failure(400, "Already a git repository");
        }

        // 执行 git init
        ProcessResult initResult = runGitCommand("git", "init");
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
            runGitCommand("git", "add", "-A");
            runGitCommand("git", "-c", "user.name=SolonCode",
                    "-c", "user.email=soloncode@noear.org",
                    "commit", "-m", "Initial commit");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("initialized", true);

        ProcessResult branchResult = runGitCommand("git", "branch", "--show-current");
        String branch = branchResult.stdout.trim();
        data.put("branch", branch.isEmpty() ? "master" : branch);

        return Result.succeed(data);
    }

    /**
     * 获取 Git Diff 内容。
     * <p>分别执行未暂存变更和已暂存变更的 diff 查询，合并输出结果。
     * 单文件 diff 超过 2000 行时自动截断。</p>
     *
     * @param path 可选的文件路径，为空时查看全部变更
     * @return 包含 diff 和 stat 的结果对象
     * @throws Exception Git 命令执行异常
     */
    public Result<Map> diff(String path) throws Exception {
        // 安全校验：防止路径穿越
        if (path != null && (path.contains("..") || path.startsWith("/"))) {
            return Result.failure(400, "Invalid path");
        }

        boolean hasPath = path != null && !path.trim().isEmpty();

        // 未暂存的变更
        List<String> unstagedCmd = new ArrayList<>(Arrays.asList("git", "diff"));
        if (hasPath) { unstagedCmd.add("--"); unstagedCmd.add(path); }
        ProcessResult unstagedResult = runGitCommand(unstagedCmd.toArray(new String[0]));

        // 已暂存的变更
        List<String> stagedCmd = new ArrayList<>(Arrays.asList("git", "diff", "--cached"));
        if (hasPath) { stagedCmd.add("--"); stagedCmd.add(path); }
        ProcessResult stagedResult = runGitCommand(stagedCmd.toArray(new String[0]));

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
        ProcessResult statResult = runGitCommand(statCmd.toArray(new String[0]));

        List<String> statCachedCmd = new ArrayList<>(Arrays.asList("git", "diff", "--cached", "--stat"));
        if (hasPath) { statCachedCmd.add("--"); statCachedCmd.add(path); }
        ProcessResult statCachedResult = runGitCommand(statCachedCmd.toArray(new String[0]));

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
     * 将指定文件添加到 Git 暂存区（git add）。
     *
     * @param path 文件路径
     * @return 包含 path 的结果对象
     * @throws Exception Git 命令执行异常
     */
    public Result<Map> stage(String path) throws Exception {
        if (!isGitRepo()) {
            return Result.failure(400, "Not a git repository");
        }

        if (path == null || path.trim().isEmpty()) {
            return Result.failure(400, "Path is required");
        }
        if (path.contains("..") || path.startsWith("/")) {
            return Result.failure(400, "Invalid path");
        }

        ProcessResult addResult = runGitCommand("git", "add", "--", path);
        if (addResult.exitCode != 0) {
            return Result.failure(500, "git add failed: " + addResult.stderr);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path);
        return Result.succeed(data);
    }

    /**
     * 将指定文件移出 Git 暂存区（git reset HEAD -- path）。
     *
     * @param path 文件路径
     * @return 包含 path 的结果对象
     * @throws Exception Git 命令执行异常
     */
    public Result<Map> unstage(String path) throws Exception {
        if (!isGitRepo()) {
            return Result.failure(400, "Not a git repository");
        }

        if (path == null || path.trim().isEmpty()) {
            return Result.failure(400, "Path is required");
        }
        if (path.contains("..") || path.startsWith("/")) {
            return Result.failure(400, "Invalid path");
        }

        ProcessResult resetResult = runGitCommand("git", "reset", "HEAD", "--", path);
        if (resetResult.exitCode != 0) {
            return Result.failure(500, "git reset failed: " + resetResult.stderr);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path);
        return Result.succeed(data);
    }

    /**
     * 获取 Git 仓库中指定版本的文件内容（git show ref:path）。
     *
     * @param path 文件路径（相对于仓库根目录）
     * @param ref  Git 引用，默认为 HEAD
     * @return 包含 content 的结果对象
     * @throws Exception Git 命令执行异常
     */
    public Result<Map> fileContent(String path, String ref) throws Exception {
        if (path == null || path.contains("..") || path.startsWith("/")) {
            return Result.failure(400, "Invalid path");
        }
        if (ref == null || ref.isEmpty()) ref = "HEAD";

        ProcessResult result = runGitCommand("git", "show", ref + ":" + path);

        if (result.exitCode != 0) {
            return Result.failure(404, "File not found: " + result.stderr);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", result.stdout);
        return Result.succeed(data);
    }

    /**
     * Git 提交：支持精确文件列表或全量 add -A。
     *
     * @param message 提交信息
     * @param files   文件路径列表，为空或 null 时执行 add -A
     * @return 包含 stdout 的结果对象
     * @throws Exception Git 命令执行异常
     */
    public Result<Map> commit(String message, List<String> files) throws Exception {
        if (!isGitRepo()) {
            return Result.failure(400, "Not a git repository");
        }

        // 校验提交信息
        if (message == null || message.trim().isEmpty()) {
            return Result.failure(400, "Commit message is required");
        }

        // git add：有指定文件时精确暂存，否则 add -A
        ProcessResult addResult;
        if (files != null && !files.isEmpty()) {
            // 先清空暂存区，避免之前已暂存的非选中文件被一起提交
            runGitCommand("git", "reset", "HEAD", "--");

            List<String> addCmd = new ArrayList<>();
            addCmd.add("git");
            addCmd.add("add");
            addCmd.add("--");
            addCmd.addAll(files);
            addResult = runGitCommand(addCmd.toArray(new String[0]));
        } else {
            addResult = runGitCommand("git", "add", "-A");
        }
        if (addResult.exitCode != 0) {
            return Result.failure(500, "git add failed: " + addResult.stderr);
        }

        // git commit
        ProcessResult commitResult = runGitCommand("git",
                "-c", "user.name=SolonCode",
                "-c", "user.email=soloncode@noear.org",
                "commit", "-m", message.trim());
        if (commitResult.exitCode != 0) {
            String err = commitResult.stderr.trim();
            if (err.isEmpty()) err = commitResult.stdout.trim();
            return Result.failure(500, "git commit failed: " + err);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stdout", commitResult.stdout.trim());
        return Result.succeed(data);
    }

    /**
     * Git 提交摘要生成：通过 AI 分析 diff 内容，生成简洁的提交信息。
     *
     * @param sessionId 当前会话 ID，用于获取用户选择的 LLM 模型
     * @param files     需要分析的文件路径列表
     * @return 包含 summary 的结果对象
     */
    public Result<Map> summary(String sessionId, List<String> files) {
        if (files == null || files.isEmpty()) {
            return Result.failure(400, "paths is required");
        }

        try {
            // 通过 sessionId 获取会话，复用用户选择的 LLM 模型
            AgentSession session = engine.getSession(sessionId);
            String selectedModel = session.getContext().getOrDefault(
                    HarnessFlags.VAR_MODEL_SELECTED,
                    engine.getMainModel().getNameOrModel()
            );
            ChatModel chatModel = engine.getModelOrMain(selectedModel);
            ReActAgent agent = engine.getAgentOrMain("git-summary");

            // 收集 diff 内容（最多 15 个文件）
            List<String> targetFiles = files.size() > 15 ? files.subList(0, 15) : files;
            StringBuilder combinedDiff = new StringBuilder();
            int MAX_DIFF_LINES = 500;
            int MAX_TOTAL_CHARS = 20000;

            for (String filePath : targetFiles) {
                if (filePath.contains("..") || filePath.startsWith("/")) continue;
                ProcessResult diffResult = runGitCommand("git", "diff", "--", filePath);
                ProcessResult cachedResult = runGitCommand("git", "diff", "--cached", "--", filePath);
                String diff = diffResult.stdout;
                if (!cachedResult.stdout.isEmpty()) {
                    diff += "\n" + cachedResult.stdout;
                }
                if (diff.trim().isEmpty()) {
                    diff = "(无差异内容)";
                }
                // 单文件截断 500 行
                String[] lines = diff.split("\n");
                if (lines.length > MAX_DIFF_LINES) {
                    diff = String.join("\n", Arrays.copyOf(lines, MAX_DIFF_LINES)) + "\n... (已截断)";
                }
                combinedDiff.append("\n\n=== ").append(filePath).append(" ===\n").append(diff);
            }

            // 总字符截断
            if (combinedDiff.length() > MAX_TOTAL_CHARS) {
                combinedDiff.setLength(MAX_TOTAL_CHARS);
                combinedDiff.append("\n\n... (总差异过大，已截断)");
            }

            String statInfo = targetFiles.size() + " 个文件" + (targetFiles.size() < files.size() ? "（仅前 15 个）" : "");

            String userMessage = "请根据以下 Git diff 变更内容，生成提交信息摘要。\n\n"
                    + "变更统计：" + statInfo
                    + "\n\n--- Diff 内容 ---\n" + combinedDiff;

            String summary = agent.prompt(userMessage)
                    .options(o -> o.chatModel(chatModel))
                    .call()
                    .getContent();

            // 清理 Markdown 格式
            if (summary != null) {
                summary = summary.replace("**", "")
                        .replace("*", "")
                        .replaceAll("^#+\\s", "")
                        .replace("`", "")
                        .trim();
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("summary", summary != null ? summary : "");
            return Result.succeed(data);
        } catch (Throwable e) {
            LOG.error("[Web] gitSummary error: {}", e.getMessage());
            return Result.failure(500, "生成摘要失败: " + e.getMessage());
        }
    }
}
