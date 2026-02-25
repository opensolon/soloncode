package org.noear.solon.ai.codecli.core.tool;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.annotation.Param;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量文件原子操作工具
 */
public class ApplyPatchTool {
    @ToolMapping(
            name = "apply_patch",
            description = "Batch file editor for multi-file changes or multiple changes within a single file.\n\n" +
                    "Use the `apply_patch` tool to edit files. Your patch language is a stripped‑down, file‑oriented diff format:\n" +
                    "\n" +
                    "*** Begin Patch\n" +
                    "[ one or more file sections ]\n" +
                    "*** End Patch\n" +
                    "\n" +
                    "Each operation starts with one of three headers:\n" +
                    "*** Add File: <path> - create a new file. Every following line is a + line.\n" +
                    "*** Delete File: <path> - remove an existing file.\n" +
                    "*** Update File: <path> - patch an existing file. You can provide multiple SEARCH/REPLACE blocks for one file.\n" +
                    "\n" +
                    "Example patch (Multiple changes in one file):\n" +
                    "```\n" +
                    "*** Begin Patch\n" +
                    "*** Update File: src/app.py\n" +
                    "<<<<<<< SEARCH\n" +
                    "def old_func_one():\n" +
                    "=======\n" +
                    "def new_func_one():\n" +
                    ">>>>>>> REPLACE\n" +
                    "<<<<<<< SEARCH\n" +
                    "def old_func_two():\n" +
                    "=======\n" +
                    "def new_func_two():\n" +
                    ">>>>>>> REPLACE\n" +
                    "*** End Patch\n" +
                    "```\n" +
                    "\n" +
                    "Rules:\n" +
                    "- For 'Update', use SEARCH/REPLACE blocks. SEARCH must exactly match the file content (including indentation).\n" +
                    "- **Multiple Blocks**: You can use multiple SEARCH/REPLACE blocks under one '*** Update File' header. They will be applied in order.\n" +
                    "- For 'Add', prefix every line with '+'.\n" +
                    "- You can move a file by adding '*** Move to:' immediately after '*** Update File:'.\n" +
                    "- Trailing whitespace is automatically ignored for better matching."
    )
    public Document applyPatch(
            @Param(name = "patchText", description = "The full patch text with SEARCH/REPLACE blocks")
            String patchText,
            String __workDir) throws Exception {

        if (patchText == null || !patchText.contains("*** Begin Patch")) {
            throw new RuntimeException("patchText is required and must contain '*** Begin Patch'");
        }

        List<PatchHunk> hunks = parsePatchText(stripMarkdown(patchText));
        if (hunks.isEmpty()) {
            throw new RuntimeException("apply_patch: no valid hunks found.");
        }

        Path worktree = Paths.get(__workDir).toAbsolutePath().normalize();
        List<FileChange> fileChanges = new ArrayList<>();

        // 1. 预校验阶段
        for (PatchHunk hunk : hunks) {
            Path filePath = worktree.resolve(hunk.path).normalize();
            assertExternalDirectory(worktree, filePath);

            FileChange change = new FileChange(filePath, hunk.type);
            switch (hunk.type) {
                case "add":
                    if (Files.exists(filePath)) {
                        throw new RuntimeException("Cannot add: file already exists: " + hunk.path);
                    }
                    change.newContent = hunk.contents;
                    break;
                case "update":
                case "move":
                    if (!Files.exists(filePath)) {
                        throw new RuntimeException("File not found: " + hunk.path);
                    }
                    change.originalContent = readFile(filePath);
                    change.newContent = applyChunks(change.originalContent, hunk.chunks, hunk.path);
                    if (hunk.movePath != null) {
                        change.movePath = worktree.resolve(hunk.movePath).normalize();
                        assertExternalDirectory(worktree, change.movePath);
                        if (Files.exists(change.movePath) && !change.movePath.equals(filePath)) {
                            throw new RuntimeException("Cannot move: target exists: " + hunk.movePath);
                        }
                        change.type = "move";
                    }
                    break;
                case "delete":
                    if (!Files.exists(filePath)) {
                        throw new RuntimeException("File not found: " + hunk.path);
                    }
                    change.originalContent = readFile(filePath);
                    change.newContent = "";
                    break;
            }
            fileChanges.add(change);
        }

        // 2. 原子执行阶段（带回滚）
        List<FileChange> executed = new ArrayList<>();
        try {
            StringBuilder summary = new StringBuilder("Success. Changes applied:\n");
            for (FileChange change : fileChanges) {
                // 补偿换行符
                if (change.newContent != null && !change.newContent.isEmpty() && !change.newContent.endsWith("\n")) {
                    change.newContent += "\n";
                }

                executeChange(change);
                executed.add(change);

                // 生成摘要报告
                String relPath = worktree.relativize(change.movePath != null ? change.movePath : change.filePath)
                        .toString().replace("\\", "/");
                String statusChar = change.type.substring(0, 1).toUpperCase();
                summary.append(statusChar).append(" ").append(relPath).append("\n");
            }

            return new Document().title("Apply Patch Success").content(summary.toString().trim());
        } catch (Exception e) {
            rollback(executed);
            throw new RuntimeException("Patch failed at operation #" + (executed.size() + 1) + ": " + e.getMessage(), e);
        }
    }

