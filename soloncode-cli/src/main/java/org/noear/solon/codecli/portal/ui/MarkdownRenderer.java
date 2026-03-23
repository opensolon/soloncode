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

    // 颜色
    private static final String C_HEADER = "\033[1;38;2;255;125;144m";
    private static final String C_CODE_INLINE = "\033[38;2;232;194;122m";
    private static final String C_CODE_BLOCK = "\033[38;2;165;214;132m";
    private static final String C_CODE_BORDER = "\033[38;2;60;65;75m";
    private static final String C_CODE_LANG = "\033[2;38;2;114;123;137m";
    private static final String C_BOLD = "\033[1;38;2;243;245;247m";
    private static final String C_LIST_BULLET = "\033[38;2;255;125;144m";
    private static final String C_LIST_NUM = "\033[38;2;130;170;255m";
    private static final String C_BLOCKQUOTE = "\033[38;2;114;123;137m";
    private static final String C_HR = "\033[38;2;60;65;75m";
    private static final String C_STRIKE = "\033[9;38;2;114;123;137m";
    private static final String C_TABLE_BORDER = "\033[38;2;80;90;110m";
    private static final String C_TABLE_HEADER = "\033[1;38;2;200;210;220m";

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
    private boolean inTableRow = false; // 表格行模式
    private boolean isTableDivider = false; // |---| 分隔行
    private boolean isFirstTableRow = true; // 第一行表头
    private final StringBuilder pendingBuf = new StringBuilder();
    private String codeBlockLang = "";

    // ── 输出回调 ──
    private final LineOutput output;

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
        this.output = output;
    }

    /** 重置状态（新的 AI 回复开始时调用） */
    public void reset() {
        state = State.NORMAL;
        atLineStart = true;
        inTableRow = false;
        isTableDivider = false;
        isFirstTableRow = true;
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
                output.append("  " + C_BLOCKQUOTE + "│ " + RESET + C_BLOCKQUOTE);
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
                inTableRow = true;
                isTableDivider = false;
                output.append("  ");
                output.append(C_TABLE_BORDER + "│" + RESET);
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
                output.append("  " + C_CODE_BORDER + "└" + repeatChar('─', 40) + RESET);
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
                output.append(C_CODE_INLINE);
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
                output.append(C_BOLD);
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
                output.append("  " + C_LIST_BULLET + "• " + RESET);
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
                output.append("  " + C_HEADER);
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
                output.append("  " + C_LIST_BULLET + "• " + RESET);
                atLineStart = false;
                return true;
            }
        }
        if (buf.equals("---") || buf.equals("***") || buf.equals("___")) {
            pendingBuf.setLength(0);
            output.append("  " + C_HR + repeatChar('─', 40) + RESET);
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
                output.append(C_STRIKE);
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
                output.append("  " + C_LIST_NUM + num + ". " + RESET);
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
                    output.append("  " + C_LIST_NUM + num + ". " + RESET);
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
                output.append("  " + C_CODE_BORDER + "└" + repeatChar('─', 40) + RESET);
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
                output.append("  " + C_CODE_BORDER + "┌" + repeatChar('─', 30)
                        + " " + C_CODE_LANG + codeBlockLang + " " + C_CODE_BORDER + repeatChar('─', 9) + RESET);
                output.flushLine();
                codeBlockLang = null;
                atLineStart = true;
                lineCharCount = 0;
                return;
            }
            if (codeBlockLang != null) {
                // 空语言名 — 输出无语言标签的代码头
                output.flushLine();
                output.append("  " + C_CODE_BORDER + "┌" + repeatChar('─', 40) + RESET);
                output.flushLine();
                codeBlockLang = null;
                atLineStart = true;
                lineCharCount = 0;
                return;
            }
            // 正常代码行换行
            if (lineCharCount == 0) {
                // 空行：仍需输出 │ 保持边框连续
                output.append("  " + C_CODE_BORDER + "│" + RESET);
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
            output.append("  " + C_CODE_BORDER + "│ " + RESET + C_CODE_BLOCK);
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
        // 表格行结束
        if (inTableRow) {
            inTableRow = false;
            if (isTableDivider) {
                isTableDivider = false;
                isFirstTableRow = false;
            }
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

        // 表格行内的 | 用边框色
        if (inTableRow && ch == '|') {
            output.append(C_TABLE_BORDER + "│" + RESET);
            lineCharCount++;
            return;
        }
        // 表格分隔行检测: |---| 中的 - 和 :
        if (inTableRow && (ch == '-' || ch == ':')) {
            isTableDivider = true;
            output.append(C_TABLE_BORDER + String.valueOf(ch) + RESET);
            lineCharCount++;
            return;
        }

        output.append(String.valueOf(ch));
        lineCharCount++;
    }

    /** 刷新所有缓冲 — 回合结束、thinking 结束等时机调用 */
    public void flush() {
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
    // 工具方法
    // ═══════════════════════════════════════════════════════════

    private static String repeatChar(char ch, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }
}
