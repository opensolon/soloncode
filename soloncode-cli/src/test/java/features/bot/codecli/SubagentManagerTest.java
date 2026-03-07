package features.bot.codecli;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.bot.core.subagent.SubagentManager;

import java.util.Arrays;
import java.util.List;

/**
 *
 * @author noear 2026/3/7 created
 *
 */
public class SubagentManagerTest {
    private final SubagentManager manager = new SubagentManager(null); // Kernel 传空即可

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

        SubagentManager.SubagentFile result = manager.parseSubagentFile(lines);

        Assertions.assertEquals("reviewer", result.name);
        Assertions.assertTrue(result.tools.contains("Read"));
        Assertions.assertTrue(result.tools.contains("Write"));
        Assertions.assertEquals("sonnet", result.model);
        Assertions.assertTrue(result.systemPrompt.contains("Review the code."));
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

        SubagentManager.SubagentFile result = manager.parseSubagentFile(lines);

        // 解析器不应识别出 name，整个内容应作为 body
        Assertions.assertNull(result.name);
        Assertions.assertTrue(result.systemPrompt.contains("name: reviewer"));
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

        SubagentManager.SubagentFile result = manager.parseSubagentFile(lines);
        Assertions.assertEquals(2, result.tools.size());
        Assertions.assertTrue(result.tools.contains("Read"));
    }

    @Test
    public void testNoClosingSeparator() {
        // 只有开头没有结尾，应全量退回为 body
        List<String> lines = Arrays.asList(
                "---",
                "name: oops",
                "This is just text now"
        );

        SubagentManager.SubagentFile result = manager.parseSubagentFile(lines);
        Assertions.assertNull(result.name);
        Assertions.assertTrue(result.systemPrompt.startsWith("---"));
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

        SubagentManager.SubagentFile result = manager.parseSubagentFile(lines);
        // 应该捕获异常并退回到普通文本模式
        Assertions.assertNull(result.name);
        Assertions.assertTrue(result.systemPrompt.contains("name: [invalid yaml"));
    }
}
