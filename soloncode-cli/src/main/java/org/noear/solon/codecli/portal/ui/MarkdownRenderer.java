/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package org.noear.solon.codecli.portal.ui;

import org.noear.solon.codecli.portal.ui.theme.PortalTheme;
import org.noear.solon.codecli.portal.ui.theme.PortalThemes;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式 Markdown 渲染器 — 接收逐字符/逐 token 输入，输出 ANSI 彩色终端文本
 *
 * <p>
 * 支持：
 * <ul>
 * <li><b>**bold**</b> → ANSI 粗体</li>
 * <li><b>`inline code`</b> → 高亮色</li>
 * <li><b>```code block```</b> → 代码块样式 + 语言标签</li>
 * <li><b># Header</b> → 粗体粗色（行首判断）</li>
 * <li><b>- list / 1. list</b> → 项目符号</li>
 * <li><b>> blockquote</b> → 引用样式</li>
 * <li><b>--- / ***</b> → 水平分隔线</li>
 * </ul>
 *
 * @author solon-cli
 */
public class MarkdownRenderer {

    // ── ANSI 样式 ──
    private static final String RESET = "\033[0m";
    private static final String ITALIC = "\033[3m";

    private PortalTheme theme = PortalThemes.defaultTheme();
    private String cHeader = theme.markdownHeader().ansiBoldFg();
    private String cCodeInline = theme.markdownInlineCode().ansiFg();
    private String cCodeBlock = theme.markdownCodeText().ansiFg();
    private String cCodeBorder = theme.markdownCodeBorder().ansiFg();
    private String cCodeLang = theme.textMuted().ansiDimFg();
    private String cBold = theme.markdownBold().ansiBoldFg();
    private String cListBullet = theme.markdownListBullet().ansiFg();
    private String cListNum = theme.markdownListNumber().ansiFg();
    private String cBlockquote = theme.markdownBlockquote().ansiFg();
    private String cHr = theme.markdownRule().ansiFg();
    private String cStrike = theme.textMuted().ansiStyledFg("9");
    private String cTableBorder = theme.tableBorder().ansiFg();
    private String cTableHeader = theme.tableHeader().ansiBoldFg();

    // ── 渲染状态 ──
    private enum State {
        NORMAL,
        IN_BOLD,
        IN_ITALIC,
        IN_STRIKETHROUGH, // ~~...~~
        IN_CODE_INLINE,
        IN_CODE_BLOCK,
        IN_HEADER,
        IN_BLOCKQUOTE,
    }

    private State state = State.NORMAL;
    private boolean atLineStart = true;
    private final List<String> pendingTableLines = new ArrayList<String>();
    private final StringBuilder tableLineBuffer = new StringBuilder();
    private boolean bufferingTableLine = false;
    private final StringBuilder pendingBuf = new StringBuilder();
    private String codeBlockLang = "";

    // ── 输出回调 ──
    private final LineOutput output;
    private final WidthProvider widthProvider;

    public interface WidthProvider {
        int getWidth();
    }

    /**
     * 输出回调接口
     */
    @FunctionalInterface
    public interface LineOutput {
        /** 追加内容到当前行缓冲 */
        void append(String styled);

        /** 刷新当前行（输出一整行到 printAbove） */
        default void flushLine() {
        }
    }

    public MarkdownRenderer(LineOutput output) {
        this(output, new WidthProvider() {
            @Override
            public int getWidth() {
                return 80;
            }
        });
    }

    public MarkdownRenderer(LineOutput output, WidthProvider widthProvider) {
        this.output = output;
        this.widthProvider = widthProvider == null ? new WidthProvider() {
            @Override
            public int getWidth() {
                return 80;
            }
        } : widthProvider;
    }

    public void setTheme(PortalTheme theme) {
        this.theme = theme == null ? PortalThemes.defaultTheme() : theme;
        this.cHeader = this.theme.markdownHeader().ansiBoldFg();
        this.cCodeInline = this.theme.markdownInlineCode().ansiFg();
        this.cCodeBlock = this.theme.markdownCodeText().ansiFg();
        this.cCodeBorder = this.theme.markdownCodeBorder().ansiFg();
        this.cCodeLang = this.theme.textMuted().ansiDimFg();
        this.cBold = this.theme.markdownBold().ansiBoldFg();
        this.cListBullet = this.theme.markdownListBullet().ansiFg();
        this.cListNum = this.theme.markdownListNumber().ansiFg();
        this.cBlockquote = this.theme.markdownBlockquote().ansiFg();
        this.cHr = this.theme.markdownRule().ansiFg();
        this.cStrike = this.theme.textMuted().ansiStyledFg("9");
        this.cTableBorder = this.theme.tableBorder().ansiFg();
        this.cTableHeader = this.theme.tableHeader().ansiBoldFg();
    }

