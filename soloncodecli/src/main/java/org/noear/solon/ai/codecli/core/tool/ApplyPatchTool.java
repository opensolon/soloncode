package org.noear.solon.ai.codecli.core.tool;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import org.noear.solon.ai.chat.tool.AbsTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class ApplyPatchTool extends AbsTool {
    private static final Logger LOG = LoggerFactory.getLogger(ApplyPatchTool.class);

    public ApplyPatchTool() {
        addParam("patchText", String.class, true, "The full patch text that describes all changes to be made");
    }

    @Override
    public String name() {
        return "apply_patch";
    }

    @Override
    public String description() {
        // 此处应返回与 apply_patch.txt 一致的内容
        return "Use the `apply_patch` tool to edit files. Your patch language is a stripped‑down, file‑oriented diff format designed to be easy to parse and safe to apply. You can think of it as a high‑level envelope:\n" +
                "\n" +
                "*** Begin Patch\n" +
                "[ one or more file sections ]\n" +
                "*** End Patch\n" +
                "\n" +
                "Within that envelope, you get a sequence of file operations.\n" +
                "You MUST include a header to specify the action you are taking.\n" +
                "Each operation starts with one of three headers:\n" +
                "\n" +
                "*** Add File: <path> - create a new file. Every following line is a + line (the initial contents).\n" +
                "*** Delete File: <path> - remove an existing file. Nothing follows.\n" +
                "*** Update File: <path> - patch an existing file in place (optionally with a rename).\n" +
                "\n" +
                "Example patch:\n" +
                "\n" +
                "```\n" +
                "*** Begin Patch\n" +
                "*** Add File: hello.txt\n" +
                "+Hello world\n" +
                "*** Update File: src/app.py\n" +
                "*** Move to: src/main.py\n" +
                "@@ def greet():\n" +
                "-print(\"Hi\")\n" +
                "+print(\"Hello, world!\")\n" +
                "*** Delete File: obsolete.txt\n" +
                "*** End Patch\n" +
                "```\n" +
                "\n" +
                "It is important to remember:\n" +
                "\n" +
                "- You must include a header with your intended action (Add/Delete/Update)\n" +
                "- You must prefix new lines with `+` even when creating a new file";
    }

    @Override
    public Object handle(Map<String, Object> args) throws Throwable {
        String patchText = (String) args.get("patchText");
        String __workDir = (String) args.get("__workDir"); //由 toolContext 传递

        // 严格对齐: if (!params.patchText) throw new Error("patchText is required")
        if (patchText == null || patchText.trim().length() == 0) {
            throw new RuntimeException("patchText is required");
        }

        // 1. Parse result 对齐
        List<PatchHunk> hunks;
        try {
            hunks = parsePatchText(patchText);
        } catch (Exception e) {
            throw new RuntimeException("apply_patch verification failed: " + e.getMessage());
        }

        // 2. 空补丁校验对齐
        if (hunks.isEmpty()) {
            String normalized = patchText.replace("\r\n", "\n").replace("\r", "\n").trim();
            if (normalized.equals("*** Begin Patch\n*** End Patch")) {
                throw new RuntimeException("patch rejected: empty patch");
            }
            throw new RuntimeException("apply_patch verification failed: no hunks found");
        }

        Path worktree = Paths.get(__workDir).toAbsolutePath().normalize();
        List<FileChange> fileChanges = new ArrayList<>();
        StringBuilder totalDiff = new StringBuilder();

        // 3. 循环准备变更
        for (PatchHunk hunk : hunks) {
            Path filePath = worktree.resolve(hunk.path).normalize();
            assertExternalDirectory(worktree, filePath);

            FileChange change = new FileChange();
            change.filePath = filePath;

            switch (hunk.type) {
                case "add":
                    change.type = "add";
                    change.oldContent = "";
                    change.newContent = (hunk.contents.length() == 0 || hunk.contents.endsWith("\n"))
                            ? hunk.contents : hunk.contents + "\n";
                    calculateDiffAndStats(change, worktree);
                    break;

                case "update":
                    // 对齐 stats 校验
                    if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
                        throw new RuntimeException("apply_patch verification failed: Failed to read file to update: " + filePath);
                    }
                    change.oldContent = readFile(filePath);
                    try {
                        // 对应 deriveNewContentsFromChunks
                        change.newContent = applyHunks(change.oldContent, hunk.lines);
                    } catch (Exception e) {
                        throw new RuntimeException("apply_patch verification failed: " + e.getMessage());
                    }

                    if (hunk.move_path != null) {
                        change.movePath = worktree.resolve(hunk.move_path).normalize();
                        assertExternalDirectory(worktree, change.movePath);
                        change.type = "move";
                    } else {
                        change.type = "update";
                    }
                    calculateDiffAndStats(change, worktree);
                    break;

                case "delete":
                    change.oldContent = readFile(filePath); // readFile 已处理 NoSuchFileException
                    change.type = "delete";
                    change.newContent = "";
                    calculateDiffAndStats(change, worktree);
                    break;
            }
            fileChanges.add(change);
            totalDiff.append(change.diff).append("\n");
        }

        // 4. 执行物理变更 (Apply the changes)
        for (FileChange change : fileChanges) {
            applyToDisk(change);
        }

        // 5. 生成摘要对齐
        List<String> summaryLines = new ArrayList<>();
        for (FileChange c : fileChanges) {
            Path base = c.movePath != null ? c.movePath : c.filePath;
            String rel = worktree.relativize(base).toString().replace("\\", "/");
            if (c.type.equals("add")) summaryLines.add("A " + rel);
            else if (c.type.equals("delete")) summaryLines.add("D " + rel);
            else summaryLines.add("M " + rel);
        }
        String output = "Success. Updated the following files:\n" + String.join("\n", summaryLines);

        // 6. 返回结构 100% 对齐 (包含 title, metadata, output)
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("diff", totalDiff.toString());
        metadata.put("files", buildFileMetadata(worktree, fileChanges));
        metadata.put("diagnostics", new HashMap<>()); // 保持结构完整性

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", output);
        result.put("metadata", metadata);
        result.put("output", output);

        return result;
    }

    private void calculateDiffAndStats(FileChange change, Path worktree) {
        List<String> oldLines = Arrays.asList(change.oldContent.split("\\r?\\n", -1));
        List<String> newLines = Arrays.asList(change.newContent.split("\\r?\\n", -1));

        if (change.type.equals("delete")) {
            // 对齐 delete 逻辑: contentToDelete.split("\n").length
            change.deletions = change.oldContent.length() == 0 ? 0 : change.oldContent.split("\n", -1).length;
            change.additions = 0;
        }

        Patch<String> patch = DiffUtils.diff(oldLines, newLines);
        if (!change.type.equals("delete")) {
            for (AbstractDelta<String> delta : patch.getDeltas()) {
                switch (delta.getType()) {
                    case INSERT: change.additions += delta.getTarget().size(); break;
                    case DELETE: change.deletions += delta.getSource().size(); break;
                    case CHANGE:
                        change.additions += delta.getTarget().size();
                        change.deletions += delta.getSource().size();
                        break;
                }
            }
        }

        // trimDiff 逻辑对齐
        String fileName = change.filePath.toString().replace("\\", "/");
        List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(fileName, fileName, oldLines, patch, 0);
        if (unifiedDiff.size() > 2) {
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < unifiedDiff.size(); i++) {
                sb.append(unifiedDiff.get(i)).append("\n");
            }
            change.diff = sb.toString();
        }
    }

    private void applyToDisk(FileChange change) throws IOException {
        Path target = (change.movePath != null) ? change.movePath : change.filePath;
        if ("delete".equals(change.type)) {
            Files.deleteIfExists(change.filePath);
        } else {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.write(target, change.newContent.getBytes(StandardCharsets.UTF_8));
            if ("move".equals(change.type)) {
                Files.deleteIfExists(change.filePath);
            }
        }
    }

    private List<PatchHunk> parsePatchText(String text) {
        List<PatchHunk> hunks = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        PatchHunk current = null;
        for (String line : lines) {
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
                // 对齐 OpenCode 的 move_path 映射
                current.move_path = line.substring(12).trim();
            } else if (current != null) {
                if ("add".equals(current.type)) {
                    current.contents += (line.startsWith("+") ? line.substring(1) : line) + "\n";
                } else if (!line.startsWith("@@") && !line.equals("*** End Patch") && !line.equals("*** Begin Patch")) {
                    current.lines.add(line);
                }
            }
        }
        return hunks;
    }

    private String applyHunks(String oldContent, List<String> diffLines) {
        List<String> lines = new ArrayList<>(Arrays.asList(oldContent.split("\\r?\\n", -1)));
        for (String d : diffLines) {
            if (d.startsWith("-")) lines.remove(d.substring(1));
            else if (d.startsWith("+")) lines.add(d.substring(1));
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i));
            if (i < lines.size() - 1) sb.append("\n");
        }
        return sb.toString();
    }

    private List<Map<String, Object>> buildFileMetadata(Path worktree, List<FileChange> changes) {
        return changes.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("filePath", c.filePath.toString());
            // relativePath 对齐：path.relative(Instance.worktree, change.movePath ?? change.filePath)
            Path target = c.movePath != null ? c.movePath : c.filePath;
            m.put("relativePath", worktree.relativize(target).toString().replace("\\", "/"));
            m.put("type", c.type);
            m.put("diff", c.diff);
            m.put("before", c.oldContent);
            m.put("after", c.newContent);
            m.put("additions", c.additions);
            m.put("deletions", c.deletions);
            m.put("movePath", c.movePath != null ? c.movePath.toString() : null);
            return m;
        }).collect(Collectors.toList());
    }

    private void assertExternalDirectory(Path worktree, Path path) {
        if (path != null && !path.normalize().startsWith(worktree)) {
            throw new RuntimeException("apply_patch verification failed: Access denied to " + path);
        }
    }

    private String readFile(Path path) throws IOException {
        try {
            byte[] encoded = Files.readAllBytes(path);
            return new String(encoded, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            // 对齐 TS 的报错文案：catch(error) => throw Error(...)
            throw new IOException(e.toString());
        }
    }

    private static class PatchHunk {
        String type, path, move_path, contents = "";
        List<String> lines = new ArrayList<>();
        PatchHunk(String t, String p) { this.type = t; this.path = p; }
    }

    private static class FileChange {
        Path filePath, movePath;
        String oldContent = "", newContent = "", type, diff = "";
        int additions = 0, deletions = 0;
    }
}