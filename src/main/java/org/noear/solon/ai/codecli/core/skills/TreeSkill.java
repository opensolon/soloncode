package org.noear.solon.ai.codecli.core.skills;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.skill.AbsSkill;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 *
 * @author noear 2026/2/16 created
 *
 */
public class TreeSkill extends AbsSkill {
    private final String workDir;
    private final Set<String> excludes = new HashSet<>(Arrays.asList(
            ".git", ".svn", "node_modules", "target", ".idea", ".system", ".DS_Store"
    ));

    public TreeSkill(String workDir) {
        this.workDir = workDir;
    }

    @ToolMapping(name = "tree", description = "查看目录结构，了解项目布局。可指定深度(maxDepth)和子路径(path)")
    public String tree(String path, Integer maxDepth) {
        File root = (path == null || path.isEmpty()) ? new File(workDir) : new File(workDir, path);
        if (!root.exists()) return "Error: 路径不存在 " + path;

        StringBuilder sb = new StringBuilder();
        sb.append("Directory: ").append(root.getAbsolutePath()).append("\n");
        generateTree(root, 0, maxDepth == null ? 2 : maxDepth, "", sb);
        return sb.toString();
    }

    private void generateTree(File node, int currentDepth, int maxDepth, String indent, StringBuilder sb) {
        if (currentDepth > maxDepth) return;
        if (excludes.contains(node.getName())) return;

        sb.append(indent).append(node.isDirectory() ? "📁 " : "📄 ").append(node.getName()).append("\n");

        if (node.isDirectory()) {
            File[] files = node.listFiles();
            if (files != null) {
                // 先排文件夹再排文件
                Arrays.sort(files, (a, b) -> {
                    if (a.isDirectory() && !b.isDirectory()) return -1;
                    if (!a.isDirectory() && b.isDirectory()) return 1;
                    return a.getName().compareTo(b.getName());
                });

                for (File child : files) {
                    generateTree(child, currentDepth + 1, maxDepth, indent + "  ", sb);
                }
            }
        }
    }
}