    /** 重置状态（新的 AI 回复开始时调用） */
    public void reset() {
        state = State.NORMAL;
        atLineStart = true;
        pendingTableLines.clear();
        tableLineBuffer.setLength(0);
        bufferingTableLine = false;
        pendingBuf.setLength(0);
        codeBlockLang = "";
    }

    // ═══════════════════════════════════════════════════════════
    // 核心：逐字符处理
    // ═══════════════════════════════════════════════════════════

    /** 喂入一个 delta 文本块（可能是单字符或多字符） */
    public void feed(String delta) {
        for (int i = 0; i < delta.length(); i++) {
            char ch = delta.charAt(i);
            feedChar(ch);
        }
    }

    private void feedChar(char ch) {
        if (bufferingTableLine) {
            handleBufferedTableChar(ch);
            return;
        }

        if (atLineStart && !pendingTableLines.isEmpty() && ch != '|') {
            flushPendingTable();
        }

        // 有未决缓冲时，先判断能否凑出完整标记
        if (pendingBuf.length() > 0) {
            pendingBuf.append(ch);
            if (resolvePending()) {
                return;
            }
            // 如果无法解析但缓冲太长（不可能匹配任何标记），flush 作为普通文本
            // 最长可能的标记：###### + 空格 = 7，数字+. +空格 可能有 10+
            if (pendingBuf.length() > 10) {
                String text = pendingBuf.toString();
                pendingBuf.setLength(0);
                for (char c : text.toCharArray()) {
                    emitChar(c);
                }
            }
            return;
        }

        // 代码块内：直接原样输出
        if (state == State.IN_CODE_BLOCK) {
            handleCodeBlock(ch);
            return;
        }

        // 可能是标记开头
        if (ch == '*' || ch == '`' || ch == '~') {
            pendingBuf.append(ch);
            return;
        }

        // 行首标记检测
        if (atLineStart && state == State.NORMAL) {
            if (ch == '#') {
                pendingBuf.append(ch);
                return;
            }
            if (ch == '>') {
                state = State.IN_BLOCKQUOTE;
                output.append("  " + cBlockquote + "│ " + RESET + cBlockquote);
                atLineStart = false;
                return;
            }
            if (ch == '-') {
                pendingBuf.append(ch);
                return;
            }
            // ___ 分割线
            if (ch == '_') {
                pendingBuf.append(ch);
                return;
            }
            // 表格行: 以 | 开头
            if (ch == '|') {
                bufferingTableLine = true;
                tableLineBuffer.setLength(0);
                tableLineBuffer.append(ch);
                atLineStart = false;
                return;
            }
            // 有序列表: 1. 2. 3. ...
            if (ch >= '0' && ch <= '9') {
                pendingBuf.append(ch);
                return;
            }
        }

        // 换行处理
        if (ch == '\n') {
            handleNewline();
            return;
        }
        if (ch == '\r') {
            return; // 忽略 \r
        }

        emitChar(ch);
    }

    private void handleBufferedTableChar(char ch) {
        if (ch == '\r') {
            return;
        }

        if (ch == '\n') {
            pendingTableLines.add(tableLineBuffer.toString());
            tableLineBuffer.setLength(0);
            bufferingTableLine = false;
            atLineStart = true;
            lineCharCount = 0;
            return;
        }

        tableLineBuffer.append(ch);
    }

    // ═══════════════════════════════════════════════════════════
    // 未决缓冲解析
    // ═══════════════════════════════════════════════════════════

