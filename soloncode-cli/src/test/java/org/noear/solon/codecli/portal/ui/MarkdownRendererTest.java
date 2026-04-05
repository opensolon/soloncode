package org.noear.solon.codecli.portal.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class MarkdownRendererTest {
    @Test
    public void shouldTreatEmojiAsDoubleWidth() {
        assertEquals(2, DisplayWidthUtils.displayWidth("\u2705"));
        assertEquals(2, DisplayWidthUtils.displayWidth("\u274C"));
        assertEquals(7, DisplayWidthUtils.displayWidth("\u2705 \u53EF\u7528"));
    }

    @Test
    public void shouldKeepTableBordersAlignedWithEmojiContent() {
        final List<String> lines = new ArrayList<String>();
        final StringBuilder lineBuffer = new StringBuilder();

        MarkdownRenderer renderer = new MarkdownRenderer(new MarkdownRenderer.LineOutput() {
            @Override
            public void append(String styled) {
                lineBuffer.append(styled);
            }

            @Override
            public void flushLine() {
                lines.add(lineBuffer.toString());
                lineBuffer.setLength(0);
            }
        }, new MarkdownRenderer.WidthProvider() {
            @Override
            public int getWidth() {
                return 120;
            }
        });

        renderer.feed(
            "| Tool | Status |\n"
                + "| --- | --- |\n"
                + "| Node.js | \u2705 \u53EF\u7528 |\n"
                + "| Python | \u274C \u672A\u5B89\u88C5 |\n");
        renderer.flush();

        List<String> tableLines = new ArrayList<String>();
        for (String line : lines) {
            if (line != null && !line.trim().isEmpty()) {
                tableLines.add(stripAnsi(line));
            }
        }

        assertTrue(tableLines.size() >= 5);

        int expectedWidth = DisplayWidthUtils.displayWidth(tableLines.get(0));
        for (String line : tableLines) {
            assertEquals(expectedWidth, DisplayWidthUtils.displayWidth(line), line);
        }
    }

    @Test
    public void shouldWrapLongTableCellsWithoutBreakingBorders() {
        final List<String> lines = new ArrayList<String>();
        final StringBuilder lineBuffer = new StringBuilder();

        MarkdownRenderer renderer = new MarkdownRenderer(new MarkdownRenderer.LineOutput() {
            @Override
            public void append(String styled) {
                lineBuffer.append(styled);
            }

            @Override
            public void flushLine() {
                lines.add(lineBuffer.toString());
                lineBuffer.setLength(0);
            }
        }, new MarkdownRenderer.WidthProvider() {
            @Override
            public int getWidth() {
                return 50;
            }
        });

        renderer.feed(
            "| 列A | 列B |\n"
                + "| --- | --- |\n"
                + "| 这是一个很长很长很长的内容，需要在单元格内部换行 | ✅ 可用 |\n");
        renderer.flush();

        List<String> tableLines = new ArrayList<String>();
        for (String line : lines) {
            if (line != null && !line.trim().isEmpty()) {
                tableLines.add(stripAnsi(line));
            }
        }

        assertTrue(tableLines.size() >= 5);
        int expectedWidth = DisplayWidthUtils.displayWidth(tableLines.get(0));
        for (String line : tableLines) {
            assertEquals(expectedWidth, DisplayWidthUtils.displayWidth(line), line);
        }
    }

    @Test
    public void shouldPreserveLeadingTrailingSpacesAndTabsInTableCells() {
        final List<String> lines = new ArrayList<String>();
        final StringBuilder lineBuffer = new StringBuilder();

        MarkdownRenderer renderer = new MarkdownRenderer(new MarkdownRenderer.LineOutput() {
            @Override
            public void append(String styled) {
                lineBuffer.append(styled);
            }

            @Override
            public void flushLine() {
                lines.add(lineBuffer.toString());
                lineBuffer.setLength(0);
            }
        }, new MarkdownRenderer.WidthProvider() {
            @Override
            public int getWidth() {
                return 80;
            }
        });

        renderer.feed(
            "| 字段A | 字段B | 字段C |\n"
                + "| --- | --- | --- |\n"
                + "| 正常 |  前面有空格 | 后面有空格  |\n"
                + "| 两个\tTab | 三个   空格 | 混合\t和空格 |\n");
        renderer.flush();

        List<String> tableLines = collectVisibleTableLines(lines);
        assertTrue(tableLines.size() >= 6);

        String row1 = findLineContaining(tableLines, "前面有空格");
        String row2 = findLineContaining(tableLines, "两个   Tab");
        assertFalse(row1.isEmpty(), tableLines.toString());
        assertFalse(row2.isEmpty(), tableLines.toString());
        assertTrue(row1.contains("前面有空格"), row1);
        assertTrue(row1.contains("后面有空格"), row1);
        assertTrue(row2.contains("两个   Tab"), row2);
        assertTrue(row2.contains("三个   空格"), row2);
        assertTrue(row2.contains("混合   和空格"), row2);
    }

    @Test
    public void shouldKeepEmptyAndWhitespaceOnlyCellsDistinct() {
        final List<String> lines = new ArrayList<String>();
        final StringBuilder lineBuffer = new StringBuilder();

        MarkdownRenderer renderer = new MarkdownRenderer(new MarkdownRenderer.LineOutput() {
            @Override
            public void append(String styled) {
                lineBuffer.append(styled);
            }

            @Override
            public void flushLine() {
                lines.add(lineBuffer.toString());
                lineBuffer.setLength(0);
            }
        }, new MarkdownRenderer.WidthProvider() {
            @Override
            public int getWidth() {
                return 80;
            }
        });

        renderer.feed(
            "| 有内容 | 纯空 | 空格 | 制表符 |\n"
                + "| --- | --- | --- | --- |\n"
                + "| hello | |   | \t |\n");
        renderer.flush();

        List<String> tableLines = collectVisibleTableLines(lines);
        assertTrue(tableLines.size() >= 5);

        String row = tableLines.get(3);
        assertTrue(row.contains(" hello "), row);
        assertFalse(row.contains("││"), row);
    }

    @Test
    public void shouldTreatIndentedTableRowsAsSingleTable() {
        final List<String> lines = new ArrayList<String>();
        final StringBuilder lineBuffer = new StringBuilder();

        MarkdownRenderer renderer = new MarkdownRenderer(new MarkdownRenderer.LineOutput() {
            @Override
            public void append(String styled) {
                lineBuffer.append(styled);
            }

            @Override
            public void flushLine() {
                lines.add(lineBuffer.toString());
                lineBuffer.setLength(0);
            }
        }, new MarkdownRenderer.WidthProvider() {
            @Override
            public int getWidth() {
                return 80;
            }
        });

        renderer.feed(
            "   | 输入 | 长度 | 备注 |\n"
                + "   | --- | --- | --- |\n"
                + "   | abc | 3 | 普通 |\n"
                + "   | a b c | 5 | 含空格 |\n");
        renderer.flush();

        List<String> tableLines = collectVisibleTableLines(lines);
        assertTrue(tableLines.size() >= 5, tableLines.toString());
        assertTrue(tableLines.get(0).contains("┌"), tableLines.toString());
        assertTrue(findLineContaining(tableLines, "含空格").contains("a b c"), tableLines.toString());
    }

    @Test
    public void shouldKeepZeroWidthCharactersInsideSingleCell() {
        final List<String> lines = new ArrayList<String>();
        final StringBuilder lineBuffer = new StringBuilder();

        MarkdownRenderer renderer = new MarkdownRenderer(new MarkdownRenderer.LineOutput() {
            @Override
            public void append(String styled) {
                lineBuffer.append(styled);
            }

            @Override
            public void flushLine() {
                lines.add(lineBuffer.toString());
                lineBuffer.setLength(0);
            }
        }, new MarkdownRenderer.WidthProvider() {
            @Override
            public int getWidth() {
                return 80;
            }
        });

        renderer.feed(
            "| 输入 | 长度 | 备注 |\n"
                + "| --- | --- | --- |\n"
                + "| a\u200Bb\u200Bc | 5 | 含零宽空格 |\n"
                + "| a\u0308bc | 4 | 组合字符 |\n");
        renderer.flush();

        List<String> tableLines = collectVisibleTableLines(lines);
        String zeroWidthRow = findLineContaining(tableLines, "含零宽空格");
        String combiningRow = findLineContaining(tableLines, "组合字符");
        assertFalse(zeroWidthRow.isEmpty(), tableLines.toString());
        assertFalse(combiningRow.isEmpty(), tableLines.toString());
        assertTrue(DisplayWidthUtils.displayWidth(zeroWidthRow) > 0, zeroWidthRow);
        assertTrue(combiningRow.contains("äbc"), combiningRow);
    }

    @Test
    public void shouldKeepFollowingIndentedRowsInsideSameTableBlock() {
        final List<String> lines = new ArrayList<String>();
        final StringBuilder lineBuffer = new StringBuilder();

        MarkdownRenderer renderer = new MarkdownRenderer(new MarkdownRenderer.LineOutput() {
            @Override
            public void append(String styled) {
                lineBuffer.append(styled);
            }

            @Override
            public void flushLine() {
                lines.add(lineBuffer.toString());
                lineBuffer.setLength(0);
            }
        }, new MarkdownRenderer.WidthProvider() {
            @Override
            public int getWidth() {
                return 80;
            }
        });

        renderer.feed(
            "| 有内容 | 纯空 | 空格 | 制表符 | 换行符 |\n"
                + "| --- | --- | --- | --- | --- |\n"
                + "| hello | |   | \t |  |\n"
                + "   | world | |  | \t |  |\n");
        renderer.flush();

        List<String> tableLines = collectVisibleTableLines(lines);
        assertTrue(tableLines.size() >= 6, tableLines.toString());
        String helloRow = findLineContaining(tableLines, "hello");
        String worldRow = findLineContaining(tableLines, "world");
        assertFalse(helloRow.isEmpty(), tableLines.toString());
        assertFalse(worldRow.isEmpty(), tableLines.toString());
        assertTrue(worldRow.contains("world"), worldRow);
        assertFalse(tableLines.get(tableLines.size() - 1).contains("world") && !tableLines.get(0).contains("┌"), tableLines.toString());
    }

    private List<String> collectVisibleTableLines(List<String> lines) {
        List<String> tableLines = new ArrayList<String>();
        for (String line : lines) {
            if (line != null && !line.trim().isEmpty()) {
                tableLines.add(stripAnsi(line));
            }
        }
        return tableLines;
    }

    private String findLineContaining(List<String> lines, String marker) {
        for (String line : lines) {
            if (line.contains(marker)) {
                return line;
            }
        }

        return "";
    }

    private String stripAnsi(String text) {
        return text.replaceAll("\u001B\\[[;\\d]*m", "");
    }

    @Test
    public void shouldKeepTableWhenCellContainsActualNewline() {
        final List<String> lines = new ArrayList<String>();
        final StringBuilder lineBuffer = new StringBuilder();

        MarkdownRenderer renderer = new MarkdownRenderer(new MarkdownRenderer.LineOutput() {
            @Override
            public void append(String styled) {
                lineBuffer.append(styled);
            }

            @Override
            public void flushLine() {
                lines.add(lineBuffer.toString());
                lineBuffer.setLength(0);
            }
        }, new MarkdownRenderer.WidthProvider() {
            @Override
            public int getWidth() {
                return 80;
            }
        });

        renderer.feed(
            "| 有内容 | 纯空 | 空格 | 制表符 | 换行符 |\n"
                + "| --- | --- | --- | --- | --- |\n"
                + "| hello | |   | \t " + "\n" + " |\n"
                + "| world | |  | \t " + "\n" + " |\n"
                + "\n"
                + "| 输入 | 长度 | 备注 |\n"
                + "| --- | --- | --- |\n"
                + "| abc | 3 | 普通 |\n"
                + "| a b c | 5 | 含空格 |\n"
                + "| a\tb\tc | 5 | 含Tab |\n"
                + "| a\u200Bb\u200Bc | 5 | 含零宽空格 |\n"
                + "| a\u0308bc | 4 | 组合字符 |\n"
        );
        renderer.flush();

        List<String> tableLines = collectVisibleTableLines(lines);
        String helloLine = findLineContaining(tableLines, "hello");
        String worldLine = findLineContaining(tableLines, "world");
        assertFalse(helloLine.isEmpty(), tableLines.toString());
        assertFalse(worldLine.isEmpty(), tableLines.toString());
        assertTrue(tableLines.indexOf(worldLine) > tableLines.indexOf(helloLine), tableLines.toString());
    }

    @Test
    public void shouldRenderMultilineNewlineCellAsOneTableRow() {
        final List<String> lines = new ArrayList<String>();
        final StringBuilder lineBuffer = new StringBuilder();

        MarkdownRenderer renderer = new MarkdownRenderer(new MarkdownRenderer.LineOutput() {
            @Override
            public void append(String styled) {
                lineBuffer.append(styled);
            }

            @Override
            public void flushLine() {
                lines.add(lineBuffer.toString());
                lineBuffer.setLength(0);
            }
        }, new MarkdownRenderer.WidthProvider() {
            @Override
            public int getWidth() {
                return 120;
            }
        });

        renderer.feed(
            "| 有内容 | 纯空 | 空格 | 制表符 | 换行符 |\n"
                + "|--------|:----:|:-----:|:------:|:------:|\n"
                + "| hello | | ` ` | ` ` | `\n"
                + "` |\n"
                + "| world | | `  ` | `                ` | `\n"
                + "` |\n"
        );
        renderer.flush();

        List<String> tableLines = collectVisibleTableLines(lines);
        assertTrue(tableLines.size() >= 5, tableLines.toString());
        assertFalse(findLineContaining(tableLines, "hello").isEmpty(), tableLines.toString());
        assertFalse(findLineContaining(tableLines, "world").isEmpty(), tableLines.toString());
        assertTrue(tableLines.indexOf(findLineContaining(tableLines, "world")) >
            tableLines.indexOf(findLineContaining(tableLines, "hello")), tableLines.toString());
        assertTrue(findLineContaining(tableLines, "| world | |").isEmpty(), tableLines.toString());
        assertTrue(findLineContaining(tableLines, "`").isEmpty(), tableLines.toString());
    }

    @Test
    public void shouldRenderTabsAndInvisibleCharactersInCodeSpans() {
        final List<String> lines = new ArrayList<String>();
        final StringBuilder lineBuffer = new StringBuilder();

        MarkdownRenderer renderer = new MarkdownRenderer(new MarkdownRenderer.LineOutput() {
            @Override
            public void append(String styled) {
                lineBuffer.append(styled);
            }

            @Override
            public void flushLine() {
                lines.add(lineBuffer.toString());
                lineBuffer.setLength(0);
            }
        }, new MarkdownRenderer.WidthProvider() {
            @Override
            public int getWidth() {
                return 120;
            }
        });

        renderer.feed(
            "| 输入 | 长度 | 备注 |\n"
                + "|------|:----:|------|\n"
                + "| `abc` | 3 | 普通 |\n"
                + "| `a b c` | 5 | 含空格 |\n"
                + "| `a\tb\tc` | 5 | 含Tab |\n"
                + "| `a\u200Bb\u200Bc` | 5 | 含零宽空格 |\n"
                + "| `ab̈c` | 4 | 组合字符 |\n"
        );
        renderer.flush();

        List<String> tableLines = collectVisibleTableLines(lines);
        assertTrue(tableLines.size() >= 7, tableLines.toString());

        int expectedWidth = DisplayWidthUtils.displayWidth(tableLines.get(0));
        for (String line : tableLines) {
            assertEquals(expectedWidth, DisplayWidthUtils.displayWidth(line), line);
        }
        assertFalse(findLineContaining(tableLines, "含Tab").isEmpty(), tableLines.toString());
        assertFalse(findLineContaining(tableLines, "含零宽空格").isEmpty(), tableLines.toString());
        assertFalse(findLineContaining(tableLines, "组合字符").isEmpty(), tableLines.toString());
    }

    @Test
    public void shouldKeepTableBordersAlignedWithTabsAndInvisibleCharacters() {
        final List<String> lines = new ArrayList<String>();
        final StringBuilder lineBuffer = new StringBuilder();

        MarkdownRenderer renderer = new MarkdownRenderer(new MarkdownRenderer.LineOutput() {
            @Override
            public void append(String styled) {
                lineBuffer.append(styled);
            }

            @Override
            public void flushLine() {
                lines.add(lineBuffer.toString());
                lineBuffer.setLength(0);
            }
        }, new MarkdownRenderer.WidthProvider() {
            @Override
            public int getWidth() {
                return 80;
            }
        });

        renderer.feed(
            "| 输入 | 长度 | 备注 |\n"
                + "| --- | --- | --- |\n"
                + "| abc | 3 | 普通 |\n"
                + "| a b c | 5 | 含空格 |\n"
                + "| a\tb\tc | 5 | 含Tab |\n"
                + "| a\u200Bb\u200Bc | 5 | 含零宽空格 |\n"
                + "| a\u0308bc | 4 | 组合字符 |\n");
        renderer.flush();

        List<String> tableLines = collectVisibleTableLines(lines);
        assertTrue(tableLines.size() >= 7, tableLines.toString());

        int expectedWidth = DisplayWidthUtils.displayWidth(tableLines.get(0));
        for (String line : tableLines) {
            assertEquals(expectedWidth, DisplayWidthUtils.displayWidth(line), line);
        }

        assertFalse(findLineContaining(tableLines, "含空格").isEmpty(), tableLines.toString());
        assertFalse(findLineContaining(tableLines, "含Tab").isEmpty(), tableLines.toString());
        assertFalse(findLineContaining(tableLines, "含零宽空格").isEmpty(), tableLines.toString());
        assertFalse(findLineContaining(tableLines, "组合字符").isEmpty(), tableLines.toString());
    }
}
