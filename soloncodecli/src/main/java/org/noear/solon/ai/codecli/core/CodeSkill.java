package org.noear.solon.ai.codecli.core;

import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Claude Code 规范对齐的代码专精技能
 *
 * @author noear
 * @since 3.9.4
 */
public class CodeSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(CodeSkill.class);

    private final String workSpace;

    public CodeSkill() {
        this(null);
    }

    public CodeSkill(String workSpace) {
        this.workSpace = workSpace;
    }

    @Override
    public String description() {
        return "代码专家技能。支持项目初始化、技术栈自动识别以及 CLAUDE.md 规约生成。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        String __cwd = prompt.attrAs(AgentKernel.ATTR_CWD);
        Path rootPath = getRootPath(__cwd);

        if (rootExists(rootPath, "CLAUDE.md")) {
            return true;
        }

        if (deepExists(rootPath, "pom.xml") || deepExists(rootPath, "package.json") || deepExists(rootPath, "go.mod") ||
                deepExists(rootPath, ".git") || deepExists(rootPath, ".github") ||
                deepExists(rootPath, "src") || deepExists(rootPath, "lib")) {
            return true;
        }

        return false;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        String __cwd = prompt.attrAs(AgentKernel.ATTR_CWD);
        Path rootPath = getRootPath(__cwd);

        StringBuilder buf = new StringBuilder();

        String msg = init(__cwd);

        buf.append("\n## 核心工程规约 (Core Engineering Protocol)\n");
        buf.append("> Project Context: ").append(msg).append("\n\n");

        buf.append("为了确保工程质量，要严格执行以下操作：\n")
                .append("1. **动作前导**: 在开始任何任务前，先读取根目录的 `CLAUDE.md` 以获取构建和测试指令。\n")
                .append("2. **验证驱动**: 修改代码后，根据 `CLAUDE.md` 中的指令运行测试，严禁未验证提交。\n")
                .append("3. **路径规范**: 严禁使用 `./` 前缀。使用相对于当前工作目录的纯净相对路径。\n");

        return buf.toString();
    }

    public String refresh(String workDir) {
        if (isSupported(null)) {
            return init(workDir);
        } else {
            return null;
        }
    }

    private Path getRootPath(String __cwd) {
        String path = (__cwd != null) ? __cwd : workSpace;
        if (path == null) throw new IllegalStateException("Working directory is not set.");
        return Paths.get(path).toAbsolutePath().normalize();
    }

    public String init(String __cwd) {
        try {
            Path rootPath = getRootPath(__cwd);

            if (!Files.isWritable(rootPath)) return "错误：目录不可写。";

            StringBuilder newContent = new StringBuilder();
            newContent.append("# CLAUDE.md\n\n");
            newContent.append("## Build and Test Commands\n\n");

            List<String> detectedStacks = new ArrayList<>();
            boolean rootHasMaven = rootExists(rootPath, "pom.xml");
            boolean rootHasNode = rootExists(rootPath, "package.json");

            if (rootHasMaven) {
                detectedStacks.add("Maven(Root)");
                appendMavenCommands(newContent, null);
            }
            if (rootHasNode) {
                detectedStacks.add("Node(Root)");
                appendNodeCommands(newContent, null);
            }

            try (Stream<Path> stream = Files.walk(rootPath, 3)) {
                List<Path> allNodes = stream.filter(Files::isDirectory)
                        .filter(p -> !p.equals(rootPath))
                        .filter(p -> !isIgnored(p)) // 使用统一的过滤逻辑
                        .collect(Collectors.toList());

                boolean hasSubModulesSection = false;

                // 存储已经处理过的模块路径，防止重复（比如父子目录都被识别）
                Set<String> processedPaths = new HashSet<>();

                for (Path dir : allNodes) {
                    String relativePath = rootPath.relativize(dir).toString().replace("\\", "/");

                    // 如果父目录已经作为模块处理过了，子目录就不再单独列出（Maven 惯例）
                    if (processedPaths.stream().anyMatch(relativePath::startsWith)) continue;

                    boolean isMaven = Files.exists(dir.resolve("pom.xml"));
                    boolean isNode = Files.exists(dir.resolve("package.json"));

                    if (isMaven || isNode) {
                        processedPaths.add(relativePath); // 标记此路径已处理

                        // 判断是否是异构项目（比如 Root 是 Maven，子目录是 Node）
                        boolean isHeterogeneous = (isMaven && !rootHasMaven) || (isNode && !rootHasNode);

                        if (isHeterogeneous) {
                            detectedStacks.add(relativePath + (isMaven ? "(Maven)" : "(Node)"));
                            if (isMaven) appendMavenCommands(newContent, relativePath);
                            if (isNode) appendNodeCommands(newContent, relativePath);
                        } else {
                            if (!hasSubModulesSection) {
                                newContent.append("### Sub-modules / Sub-projects\n");
                                hasSubModulesSection = true;
                            }
                            newContent.append("- ").append(relativePath).append(": ")
                                    .append(isMaven ? "Maven module" : "Node project")
                                    .append(". Controlled by root project commands.\n");
                        }
                    }
                }
                if (hasSubModulesSection) newContent.append("\n");
            }

            appendGuidelines(newContent);

            Path targetPath = rootPath.resolve("CLAUDE.md");
            String finalContent = newContent.toString();
            boolean updated = true;
            if (Files.exists(targetPath)) {
                updated = !finalContent.equals(new String(Files.readAllBytes(targetPath), StandardCharsets.UTF_8));
            }
            if (updated) Files.write(targetPath, finalContent.getBytes(StandardCharsets.UTF_8));

            ensureInGitignore(rootPath, "CLAUDE.md");
            ensureInGitignore(rootPath, "TODO.md");

            StringBuilder resultMsg = new StringBuilder();
            resultMsg.append(updated ? "已更新" : "已验证").append("项目工程规范");
            if (!detectedStacks.isEmpty()) {
                resultMsg.append(" (检测到技术栈: ").append(String.join(", ", detectedStacks)).append(")");
            } else {
                resultMsg.append(" (未检测到明确的技术栈)");
            }

            return resultMsg.toString();
        } catch (Exception e) {
            LOG.error("Init failed", e);
            return "初始化失败: " + e.getMessage();
        }
    }

    private void appendMavenCommands(StringBuilder buf, String moduleName) {
        if (moduleName == null) {
            buf.append("### Root Project (Maven)\n")
                    .append("- Build: `mvn clean compile`\n")
                    .append("- Test all: `mvn test`\n")
                    .append("- Test single: `mvn test -Dtest=ClassName` (Replace with actual class)\n\n");
        } else {
            buf.append("### Module: ").append(moduleName).append(" (Maven)\n")
                    .append("- Build: `cd ").append(moduleName).append(" && mvn clean compile`\n")
                    .append("- Test: `cd ").append(moduleName).append(" && mvn test`\n\n");
        }
    }

    private void appendNodeCommands(StringBuilder buf, String moduleName) {
        if (moduleName == null) {
            buf.append("### Root Project (Node)\n")
                    .append("- Install: `npm install`\n")
                    .append("- Build: `npm run build`\n")
                    .append("- Test: `npm test`\n\n"); // 建议增加 Test 项
        } else {
            buf.append("### Module: ").append(moduleName).append(" (Node)\n")
                    .append("- Install: `cd ").append(moduleName).append(" && npm install`\n")
                    .append("- Build: `cd ").append(moduleName).append(" && npm run build`\n")
                    .append("- Test: `cd ").append(moduleName).append(" && npm test`\n\n");
        }
    }

    private void appendGuidelines(StringBuilder buf) {
        buf.append("## Guidelines\n\n")
                .append("- **Read-Before-Edit**: Always read the full file content before applying any changes.\n") // 增加读前必改
                .append("- **Atomic Work**: Implement one feature/fix at a time.\n")
                .append("- **Verification**: Run tests before considering a task complete.\n")
                .append("- **Path Usage**: Use relative paths only (e.g., `src/main.java`, NOT `./src/main.java`).\n") // 明确路径格式
                .append("- **Style**: Follow existing patterns in the codebase.\n\n");
    }

    private void ensureInGitignore(Path rootPath, String fileName) {
        try {
            Path gitignore = rootPath.resolve(".gitignore");
            if (Files.exists(gitignore)) {
                String content = new String(Files.readAllBytes(gitignore));
                if (!content.contains(fileName)) {
                    String separator = content.endsWith("\n") ? "" : "\n";
                    Files.write(gitignore, (separator + fileName + "\n").getBytes(), java.nio.file.StandardOpenOption.APPEND);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private boolean rootExists(Path rootPath, String path) {
        return Files.exists(rootPath.resolve(path));
    }

    private boolean deepExists(Path rootPath, String path) {
        try (Stream<Path> stream = Files.walk(rootPath, 3)) {
            return stream.filter(Files::isDirectory)
                    .filter(p -> !isIgnored(p)) // 清爽的过滤
                    .anyMatch(dir -> Files.exists(dir.resolve(path)));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isIgnored(Path path) {
        String name = path.getFileName().toString();
        // 过滤隐藏目录、依赖目录和构建输出目录
        return name.startsWith(".")
                || "node_modules".equals(name)
                || "target".equals(name)
                || "bin".equals(name)
                || "venv".equals(name)
                || "vendor".equals(name)
                || "build".equals(name);
    }
}