    /** 尝试解析 pendingBuf 中的标记，返回 true 表示已处理 */
    private boolean resolvePending() {
        String buf = pendingBuf.toString();

        // ```: 代码块开始/结束
        if (buf.equals("```")) {
            pendingBuf.setLength(0);
            if (state == State.IN_CODE_BLOCK) {
                // 结束代码块
                output.append(RESET);
                output.flushLine();
                output.append("  " + cCodeBorder + "└" + repeatChar('─', 40) + RESET);
                output.flushLine();
                state = State.NORMAL;
                atLineStart = true;
            } else {
                // 开始代码块 — 后面可能跟语言名
                state = State.IN_CODE_BLOCK;
                codeBlockLang = "";
                // 语言名在后续字符中收集（到换行为止）
            }
            return true;
        }
        if (buf.length() < 3 && buf.charAt(0) == '`' && (buf.length() == 1 || buf.charAt(buf.length() - 1) == '`')) {
            return false; // 继续等待，可能是 ``` 的一部分
        }

        // `: 行内代码
        if (buf.equals("`") && pendingBuf.length() == 1) {
            // 等一下，看看下一个是不是也是 `
            return false;
        }
        if (buf.length() == 2 && buf.equals("``")) {
            return false; // 等第三个字符
        }
        if (buf.startsWith("`") && !buf.startsWith("``")) {
            // 确认是单 ` — 行内代码切换
            pendingBuf.setLength(0);
            if (state == State.IN_CODE_INLINE) {
                output.append(RESET);
                state = State.NORMAL;
            } else if (state == State.NORMAL || state == State.IN_HEADER || state == State.IN_BLOCKQUOTE) {
                state = State.IN_CODE_INLINE;
                output.append(cCodeInline);
            }
            // buf 剩余字符（第2个字符开始）继续喂入
            for (int i = 1; i < buf.length(); i++) {
                feedChar(buf.charAt(i));
            }
            return true;
        }

        // **: 粗体
        if (buf.equals("**")) {
            return false; // 等下一个字符确认不是第3个 *
        }
        if (buf.startsWith("**") && buf.length() > 2) {
            pendingBuf.setLength(0);
            if (state == State.IN_BOLD) {
                output.append(RESET);
                state = State.NORMAL;
            } else if (state == State.NORMAL) {
                state = State.IN_BOLD;
                output.append(cBold);
            }
            // 第3个字符开始继续喂入
            for (int i = 2; i < buf.length(); i++) {
                feedChar(buf.charAt(i));
            }
            return true;
        }

        // *: 可能是斜体或列表符号
        if (buf.equals("*")) {
            return false; // 等下一个字符
        }
        if (buf.startsWith("*") && !buf.startsWith("**") && buf.length() >= 2) {
            pendingBuf.setLength(0);
            if (atLineStart && buf.charAt(1) == ' ') {
                // 无序列表符号
                output.append("  " + cListBullet + "• " + RESET);
                atLineStart = false;
                // 后续字符
                for (int i = 2; i < buf.length(); i++) {
                    feedChar(buf.charAt(i));
                }
                return true;
            }
            // 斜体切换
            if (state == State.IN_ITALIC) {
                output.append(RESET);
                state = State.NORMAL;
            } else if (state == State.NORMAL) {
                state = State.IN_ITALIC;
                output.append(ITALIC);
            }
            for (int i = 1; i < buf.length(); i++) {
                feedChar(buf.charAt(i));
            }
            return true;
        }

        // #: Header
        if (buf.matches("^#{1,6}$")) {
            return false; // 继续等空格
        }
        if (buf.matches("^#{1,6} .*") || (buf.matches("^#{1,6}[^#].*"))) {
            if (atLineStart) {
                pendingBuf.setLength(0);
                int level = 0;
                while (level < buf.length() && buf.charAt(level) == '#')
                    level++;
                state = State.IN_HEADER;
                output.append("  " + cHeader);
                atLineStart = false;
                // 跳过 # 和空格，输出标题内容
                int start = level;
                if (start < buf.length() && buf.charAt(start) == ' ')
                    start++;
                for (int i = start; i < buf.length(); i++) {
                    feedChar(buf.charAt(i));
                }
                return true;
            }
        }

        // -: 可能是列表项或分隔线
        if (buf.equals("-")) {
            return false;
        }
        if (buf.equals("- ") || (buf.length() == 2 && buf.charAt(0) == '-' && buf.charAt(1) == ' ')) {
            pendingBuf.setLength(0);
            if (atLineStart) {
                output.append("  " + cListBullet + "• " + RESET);
                atLineStart = false;
                return true;
            }
        }
        if (buf.equals("---") || buf.equals("***") || buf.equals("___")) {
            pendingBuf.setLength(0);
            output.append("  " + cHr + repeatChar('─', 40) + RESET);
            output.flushLine();
            atLineStart = true;
            return true;
        }
        // _ 的区分：___ 是 HR，否则是普通文本
        if (buf.startsWith("_") && buf.length() >= 2 && !buf.equals("__") && !buf.equals("___")) {
            pendingBuf.setLength(0);
            for (char c : buf.toCharArray()) {
                emitChar(c);
            }
            return true;
        }
        if (buf.equals("_") || buf.equals("__")) {
            return false; // 等有没有更多 _
        }
        if (buf.startsWith("-") && buf.length() >= 2 && buf.charAt(1) != '-' && buf.charAt(1) != ' ') {
            pendingBuf.setLength(0);
            for (char c : buf.toCharArray()) {
                emitChar(c);
            }
            return true;
        }
        if (buf.startsWith("--") && buf.length() < 3) {
            return false;
        }

        // ~~: 删除线
        if (buf.equals("~")) {
            return false;
        }
        if (buf.equals("~~")) {
            return false; // 等下一个字符确认
        }
        if (buf.startsWith("~~") && buf.length() > 2) {
            pendingBuf.setLength(0);
            if (state == State.IN_STRIKETHROUGH) {
                output.append(RESET);
                state = State.NORMAL;
            } else if (state == State.NORMAL) {
                state = State.IN_STRIKETHROUGH;
                output.append(cStrike);
            }
            for (int i = 2; i < buf.length(); i++) {
                feedChar(buf.charAt(i));
            }
            return true;
        }
        if (buf.startsWith("~") && !buf.startsWith("~~") && buf.length() >= 2) {
            // 单个 ~ 不是标记，当成普通文本
            pendingBuf.setLength(0);
            for (char c : buf.toCharArray()) {
                emitChar(c);
            }
            return true;
        }

        // 有序列表: 1. 2. 3. ... (行首数字+点+空格)
        if (buf.matches("^\\d+$")) {
            return false; // 继续等 . 或其他字符
        }
        if (buf.matches("^\\d+\\. $")) {
            pendingBuf.setLength(0);
            if (atLineStart) {
                // 提取数字部分
                String num = buf.substring(0, buf.indexOf('.'));
                output.append("  " + cListNum + num + ". " + RESET);
                atLineStart = false;
                return true;
            }
        }
        if (buf.matches("^\\d+\\..*") && !buf.matches("^\\d+\\. $")) {
            // 数字后面跟 . 但不是列表格式，或已经有后续字符
            if (buf.matches("^\\d+\\. .+")) {
                // 是列表，处理
                pendingBuf.setLength(0);
                if (atLineStart) {
                    int dotIdx = buf.indexOf('.');
                    String num = buf.substring(0, dotIdx);
                    output.append("  " + cListNum + num + ". " + RESET);
                    atLineStart = false;
                    for (int i = dotIdx + 2; i < buf.length(); i++) {
                        feedChar(buf.charAt(i));
                    }
                    return true;
                }
            }
            if (buf.matches("^\\d+\\.$")) {
                return false; // 等空格
            }
            // 普通文本
            pendingBuf.setLength(0);
            for (char c : buf.toCharArray()) {
                emitChar(c);
            }
            return true;
        }

        return false;
    }

