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
package org.noear.solon.codecli.portal.ui.bottom;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Attributes;
import org.jline.terminal.Cursor;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.noear.solon.codecli.portal.ui.CommandRegistry;
import org.noear.solon.codecli.portal.ui.bottom.mode.BottomListMode;
import org.noear.solon.codecli.portal.ui.bottom.mode.CommandPaletteMode;
import org.noear.solon.codecli.portal.ui.bottom.mode.FileCompletionMode;
import org.noear.solon.codecli.portal.ui.bottom.mode.PickerMode;
import org.noear.solon.codecli.portal.ui.bottom.panel.BottomListPanel;
import org.noear.solon.codecli.portal.ui.theme.PortalTheme;
import org.noear.solon.codecli.portal.ui.theme.PortalThemes;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 底部输入支持：完全自管输入缓冲、候选面板、输入历史与固定底部渲染。
 */
public class BottomInputController {
    private static final int MAX_COMPLETION_ROWS = 8;
    private final Terminal terminal;
    private final BottomInputCompletionProvider inputCompleter;
    private final Listener listener;
    private final CompletionState completionState = new CompletionState();
    private final PickerMode selectionMode = new PickerMode();
    private final CommandPaletteMode commandCompletionMode;
    private final FileCompletionMode fileCompletionMode;
    private final List<String> commandHistory = new ArrayList<String>();
    private final StringBuilder inputBuffer = new StringBuilder();
    private final Object renderLock = new Object();
    private LineReader printReader;
    private Reader terminalReader;
    private Attributes rawModeAttributes;
    private int cursor = 0;
    private int historyIndex = -1;
    private String historyDraft = "";
    private boolean historyDraftSaved = false;
    private volatile boolean eofReached = false;
    private Object selectionResult;
    private PortalTheme theme = PortalThemes.defaultTheme();
    private SelectionCallbacks<Object> selectionCallbacks;
    private volatile ReentrantLock terminalLock;
    private volatile String footerNotice;
    public BottomInputController(Terminal terminal, CommandRegistry commandRegistry, Path workDir, Listener listener) {
        this.terminal = terminal;
        this.inputCompleter = new BottomInputCompletionProvider(commandRegistry, workDir);
        this.listener = listener;
        this.commandCompletionMode = new CommandPaletteMode(completionState, new java.util.function.Supplier<String>() {
            @Override
            public String get() {
                return inputBuffer.toString();
            }
        });
        this.fileCompletionMode = new FileCompletionMode(completionState, new java.util.function.Supplier<String>() {
            @Override
            public String get() {
                return inputBuffer.toString();
            }
        });
    }

    /**
     * 创建仅用于 printAbove 的 LineReader，不再负责输入逻辑。
     */
    public LineReader createReader() {
        this.printReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .build();
        this.printReader.setVariable(LineReader.BELL_STYLE, "none");
        this.terminalReader = terminal.reader();
        return printReader;
    }

    public void setTheme(PortalTheme theme) {
        this.theme = theme == null ? PortalThemes.defaultTheme() : theme;
        renderNow();
    }

    public void setTerminalLock(ReentrantLock terminalLock) {
        this.terminalLock = terminalLock;
    }

    public String readInput() {
        ensureRawMode();

        while (true) {
            renderNow();

            Key key;
            try {
                key = readKey();
            } catch (IOException e) {
                throw new RuntimeException("Read bottom input failure", e);
            }

            if (eofReached || key == null) {
                return null;
            }

            String submitted = handleInput(key);
            if (submitted != null) {
                renderNow();
                return submitted;
            }

            renderNow();
        }
    }

    public void renderNow() {
        synchronized (renderLock) {
            List<AttributedString> popupLines = buildPopupLines();
            AttributedString inputLine = buildInputLine();
            int popupCount = popupLines.size();
            listener.updateFooter(popupLines, inputLine, buildFooterCursor(popupCount));
        }
    }

    public void close() {
        withTerminalLock(() -> {
            synchronized (renderLock) {
                if (rawModeAttributes != null) {
                    try {
                        terminal.setAttributes(rawModeAttributes);
                    } catch (Throwable ignored) {
                    }
                    rawModeAttributes = null;
                }
                listener.showTerminalCursor();
            }
        });
    }

    public <T> T selectFromList(String prompt, List<BottomListPanel.Item<T>> items) {
        return selectFromList(prompt, items, 0, null);
    }

