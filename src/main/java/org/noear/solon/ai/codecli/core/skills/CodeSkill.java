package org.noear.solon.ai.codecli.core.skills;

import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Claude Code 规范对齐的代码专精技能
 * 负责项目初始化、技术栈识别与深度 CLAUDE.md 规约生成
 *
 * @author noear
 * @since 3.9.4
 */
public class CodeSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(CodeSkill.class);
    private final Path rootPath;

    public CodeSkill(String workDir) {
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "code_specialist_skill";
    }

    @Override
    public String description() {
        return "代码专家技能。支持项目初始化、技术栈自动识别以及 CLAUDE.md 规约生成。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        // 核心对齐：优先探测 CLAUDE.md
        if (exists("CLAUDE.md") || exists("pom.xml") || exists("package.json") ||
                exists("go.mod") || exists(".git")) {
            return true;
        }

        if (exists("Makefile") || exists("ffmpeg") || exists("scripts") || exists("assets")) {
            return true;
        }

        if (exists("src") || exists("lib") || exists(".github")) {
            return true;
        }

        if (prompt != null) {
            String cmd = prompt.getUserContent();
            if (cmd == null) return false;

            String cmdLower = cmd.toLowerCase();
            String[] codeKeywords = {"代码", "编程", "构建", "测试", "项目", "init", "bug", "修复", "重构", "compile", "repo"};
            for (String kw : codeKeywords) {
                if (cmdLower.contains(kw)) return true;
            }
        }

        return false;
    }

    private String cachedMsg;

    @Override
    public String getInstruction(Prompt prompt) {
        // 自动探测并初始化（或更新）规约文件
        if(cachedMsg == null) {
            String result = init();
            cachedMsg = (result == null) ? "Initialization failed." : result;
        }

        StringBuilder buf = new StringBuilder();
        buf.append("\n### 实时工程契约 (Active Project Contract)\n");
        buf.append("> 状态汇报: ").append(cachedMsg).append("\n\n");

        // 强化约束：确保它意识到这是一个物理约束
        buf.append("你当前处于受限的工程模式，必须遵守以下物理契约：\n")
                .append("1. **规约依赖**: 必须先读取根目录的 `CLAUDE.md` 以获取构建和测试指令。\n")
                .append("2. **原子追踪**: 所有逻辑步骤必须实时同步至 `TODO.md`，严禁口头承诺进度。\n")
                .append("3. **验证闭环**: 修改文件后，必须根据 `CLAUDE.md` 提供的命令进行验证。\n");

        return buf.toString();
    }

    public String init() {
        try {
            if (!Files.isWritable(rootPath)) {
                return "Error: Directory not writable. Please check permissions.";
            }

            boolean alreadyExists = exists("CLAUDE.md");
            List<String> detected = new ArrayList<>();
            StringBuilder sb = new StringBuilder();

            // 1. 标准头部定义 (Strict Claude Code style)
            sb.append("# CLAUDE.md\n\n");
            sb.append("This file contains project-specific build, test, and style guidelines.\n");
            sb.append("AI assistants must consult this file before making any changes.\n\n");

            // 2. 指令对齐 (针对不同技术栈提供具体的命令模版)
            sb.append("## Build and Test Commands\n\n");
            if (exists("pom.xml")) {
                detected.add("Java/Maven");
                sb.append("- Build: `mvn clean compile`\n");
                sb.append("- Test all: `mvn test`\n");
                sb.append("- Test single class: `mvn test -Dtest=ClassName` (Replace with actual class)\n");
                sb.append("- Solon test: `mvn solon:test`\n\n");
            } else if (exists("package.json")) {
                detected.add("Node.js");
                sb.append("- Install dependencies: `npm install`\n");
                sb.append("- Build: `npm run build`\n");
                sb.append("- Test all: `npm test`\n\n");
            } else if (exists("go.mod")) {
                detected.add("Go");
                sb.append("- Build: `go build ./...`\n");
                sb.append("- Test all: `go test ./...`\n");
                sb.append("- Test single package: `go test ./path/to/pkg`\n\n");
            } else {
                // 通用兜底
                sb.append("- Build: [Specify build command]\n");
                sb.append("- Test: [Specify test command]\n\n");
            }

            // 3. 核心准则 (Strictly align with Claude Code's "Read-Before-Edit" philosophy)
            sb.append("## Guidelines\n\n");
            sb.append("- **Read-Before-Edit**: Always read the full file content before applying any changes.\n");
            sb.append("- **Atomic Changes**: Implement one logical change at a time and verify immediately.\n");
            sb.append("- **Test-Driven**: Run relevant test commands from this file after every modification.\n");
            sb.append("- **Path Usage**: Use relative paths only (no './' prefix or absolute paths).\n");
            sb.append("- **Code Style**: Follow the existing project patterns and maintain Solon best practices.\n\n");

            // 4. 环境保护
            ensureInGitignore("CLAUDE.md");
            ensureInGitignore("TODO.md");

            // 5. 物理写入
            Files.write(rootPath.resolve("CLAUDE.md"), sb.toString().getBytes(StandardCharsets.UTF_8));

            Path todoPath = rootPath.resolve("TODO.md");
            if (!Files.exists(todoPath)) {
                String initialTodo = "# TODO\n\n- [ ] Initial task identified\n";
                Files.write(todoPath, initialTodo.getBytes(StandardCharsets.UTF_8));
            }

            // 返回结果：保持专业简洁，提供明确的下一行动指令
            String status = alreadyExists ? "Updated" : "Initialized";
            String stack = detected.isEmpty() ? "General" : String.join(", ", detected);

            return String.format("%s CLAUDE.md for %s project.\n" +
                    "[Instruction]: Please read CLAUDE.md to synchronize project rules.", status, stack);

        } catch (Exception e) {
            LOG.error("Init failed", e);
            return "Error: Failed to initialize CLAUDE.md: " + e.getMessage();
        }
    }

    private void ensureInGitignore(String fileName) {
        try {
            Path gitignore = rootPath.resolve(".gitignore");
            if (Files.exists(gitignore)) {
                String content = new String(Files.readAllBytes(gitignore));
                if (!content.contains(fileName)) {
                    Files.write(gitignore, ("\n" + fileName).getBytes(), java.nio.file.StandardOpenOption.APPEND);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private boolean exists(String path) {
        return Files.exists(rootPath.resolve(path));
    }
}