    // ═══════════════════════════════════════════════════════════
    // 代码块处理
    // ═══════════════════════════════════════════════════════════

    private final StringBuilder codeBlockCloseBuf = new StringBuilder();

    private void handleCodeBlock(char ch) {
        // 检测代码块结束标记 ```
        if (ch == '`') {
            codeBlockCloseBuf.append(ch);
            if (codeBlockCloseBuf.length() == 3) {
                // 代码块结束
                codeBlockCloseBuf.setLength(0);
                output.append(RESET);
                output.flushLine();
                output.append("  " + cCodeBorder + "└" + repeatChar('─', 40) + RESET);
                output.flushLine();
                state = State.NORMAL;
                atLineStart = true;
                lineCharCount = 0;
            }
            return;
        }

        // 不是 ` — 先输出之前累积的 ` 字符
        if (codeBlockCloseBuf.length() > 0) {
            String partial = codeBlockCloseBuf.toString();
            codeBlockCloseBuf.setLength(0);
            for (char c : partial.toCharArray()) {
                outputCodeBlockChar(c);
            }
        }

        if (ch == '\n') {
            // 代码块中的换行
            if (codeBlockLang != null && !codeBlockLang.isEmpty()) {
                // 第一个换行 — 输出代码块头
                output.flushLine();
                output.append("  " + cCodeBorder + "┌" + repeatChar('─', 30)
                        + " " + cCodeLang + codeBlockLang + " " + cCodeBorder + repeatChar('─', 9) + RESET);
                output.flushLine();
                codeBlockLang = null;
                atLineStart = true;
                lineCharCount = 0;
                return;
            }
            if (codeBlockLang != null) {
                // 空语言名 — 输出无语言标签的代码头
                output.flushLine();
                output.append("  " + cCodeBorder + "┌" + repeatChar('─', 40) + RESET);
                output.flushLine();
                codeBlockLang = null;
                atLineStart = true;
                lineCharCount = 0;
                return;
            }
            // 正常代码行换行
            if (lineCharCount == 0) {
                // 空行：仍需输出 │ 保持边框连续
                output.append("  " + cCodeBorder + "│" + RESET);
            }
            output.append(RESET);
            output.flushLine();
            atLineStart = true;
            lineCharCount = 0;
            return;
        }

        // 收集语言名（在第一个换行之前）
        if (codeBlockLang != null) {
            codeBlockLang += ch;
            return;
        }

        outputCodeBlockChar(ch);
    }

