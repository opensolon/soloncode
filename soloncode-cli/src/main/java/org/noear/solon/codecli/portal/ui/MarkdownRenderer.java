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
import java.util.regex.Pattern;

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
    private static final Pattern TABLE_DIVIDER_CELL = Pattern.compile(":?-{3,}:?");
    private static final int TABLE_TAB_WIDTH = 4;

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
    private final StringBuilder lineStartWhitespace = new StringBuilder();
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
        lineStartWhitespace.setLength(0);
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

        if (atLineStart && !pendingTableLines.isEmpty() && !isTableRowPrefixChar(ch)
            && !pendingTableExpectsContinuation()) {
            flushPendingTable();
        }

        if (atLineStart && state == State.NORMAL) {
            if (ch == ' ' || ch == '\t') {
                lineStartWhitespace.append(ch);
                return;
            }

            if (lineStartWhitespace.length() > 0) {
                if (ch == '|') {
                    bufferingTableLine = true;
                    tableLineBuffer.setLength(0);
                    tableLineBuffer.append(lineStartWhitespace);
                    tableLineBuffer.append(ch);
                    lineStartWhitespace.setLength(0);
                    atLineStart = false;
                    return;
                }

                if (pendingTableExpectsContinuation()) {
                    bufferingTableLine = true;
                    tableLineBuffer.setLength(0);
                    tableLineBuffer.append(lineStartWhitespace);
                    tableLineBuffer.append(ch);
                    lineStartWhitespace.setLength(0);
                    atLineStart = false;
                    return;
                }

                String bufferedWhitespace = lineStartWhitespace.toString();
                lineStartWhitespace.setLength(0);
                for (int i = 0; i < bufferedWhitespace.length(); i++) {
                    emitChar(bufferedWhitespace.charAt(i));
                }
            }

            if (pendingTableExpectsContinuation() && ch != '\n' && ch != '\r') {
                bufferingTableLine = true;
                tableLineBuffer.setLength(0);
                tableLineBuffer.append(ch);
                atLineStart = false;
                return;
            }
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
        lineStartWhitespace.setLength(0);
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

        if (lineStartWhitespace.length() > 0) {
            String text = lineStartWhitespace.toString();
            lineStartWhitespace.setLength(0);
            for (int i = 0; i < text.length(); i++) {
                emitChar(text.charAt(i));
            }
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
        List<String> normalizedLines = mergeTableContinuationLines(pendingTableLines);
        for (String rawLine : normalizedLines) {
            if (rawLine == null) {
                continue;
            }

            String line = stripTableLineTrailingBreaks(rawLine);
            if (line.isEmpty()) {
                continue;
            }

            int firstContentIndex = firstNonWhitespaceIndex(line);
            if (firstContentIndex < 0 || line.charAt(firstContentIndex) != '|') {
                continue;
            }

            List<String> cells = splitTableCells(line.substring(firstContentIndex));
            if (cells.isEmpty()) {
                continue;
            }

            rows.add(new TableRow(cells, isTableDividerRow(cells)));
        }

        return rows;
    }

    private List<String> mergeTableContinuationLines(List<String> lines) {
        List<String> merged = new ArrayList<String>();
        for (int i = 0; i < lines.size(); i++) {
            String current = stripTableLineTrailingBreaks(lines.get(i));
            if (current == null || current.isEmpty()) {
                continue;
            }

            while (i + 1 < lines.size()) {
                String next = stripTableLineTrailingBreaks(lines.get(i + 1));
                if (next == null) {
                    i++;
                    continue;
                }

                if (hasUnclosedBacktick(current) || isTableContinuationLine(next)) {
                    current = current + " " + next.trim();
                    i++;
                    continue;
                }
                break;
            }

            merged.add(current);
        }
        return merged;
    }

    private boolean pendingTableExpectsContinuation() {
        if (pendingTableLines.isEmpty()) {
            return false;
        }

        List<String> normalizedLines = mergeTableContinuationLines(pendingTableLines);
        if (normalizedLines.isEmpty()) {
            return false;
        }

        return hasUnclosedBacktick(normalizedLines.get(normalizedLines.size() - 1));
    }

    private boolean hasUnclosedBacktick(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }

        int count = 0;
        boolean escaped = false;
        for (int i = 0; i < line.length(); ) {
            int codePoint = line.codePointAt(i);
            if (escaped) {
                escaped = false;
            } else if (codePoint == '\\') {
                escaped = true;
            } else if (codePoint == '`') {
                count++;
            }
            i += Character.charCount(codePoint);
        }
        return (count % 2) != 0;
    }

    private boolean isTableContinuationLine(String line) {
        if (line == null) {
            return false;
        }

        String trimmed = line.trim();
        return trimmed.matches("^\\|\\s*$");
    }

    private List<String> splitTableCells(String line) {
        String text = stripTableLineTrailingBreaks(line);
        if (text.startsWith("|")) {
            text = text.substring(1);
        }
        if (text.endsWith("|")) {
            text = text.substring(0, text.length() - 1);
        }

        List<String> cells = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        boolean inCodeSpan = false;
        int codeSpanTicks = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (escaped) {
                current.appendCodePoint(codePoint);
                escaped = false;
                i += Character.charCount(codePoint);
                continue;
            }

            if (codePoint == '\\') {
                escaped = true;
                i += Character.charCount(codePoint);
                continue;
            }

            if (codePoint == '`') {
                int tickCount = 0;
                while (i + tickCount < text.length() && text.charAt(i + tickCount) == '`') {
                    tickCount++;
                }
                if (!inCodeSpan) {
                    inCodeSpan = true;
                    codeSpanTicks = tickCount;
                } else if (tickCount == codeSpanTicks) {
                    inCodeSpan = false;
                    codeSpanTicks = 0;
                }
                for (int j = 0; j < tickCount; j++) {
                    current.append('`');
                }
                i += tickCount;
                continue;
            }

            if (codePoint == '|' && !inCodeSpan) {
                cells.add(current.toString());
                current.setLength(0);
                i += Character.charCount(codePoint);
                continue;
            }

            current.appendCodePoint(codePoint);
            i += Character.charCount(codePoint);
        }

        cells.add(current.toString());
        return cells;
    }

    private boolean isTableDividerRow(List<String> cells) {
        if (cells == null || cells.isEmpty()) {
            return false;
        }

        for (String cell : cells) {
            String text = cell == null ? "" : collapseDividerCell(cell);
            if (!TABLE_DIVIDER_CELL.matcher(text).matches()) {
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
        List<List<String>> wrappedCells = new ArrayList<List<String>>(widths.length);
        int rowHeight = 1;
        for (int i = 0; i < widths.length; i++) {
            String cell = i < row.cells.size() ? row.cells.get(i) : "";
            List<String> lines = wrapTableCell(cell, widths[i]);
            wrappedCells.add(lines);
            rowHeight = Math.max(rowHeight, lines.size());
        }

        for (int lineIndex = 0; lineIndex < rowHeight; lineIndex++) {
            StringBuilder builder = new StringBuilder();
            builder.append("  ").append(cTableBorder).append("│").append(RESET);

            for (int columnIndex = 0; columnIndex < widths.length; columnIndex++) {
                List<String> cellLines = wrappedCells.get(columnIndex);
                String plain = lineIndex < cellLines.size() ? cellLines.get(lineIndex) : "";
                String styled = stylePlainTableCell(plain, header);

                builder.append(' ')
                    .append(styled)
                    .append(repeatChar(' ', Math.max(0, widths[columnIndex] - displayWidth(plain))))
                    .append(' ')
                    .append(cTableBorder).append("│").append(RESET);
            }

            output.append(builder.toString());
            output.flushLine();
        }
    }

    private List<String> wrapTableCell(String text, int maxWidth) {
        List<String> lines = new ArrayList<String>();
        String plain = plainTableCellText(text);
        if (plain == null || plain.isEmpty()) {
            lines.add("");
            return lines;
        }

        if (maxWidth <= 0) {
            lines.add(plain);
            return lines;
        }

        StringBuilder current = new StringBuilder();
        int currentWidth = 0;
        for (int i = 0; i < plain.length(); ) {
            int codePoint = plain.codePointAt(i);
            String piece = new String(Character.toChars(codePoint));
            int pieceWidth = displayWidth(piece);

            if (currentWidth > 0 && currentWidth + pieceWidth > maxWidth) {
                lines.add(current.toString());
                current.setLength(0);
                currentWidth = 0;
            }

            if (pieceWidth > maxWidth && currentWidth == 0) {
                lines.add(piece);
                i += Character.charCount(codePoint);
                continue;
            }

            current.append(piece);
            currentWidth += pieceWidth;
            i += Character.charCount(codePoint);
        }

        if (current.length() > 0) {
            lines.add(current.toString());
        }

        if (lines.isEmpty()) {
            lines.add("");
        }

        return lines;
    }

    private String stylePlainTableCell(String text, boolean header) {
        if (header) {
            return cTableHeader + text + RESET;
        }

        return text;
    }

    private String renderInlineTableCell(String text, boolean header) {
        String source = normalizeTableCellSource(text);
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
        String value = normalizeTableCellSource(text);
        return value
                .replace("**", "")
                .replace("`", "")
                .replace("~~", "");
    }

    private String normalizeTableCellSource(String text) {
        if (text == null) {
            return "";
        }

        return expandTableWhitespace(text.replace("\\|", "|"));
    }

    private String expandTableWhitespace(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (codePoint == '\t') {
                int spaces = TABLE_TAB_WIDTH - (width % TABLE_TAB_WIDTH);
                if (spaces <= 0) {
                    spaces = TABLE_TAB_WIDTH;
                }
                builder.append(repeatChar(' ', spaces));
                width += spaces;
                i += Character.charCount(codePoint);
                continue;
            }

            if (shouldEscapeTableCodePoint(codePoint)) {
                String escaped = unicodeEscape(codePoint);
                builder.append(escaped);
                width += escaped.length();
                i += Character.charCount(codePoint);
                continue;
            }

            builder.appendCodePoint(codePoint);
            width += displayWidth(new String(Character.toChars(codePoint)));
            i += Character.charCount(codePoint);
        }

        return builder.toString();
    }

    private boolean shouldEscapeTableCodePoint(int codePoint) {
        if (codePoint == 0x200B || codePoint == 0x200C || codePoint == 0x2060 || codePoint == 0xFEFF) {
            return true;
        }

        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
            || type == Character.COMBINING_SPACING_MARK
            || type == Character.ENCLOSING_MARK;
    }

    private String unicodeEscape(int codePoint) {
        if (codePoint <= 0xFFFF) {
            return String.format("\\u%04X", codePoint);
        }

        return String.format("\\U%08X", codePoint);
    }

    private String collapseDividerCell(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (!Character.isWhitespace(codePoint)) {
                builder.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        return builder.toString();
    }

    private String stripTableLineTrailingBreaks(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        int end = text.length();
        while (end > 0) {
            char ch = text.charAt(end - 1);
            if (ch == '\r' || ch == '\n') {
                end--;
            } else {
                break;
            }
        }

        return text.substring(0, end);
    }

    private int firstNonWhitespaceIndex(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }

        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (!Character.isWhitespace(codePoint)) {
                return i;
            }
            i += Character.charCount(codePoint);
        }

        return -1;
    }

    private boolean isTableRowPrefixChar(char ch) {
        return ch == '|' || ch == ' ' || ch == '\t';
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
        return DisplayWidthUtils.displayWidth(text);
    }

    private String clipToWidth(String text, int maxWidth) {
        return DisplayWidthUtils.clipToWidth(text, maxWidth);
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
