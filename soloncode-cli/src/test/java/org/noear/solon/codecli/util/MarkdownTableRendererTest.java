package org.noear.solon.codecli.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MarkdownTableRenderer}.
 *
 * <p>The CLI used to print model replies verbatim, so a Markdown table's column
 * widths came from each cell's own length and the rows did not line up. These
 * tests pin the alignment behaviour, including CJK cells, which occupy two
 * terminal columns.</p>
 */
public class MarkdownTableRendererTest {
    @Test
    public void render_alignsColumnsToWidestCell() {
        String input = "| a | bb |\n"
                + "|---|---|\n"
                + "| 1 | 2 |\n"
                + "| 333 | 4 |";

        String out = MarkdownTableRenderer.render(input);
        String[] lines = out.split("\n");

        assertEquals(4, lines.length);
        // 每行等宽，列边界对齐
        for (String line : lines) {
            assertEquals(lines[0].length(), line.length());
        }
        assertTrue(lines[1].startsWith("|---"));
        assertTrue(lines[0].contains("a "));
        assertTrue(lines[2].contains("1 "));
    }

    @Test
    public void render_keepsNonTableTextUntouched() {
        String input = "第一段\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n结尾";

        String out = MarkdownTableRenderer.render(input);

        assertTrue(out.startsWith("第一段"));
        assertTrue(out.endsWith("结尾"));
        assertTrue(out.contains("\n\n"));
    }

    @Test
    public void render_returnsInputWhenNoTable() {
        String input = "普通文本\n第二行";

        assertEquals(input, MarkdownTableRenderer.render(input));
    }

    @Test
    public void render_handlesNullAndEmpty() {
        assertNull(MarkdownTableRenderer.render(null));
        assertEquals("", MarkdownTableRenderer.render(""));
    }

    @Test
    public void render_alignsCjkCellsByDisplayWidth() {
        String input = "| 名称 | 值 |\n"
                + "|---|---|\n"
                + "| 中文 | x |\n"
                + "| ab | y |";

        String out = MarkdownTableRenderer.render(input);
        String[] lines = out.split("\n");

        // 中文占两列，所以 "中文" 与 "ab" 的显示宽度相同，列仍对齐
        for (String line : lines) {
            assertEquals(displayWidth(line), displayWidth(lines[0]));
        }
    }

    @Test
    public void render_leavesNonTablePipeLinesAlone() {
        // 只有一行以 | 包裹，且第二行不是分隔行 —— 不是表格
        String input = "| 这不是表格 |\n普通文本";

        assertEquals(input, MarkdownTableRenderer.render(input));
    }

    @Test
    public void render_handlesEscapedPipeInCell() {
        String input = "| a | b |\n"
                + "|---|---|\n"
                + "| x \\| y | z |";

        String out = MarkdownTableRenderer.render(input);
        String[] lines = out.split("\n");

        assertEquals(3, lines.length);
        assertTrue(lines[2].contains("x | y"));
    }

    @Test
    public void render_padsShortRowsToColumnCount() {
        String input = "| a | b | c |\n"
                + "|---|---|---|\n"
                + "| 1 | 2 |";

        String out = MarkdownTableRenderer.render(input);
        String[] lines = out.split("\n");

        assertEquals(3, lines.length);
        for (String line : lines) {
            assertEquals(lines[0].length(), line.length());
        }
    }

    @Test
    public void render_supportsAlignmentColons() {
        String input = "| 左 | 中 | 右 |\n"
                + "|:---|:--:|---:|\n"
                + "| 1 | 2 | 3 |";

        String out = MarkdownTableRenderer.render(input);
        String[] lines = out.split("\n");

        assertEquals(3, lines.length);
        assertTrue(lines[1].contains("---"));
    }

    @Test
    public void displayWidth_countsCjkAsTwo() {
        assertEquals(2, MarkdownTableRenderer.displayWidth("中"));
        assertEquals(4, MarkdownTableRenderer.displayWidth("中文"));
        assertEquals(2, MarkdownTableRenderer.displayWidth("ab"));
        assertEquals(0, MarkdownTableRenderer.displayWidth(""));
        assertEquals(0, MarkdownTableRenderer.displayWidth(null));
    }

    private static int displayWidth(String value) {
        int width = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            width += (c >= 0x2E80 && c <= 0x9FFF) ? 2 : 1;
        }
        return width;
    }
}