    private void outputCodeBlockChar(char ch) {
        if (atLineStart || lineCharCount == 0) {
            output.append("  " + cCodeBorder + "│ " + RESET + cCodeBlock);
            atLineStart = false; // ← 关键修复：设置为 false，后续字符不再加 │
            lineCharCount = 1;
        }
        output.append(String.valueOf(ch));
    }

    private int lineCharCount = 0;

    // ═══════════════════════════════════════════════════════════
    // 换行处理
    // ═══════════════════════════════════════════════════════════

    private void handleNewline() {
        // 所有非代码块的行内状态都在换行时强制关闭
        // 否则 IN_BOLD/IN_ITALIC 等会泄漏到下一行，导致标题等行首检测失败
        switch (state) {
            case IN_HEADER:
            case IN_BLOCKQUOTE:
            case IN_CODE_INLINE:
            case IN_STRIKETHROUGH:
            case IN_BOLD:
            case IN_ITALIC:
                output.append(RESET);
                state = State.NORMAL;
                break;
            case IN_CODE_BLOCK:
                // 代码块不重置 — 由 ``` 关闭标记处理
                break;
            default:
                break;
        }
        output.flushLine();
        atLineStart = true;
        lineCharCount = 0;
    }

    // ═══════════════════════════════════════════════════════════
    // 字符输出
    // ═══════════════════════════════════════════════════════════

    private void emitChar(char ch) {
        if (ch == '\n') {
            handleNewline();
            return;
        }
        if (ch == '\r') {
            return;
        }

        if (atLineStart && state != State.IN_CODE_BLOCK) {
            output.append("  ");
            atLineStart = false;
        }

        output.append(String.valueOf(ch));
        lineCharCount++;
    }

    /** 刷新所有缓冲 — 回合结束、thinking 结束等时机调用 */
    public void flush() {
        if (bufferingTableLine && tableLineBuffer.length() > 0) {
            pendingTableLines.add(tableLineBuffer.toString());
            tableLineBuffer.setLength(0);
            bufferingTableLine = false;
            atLineStart = true;
        }

        if (!pendingTableLines.isEmpty()) {
            flushPendingTable();
        }

        // 刷出未决缓冲
        if (pendingBuf.length() > 0) {
            String text = pendingBuf.toString();
            pendingBuf.setLength(0);
            for (char c : text.toCharArray()) {
                emitChar(c);
            }
        }
        if (codeBlockCloseBuf.length() > 0) {
            String text = codeBlockCloseBuf.toString();
            codeBlockCloseBuf.setLength(0);
            for (char c : text.toCharArray()) {
                emitChar(c);
            }
        }
        // 重置样式
        if (state != State.NORMAL) {
            output.append(RESET);
            state = State.NORMAL;
        }
        output.flushLine();
        lineCharCount = 0;
    }

    // ═══════════════════════════════════════════════════════════
    // 表格渲染
    // ═══════════════════════════════════════════════════════════

