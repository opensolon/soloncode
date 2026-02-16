package org.noear.solon.ai.codecli.core.skills;

import org.noear.solon.ai.annotation.ToolMapping;
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

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 代码专家引导规范 (Code Specialist Guidelines)\n\n");
        sb.append("你现在的角色是一个具备工程思维的代码专家。在使用 CLI 工具操作代码库时，必须遵循以下逻辑：\n\n");

        sb.append("#### 1. 初始化优先策略\n");
        sb.append("- **探测契约**: 进入项目后，优先检查根目录是否存在 `CLAUDE.md`。\n");
        sb.append("- **主动初始化**: 如果文件不存在，**必须建议用户或主动调用** `code_init` 工具来识别技术栈并建立规约。\n\n");

        sb.append("#### 2. 工程质量闭环\n");
        sb.append("- **上下文感知**: 在执行修改前，必须阅读 `CLAUDE.md` 以获取构建、测试命令和项目特定的编码风格。\n");
        sb.append("- **原子化改动**: 遵循“读-改-验”流程。修改后优先使用规约中定义的测试命令（如 `mvn solon:test`）进行验证。\n\n");

        sb.append("#### 3. 规约文件更新\n");
        sb.append("- 如果你在开发过程中发现了项目的新特征（如路径约定或新的构建脚本），可以调用 `write_to_file` 更新 `CLAUDE.md`。\n");

        return sb.toString();
    }

    @ToolMapping(name = "code_init", description = "初始化项目规约。识别技术栈、测试框架并生成 CLAUDE.md。")
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
            ensureInGitignore(".todo.md");

            // 5. 物理写入
            Files.write(rootPath.resolve("CLAUDE.md"), sb.toString().getBytes(StandardCharsets.UTF_8));

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
        } catch (Exception ignored) {}
    }

    private boolean exists(String path) {
        return Files.exists(rootPath.resolve(path));
    }
}