    public <T> T selectFromList(String prompt,
                                List<BottomListPanel.Item<T>> items,
                                int selectedIndex,
                                SelectionCallbacks<T> callbacks) {
        ensureRawMode();
        openSelectionPanel(prompt, items, selectedIndex, callbacks);

        while (selectionMode.isActive()) {
            renderNow();

            Key key;
            try {
                key = readKey();
            } catch (IOException e) {
                fireSelectionCancel();
                closeSelectionPanel();
                throw new RuntimeException("Read bottom selection failure", e);
            }

            if (eofReached || key == null) {
                fireSelectionCancel();
                closeSelectionPanel();
                return null;
            }

            handleSelectionKey(key);
        }

        renderNow();
        return consumeSelectionResult();
    }

    private void ensureRawMode() {
        if (rawModeAttributes != null) {
            return;
        }
        rawModeAttributes = terminal.enterRawMode();
        Attributes attrs = terminal.getAttributes();
        attrs.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        terminal.setAttributes(attrs);
        terminal.echo(false);
        this.terminalReader = terminal.reader();
    }

    @SuppressWarnings("unchecked")
    private <T> void openSelectionPanel(String prompt,
                                        List<BottomListPanel.Item<T>> items,
                                        int selectedIndex,
                                        SelectionCallbacks<T> callbacks) {
        synchronized (renderLock) {
            selectionResult = null;
            selectionCallbacks = (SelectionCallbacks<Object>) callbacks;
            selectionMode.open(prompt, items, selectedIndex);
            fireSelectionFocus();
        }
    }