    private void flushPendingTable() {
        if (pendingTableLines.isEmpty()) {
            return;
        }

        List<TableRow> rows = parseTableRows();
        pendingTableLines.clear();
        if (rows.isEmpty()) {
            return;
        }

        int columnCount = 0;
        for (TableRow row : rows) {
            columnCount = Math.max(columnCount, row.cells.size());
        }
        if (columnCount == 0) {
            return;
        }

        int[] widths = computeTableWidths(rows, columnCount);
        boolean hasHeader = rows.size() > 1 && !rows.get(0).divider && rows.get(1).divider;

        emitTableBorder('┌', '┬', '┐', widths);
        int startIndex = 0;
        if (hasHeader) {
            emitTableRow(rows.get(0), widths, true);
            emitTableBorder('├', '┼', '┤', widths);
            startIndex = 2;
        }

        boolean firstDataRow = true;
        for (int i = startIndex; i < rows.size(); i++) {
            TableRow row = rows.get(i);
            if (row.divider) {
                continue;
            }

            if (!firstDataRow) {
                emitTableBorder('├', '┼', '┤', widths);
            }
            emitTableRow(row, widths, false);
            firstDataRow = false;
        }

        if (!hasHeader && firstDataRow && !rows.get(0).divider) {
            emitTableRow(rows.get(0), widths, false);
        }

        emitTableBorder('└', '┴', '┘', widths);
        atLineStart = true;
        lineCharCount = 0;
    }

    private List<TableRow> parseTableRows() {
        List<TableRow> rows = new ArrayList<TableRow>();
        for (String rawLine : pendingTableLines) {
            if (rawLine == null) {
                continue;
            }

            String line = rawLine.trim();
            if (line.isEmpty() || line.charAt(0) != '|') {
                continue;
            }

            List<String> cells = splitTableCells(line);
            if (cells.isEmpty()) {
                continue;
            }

            rows.add(new TableRow(cells, isTableDividerRow(cells)));
        }

        return rows;
    }

    private List<String> splitTableCells(String line) {
        String text = line.trim();
        if (text.startsWith("|")) {
            text = text.substring(1);
        }
        if (text.endsWith("|")) {
            text = text.substring(0, text.length() - 1);
        }

        List<String> cells = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) {
                current.append(ch);
                escaped = false;
                continue;
            }

            if (ch == '\\') {
                escaped = true;
                continue;
            }

            if (ch == '|') {
                cells.add(current.toString().trim());
                current.setLength(0);
                continue;
            }

