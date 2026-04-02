/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.portal.ui;

import org.jline.terminal.Cursor;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.InfoCmp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 统一屏幕渲染器。
 *
 * <p>
 * 正文区采用真实终端输出，让历史进入终端自己的 scrollback；
 * 底部区采用固定 overlay 重绘。
 * </p>
 */
public class PortalScreenRenderer {
    private final Terminal terminal;
    private final StatusBar statusBar;
    private volatile ReentrantLock terminalLock;
    private volatile Cursor contentCursor = new Cursor(0, 0);
    private volatile int lastFooterLineCount = 0;

    public PortalScreenRenderer(Terminal terminal, StatusBar statusBar) {
        this.terminal = terminal;
        this.statusBar = statusBar;
    }

    public void setTerminalLock(ReentrantLock terminalLock) {
        this.terminalLock = terminalLock;
    }

    public void clearContent() {
        withTerminalLock(new Runnable() {
            @Override
            public void run() {
                StatusBar.RenderSnapshot footerSnapshot = statusBar.snapshot();
                resetScrollRegion();
                terminal.puts(InfoCmp.Capability.clear_screen);
                moveCursor(1, 1);
                terminal.flush();
                contentCursor = new Cursor(0, 0);
                renderFooterInternal(footerSnapshot);
            }
        });
    }

    public void replaceContent(List<String> lines) {
        withTerminalLock(new Runnable() {
            @Override
            public void run() {
                StatusBar.RenderSnapshot footerSnapshot = statusBar.snapshot();
                resetScrollRegion();
                terminal.puts(InfoCmp.Capability.clear_screen);
                moveCursor(1, 1);
                terminal.flush();
                contentCursor = new Cursor(0, 0);
                for (String line : lines) {
                    appendContentLineInternal(line, footerSnapshot);
                }
                renderFooterInternal(footerSnapshot);
            }
        });
    }

    public void appendContentLine(String line) {
        withTerminalLock(new Runnable() {
            @Override
            public void run() {
                StatusBar.RenderSnapshot footerSnapshot = statusBar.snapshot();
                appendContentLineInternal(line, footerSnapshot);
                renderFooterInternal(footerSnapshot);
            }
        });
    }