    private void closeSelectionPanel() {
        synchronized (renderLock) {
            selectionMode.cancel();
            selectionResult = null;
            selectionCallbacks = null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T consumeSelectionResult() {
        T result = (T) selectionResult;
        selectionResult = null;
        return result;
    }

    private void handleSelectionKey(Key key) {
        synchronized (renderLock) {
            BottomListMode activeMode = getActivePanelMode();
            if (activeMode == null) {
                return;
            }

            if (key.ctrl && key.code == 'c') {
                activeMode.cancel();
                selectionResult = null;
                fireSelectionCancel();
                return;
            }

            if (key.type == KeyType.ESC) {
                activeMode.cancel();
                selectionResult = null;
                fireSelectionCancel();
                return;
            }

            if (key.type == KeyType.UP) {
                activeMode.moveUp();
                fireSelectionFocus();
                return;
            }

            if (key.type == KeyType.DOWN) {
                activeMode.moveDown();
                fireSelectionFocus();
                return;
            }

            if (key.type == KeyType.TAB || key.type == KeyType.ENTER) {
                selectionResult = activeMode.confirm();
                activeMode.cancel();
                fireSelectionConfirm(selectionResult);
            }
        }
    }

    private String handleInput(Key key) {
        if (key.ctrl && key.code == 'c') {
            clearFooterNotice();
            clearCurrentInput();
            return null;
        }

        if (key.ctrl && key.code == 'l') {
            clearFooterNotice();
            listener.clearScreen();
            return null;
        }

        if (key.type == KeyType.ESC) {
            clearFooterNotice();
            if (completionState.getMode() != CompletionState.Mode.NONE) {
                completionState.reset();
                return null;
            }

            if (listener.isTaskRunning()) {
                listener.cancelRunningTask();
                listener.clearPendingInputs();
            }
            return null;
        }

        if (key.type == KeyType.UP) {
            if (completionState.getMode() != CompletionState.Mode.NONE && completionState.hasCandidates()) {
                completionState.moveUp();
                return null;
            }
            historyUp();
            return null;
        }

        if (key.type == KeyType.DOWN) {
            if (completionState.getMode() != CompletionState.Mode.NONE && completionState.hasCandidates()) {
                completionState.moveDown();
                return null;
            }
            historyDown();
            return null;
        }

        if (key.type == KeyType.LEFT) {
            clearFooterNotice();
            if (cursor > 0) {
                cursor--;
            }
            return null;
        }

        if (key.type == KeyType.RIGHT) {
            clearFooterNotice();
            if (cursor < inputBuffer.length()) {
                cursor++;
            }
            return null;
        }

        if (key.type == KeyType.HOME) {
            clearFooterNotice();
            cursor = 0;
            return null;
        }

        if (key.type == KeyType.END) {
            clearFooterNotice();
            cursor = inputBuffer.length();
            return null;
        }

        if (key.type == KeyType.BACKSPACE) {
            clearFooterNotice();
            if (cursor > 0 && inputBuffer.length() > 0) {
                inputBuffer.deleteCharAt(cursor - 1);
                cursor--;
                checkAndUpdateCompletion();
            }
            return null;
        }

        if (key.type == KeyType.DELETE) {
            clearFooterNotice();
            if (cursor < inputBuffer.length()) {
                inputBuffer.deleteCharAt(cursor);
                checkAndUpdateCompletion();
            }
            return null;
        }

        if (key.type == KeyType.TAB) {
            clearFooterNotice();
            applySelectedCompletion();
            return null;
        }

        if (key.type == KeyType.ENTER) {
            if (completionState.getMode() != CompletionState.Mode.NONE && completionState.hasCandidates()) {
                applySelectedCompletion();
                return null;
            }

            if (endsWithBackslash()) {
                inputBuffer.deleteCharAt(inputBuffer.length() - 1);
                inputBuffer.insert(Math.max(0, cursor - 1), '\n');
                checkAndUpdateCompletion();
                return null;
            }

            String text = inputBuffer.toString().trim();
            if (text.isEmpty()) {
                return null;
            }

            if (listener.isTaskRunning()) {
                int queued = listener.enqueuePendingInput(text);
                if (queued >= 0) {
                    clearFooterNotice();
                    clearCurrentInput();
                } else {
                    setFooterNotice("队列已满（" + listener.getPendingInputLimit()
                            + "/" + listener.getPendingInputLimit() + "），等待当前任务完成或按 Esc 中断");
                }
                return null;
            }

            if (listener.isHitlActive()) {
                clearFooterNotice();
                clearCurrentInput();
                listener.handleHitlInput(text);
                return null;
            }

            String submitted = inputBuffer.toString();
            clearFooterNotice();
            addToHistory(submitted);
            clearCurrentInput();
            return submitted;
        }

        if (key.type == KeyType.CHAR) {
            insertChar((char) key.code);
        }

        return null;
    }

    private void clearCurrentInput() {
        inputBuffer.setLength(0);
        cursor = 0;
        completionState.reset();
        resetHistoryIndex();
    }

    private boolean endsWithBackslash() {
        return inputBuffer.length() > 0 && inputBuffer.charAt(inputBuffer.length() - 1) == '\\';
    }

    private void insertChar(char ch) {
        clearFooterNotice();
        inputBuffer.insert(cursor, ch);
        cursor++;
        checkAndUpdateCompletion();
    }

    private void setBuffer(String text) {
        clearFooterNotice();
        inputBuffer.setLength(0);
        inputBuffer.append(text == null ? "" : text);
        cursor = inputBuffer.length();
        checkAndUpdateCompletion();
    }

    private void historyUp() {
        if (commandHistory.isEmpty()) {
            return;
        }

        if (historyIndex < commandHistory.size() - 1) {
            if (historyIndex < 0) {
                rememberHistoryDraft(inputBuffer.toString());
            }
            historyIndex++;
            setBuffer(commandHistory.get(commandHistory.size() - 1 - historyIndex));
        }
    }

    private void historyDown() {
        if (historyIndex > 0) {
            historyIndex--;
            setBuffer(commandHistory.get(commandHistory.size() - 1 - historyIndex));
        } else if (historyIndex == 0) {
            setBuffer(restoreHistoryDraft());
        }
    }

    private void addToHistory(String text) {
        if (text != null && !text.isEmpty()) {
            if (commandHistory.isEmpty() || !commandHistory.get(commandHistory.size() - 1).equals(text)) {
                commandHistory.add(text);
            }
        }
        resetHistoryIndex();
    }

    private void resetHistoryIndex() {
        historyIndex = -1;
        historyDraft = "";
        historyDraftSaved = false;
    }

    private void rememberHistoryDraft(String draft) {
        if (!historyDraftSaved) {
            historyDraft = draft == null ? "" : draft;
            historyDraftSaved = true;
        }
    }

    private String restoreHistoryDraft() {
        String draft = historyDraftSaved ? historyDraft : "";
        historyDraft = "";
        historyDraftSaved = false;
        historyIndex = -1;
        return draft;
    }

    private void checkAndUpdateCompletion() {
        String text = inputBuffer.toString();
        int cursorPos = cursor;

        if (text.startsWith("/")) {
            int slashEnd = findCommandEnd(text);
            if (cursorPos <= slashEnd) {
                String filter = text.substring(0, slashEnd);
                completionState.setMode(CompletionState.Mode.COMMAND);
                completionState.setCommandStart(0);
                completionState.setFilter(filter);
                completionState.setCandidates(inputCompleter.findCommandCandidates(filter));
                completionState.setUserInputPath("");
                return;
            }
        }

        int atIndex = text.lastIndexOf('@', Math.max(0, cursorPos - 1));
        if (atIndex >= 0 && isFileTokenActive(text, atIndex, cursorPos)) {
            String userPath = text.substring(atIndex + 1, cursorPos);
            completionState.setMode(CompletionState.Mode.FILE);
            completionState.setUserInputPath(userPath);
            completionState.setCurrentPath("");
            completionState.setCandidates(inputCompleter.findFileCandidates("@" + userPath));
            return;
        }

        completionState.reset();
    }

    private int findCommandEnd(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return text.length();
    }

    private boolean isFileTokenActive(String text, int atIndex, int cursorPos) {
        for (int i = atIndex + 1; i < cursorPos; i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private void applySelectedCompletion() {
        if (completionState.getMode() == CompletionState.Mode.NONE || !completionState.hasCandidates()) {
            return;
        }

        CompletionCandidate candidate = completionState.getSelectedCandidate();
        if (candidate == null) {
            return;
        }

        if (completionState.getMode() == CompletionState.Mode.COMMAND) {
            int commandEnd = findCommandEnd(inputBuffer.toString());
            String tail = inputBuffer.substring(commandEnd);
            inputBuffer.setLength(0);
            inputBuffer.append(candidate.getValue());
            inputBuffer.append(tail);
            cursor = candidate.getValue().length();
            completionState.reset();
            return;
        }

        if (completionState.getMode() != CompletionState.Mode.FILE) {
            return;
        }

        String currentText = inputBuffer.toString();
        int atIndex = currentText.lastIndexOf('@', Math.max(0, cursor - 1));
        if (atIndex < 0) {
            completionState.reset();
            return;
        }

        String before = currentText.substring(0, atIndex + 1);
        String after = currentText.substring(cursor);
        String selectedPath = candidate.getValue();

        if (candidate.isDirectory()) {
            String dirValue = selectedPath.endsWith("/") ? selectedPath : selectedPath + "/";
            inputBuffer.setLength(0);
            inputBuffer.append(before).append(dirValue).append(after);
            cursor = before.length() + dirValue.length();
            completionState.setMode(CompletionState.Mode.FILE);
            completionState.setUserInputPath(dirValue);
            completionState.setCurrentPath("");
            completionState.setCandidates(inputCompleter.findFileCandidates("@" + dirValue));
            return;
        }

        inputBuffer.setLength(0);
        inputBuffer.append(before).append(selectedPath).append(after);
        cursor = before.length() + selectedPath.length();
        completionState.reset();
    }

    private List<AttributedString> buildPopupLines() {
        List<AttributedString> lines = new ArrayList<AttributedString>();
        List<String> pendingInputs = listener.getPendingInputs();
        if (pendingInputs != null && !pendingInputs.isEmpty()) {
            appendPendingInputLines(lines, pendingInputs);
            lines.add(padLine(new AttributedString("")));
        }

        BottomListMode activeMode = getActivePanelMode();
        if (activeMode != null && activeMode.hasItems()) {
            appendSelectionPanelLines(lines, activeMode);
        }
        return lines;
    }

    private AttributedString buildInputLine() {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(styleAccentBold());
        sb.append("› ");
        sb.style(styleText());
        sb.append(getDisplayedInputText());
        if (footerNotice != null && !footerNotice.isEmpty()) {
            sb.style(styleWarn());
            sb.append("  ");
            sb.append(footerNotice);
        }
        return padLine(sb.toAttributedString());
    }

    private void appendPendingInputLines(List<AttributedString> lines, List<String> pendingInputs) {
        for (String pending : pendingInputs) {
            AttributedStringBuilder sb = new AttributedStringBuilder();
            sb.style(stylePending());
            sb.append("  ↳ ");
            sb.append(normalizePendingPreview(pending));
            lines.add(padLine(sb.toAttributedString()));
        }
    }

    private String normalizePendingPreview(String pending) {
        if (pending == null || pending.isEmpty()) {
            return "";
        }

        String preview = pending.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\n', ' ')
                .trim();

        if (preview.isEmpty()) {
            return "(empty)";
        }

        return preview;
    }

    private void appendSelectionPanelLines(List<AttributedString> lines, BottomListMode panelMode) {
        List<BottomListPanel.Item<Object>> items = panelMode.getItems();
        int totalRows = Math.min(MAX_COMPLETION_ROWS, items.size());
        int start = 0;
        if (panelMode.getSelectedIndex() >= totalRows) {
            start = panelMode.getSelectedIndex() - totalRows + 1;
        }

        for (int i = 0; i < totalRows; i++) {
            int index = start + i;
            if (index >= items.size()) {
                break;
            }
            lines.add(buildSelectionLine(items.get(index), index == panelMode.getSelectedIndex(), commandColumnWidth(items)));
        }
    }

    private AttributedString buildSelectionLine(BottomListPanel.Item<?> item, boolean selected, int primaryColumnWidth) {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.append("  ");

        String display = item.getPrimary();
        AttributedStyle valueStyle = resolvePrimaryStyle(item.getTone(), selected);
        sb.style(valueStyle);
        sb.append(display);

        if (item.getSecondary() != null && !item.getSecondary().isEmpty()) {
            int displayWidth = visibleWidth(display);
            int spaces = Math.max(2, primaryColumnWidth - displayWidth);
            sb.append(repeat(' ', spaces));
            sb.style(styleMuted());
            sb.append(item.getSecondary());
        }
        return padLine(sb.toAttributedString());
    }

    private int totalFooterLines(int popupCount) {
        return popupCount + 5;
    }

    private int inputLineIndex(int popupCount) {
        return popupCount + 2;
    }

    private Cursor buildFooterCursor(int popupCount) {
        int cursorColumn = 3 + visibleWidth(getDisplayedInputCursorText());
        return new Cursor(Math.max(0, cursorColumn - 1), Math.max(0, inputLineIndex(popupCount)));
    }

    private AttributedString padLine(AttributedString line) {
        int width = Math.max(1, terminal.getWidth());
        int visible = line.columnLength();
        if (visible >= width) {
            return line.columnSubSequence(0, width);
        }

        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.append(line);
        sb.style(AttributedStyle.DEFAULT);
        sb.append(repeat(' ', width - visible));
        return sb.toAttributedString();
    }

    private int commandColumnWidth(List<? extends BottomListPanel.Item<?>> items) {
        int max = 0;
        for (BottomListPanel.Item<?> item : items) {
            max = Math.max(max, visibleWidth(item.getPrimary()));
        }
        return Math.min(Math.max(max + 2, 14), 28);
    }

    private AttributedStyle resolvePrimaryStyle(BottomListPanel.Tone tone, boolean selected) {
        if (selected) {
            return styleAccentBold();
        }

        if (tone == BottomListPanel.Tone.SOFT) {
            return styleSoft();
        }
        if (tone == BottomListPanel.Tone.ACCENT) {
            return styleAccent();
        }
        return styleText();
    }

    private String getDisplayedInputText() {
        BottomListMode activeMode = getActivePanelMode();
        if (activeMode != null) {
            return activeMode.getPrompt();
        }
        return inputBuffer.toString();
    }

    private String getDisplayedInputCursorText() {
        BottomListMode activeMode = getActivePanelMode();
        if (activeMode != null) {
            return activeMode.getPrompt();
        }
        return inputBuffer.substring(0, Math.min(cursor, inputBuffer.length()));
    }

    private BottomListMode getActivePanelMode() {
        if (selectionMode.isActive()) {
            return selectionMode;
        }
        if (commandCompletionMode.isActive()) {
            return commandCompletionMode;
        }
        if (fileCompletionMode.isActive()) {
            return fileCompletionMode;
        }
        return null;
    }

    private int visibleWidth(String text) {
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

    private String repeat(char ch, int count) {
        if (count <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(ch);
        }
        return sb.toString();
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

        // Java 8 does not expose CJK extension E/F blocks, but they are supplementary code points.
        if (Character.isSupplementaryCodePoint(codePoint)) {
            return 2;
        }

        if (Character.isISOControl(codePoint)) {
            return 0;
        }

        return 1;
    }

    private void fireSelectionFocus() {
        if (selectionCallbacks != null) {
            selectionCallbacks.onFocus(selectionMode.getSelectedValue());
        }
    }

    private void fireSelectionConfirm(Object value) {
        if (selectionCallbacks != null) {
            selectionCallbacks.onConfirm(value);
            selectionCallbacks = null;
        }
    }

    private void fireSelectionCancel() {
        if (selectionCallbacks != null) {
            selectionCallbacks.onCancel();
            selectionCallbacks = null;
        }
    }

    private AttributedStyle styleAccent() {
        return theme.accent().style();
    }

    private AttributedStyle styleAccentBold() {
        return theme.accentStrong().boldStyle();
    }

    private AttributedStyle styleText() {
        return theme.textPrimary().style();
    }

    private AttributedStyle styleMuted() {
        return theme.textMuted().style();
    }

    private AttributedStyle styleSoft() {
        return theme.textSoft().style();
    }

    private AttributedStyle stylePending() {
        return theme.textMuted().style().faint();
    }

    private AttributedStyle styleWarn() {
        return theme.warning().style();
    }

    private void setFooterNotice(String footerNotice) {
        this.footerNotice = footerNotice == null ? null : footerNotice.trim();
    }

    private void clearFooterNotice() {
        this.footerNotice = null;
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

    private Key readKey() throws IOException {
        int first = terminalReader.read();
        if (first < 0) {
            eofReached = true;
            return null;
        }

        if (first == 4) {
            eofReached = true;
            return null;
        }

        if (first == 3) {
            return new Key(KeyType.CHAR, 'c', true);
        }
        if (first == 12) {
            return new Key(KeyType.CHAR, 'l', true);
        }
        if (first == 9) {
            return new Key(KeyType.TAB, 9, false);
        }
        if (first == 13 || first == 10) {
            return new Key(KeyType.ENTER, first, false);
        }
        if (first == 127 || first == 8) {
            return new Key(KeyType.BACKSPACE, first, false);
        }
        if (first == 27) {
            if (!terminalReader.ready()) {
                return new Key(KeyType.ESC, 27, false);
            }

            int second = terminalReader.read();
            if (second == '[' || second == 'O') {
                int third = terminalReader.read();
                if (third == 'A') {
                    return new Key(KeyType.UP, third, false);
                }
                if (third == 'B') {
                    return new Key(KeyType.DOWN, third, false);
                }
                if (third == 'C') {
                    return new Key(KeyType.RIGHT, third, false);
                }
                if (third == 'D') {
                    return new Key(KeyType.LEFT, third, false);
                }
                if (third == 'H') {
                    return new Key(KeyType.HOME, third, false);
                }
                if (third == 'F') {
                    return new Key(KeyType.END, third, false);
                }
                if (third >= '0' && third <= '9') {
                    StringBuilder digits = new StringBuilder();
                    digits.append((char) third);
                    int ch;
                    while ((ch = terminalReader.read()) > 0 && ch != '~') {
                        digits.append((char) ch);
                    }
                    String code = digits.toString();
                    if ("3".equals(code)) {
                        return new Key(KeyType.DELETE, 0, false);
                    }
                    if ("1".equals(code) || "7".equals(code)) {
                        return new Key(KeyType.HOME, 0, false);
                    }
                    if ("4".equals(code) || "8".equals(code)) {
                        return new Key(KeyType.END, 0, false);
                    }
                }
            }
            return new Key(KeyType.ESC, 27, false);
        }

        if (first >= 32) {
            return new Key(KeyType.CHAR, first, false);
        }

        return null;
    }

    private enum KeyType {
        CHAR,
        ENTER,
        TAB,
        ESC,
        BACKSPACE,
        DELETE,
        UP,
        DOWN,
        LEFT,
        RIGHT,
        HOME,
        END
    }

    public interface Listener {
        boolean isTaskRunning();

        void cancelRunningTask();

        int clearPendingInputs();

        List<String> getPendingInputs();

        int enqueuePendingInput(String text);

        int getPendingInputLimit();

        boolean isHitlActive();

        void handleHitlInput(String text);

        void clearScreen();

        void showTerminalCursor();

        void updateFooter(List<AttributedString> popupLines, AttributedString inputLine, Cursor cursor);
    }

    public interface SelectionCallbacks<T> {
        void onFocus(T value);

        void onConfirm(T value);

        void onCancel();
    }

    private static final class Key {
        private final KeyType type;
        private final int code;
        private final boolean ctrl;

        private Key(KeyType type, int code, boolean ctrl) {
            this.type = type;
            this.code = code;
            this.ctrl = ctrl;
        }
    }
}