    private void rollback(List<FileChange> executed) {
        for (int i = executed.size() - 1; i >= 0; i--) {
            FileChange change = executed.get(i);
            try {
                switch (change.type) {
                    case "add":
                        Files.deleteIfExists(change.filePath);
                        break;
                    case "update":
                        if (change.originalContent != null) {
                            Files.write(change.filePath, change.originalContent.getBytes(StandardCharsets.UTF_8));
                        }
                        break;
                    case "move":
                        Files.deleteIfExists(change.movePath);
                        if (change.originalContent != null) {
                            Files.write(change.filePath, change.originalContent.getBytes(StandardCharsets.UTF_8));
                        }
                        break;
                    case "delete":
                        if (change.originalContent != null) {
                            Files.write(change.filePath, change.originalContent.getBytes(StandardCharsets.UTF_8));
                        }
                        break;
                }
            } catch (IOException e) {
                System.err.println("Rollback failed for: " + change.filePath);
            }
        }
    }

    private String stripMarkdown(String text) {
        String input = text.trim();
        if (input.startsWith("```")) {
            int start = input.indexOf('\n');
            int end = input.lastIndexOf("```");
            if (start != -1 && end > start) {
                return input.substring(start + 1, end).trim();
            }
        }
        return input;
    }

    private String applyChunks(String content, List<Chunk> chunks, String path) {
        String res = content;
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);

            // 精确匹配
            if (res.contains(chunk.search)) {
                res = replaceFirst(res, chunk.search, chunk.replace);
                continue;
            }

            // 模糊匹配（去除尾随空白）
            String searchNorm = normalizeWhitespace(chunk.search);
            String replaceNorm = normalizeWhitespace(chunk.replace);

            if (res.contains(searchNorm)) {
                res = replaceFirst(res, searchNorm, replaceNorm);
                continue;
            }

            // 匹配失败
            throw new RuntimeException(String.format(
                    "SEARCH block #%d not found in %s\nFirst 80 chars:\n%s",
                    i + 1, path, chunk.search.substring(0, Math.min(80, chunk.search.length()))
            ));
        }
        return res;
    }

    private String normalizeWhitespace(String s) {
        return s.replaceAll("[ \\t]+$", "")
                .replaceAll("\\r\\n", "\n");
    }

    private String replaceFirst(String text, String search, String replace) {
        int pos = text.indexOf(search);
        if (pos == -1) return text;
        return text.substring(0, pos) + replace + text.substring(pos + search.length());
    }

    private List<PatchHunk> parsePatchText(String patchText) {
        List<PatchHunk> hunks = new ArrayList<>();
        String[] lines = patchText.split("\\R");
        PatchHunk current = null;
        StringBuilder sBuf = null, rBuf = null;
        boolean inS = false, inR = false;

        for (String line : lines) {
            // 跳过容器标记
            if (line.trim().isEmpty() ||
                    line.trim().equals("*** Begin Patch") ||
                    line.trim().equals("*** End Patch")) {
                continue;
            }

            // 解析指令行
            if (line.startsWith("*** ")) {
                if (line.startsWith("*** Add File:")) {
                    current = new PatchHunk("add", line.substring(13).trim());
                    hunks.add(current);
                } else if (line.startsWith("*** Update File:")) {
                    current = new PatchHunk("update", line.substring(16).trim());
                    hunks.add(current);
                } else if (line.startsWith("*** Delete File:")) {
                    current = new PatchHunk("delete", line.substring(16).trim());
                    hunks.add(current);
                } else if (line.startsWith("*** Move to:") && current != null) {
                    current.movePath = line.substring(12).trim();
                }
                continue;
            }

            // 状态机转换
            if (line.equals("<<<<<<< SEARCH")) {
                inS = true;
                sBuf = new StringBuilder();
                continue;
            } else if (line.equals("=======")) {
                inS = false;
                inR = true;
                rBuf = new StringBuilder();
                continue;
            } else if (line.equals(">>>>>>> REPLACE")) {
                inR = false;
                if (current != null && sBuf != null && rBuf != null) {
                    current.chunks.add(new Chunk(sBuf.toString(), rBuf.toString()));
                }
                sBuf = null;
                rBuf = null;
                continue;
            }

            // 内容累加
            if (inS) {
                sBuf.append(line).append('\n');
            } else if (inR) {
                rBuf.append(line).append('\n');
            } else if (current != null && "add".equals(current.type)) {
                String content = line.startsWith("+") ? line.substring(1) : line;
                current.contents += content + "\n";
            }
        }
        return hunks;
    }

    private void executeChange(FileChange change) throws IOException {
        Path target = (change.movePath != null) ? change.movePath : change.filePath;
        if (!"delete".equals(change.type)) {
            Files.createDirectories(target.getParent());
            Files.write(target, change.newContent.getBytes(StandardCharsets.UTF_8));
            if (change.movePath != null && !change.movePath.equals(change.filePath)) {
                Files.deleteIfExists(change.filePath);
            }
        } else {
            Files.deleteIfExists(change.filePath);
        }
    }

    private void assertExternalDirectory(Path worktree, Path path) {
        if (!path.startsWith(worktree)) {
            throw new SecurityException("Access denied: " + path);
        }
    }

    private String readFile(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static class PatchHunk {
        String type, path, movePath, contents = "";
        List<Chunk> chunks = new ArrayList<>();

        PatchHunk(String t, String p) {
            this.type = t;
            this.path = p;
        }
    }

    private static class Chunk {
        String search, replace;

        Chunk(String s, String r) {
            this.search = s;
            this.replace = r;
        }
    }

    private static class FileChange {
        Path filePath, movePath;
        String newContent, originalContent, type;

        FileChange(Path p, String t) {
            this.filePath = p;
            this.type = t;
        }
    }
}