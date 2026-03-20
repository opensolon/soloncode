package org.noear.solon.bot.core.util;

import org.noear.snack4.ONode;
import org.noear.solon.core.util.Assert;
import org.yaml.snakeyaml.Yaml;

import java.util.List;

/**
 * 有元数据（YamlFrontmatter）的 markdown 文档工具
 *
 * @author noear 2026/3/20 created
 */
public class MarkdownUtil {
    /**
     * 分析有元数据（YamlFrontmatter）的 markdown 文档
     */
    public static Markdown resolve(List<String> markdownLines) {
        Markdown markdown = new Markdown();

        if (Assert.isEmpty(markdownLines)) {
            return markdown;
        }

        // 检查是否有 Frontmatter 标记
        if (markdownLines.size() > 2 && "---".equals(markdownLines.get(0).trim())) {
            int endSeparatorIndex = -1;
            for (int i = 1; i < markdownLines.size(); i++) {
                if ("---".equals(markdownLines.get(i).trim())) {
                    endSeparatorIndex = i;
                    break;
                }
            }

            if (endSeparatorIndex > 0) {
                List<String> metaLines = markdownLines.subList(1, endSeparatorIndex);
                String metadataStr = String.join("\n", metaLines);

                try {
                    Yaml yaml = new Yaml();
                    Object loaded = yaml.load(metadataStr);
                    if (loaded != null) {
                        // 将 Map 转换为 ONode
                        markdown.metadata.fill(loaded);
                    }
                } catch (Exception e) {
                    // 建议记录日志，防止 YAML 格式错误导致解析崩溃
                }

                // 修正索引：获取第二个 --- 之后的所有内容
                if (endSeparatorIndex + 1 < markdownLines.size()) {
                    List<String> contentLines = markdownLines.subList(endSeparatorIndex + 1, markdownLines.size());
                    markdown.content = String.join("\n", contentLines).trim();
                }

                return markdown;
            }
        }

        // 处理没有元数据的情况
        markdown.content = String.join("\n", markdownLines);
        return markdown;
    }
}