/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.command.builtin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Git worktree 管理器，用于创建、删除和清理 worktree。
 *
 * @author noear
 * @since 3.9.1
 */
public class WorktreeManager {
    private static final Logger log = LoggerFactory.getLogger(WorktreeManager.class);
    private static final String LOOP_PREFIX = "loop/";
    private static final String WORKTREE_DIR = ".loop-worktrees";
    private static final int TIMEOUT_SECONDS = 30;

    /**
     * 创建 worktree，返回 worktree 路径
     *
     * @param basePath    git 仓库根路径
     * @param branchName  分支名称（不含 loop/ 前缀）
     * @return worktree 的绝对路径，失败时返回 null
     */
    public String create(String basePath, String branchName) {
        if (!isGitRepo(basePath)) {
            return null;
        }

        File baseDir = new File(basePath);
        String worktreePath = new File(baseDir, WORKTREE_DIR + "/" + branchName).getAbsolutePath();
        String fullBranch = LOOP_PREFIX + branchName;

        // 先尝试创建新分支
        String output = execGit(baseDir, "worktree", "add", worktreePath, "-b", fullBranch);
        if (output != null) {
            log.info("Created worktree with new branch '{}' at: {}", fullBranch, worktreePath);
            return worktreePath;
        }

        // 分支已存在，直接用已有分支
        output = execGit(baseDir, "worktree", "add", worktreePath, fullBranch);
        if (output != null) {
            log.info("Created worktree with existing branch '{}' at: {}", fullBranch, worktreePath);
            return worktreePath;
        }

        log.warn("Failed to create worktree for branch '{}'", branchName);
        return null;
    }

    /**
     * 删除 worktree 及其关联分支
     *
     * @param worktreePath worktree 路径
     */
    public void remove(String worktreePath) {
        if (worktreePath == null || worktreePath.isEmpty()) {
            return;
        }

        File worktreeDir = new File(worktreePath);
        File gitRoot = findGitRoot(worktreeDir);
        if (gitRoot == null) {
            log.warn("Cannot find git root for worktree: {}", worktreePath);
            return;
        }

        // 移除 worktree
        execGit(gitRoot, "worktree", "remove", worktreePath, "--force");

        // 尝试推导分支名并删除分支
        String dirName = worktreeDir.getName();
        String branchName = LOOP_PREFIX + dirName;
        execGit(gitRoot, "branch", "-D", branchName);

        log.info("Removed worktree and branch '{}'", branchName);
    }

    /**
     * 检查指定路径是否为 git 仓库
     *
     * @param path 待检查路径
     * @return 是否为 git 仓库
     */
    public boolean isGitRepo(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }

        String result = execGit(new File(path), "rev-parse", "--is-inside-work-tree");
        return "true".equals(result);
    }

    /**
     * 清理所有 loop/ 前缀的 worktree
     *
     * @param basePath git 仓库根路径
     */
    public void cleanup(String basePath) {
        if (!isGitRepo(basePath)) {
            return;
        }

        File baseDir = new File(basePath);
        String output = execGit(baseDir, "worktree", "list", "--porcelain");
        if (output == null) {
            return;
        }

        List<String> loopWorktrees = new ArrayList<>();
        String currentWorktree = null;

        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.startsWith("worktree ")) {
                currentWorktree = line.substring("worktree ".length());
            } else if (line.startsWith("branch refs/heads/" + LOOP_PREFIX) && currentWorktree != null) {
                loopWorktrees.add(currentWorktree);
                currentWorktree = null;
            }
        }

        for (String wtPath : loopWorktrees) {
            log.info("Cleaning up worktree: {}", wtPath);
            remove(wtPath);
        }

        if (loopWorktrees.isEmpty()) {
            log.info("No loop/ worktrees found to clean up.");
        } else {
            log.info("Cleaned up {} loop/ worktree(s).", loopWorktrees.size());
        }
    }

    /**
     * 执行 git 命令，返回 stdout 字符串
     *
     * @param dir 工作目录
     * @param args git 命令参数
     * @return stdout 内容，失败时返回 null
     */
    protected String execGit(File dir, String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            for (String arg : args) {
                command.add(arg);
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(dir);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Git command timed out: {}", String.join(" ", command));
                return null;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("Git command failed (exit {}): {} — {}", exitCode, String.join(" ", command), output.toString().trim());
                return null;
            }

            return output.toString().trim();

        } catch (Exception e) {
            log.warn("Failed to execute git command: {} — {}", String.join(" ", args), e.getMessage());
            return null;
        }
    }

    /**
     * 从 worktree 目录向上查找 git 根目录
     */
    private File findGitRoot(File worktreeDir) {
        String output = execGit(worktreeDir, "rev-parse", "--show-toplevel");
        if (output != null && !output.isEmpty()) {
            return new File(output);
        }
        return null;
    }
}
