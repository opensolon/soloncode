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

import org.jline.terminal.Attributes;
import org.jline.terminal.Cursor;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.noear.solon.codecli.portal.ui.theme.PortalTheme;
import org.noear.solon.codecli.portal.ui.theme.PortalThemes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 基于 JLine Status 的底部栏。
 * 布局顺序：
 * 空行 -> 运行态 -> 空行 -> 列表 -> 输入框 -> 空行 -> 状态栏
 */
public class StatusBar {
    static final String[] ALL_FIELDS = {
            "model", "time", "tokens", "dir", "version", "session", "turns", "mode"
    };
    static final String[] FIELD_DESCRIPTIONS = {
            "当前模型名称", "最近一次任务时长", "Token 用量", "工作目录",
            "CLI 版本号", "会话 ID", "对话轮次", "简约/详细模式"
    };

    private static final String SEP = " | ";
    private static final String GAP = " ";
    private static final String[] SPINNER_FRAMES = {
            "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"
    };
    private static final AttributedStyle STYLE_BG = AttributedStyle.DEFAULT;
    private static final String RESET = "\033[0m";

    private final Terminal terminal;
    private PortalTheme theme = PortalThemes.defaultTheme();

    private final Set<String> enabledFields = new LinkedHashSet<String>(
            Arrays.asList("model", "time", "dir"));

    private volatile String currentStatus = "idle";
    private volatile long taskStartTime = 0;
    private volatile long stateStartTime = 0;
    private volatile long lastTaskDuration = 0;
    private volatile long lastTokens = 0;

    private String modelName = "unknown";
    private String workDir = "";
    private String version = "";
    private String sessionId = "";
    private int turns = 0;
    private boolean compactMode = false;

    private volatile List<AttributedString> popupLines = Collections.emptyList();
    private volatile AttributedString inputLine = new AttributedString("");

