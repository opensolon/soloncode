package org.noear.solon.codecli.portal.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MarkdownRendererTerminalSafeTableTest {
    @Test
    public void shouldEscapeTerminalUnsafeCharactersInTableCells() {
        List<String> tableLines = renderVisibleLines(
            "| Input | Len | Note |\n"
                + "| --- | --- | --- |\n"
                + "| `a\tb\tc` | 5 | tab |\n"
                + "| `a\u200Bb\u200Bc` | 5 | zero-width |\n"
                + "| `ab\u0308c` | 4 | combining |\n",
            120
        );

        String zeroWidthRow = findLineContaining(tableLines, "zero-width");
        String combiningRow = findLineContaining(tableLines, "combining");

        assertTrue(zeroWidthRow.contains("\\u200B"), zeroWidthRow);
        assertTrue(combiningRow.contains("\\u0308"), combiningRow);
    }

    @Test
    public void shouldKeepBordersAlignedAfterEscapingTerminalUnsafeCharacters() {
        List<String> tableLines = renderVisibleLines(
            "| Input | Len | Note |\n"
                + "| --- | --- | --- |\n"
                + "| abc | 3 | plain |\n"
                + "| a\tb\tc | 5 | tab |\n"
                + "| a\u200Bb\u200Bc | 5 | zero-width |\n"
                + "| ab\u0308c | 4 | combining |\n",
            120
        );

        int expectedWidth = DisplayWidthUtils.displayWidth(tableLines.get(0));
        for (String line : tableLines) {
            assertEquals(expectedWidth, DisplayWidthUtils.displayWidth(line), line);
        }
    }

    private List<String> renderVisibleLines(String markdown, int width) {
        final List<String> lines = new ArrayList<String>();
        final StringBuilder lineBuffer = new StringBuilder();

        MarkdownRenderer renderer = new MarkdownRenderer(new MarkdownRenderer.LineOutput() {
            @Override
            public void append(String styled) {
                lineBuffer.append(styled);
            }

            @Override
            public void flushLine() {
                lines.add(stripAnsi(lineBuffer.toString()));
                lineBuffer.setLength(0);
            }
        }, new MarkdownRenderer.WidthProvider() {
            @Override
            public int getWidth() {
                return width;
            }
        });

        renderer.feed(markdown);
        renderer.flush();

        List<String> visibleLines = new ArrayList<String>();
        for (String line : lines) {
            if (line != null && !line.trim().isEmpty()) {
                visibleLines.add(line);
            }
        }
        return visibleLines;
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
}
