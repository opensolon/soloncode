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
 * ApplyPatchTool - 性能极致优化版
 * 保持 ToolMapping 描述不变，内部采用非正则解析与高效字符串匹配
 */
public class ApplyPatchTool {
    @ToolMapping(
            name = "apply_patch",
            description = "批量文件编辑原子工具。使用面向文件的 diff 格式进行多文件的创建、修改、重命名或删除。\n" +
                    "所有操作必须包裹在 *** Begin Patch 和 *** End Patch 之间。\n" +
                    "工具会验证 SEARCH 块的精确匹配以确保编辑安全。"
    )
    public Document applyPatch(
            @Param(name = "patchText", description =
                    "完整补丁文本。必须遵循以下结构规范：\n\n" +
                            "1. 容器：必须以 '*** Begin Patch' 开始，'*** End Patch' 结束。\n" +
                            "2. 新增 (Add File)：\n" +
                            "   *** Add File: <path>\n" +
                            "   随后行必须以 + 开头表示内容。\n" +
                            "3. 修改 (Update File)：\n" +
                            "   *** Update File: <path>\n" +
                            "   (可选) *** Move to: <new_path>\n" +
                            "   必须使用精确匹配块：\n" +
                            "   <<<<<<< SEARCH\n" +
                            "   [原文件中完全一致的代码段]\n" +
                            "   =======\n" +
                            "   [替换后的代码段]\n" +
                            "   >>>>>>> REPLACE\n" +
                            "4. 删除 (Delete File)：\n" +
                            "   *** Delete File: <path>\n\n" +
                            "注意：Update 操作时，SEARCH 块内的空白符和缩进必须与源码严格一致。")
            String patchText,
            String __workDir) throws Exception {

        if (patchText == null || !patchText.contains("*** Begin Patch")) {
            throw new RuntimeException("patchText is required and must contain '*** Begin Patch'");
        }


        // 1. 高效清洗与解析 (避免全量正则扫描)
        List<PatchHunk> hunks = parsePatchText(stripMarkdown(patchText));

        if (hunks.isEmpty()) {
            throw new RuntimeException("apply_patch verification failed: no valid hunks found.");
        }

        Path worktree = Paths.get(__workDir).toAbsolutePath().normalize();

        // 2. 预校验
        List<FileChange> fileChanges = new ArrayList<>();
        for (PatchHunk hunk : hunks) {
            Path filePath = worktree.resolve(hunk.path).normalize();
            assertExternalDirectory(worktree, filePath);

            FileChange change = new FileChange(filePath, hunk.type);
            switch (hunk.type) {
                case "add":
                    change.newContent = hunk.contents;
                    break;
                case "update":
                case "move":
                    if (!Files.exists(filePath)) throw new RuntimeException("File not found: " + hunk.path);
                    change.newContent = applyChunks(readFile(filePath), hunk.chunks, hunk.path);
                    if (hunk.movePath != null) {
                        change.movePath = worktree.resolve(hunk.movePath).normalize();
                        assertExternalDirectory(worktree, change.movePath);
                        change.type = "move";
                    }
                    break;
                case "delete":
                    if (!Files.exists(filePath)) throw new RuntimeException("File not found: " + hunk.path);
                    change.newContent = "";
                    break;
            }
            fileChanges.add(change);
        }

        // 3. 原子写入
        for (FileChange change : fileChanges) {
            executeChange(change);
        }

        // 4. 结果生成
        String resultMsg = "Success. Updated " + fileChanges.size() + " files.";
        return new Document().title(resultMsg).content(resultMsg);
    }

    /**
     * 优化点 1：基于索引的文本清洗
     * 避免在大文本（如 1MB+ 补丁）上运行复杂的非贪婪正则，减少 CPU 抖动
     */
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

    /**
     * 优化点 2：低延迟字符串匹配
     * 优先使用 indexOf 进行 O(n) 精确匹配，仅在失败时触发修剪逻辑
     */
    private String applyChunks(String content, List<Chunk> chunks, String path) {
        String res = content;
        for (Chunk chunk : chunks) {
            if (chunk.search.isEmpty()) {
                res = res + (res.endsWith("\n") ? "" : "\n") + chunk.replace;
                continue;
            }

            if (res.contains(chunk.search)) {
                res = res.replace(chunk.search, chunk.replace);
            } else {
                // 仅在必要时执行正则/修剪，减少 GC 压力
                String sTrimmed = stripTrailing(chunk.search);
                if (res.contains(sTrimmed)) {
                    res = res.replace(sTrimmed, stripTrailing(chunk.replace));
                } else {
                    throw new RuntimeException("apply_patch verification failed: SEARCH block mismatch in " + path);
                }
            }
        }
        return res;
    }

    /**
     * 优化点 3：手动扫描实现修剪 (替代 replaceAll 正则)
     */
    private String stripTrailing(String s) {
        int len = s.length();
        while (len > 0 && s.charAt(len - 1) <= ' ') len--;
        return s.substring(0, len);
    }

    /**
     * 优化点 4：单次线性扫描解析器
     */
    private List<PatchHunk> parsePatchText(String patchText) {
        List<PatchHunk> hunks = new ArrayList<>();
        String[] lines = patchText.split("\\R");
        PatchHunk current = null;
        StringBuilder sBuf = null, rBuf = null;
        boolean inS = false, inR = false;

        for (String line : lines) {
            if (line.isEmpty()) continue;

            // 通过前缀快速过滤指令行
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

            // 状态机处理块
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
                if (current != null) current.chunks.add(new Chunk(sBuf.toString(), rBuf.toString()));
                continue;
            }

            if (inS) sBuf.append(line).append('\n');
            else if (inR) rBuf.append(line).append('\n');
            else if (current != null && "add".equals(current.type) && line.startsWith("+")) {
                current.contents += line.substring(1) + "\n";
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
        String newContent, type;

        FileChange(Path p, String t) {
            this.filePath = p;
            this.type = t;
        }
    }
}