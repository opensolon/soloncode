package features.bot.codecli;

import org.junit.jupiter.api.Test;
import org.noear.solon.bot.core.subagent.SubAgentMetadata;

import java.util.Arrays;
import java.util.List;

/**
 * SubAgent 元数据解析测试
 *
 * 测试从文件内容解析子代理元数据的功能
 *
 * @author noear 2026/3/7 created
 * @author bai 2026/3/9 updated
 */
public class SubagentManagerTest {

    @Test
    public void testStandardFormat() {
        List<String> lines = Arrays.asList(
                "---",
                "name: reviewer",
                "tools: Read, Write",
                "model: sonnet",
                "---",
                "# Instruction",
                "Review the code."
        );

        SubAgentMetadata.PromptWithMetadata result = SubAgentMetadata.fromFileLines(lines);

        assert result.getMetadata().getName().equals("reviewer");
        assert result.getMetadata().getTools().contains("Read");
        assert result.getMetadata().getTools().contains("Write");
        assert result.getMetadata().getModel().equals("sonnet");
        assert result.getPrompt().contains("Review the code.");
    }

    @Test
    public void testFirstLineEmpty_ShouldBePlainBody() {
        // 规范：第一行是空行，不触发 Frontmatter 解析
        List<String> lines = Arrays.asList(
                "",
                "---",
                "name: reviewer",
                "---",
                "Just content"
        );

        SubAgentMetadata.PromptWithMetadata result = SubAgentMetadata.fromFileLines(lines);

        // 解析器不应识别出 name，整个内容应作为 body
        assert result.getMetadata().getName() == null;
        assert result.getPrompt().contains("name: reviewer");
    }

    @Test
    public void testToolsAsArray() {
        List<String> lines = Arrays.asList(
                "---",
                "tools:",
                "  - Read",
                "  - Write",
                "---",
                "Body"
        );

        SubAgentMetadata.PromptWithMetadata result = SubAgentMetadata.fromFileLines(lines);

        assert result.getMetadata().getTools().size() == 2;
        assert result.getMetadata().getTools().contains("Read");
    }

    @Test
    public void testNoClosingSeparator() {
        // 只有开头没有结尾，应全量退回为 body
        List<String> lines = Arrays.asList(
                "---",
                "name: oops",
                "This is just text now"
        );

        SubAgentMetadata.PromptWithMetadata result = SubAgentMetadata.fromFileLines(lines);

        assert result.getMetadata().getName() == null;
        assert result.getPrompt().startsWith("---");
    }

    @Test
    public void testYamlSyntaxError() {
        // YAML 缩进错误或其他语法错误
        List<String> lines = Arrays.asList(
                "---",
                "name: [invalid yaml",
                "---",
                "Body"
        );

        SubAgentMetadata.PromptWithMetadata result = SubAgentMetadata.fromFileLines(lines);

        // 应该捕获异常并退回到普通文本模式
        assert result.getMetadata().getName() == null;
        assert result.getPrompt().contains("name: [invalid yaml");
    }

    @Test
    public void testParseMetadataWithAllFields() {
        String prompt = "---\n" +
                "name: explore\n" +
                "description: Fast codebase exploration expert\n" +
                "tools: Glob, Grep, Read\n" +
                "model: glm-4-flash\n" +
                "---\n\n" +
                "## 探索代理\n\n" +
                "你是一个快速的代码库探索专家。";

        SubAgentMetadata metadata = SubAgentMetadata.fromPrompt(prompt);

        assert metadata.getName().equals("explore");
        assert metadata.getDescription().equals("Fast codebase exploration expert");
        assert metadata.getModel().equals("glm-4-flash");
        assert metadata.getTools().size() == 3;
        assert metadata.getTools().contains("Glob");
        assert metadata.getTools().contains("Grep");
        assert metadata.getTools().contains("Read");
    }

    @Test
    public void testParseMetadataWithPartialFields() {
        String prompt = "---\n" +
                "name: plan\n" +
                "model: glm-4.7\n" +
                "---\n\n" +
                "## 计划代理";

        SubAgentMetadata metadata = SubAgentMetadata.fromPrompt(prompt);

        assert metadata.getName().equals("plan");
        assert metadata.getModel().equals("glm-4.7");
        assert metadata.getDescription() == null;
        assert metadata.getTools().isEmpty();
    }

    @Test
    public void testParseAndClean() {
        String prompt = "---\n" +
                "name: bash\n" +
                "model: glm-4-flash\n" +
                "---\n\n" +
                "## Bash 代理\n\n" +
                "你是一个命令行执行专家。";

        SubAgentMetadata.PromptWithMetadata result = SubAgentMetadata.parseAndClean(prompt);

        // 验证元数据
        assert result.getMetadata().getName().equals("bash");
        assert result.getMetadata().getModel().equals("glm-4-flash");

        // 验证清理后的提示词（不包含 YAML 头部）
        String cleaned = result.getPrompt();
        assert !cleaned.contains("---");
        assert !cleaned.contains("name: bash");
        assert cleaned.contains("## Bash 代理");
        assert cleaned.contains("你是一个命令行执行专家");
    }
}