    public void appendContentLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }

        withTerminalLock(new Runnable() {
            @Override
            public void run() {
                StatusBar.RenderSnapshot footerSnapshot = statusBar.snapshot();
                for (String line : lines) {
                    appendContentLineInternal(line, footerSnapshot);
                }
                renderFooterInternal(footerSnapshot);
            }
        });
    }

    public void renderNow() {
        withTerminalLock(new Runnable() {
            @Override
            public void run() {
                renderFooterInternal(statusBar.snapshot());
            }
        });
    }

    public void showTerminalCursor() {
        withTerminalLock(new Runnable() {
            @Override
            public void run() {
                try {
                    resetScrollRegion();
                    terminal.writer().print("\033[?25h");
                    terminal.flush();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void appendContentLineInternal(String line, StatusBar.RenderSnapshot footerSnapshot) {
        Layout layout = layoutFor(footerSnapshot);
        String safeLine = line == null ? "" : line;
        applyScrollRegion(layout);
        contentCursor = clampContentCursor(contentCursor, layout);
        moveCursor(contentCursor.getY() + 1, contentCursor.getX() + 1);
        terminal.writer().print(safeLine);
        terminal.writer().print("\r\n");
        terminal.flush();
        contentCursor = advanceContentCursor(contentCursor, safeLine, layout);
    }

    private void renderFooterInternal(StatusBar.RenderSnapshot footerSnapshot) {
        Layout layout = layoutFor(footerSnapshot);
        FooterRenderState footerState = footerStateFor(footerSnapshot, layout);
        int currentLineCount = footerState.lines.size();

        try {
            terminal.writer().print("\033[?25l");
            applyScrollRegion(layout);

            for (int row = layout.clearFooterTopRow; row <= layout.terminalHeight; row++) {
                moveCursor(row, 1);
                terminal.writer().print("\033[2K");

                if (row >= layout.footerTopRow) {
                    int footerIndex = row - layout.footerTopRow;
                    AttributedString line = footerIndex < currentLineCount
                            ? footerState.lines.get(footerIndex)
                            : blankLine();
                    terminal.writer().print(line.toAnsi(terminal));
                }
            }

            Cursor footerCursor = footerState.cursor;
            if (footerCursor != null) {
                int absoluteRow = Math.min(layout.terminalHeight, layout.footerTopRow + footerCursor.getY());
                int absoluteCol = Math.max(1, footerCursor.getX() + 1);
                moveCursor(absoluteRow, absoluteCol);
                terminal.writer().print("\033[?25h");
            }
            terminal.flush();
            lastFooterLineCount = currentLineCount;
        } catch (Throwable ignored) {
            try {
                terminal.writer().print("\033[?25h");
                terminal.flush();
            } catch (Throwable e) {
            }
        }
    }

    private Cursor advanceContentCursor(Cursor baseCursor, String line, Layout layout) {
        Cursor safeCursor = clampContentCursor(baseCursor == null ? new Cursor(0, 0) : baseCursor, layout);
        int width = Math.max(1, terminal.getWidth());
        int nextY = safeCursor.getY();

        List<AttributedString> rows = AttributedString.fromAnsi(line == null ? "" : line)
                .columnSplitLength(width);
        int usedRows = rows == null || rows.isEmpty() ? 1 : rows.size();
        nextY = Math.min(layout.contentBottomRow - 1, nextY + Math.max(1, usedRows));

        return new Cursor(0, Math.max(0, nextY));
    }

    private Layout layoutFor(StatusBar.RenderSnapshot footerSnapshot) {
        int terminalHeight = Math.max(2, terminal.getHeight());
        int requestedFooterLineCount = footerSnapshot == null ? 0 : footerSnapshot.getLines().size();
        int maxFooterLineCount = Math.max(0, terminalHeight - 1);
        int visibleFooterLineCount = Math.min(requestedFooterLineCount, maxFooterLineCount);
        int previousVisibleFooterLineCount = Math.min(lastFooterLineCount, maxFooterLineCount);
        int clearFooterLineCount = Math.max(previousVisibleFooterLineCount, visibleFooterLineCount);
        int footerTopRow = terminalHeight - visibleFooterLineCount + 1;
        int clearFooterTopRow = terminalHeight - clearFooterLineCount + 1;
        int contentBottomRow = Math.max(1, footerTopRow - 1);

        return new Layout(terminalHeight, contentBottomRow, footerTopRow, clearFooterTopRow, visibleFooterLineCount);
    }

    private FooterRenderState footerStateFor(StatusBar.RenderSnapshot footerSnapshot, Layout layout) {
        if (footerSnapshot == null || layout.footerLineCount <= 0) {
            return new FooterRenderState(Collections.<AttributedString>emptyList(), null);
        }

        List<AttributedString> allLines = footerSnapshot.getLines();
        if (allLines == null || allLines.isEmpty()) {
            return new FooterRenderState(Collections.<AttributedString>emptyList(), null);
        }

        int droppedTopLines = Math.max(0, allLines.size() - layout.footerLineCount);
        List<AttributedString> visibleLines = new ArrayList<AttributedString>(
                allLines.subList(droppedTopLines, allLines.size()));

        Cursor cursor = adjustFooterCursor(footerSnapshot.getCursor(), droppedTopLines, visibleLines.size());
        return new FooterRenderState(Collections.unmodifiableList(visibleLines), cursor);
    }

    private Cursor adjustFooterCursor(Cursor cursor, int droppedTopLines, int visibleLineCount) {
        if (cursor == null) {
            return null;
        }

        int adjustedY = cursor.getY() - droppedTopLines;
        if (adjustedY < 0 || adjustedY >= visibleLineCount) {
            return null;
        }

        int width = Math.max(1, terminal.getWidth());
        int cursorX = Math.max(0, Math.min(cursor.getX(), width - 1));
        return new Cursor(cursorX, adjustedY);
    }

    private Cursor clampContentCursor(Cursor cursor, Layout layout) {
        if (cursor == null) {
            return new Cursor(0, 0);
        }

        int width = Math.max(1, terminal.getWidth());
        int cursorY = Math.max(0, Math.min(cursor.getY(), layout.contentBottomRow - 1));
        int cursorX = Math.max(0, Math.min(cursor.getX(), width - 1));
        return new Cursor(cursorX, cursorY);
    }

    private void applyScrollRegion(Layout layout) {
        terminal.writer().print("\033[1;" + layout.contentBottomRow + "r");
    }

    private void resetScrollRegion() {
        terminal.writer().print("\033[r");
    }

    private void moveCursor(int row, int col) {
        terminal.writer().print("\033[" + Math.max(1, row) + ";" + Math.max(1, col) + "H");
    }

    private AttributedString blankLine() {
        int width = Math.max(1, terminal.getWidth());
        return AttributedString.fromAnsi(repeat(' ', width));
    }

    private String repeat(char ch, int count) {
        if (count <= 0) {
            return "";
        }

        StringBuilder buf = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            buf.append(ch);
        }
        return buf.toString();
    }

    private static final class Layout {
        private final int terminalHeight;
        private final int contentBottomRow;
        private final int footerTopRow;
        private final int clearFooterTopRow;
        private final int footerLineCount;

        private Layout(int terminalHeight, int contentBottomRow, int footerTopRow,
                       int clearFooterTopRow, int footerLineCount) {
            this.terminalHeight = terminalHeight;
            this.contentBottomRow = contentBottomRow;
            this.footerTopRow = footerTopRow;
            this.clearFooterTopRow = clearFooterTopRow;
            this.footerLineCount = footerLineCount;
        }
    }

    private static final class FooterRenderState {
        private final List<AttributedString> lines;
        private final Cursor cursor;

        private FooterRenderState(List<AttributedString> lines, Cursor cursor) {
            this.lines = lines;
            this.cursor = cursor;
        }
    }

    private void withTerminalLock(Runnable action) {
        ReentrantLock lock = terminalLock;
        if (lock == null) {
            action.run();
            return;
        }

        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }
}