    private volatile int animationTick = 0;
    private final ScheduledExecutorService animExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "statusbar-anim");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> animTask;
    private volatile Cursor restoreCursor;
    private volatile Runnable renderRequester;
    private volatile boolean configActive = false;
    private volatile Set<String> configEnabledFields = Collections.emptySet();
    private volatile int configCursor = 0;

    public StatusBar(Terminal terminal) {
        this.terminal = terminal;
        this.renderRequester = new Runnable() {
            @Override
            public void run() {
            }
        };
    }

    public void setTheme(PortalTheme theme) {
        this.theme = theme == null ? PortalThemes.defaultTheme() : theme;
        requestRender();
    }

    public void setModelName(String name) {
        this.modelName = name;
    }

    public void setWorkDir(String dir) {
        this.workDir = dir;
    }

    public void setVersion(String ver) {
        this.version = ver;
    }

    public void setSessionId(String id) {
        this.sessionId = id;
    }

    public void setCompactMode(boolean compact) {
        this.compactMode = compact;
        requestRender();
    }

    public void incrementTurns() {
        this.turns++;
    }

    public void setup() {
    }

    public void setJLineLock(java.util.concurrent.locks.ReentrantLock lock) {
        // 兼容旧调用点。渲染已统一收口到 PortalScreenRenderer。
    }

    public void setRestoreCursor(Cursor cursor) {
        if (cursor == null) {
            this.restoreCursor = null;
        } else {
            this.restoreCursor = new Cursor(cursor.getX(), cursor.getY());
        }
    }

    public void setRenderRequester(Runnable renderRequester) {
        if (renderRequester == null) {
            this.renderRequester = new Runnable() {
                @Override
                public void run() {
                }
            };
        } else {
            this.renderRequester = renderRequester;
        }
    }

    public void draw() {
        requestRender();
    }

    public void suspend() {
        // 统一渲染口径下，正文区与底部区都由 PortalScreenRenderer 负责落屏。
        // 这里不再直接触碰终端。
    }

    public void restore() {
        requestRender();
    }

    public void updateFooter(List<AttributedString> popupLines, AttributedString inputLine, Cursor cursor) {
        if (popupLines == null || popupLines.isEmpty()) {
            this.popupLines = Collections.emptyList();
        } else {
            this.popupLines = new ArrayList<AttributedString>(popupLines);
        }
        this.inputLine = inputLine == null ? new AttributedString("") : inputLine;
        setRestoreCursor(cursor);
        requestRender();
    }

    public void updateStatus(String status) {
        String normalized = normalizeStatus(status);
        if (!normalized.equals(this.currentStatus)) {
            this.stateStartTime = System.currentTimeMillis();
        }
        this.currentStatus = normalized;
        requestRender();
    }

    public String getStatusText() {
        return currentStatus;
    }

    public String getTaskTimeText() {
        long duration = taskStartTime > 0
                ? Math.max(0, System.currentTimeMillis() - taskStartTime)
                : Math.max(0, lastTaskDuration);
        return formatDuration(duration);
    }

    public void taskStart() {
        this.taskStartTime = System.currentTimeMillis();
        this.stateStartTime = this.taskStartTime;
        this.lastTaskDuration = 0;
        this.lastTokens = 0;
        this.currentStatus = "thinking";
        startAnimation();
        requestRender();
    }

    public void taskEnd(long tokens) {
        this.lastTokens = tokens;
        if (taskStartTime > 0) {
            this.lastTaskDuration = Math.max(0, System.currentTimeMillis() - taskStartTime);
        }
        this.taskStartTime = 0;
        this.currentStatus = "idle";
        this.stateStartTime = System.currentTimeMillis();
        stopAnimation();
        requestRender();
    }

    public void updateTokens(long tokens) {
        this.lastTokens = tokens;
        requestRender();
    }

    public boolean isIdle() {
        return "idle".equals(currentStatus);
    }

    private AttributedString buildRuntimeLine() {
        if (isIdle() || taskStartTime <= 0) {
            return blankLine();
        }

        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(STYLE_BG);
        sb.append(" ");

        RuntimeStatusInfo info = RuntimeStatusInfo.from(currentStatus, this);
        sb.style(info.spinnerStyle);
        sb.append(SPINNER_FRAMES[animationTick % SPINNER_FRAMES.length]);
        sb.style(styleMuted());
        sb.append(GAP);
        appendAnimatedText(sb, info.text, info.textStyle, info.peakStyle, info.animated);
        sb.style(styleMuted());
        sb.append(buildRuntimeHint());
        return padLine(sb.toAttributedString());
    }

    private void appendAnimatedText(AttributedStringBuilder sb, String text, AttributedStyle baseStyle,
                                    AttributedStyle peakStyle, boolean animated) {
        if (text == null || text.isEmpty()) {
            return;
        }

        if (!animated) {
            sb.style(baseStyle);
            sb.append(text);
            return;
        }

        int waveCenter = animationTick % (text.length() + 4);
        for (int i = 0; i < text.length(); i++) {
            int distance = Math.abs(i - waveCenter);
            AttributedStyle style = baseStyle;
            if (distance == 0) {
                style = peakStyle;
            } else if (distance >= 3) {
                style = styleSoft();
            }
            sb.style(style);
            sb.append(String.valueOf(text.charAt(i)));
        }
    }

    private String buildRuntimeHint() {
        long now = System.currentTimeMillis();
        long phaseDuration = stateStartTime > 0 ? Math.max(0, now - stateStartTime) : 0;
        long totalDuration = taskStartTime > 0 ? Math.max(0, now - taskStartTime) : Math.max(0, lastTaskDuration);
        return " (" + formatDuration(phaseDuration)
                + " / " + formatDuration(totalDuration)
                + " / Esc 停止会话)";
    }

    private AttributedString buildStatusLine() {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(STYLE_BG);
        sb.append(" ");

        boolean first = true;
        for (String field : enabledFields) {
            if (!first) {
                sb.style(styleSeparator());
                sb.append(SEP);
            }

            int before = sb.columnLength();
            appendField(sb, field);
            if (sb.columnLength() == before) {
                if (!first) {
                    trimRight(sb, SEP.length());
                }
                continue;
            }
            first = false;
        }

        return padLine(sb.toAttributedString());
    }

    private void appendField(AttributedStringBuilder sb, String field) {
        if ("model".equals(field)) {
            if (isBlank(modelName)) {
                return;
            }
            sb.style(styleMuted());
            sb.append(modelName);
            return;
        }

        if ("time".equals(field)) {
            sb.style(styleMuted());
            if (taskStartTime > 0) {
                sb.append("--");
            } else if (lastTaskDuration > 0) {
                sb.append(formatDuration(lastTaskDuration));
            } else {
                sb.append("--");
            }
            return;
        }

        if ("tokens".equals(field)) {
            if (lastTokens <= 0) {
                return;
            }
            sb.style(styleMuted());
            sb.append(lastTokens + " tok");
            return;
        }

        if ("dir".equals(field)) {
            if (isBlank(workDir)) {
                return;
            }
            int usedWidth = sb.columnLength();
            int remaining = Math.max(0, terminal.getWidth() - usedWidth - 2);
            if (remaining <= 8) {
                return;
            }
            sb.style(styleMuted());
            sb.append(shortenPath(workDir, remaining));
            return;
        }

        if ("version".equals(field)) {
            if (isBlank(version)) {
                return;
            }
            sb.style(styleMuted());
            sb.append(version);
            return;
        }

        if ("session".equals(field)) {
            if (isBlank(sessionId)) {
                return;
            }
            sb.style(styleMuted());
            sb.append(sessionId);
            return;
        }

        if ("turns".equals(field)) {
            sb.style(styleMuted());
            sb.append("#" + turns);
            return;
        }

        if ("mode".equals(field)) {
            sb.style(styleMuted());
            sb.append(compactMode ? "simplified" : "detailed");
        }
    }

    private void trimRight(AttributedStringBuilder sb, int count) {
        String text = sb.toAnsi(terminal);
        if (count <= 0 || text.length() < count) {
            return;
        }
        sb.setLength(Math.max(0, sb.length() - count));
    }

    public void showConfigUI() {
        Attributes savedAttrs = terminal.getAttributes();
        try {
            terminal.enterRawMode();
            configEnabledFields = new LinkedHashSet<String>(enabledFields);
            configCursor = 0;
            configActive = true;
            requestRender();

            while (true) {
                int key = readKey();

                if (key == -1) {
                    break;
                } else if (key == 27) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                    }
                    if (isReaderReady()) {
                        int next = readKey();
                        if (next == '[' || next == 'O') {
                            if (isReaderReady()) {
                                int arrow = readKey();
                                if (arrow == 'A') {
                                    configCursor = Math.max(0, configCursor - 1);
                                    requestRender();
                                    continue;
                                } else if (arrow == 'B') {
                                    configCursor = Math.min(ALL_FIELDS.length - 1, configCursor + 1);
                                    requestRender();
                                    continue;
                                }
                            }
                        }
                    }
                    break;
                } else if (key == ' ') {
                    String field = ALL_FIELDS[configCursor];
                    Set<String> next = new LinkedHashSet<String>(configEnabledFields);
                    if (next.contains(field)) {
                        next.remove(field);
                    } else {
                        next.add(field);
                    }
                    configEnabledFields = next;
                    requestRender();
                } else if (key == 'k' || key == 'K') {
                    configCursor = Math.max(0, configCursor - 1);
                    requestRender();
                } else if (key == 'j' || key == 'J') {
                    configCursor = Math.min(ALL_FIELDS.length - 1, configCursor + 1);
                    requestRender();
                } else if (key == '\r' || key == '\n') {
                    enabledFields.clear();
                    enabledFields.addAll(configEnabledFields);
                    break;
                }
            }
        } finally {
            configActive = false;
            configEnabledFields = Collections.emptySet();
            configCursor = 0;
            terminal.setAttributes(savedAttrs);
            requestRender();
        }
    }

    private void startAnimation() {
        stopAnimation();
        animationTick = 0;
        animTask = animExecutor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                animationTick++;
                requestRender();
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    private void stopAnimation() {
        ScheduledFuture<?> task = animTask;
        if (task != null) {
            task.cancel(false);
            animTask = null;
        }
    }

    private int readKey() {
        try {
            return terminal.reader().read();
        } catch (Throwable e) {
            return -1;
        }
    }

    private boolean isReaderReady() {
        try {
            return terminal.reader().ready();
        } catch (Throwable e) {
            return false;
        }
    }

    private AttributedString blankLine() {
        return padLine(new AttributedString(""));
    }

    private AttributedString padLine(AttributedString line) {
        int width = Math.max(1, terminal.getWidth());
        int visible = line.columnLength();
        if (visible >= width) {
            return line.columnSubSequence(0, width);
        }

        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.append(line);
        sb.style(STYLE_BG);
        for (int i = 0; i < width - visible; i++) {
            sb.append(' ');
        }
        return sb.toAttributedString();
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

    private AttributedStyle styleSeparator() {
        return theme.separator().style();
    }

    private AttributedStyle styleAccent() {
        return theme.accent().style();
    }

    private AttributedStyle styleAccentBold() {
        return theme.accentStrong().boldStyle();
    }

    private AttributedStyle styleWarn() {
        return theme.warning().style();
    }

    private AttributedStyle styleWarnBold() {
        return theme.warning().boldStyle();
    }

    private AttributedStyle styleSuccess() {
        return theme.success().style();
    }

    private AttributedStyle styleTool() {
        return theme.toolTitle().style();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String normalizeStatus(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "idle";
        }
        return value.trim();
    }

    static String formatDuration(long millis) {
        long seconds = Math.max(0, (millis + 999) / 1000);
        if (seconds < 60) {
            return seconds + "s";
        }
        long mins = seconds / 60;
        long secs = seconds % 60;
        if (mins < 60) {
            return String.format("%dm%02ds", mins, secs);
        }
        long hours = mins / 60;
        long remMins = mins % 60;
        return String.format("%dh%02dm%02ds", hours, remMins, secs);
    }

    private static String shortenPath(String path, int maxLen) {
        if (path == null || path.length() <= maxLen) {
            return path;
        }
        if (maxLen <= 3) {
            return path.substring(0, Math.max(0, maxLen));
        }
        int headLen = Math.max(1, maxLen / 3);
        int tailLen = Math.max(1, maxLen - headLen - 3);
        if (headLen + tailLen + 3 > path.length()) {
            return path;
        }
        return path.substring(0, headLen) + "..." + path.substring(path.length() - tailLen);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public RenderSnapshot snapshot() {
        if (configActive) {
            return new RenderSnapshot(buildConfigLines(), null);
        }

        List<AttributedString> lines = new ArrayList<AttributedString>();
        lines.add(buildRuntimeLine());
        lines.add(blankLine());
        lines.addAll(popupLines);
        lines.add(padLine(inputLine == null ? new AttributedString("") : inputLine));
        lines.add(blankLine());
        lines.add(buildStatusLine());

        Cursor cursor = restoreCursor == null ? null : new Cursor(restoreCursor.getX(), restoreCursor.getY());
        return new RenderSnapshot(lines, cursor);
    }

    private void requestRender() {
        Runnable requester = renderRequester;
        if (requester != null) {
            requester.run();
        }
    }

    private List<AttributedString> buildConfigLines() {
        List<AttributedString> lines = new ArrayList<AttributedString>();
        lines.add(blankLine());

        AttributedStringBuilder header = new AttributedStringBuilder();
        header.append("  ");
        header.style(styleMuted());
        header.append("--- ");
        header.style(styleText());
        header.append("Status Bar 配置");
        header.style(styleMuted());
        header.append(" ---  ↑↓/jk  Space  Enter  Esc");
        lines.add(padLine(header.toAttributedString()));
        lines.add(blankLine());

        Set<String> enabled = configEnabledFields == null ? Collections.<String>emptySet() : configEnabledFields;
        for (int i = 0; i < ALL_FIELDS.length; i++) {
            String field = ALL_FIELDS[i];
            boolean checked = enabled.contains(field);
            boolean selected = i == configCursor;

            AttributedStringBuilder row = new AttributedStringBuilder();
            if (selected) {
                row.append("  ");
                row.style(styleAccent());
                row.append("▸");
            } else {
                row.append("   ");
            }

            row.append(" ");
            row.style(checked ? styleSuccess() : styleMuted());
            row.append(checked ? "[✔]" : "[ ]");
            row.append(" ");
            row.style(selected ? styleAccentBold() : (checked ? styleText() : styleMuted()));
            row.append(String.format("%-10s", capitalize(field)));
            row.append(" ");
            row.style(styleMuted());
            row.append(FIELD_DESCRIPTIONS[i]);
            lines.add(padLine(row.toAttributedString()));
        }

        lines.add(blankLine());
        return lines;
    }

    public static final class RenderSnapshot {
        private final List<AttributedString> lines;
        private final Cursor cursor;

        private RenderSnapshot(List<AttributedString> lines, Cursor cursor) {
            this.lines = Collections.unmodifiableList(new ArrayList<AttributedString>(lines));
            this.cursor = cursor;
        }

        public List<AttributedString> getLines() {
            return lines;
        }

        public Cursor getCursor() {
            return cursor;
        }
    }

    private static final class RuntimeStatusInfo {
        private final String text;
        private final AttributedStyle spinnerStyle;
        private final AttributedStyle textStyle;
        private final AttributedStyle peakStyle;
        private final boolean animated;

        private RuntimeStatusInfo(String text, AttributedStyle spinnerStyle, AttributedStyle textStyle,
                                  AttributedStyle peakStyle, boolean animated) {
            this.text = text;
            this.spinnerStyle = spinnerStyle;
            this.textStyle = textStyle;
            this.peakStyle = peakStyle;
            this.animated = animated;
        }

        private static RuntimeStatusInfo from(String status, StatusBar owner) {
            if ("thinking".equals(status)) {
                return new RuntimeStatusInfo("thinking", owner.styleWarn(),
                        owner.styleWarn(), owner.styleWarnBold(), true);
            }
            if ("responding".equals(status)) {
                return new RuntimeStatusInfo("responding",
                        owner.styleAccent(), owner.styleAccent(), owner.styleAccentBold(), true);
            }
            if (status != null && status.startsWith("tool:")) {
                String toolName = status.substring("tool:".length()).trim();
                if (toolName.isEmpty()) {
                    toolName = "tool";
                }
                return new RuntimeStatusInfo(toolName, owner.styleAccent(),
                        owner.styleTool(), owner.styleTool(), false);
            }
            if ("idle".equals(status)) {
                return new RuntimeStatusInfo("", owner.styleSuccess(),
                        owner.styleSuccess(), owner.styleSuccess(), false);
            }
            return new RuntimeStatusInfo(status == null ? "" : status,
                    owner.styleMuted(), owner.styleText(), owner.styleText(), false);
        }
    }
}
