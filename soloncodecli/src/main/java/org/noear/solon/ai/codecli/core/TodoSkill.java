package org.noear.solon.ai.codecli.core;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 任务进度追踪技能 (对齐 OpenCode/Claude Code 规范)
 *
 * @author noear
 * @since 3.9.5
 */
public class TodoSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(TodoSkill.class);

    @Override
    public String description() {
        return "任务进度追踪专家。通过维护 TODO.md 状态机，确保复杂任务的原子性与执行进度透明。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        // 返回 null 是正确的，让模型完全依赖工具描述，减少冗余上下文
        return null;
    }

    @ToolMapping(name = "todoread", description =
            "读取任务清单。仅在开始新任务、中途迷失方向或确认最终进度时使用。")
    public String todoRead(String __workDir) throws IOException {
        Path rootPath = Paths.get(__workDir).toAbsolutePath().normalize();
        Path todoFile = rootPath.resolve("TODO.md");

        if (!Files.exists(todoFile)) {
            return "[] (当前任务清单为空。若任务复杂，请使用 `todowrite` 初始化计划。)";
        }

        byte[] encoded = Files.readAllBytes(todoFile);
        return new String(encoded, StandardCharsets.UTF_8);
    }

    @ToolMapping(name = "todowrite", description =
            "管理任务列表。必须在处理 3 步以上复杂任务时使用。\n\n" +
                    "## 要求：\n" +
                    "1. 避免频繁更新，优先执行实际动作；\n" +
                    "2. 只有在完成一个物理操作（如修改文件、运行成功）后才更新清单状态；\n" +
                    "3. 严禁连续调用 todowrite。\n"+
                    "## 推理示例：\n" +
                    "<example>\n" +
                    "用户：帮我把项目里的 getCwd 改成 getCurrentWorkingDirectory。\n" +
                    "助手：我先搜索全局出现的次数。发现涉及 8 个文件。我将建立清单以防遗漏。\n" +
                    "调用 `todowrite` (todos: \"- [ ] 备份源码\\n- [/] 修改 src/main.java (in_progress)\\n- [ ] 修改 lib/util.java (pending)...\")\n" +
                    "</example>")
    public String todoWrite(
            @Param(value = "todos", description = "更新后的完整 Markdown 列表。") String todosMarkdown,
            String __workDir
    ) throws IOException {

        Path rootPath = Paths.get(__workDir).toAbsolutePath().normalize();
        Path todoFile = rootPath.resolve("TODO.md");

        StringBuilder sb = new StringBuilder();
        sb.append("# TODO\n\n");
        sb.append("\n\n");
        sb.append(todosMarkdown.trim());

        Files.write(todoFile, sb.toString().getBytes(StandardCharsets.UTF_8));
        ensureInGitignore(rootPath, "TODO.md");

        return "TODO.md 已物理更新。请保持专注，继续执行标记为 in_progress 的任务。";
    }

    private void ensureInGitignore(Path rootPath, String fileName) {
        try {
            Path gitignore = rootPath.resolve(".gitignore");
            if (Files.exists(gitignore)) {
                List<String> lines = Files.readAllLines(gitignore, StandardCharsets.UTF_8);
                boolean exists = false;
                for (String line : lines) {
                    if (line.trim().equals(fileName)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    String entry = "\n# AI Task Tracker\n" + fileName + "\n";
                    Files.write(gitignore, entry.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
                }
            }
        } catch (Exception ignored) {
            LOG.warn("Failed to update .gitignore", ignored);
        }
    }
}