package org.noear.solon.codecli.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 把 Markdown 表格在终端里对齐渲染。
 *
 * <p>模型回复里的表格若原样输出，列宽由各单元格自身长度决定，终端里会参差
 * 错位难以阅读。这里按每列的最大显示宽度重新排版，并用 ─ 画分隔线。</p>
 *
 * <p>宽度按显示宽度计算：CJK 字符与全角符号占两列，制表符等控制字符按零宽
 * 处理，避免中文表格对不齐。</p>
 */
public final class MarkdownTableRenderer {
    private static final int MIN_COLUMN_WIDTH = 3;

    private MarkdownTableRenderer() {
    }

    /**
     * 渲染一段文本中的 Markdown 表格，非表格内容原样保留。
     *
     * <p>没有表格时返回原字符串，调用方无需判断。</p>
     */
    public static String render(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        boolean changed = false;

        int i = 0;
        while (i < lines.length) {
            if (!isTableLine(lines[i])) {
                out.add(lines[i]);
                i++;
                continue;
            }

            int start = i;
            while (i < lines.length && isTableLine(lines[i])) {
                i++;
            }

            List<String> rendered = renderBlock(Arrays.asList(lines).subList(start, i));
            if (rendered != null) {
                out.addAll(rendered);
                changed = true;
            } else {
                // 不是合法表格（例如缺少分隔行），原样输出
                for (int k = start; k < i; k++) {
                    out.add(lines[k]);
                }
            }
        }

        return changed ? String.join("\n", out) : text;
    }

    /**
     * 判断一行是否像表格行：以 | 开头和结尾，且中间至少还有一个 |。
     */
    static boolean isTableLine(String line) {
        String trimmed = line.trim();
        if (trimmed.length() < 3 || trimmed.charAt(0) != '|' || trimmed.charAt(trimmed.length() - 1) != '|') {
            return false;
        }
        return trimmed.indexOf('|', 1) < trimmed.length() - 1;
    }

    /**
     * 渲染一个连续的表格块；不是合法表格时返回 null。
     */
    private static List<String> renderBlock(List<String> block) {
        if (block.size() < 2) {
            return null;
        }

        List<String[]> rows = new ArrayList<>(block.size());
        for (String line : block) {
            rows.add(splitRow(line));
        }

        // 第二行必须是分隔行（|---|:--:|），否则不是表格
        if (!isDelimiterRow(rows.get(1))) {
            return null;
        }

        int columns = 0;
        for (String[] row : rows) {
            columns = Math.max(columns, row.length);
        }

        int[] widths = new int[columns];
        for (String[] row : rows) {
            for (int c = 0; c < row.length; c++) {
                widths[c] = Math.max(widths[c], displayWidth(row[c]));
            }
        }
        for (int c = 0; c < columns; c++) {
            widths[c] = Math.max(widths[c], MIN_COLUMN_WIDTH);
        }

        List<String> out = new ArrayList<>(rows.size() + 1);
        out.add(formatRow(rows.get(0), widths));
        out.add(delimiterLine(widths));

        for (int r = 2; r < rows.size(); r++) {
            out.add(formatRow(rows.get(r), widths));
        }
        return out;
    }

    /**
     * 拆一行表格为单元格，去掉首尾的 | 并 trim。
     */
    private static String[] splitRow(String line) {
        String trimmed = line.trim();
        String inner = trimmed.substring(1, trimmed.length() - 1);
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '\\' && i + 1 < inner.length() && inner.charAt(i + 1) == '|') {
                // 转义的竖线属于单元格内容
                current.append('|');
                i++;
            } else if (c == '|') {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString().trim());

        return cells.toArray(new String[0]);
    }

    /**
     * 判断是否为分隔行，如 {@code |---|:--:|---:|}
     */
    private static boolean isDelimiterRow(String[] cells) {
        if (cells.length == 0) {
            return false;
        }
        for (String cell : cells) {
            if (!cell.matches(":?-+:?")) {
                return false;
            }
        }
        return true;
    }

    private static String formatRow(String[] cells, int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < widths.length; c++) {
            String value = c < cells.length ? cells[c] : "";
            sb.append("| ").append(pad(value, widths[c])).append(' ');
        }
        sb.append('|');
        return sb.toString();
    }

    private static String delimiterLine(int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int width : widths) {
            sb.append('|').append(repeat('-', width + 2));
        }
        sb.append('|');
        return sb.toString();
    }

    /**
     * 按显示宽度右补空格。
     */
    private static String pad(String value, int width) {
        int padding = width - displayWidth(value);
        if (padding <= 0) {
            return value;
        }
        return value + repeat(' ', padding);
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 计算字符串的终端显示宽度：CJK 与全角符号算两列，组合用控制字符算零列。
     */
    static int displayWidth(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }

        int width = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isCombining(c)) {
                continue;
            }
            width += isWide(c) ? 2 : 1;
        }
        return width;
    }

    private static boolean isWide(char c) {
        // 常见 CJK 统一表意文字、兼容表意、全角 forms、平/片假名、谚文
        return (c >= 0x1100 && c <= 0x115F)
                || (c >= 0x2E80 && c <= 0x303E)
                || (c >= 0x3041 && c <= 0x33FF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0xA000 && c <= 0xA4CF)
                || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE6F)
                || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6);
    }

    private static boolean isCombining(char c) {
        return (c >= 0x0300 && c <= 0x036F)
                || (c >= 0x200B && c <= 0x200F)
                || c == 0xFEFF;
    }
}