            current.append(ch);
        }

        cells.add(current.toString().trim());
        return cells;
    }

    private boolean isTableDividerRow(List<String> cells) {
        if (cells == null || cells.isEmpty()) {
            return false;
        }

        for (String cell : cells) {
            String text = cell == null ? "" : cell.trim().replace(" ", "");
            if (!text.matches(":?-{3,}:?")) {
                return false;
            }
        }

        return true;
    }

    private int[] computeTableWidths(List<TableRow> rows, int columnCount) {
        int[] widths = new int[columnCount];
        for (int i = 0; i < columnCount; i++) {
            widths[i] = 3;
        }

        for (TableRow row : rows) {
            if (row.divider) {
                continue;
            }

            for (int i = 0; i < columnCount; i++) {
                String cell = i < row.cells.size() ? row.cells.get(i) : "";
                widths[i] = Math.max(widths[i], Math.min(plainDisplayWidth(cell), 48));
            }
        }

        int maxLineWidth = Math.max(24, getRenderWidth() - 2);
        int available = Math.max(columnCount * 3, maxLineWidth - 1 - (columnCount * 3));
        int total = 0;
        for (int width : widths) {
            total += width;
        }

        while (total > available) {
            int widestIndex = 0;
            for (int i = 1; i < widths.length; i++) {
                if (widths[i] > widths[widestIndex]) {
                    widestIndex = i;
                }
            }

            if (widths[widestIndex] <= 3) {
                break;
            }

            widths[widestIndex]--;
            total--;
        }

        return widths;
    }

    private void emitTableBorder(char left, char middle, char right, int[] widths) {
        StringBuilder builder = new StringBuilder();
        builder.append("  ").append(cTableBorder).append(left);
        for (int i = 0; i < widths.length; i++) {
            builder.append(repeatChar('─', widths[i] + 2));
            builder.append(i == widths.length - 1 ? right : middle);
        }
        builder.append(RESET);
        output.append(builder.toString());
        output.flushLine();
    }

    private void emitTableRow(TableRow row, int[] widths, boolean header) {
        StringBuilder builder = new StringBuilder();
        builder.append("  ").append(cTableBorder).append("│").append(RESET);

        for (int i = 0; i < widths.length; i++) {
            String cell = i < row.cells.size() ? row.cells.get(i) : "";
            String plain = plainTableCellText(cell);
            String shownPlain = displayWidth(plain) > widths[i] ? clipToWidth(plain, widths[i]) : plain;
            String styled = shownPlain.equals(plain)
                    ? renderInlineTableCell(cell, header)
                    : stylePlainTableCell(shownPlain, header);

            builder.append(' ')
                    .append(styled)
                    .append(repeatChar(' ', Math.max(0, widths[i] - displayWidth(shownPlain))))
                    .append(' ')
                    .append(cTableBorder).append("│").append(RESET);
        }

        output.append(builder.toString());
        output.flushLine();
    }

    private String stylePlainTableCell(String text, boolean header) {
        if (header) {
            return cTableHeader + text + RESET;
        }

        return text;
    }

    private String renderInlineTableCell(String text, boolean header) {
        String source = text == null ? "" : text.trim().replace("\\|", "|");
        String baseStyle = header ? cTableHeader : "";
        StringBuilder builder = new StringBuilder();
        if (!baseStyle.isEmpty()) {
            builder.append(baseStyle);
        }

        boolean inCode = false;
        boolean inBold = false;
        for (int i = 0; i < source.length(); ) {
            if (!inCode && source.startsWith("**", i)) {
                if (!header) {
                    if (inBold) {
                        builder.append(RESET).append(baseStyle);
                    } else {
                        builder.append(cBold);
                    }
                    inBold = !inBold;
                }
                i += 2;
                continue;
            }

            if (source.charAt(i) == '`') {
                if (inCode) {
                    builder.append(RESET).append(baseStyle);
                    if (inBold && !header) {
                        builder.append(cBold);
                    }
                } else {
                    builder.append(cCodeInline);
                }
                inCode = !inCode;
                i++;
                continue;
            }

            builder.append(source.charAt(i));
            i++;
        }

        if (inCode || inBold || !baseStyle.isEmpty()) {
            builder.append(RESET);
        }

        return builder.toString();
    }

    private String plainTableCellText(String text) {
        String value = text == null ? "" : text.trim().replace("\\|", "|");
        return value
                .replace("**", "")
                .replace("`", "")
                .replace("~~", "");
    }

    private int plainDisplayWidth(String text) {
        return displayWidth(plainTableCellText(text));
    }

    private int getRenderWidth() {
        try {
            return Math.max(40, widthProvider.getWidth());
        } catch (Throwable e) {
            return 80;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════

    private int displayWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            width += charDisplayWidth(codePoint);
            i += Character.charCount(codePoint);
        }
        return width;
    }

    private String clipToWidth(String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }

        if (displayWidth(text) <= maxWidth) {
            return text;
        }

        String ellipsis = "...";
        int targetWidth = Math.max(0, maxWidth - displayWidth(ellipsis));
        StringBuilder builder = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int charWidth = charDisplayWidth(codePoint);
            if (width + charWidth > targetWidth) {
                break;
            }
            builder.appendCodePoint(codePoint);
            width += charWidth;
            i += Character.charCount(codePoint);
        }
        builder.append(ellipsis);
        return builder.toString();
    }

    private int charDisplayWidth(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT
                || block == Character.UnicodeBlock.CJK_RADICALS_SUPPLEMENT
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_A
                || block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_B
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
                || block == Character.UnicodeBlock.BOPOMOFO
                || block == Character.UnicodeBlock.BOPOMOFO_EXTENDED
                || block == Character.UnicodeBlock.ENCLOSED_CJK_LETTERS_AND_MONTHS
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS) {
            return 2;
        }

        if (Character.isSupplementaryCodePoint(codePoint)) {
            return 2;
        }

        if (Character.isISOControl(codePoint)) {
            return 0;
        }

        return 1;
    }

    private static class TableRow {
        private final List<String> cells;
        private final boolean divider;

        private TableRow(List<String> cells, boolean divider) {
            this.cells = cells;
            this.divider = divider;
        }
    }

    private static String repeatChar(char ch, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }
}
