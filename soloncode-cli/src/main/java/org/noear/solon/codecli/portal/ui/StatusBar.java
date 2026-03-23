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
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 底部固定状态栏 — 基于 JLine3 Status API
 *
 * <p>
 * 利用 {@link org.jline.utils.Status} 实现终端底部固定行，
 * 不随滚动消失，不与 printAbove / prompt 冲突。
 * </p>
 *
 * @author noear
 * @since 0.0.19
 */
public class StatusBar {
    private final Terminal terminal;
    private volatile Status status;

    // ── 可选字段定义 ──
    static final String[] ALL_FIELDS = {
            "model", "status", "time", "tokens", "dir",
            "version", "session", "turns", "mode", "context"
    };
    static final String[] FIELD_DESCRIPTIONS = {
            "当前模型名", "状态 + 持续时长", "任务总时长",
            "Token 用量", "工作目录", "CLI 版本号",
            "会话 ID", "对话轮次", "精简/详细模式", "上下文窗口占用"
    };

    // ── 配置 ──
    private final Set<String> enabledFields = new LinkedHashSet<>(
            Arrays.asList("model", "status", "time", "tokens", "dir"));

    // ── 动态状态 ──
    private volatile String currentStatus = "idle";
    private volatile long taskStartTime = 0;
    private volatile long stateStartTime = 0;
    private volatile long lastTokens = 0;

    // ── 静态数据 ──
    private String modelName = "unknown";
    private String workDir = "";
    private String version = "";
    private String sessionId = "";
    private int turns = 0;
    private boolean compactMode = false;

    // ── 底部状态栏动效 ──
    private volatile int glowPosition = 0;
    private final ScheduledExecutorService barAnimExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "statusbar-anim");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> barAnimTask;

    // ── 样式常量 ──
    private static final AttributedStyle STYLE_TEXT = AttributedStyle.DEFAULT
            .foreground(243, 245, 247);
    private static final AttributedStyle STYLE_MUTED = AttributedStyle.DEFAULT
            .foreground(114, 123, 137);
    private static final AttributedStyle STYLE_SEP = AttributedStyle.DEFAULT
            .foreground(60, 65, 75);
    private static final AttributedStyle STYLE_GREEN = AttributedStyle.DEFAULT
            .foreground(39, 201, 63);
    private static final AttributedStyle STYLE_WARN = AttributedStyle.DEFAULT
            .foreground(232, 194, 122);
    private static final AttributedStyle STYLE_BG = AttributedStyle.DEFAULT
            .foreground(160, 168, 184).background(22, 27, 36);

    // ── 旧版 ANSI 常量（仅 configUI 使用）──
    private static final String ACCENT_BOLD = "\033[1;38;2;255;125;144m",
            ACCENT = "\033[38;2;255;125;144m",
            TEXT = "\033[38;2;243;245;247m",
            MUTED = "\033[38;2;114;123;137m",
            DIM = "\033[2m",
            GREEN = "\033[38;2;39;201;63m",
            RESET = "\033[0m";

    public StatusBar(Terminal terminal) {
        this.terminal = terminal;
    }

    // ═══════════════════════════════════════════════════════════
    // 初始化
    // ═══════════════════════════════════════════════════════════

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
        draw();
    }

    public void incrementTurns() {
        this.turns++;
    }

    // ═══════════════════════════════════════════════════════════
    // JLine Status 生命周期
    // ═══════════════════════════════════════════════════════════

    /** 初始化底部状态栏 */
    public void setup() {
        try {
            this.status = Status.getStatus(terminal);
        } catch (Throwable e) {
            // 某些终端可能不支持 Status
            this.status = null;
        }
    }

    /** 绘制状态栏到终端底部 */
    public void draw() {
        if (status == null)
            return;

        synchronized (terminal) {
            try {
                List<AttributedString> lines = new ArrayList<>();
                lines.add(buildStatusLine());
                terminal.writer().print("\033[?25l");
                terminal.flush();
                status.update(lines);
                terminal.writer().print("\033[?25h");
                terminal.flush();
            } catch (Throwable ignored) {
                try {
                    terminal.writer().print("\033[?25h");
                    terminal.flush();
                } catch (Throwable e) {
                }
            }
        }
    }

    /** 隐藏状态栏（配置 UI 等场景） */
    public void suspend() {
        if (status != null) {
            try {
                status.suspend();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 恢复状态栏显示 */
    public void restore() {
        if (status != null) {
            try {
                status.restore();
                draw();
            } catch (Throwable ignored) {
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 状态更新
    // ═══════════════════════════════════════════════════════════

    /** 更新状态并重绘 */
    public void updateStatus(String status) {
        if (!status.equals(this.currentStatus)) {
            this.stateStartTime = System.currentTimeMillis();
        }
        this.currentStatus = status;
        draw();
    }

    /** 获取当前状态文本 */
    public String getStatusText() {
        boolean isIdle = "idle".equals(currentStatus);
        if (isIdle) {
            return "idle";
        }
        long elapsed = System.currentTimeMillis() - stateStartTime;
        return currentStatus + " " + formatDuration(elapsed);
    }

    /** 获取任务总时长文本 */
    public String getTaskTimeText() {
        if (taskStartTime == 0) {
            return "";
        }
        long elapsed = System.currentTimeMillis() - taskStartTime;
        return formatDuration(elapsed);
    }

    /** 任务开始 */
    public void taskStart() {
        this.taskStartTime = System.currentTimeMillis();
        this.stateStartTime = System.currentTimeMillis();
        this.lastTokens = 0;
        this.currentStatus = "⚙ thinking";
        startBarAnimation();
        draw();
    }

    /** 任务结束 */
    public void taskEnd(long tokens) {
        this.lastTokens = tokens;
        this.currentStatus = "idle";
        this.stateStartTime = System.currentTimeMillis();
        stopBarAnimation();
        draw();
    }

    /** 更新 tokens */
    public void updateTokens(long tokens) {
        this.lastTokens = tokens;
        draw();
    }

    public boolean isIdle() {
        return "idle".equals(currentStatus);
    }

    // ═══════════════════════════════════════════════════════════
    // 状态栏渲染
    // ═══════════════════════════════════════════════════════════

    /** 构建一行 AttributedString 状态栏 */
    private AttributedString buildStatusLine() {
        int termWidth = terminal.getWidth();
        AttributedStringBuilder sb = new AttributedStringBuilder();

        // 左侧留一个空格
        sb.styled(STYLE_MUTED, " ");

        // 各段之间用分隔符
        String sep = " │ ";
        boolean first = true;
        int glowStart = -1, glowEnd = -1; // 模型+状态的字符范围（扫光区域）

        for (String field : enabledFields) {
            // 跳过无数据的可选段
            if ("time".equals(field) && taskStartTime == 0)
                continue;
            if ("tokens".equals(field) && lastTokens == 0)
                continue;
            if ("context".equals(field))
                continue;

            if (!first) {
                sb.styled(STYLE_SEP, sep);
            }
            first = false;

            switch (field) {
                case "model":
                    glowStart = sb.toAttributedString().length(); // 扫光从 model 开始
                    sb.styled(STYLE_TEXT.bold(), modelName);
                    break;
                case "status":
                    appendStatusSegment(sb);
                    glowEnd = sb.toAttributedString().length(); // 扫光到 status 结束
                    break;
                case "time":
                    long elapsed = System.currentTimeMillis() - taskStartTime;
                    sb.styled(STYLE_MUTED, "⏱ ");
                    sb.styled(STYLE_TEXT, formatDuration(elapsed));
                    break;
                case "tokens":
                    sb.styled(STYLE_MUTED, "↑ ");
                    sb.styled(STYLE_TEXT, String.valueOf(lastTokens));
                    break;
                case "dir":
                    int usedWidth = sb.toAttributedString().columnLength();
                    int sepWidth = sep.length();
                    int availForDir = termWidth - usedWidth - sepWidth - 2;
                    if (availForDir > 10) {
                        String displayDir = shortenPath(workDir, availForDir);
                        sb.styled(STYLE_MUTED, displayDir);
                    }
                    break;
                case "version":
                    sb.styled(STYLE_MUTED, version);
                    break;
                case "session":
                    sb.styled(STYLE_MUTED, "⊙ ");
                    sb.styled(STYLE_TEXT, sessionId);
                    break;
                case "turns":
                    sb.styled(STYLE_MUTED, "#");
                    sb.styled(STYLE_TEXT, String.valueOf(turns));
                    break;
                case "mode":
                    sb.styled(STYLE_TEXT, compactMode ? "compact" : "verbose");
                    break;
            }
        }

        // 填充到终端宽度
        AttributedString line = sb.toAttributedString();
        int currentLen = line.columnLength();
        if (currentLen < termWidth) {
            sb.styled(STYLE_BG, repeat(' ', termWidth - currentLen));
            line = sb.toAttributedString();
        } else if (currentLen > termWidth) {
            line = line.columnSubSequence(0, termWidth);
        }

        // 非 idle 状态：对 model+status 段应用扫光
        if (!"idle".equals(currentStatus) && glowStart >= 0 && glowEnd > glowStart) {
            line = applyGlow(line, glowStart, glowEnd);
        }

        return line;
    }

    /** 对指定范围应用扫光效果（只改 status 段，其他段原样保留） */
    private AttributedString applyGlow(AttributedString line, int rangeStart, int rangeEnd) {
        int len = line.length();
        // 安全边界
        rangeStart = Math.max(0, Math.min(rangeStart, len));
        rangeEnd = Math.max(rangeStart, Math.min(rangeEnd, len));
        int rangeLen = rangeEnd - rangeStart;
        int glow = rangeStart + (glowPosition % (rangeLen + 6));
        int glowRadius = 3;

        AttributedStringBuilder result = new AttributedStringBuilder();

        // ① 原样保留 status 段前面的内容（model + 分隔符等）
        if (rangeStart > 0) {
            result.append(line, 0, rangeStart);
        }

        // ② 对 status 段逐字符应用扫光
        for (int i = rangeStart; i < rangeEnd; i++) {
            char ch = line.charAt(i);
            int dist = Math.abs(i - glow);
            if (dist <= glowRadius) {
                float factor = 1.0f - (dist / (float) (glowRadius + 1));
                int r = Math.min(255, (int) (160 + 95 * factor));
                int g = Math.min(255, (int) (168 + 87 * factor));
                int b = Math.min(255, (int) (184 + 71 * factor));
                result.styled(AttributedStyle.DEFAULT.foreground(r, g, b).background(22, 27, 36),
                        String.valueOf(ch));
            } else {
                result.append(line, i, i + 1);
            }
        }

        // ③ 原样保留 status 段后面的内容（tokens, dir 等）
        if (rangeEnd < len) {
            result.append(line, rangeEnd, len);
        }

        return result.toAttributedString();
    }

    /** 启动底部状态栏扫光动效 */
    private void startBarAnimation() {
        stopBarAnimation();
        glowPosition = 0;
        barAnimTask = barAnimExecutor.scheduleAtFixedRate(() -> {
            try {
                glowPosition++;
                draw();
            } catch (Throwable ignored) {
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    /** 停止底部状态栏扫光动效 */
    private void stopBarAnimation() {
        ScheduledFuture<?> task = barAnimTask;
        if (task != null) {
            task.cancel(false);
            barAnimTask = null;
        }
    }

    /** 渲染状态段（idle 绿色，运行中显示动态描述） */
    private void appendStatusSegment(AttributedStringBuilder sb) {
        boolean isIdle = "idle".equals(currentStatus);
        if (isIdle) {
            sb.styled(STYLE_GREEN, "● idle");
        } else {
            long elapsed = System.currentTimeMillis() - stateStartTime;
            sb.styled(STYLE_WARN, currentStatus);
            sb.styled(STYLE_MUTED, " " + formatDuration(elapsed));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // /statusbar 交互式配置
    // ═══════════════════════════════════════════════════════════

    /** 显示交互式配置 UI */
    public void showConfigUI() {
        suspend(); // 暂停底部状态栏，避免与配置菜单冲突

        Attributes savedAttrs = terminal.getAttributes();
        try {
            terminal.enterRawMode();
            Set<String> tempEnabled = new LinkedHashSet<>(enabledFields);
            int cursor = 0;

            drawConfigMenu(tempEnabled, cursor);

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
                                    cursor = Math.max(0, cursor - 1);
                                    drawConfigMenu(tempEnabled, cursor);
                                    continue;
                                } else if (arrow == 'B') {
                                    cursor = Math.min(ALL_FIELDS.length - 1, cursor + 1);
                                    drawConfigMenu(tempEnabled, cursor);
                                    continue;
                                }
                            }
                        }
                    }
                    break;
                } else if (key == ' ') {
                    String field = ALL_FIELDS[cursor];
                    if (tempEnabled.contains(field)) {
                        tempEnabled.remove(field);
                    } else {
                        tempEnabled.add(field);
                    }
                    drawConfigMenu(tempEnabled, cursor);
                } else if (key == 'k' || key == 'K') {
                    cursor = Math.max(0, cursor - 1);
                    drawConfigMenu(tempEnabled, cursor);
                } else if (key == 'j' || key == 'J') {
                    cursor = Math.min(ALL_FIELDS.length - 1, cursor + 1);
                    drawConfigMenu(tempEnabled, cursor);
                } else if (key == '\r' || key == '\n') {
                    enabledFields.clear();
                    enabledFields.addAll(tempEnabled);
                    break;
                }
            }
        } finally {
            terminal.setAttributes(savedAttrs);
        }

        clearConfigMenu();
        restore(); // 恢复底部状态栏
    }

    private void drawConfigMenu(Set<String> tempEnabled, int cursor) {
        StringBuilder sb = new StringBuilder();
        sb.append("\r\033[J");

        sb.append("\n");
        sb.append(MUTED + "  ─── " + RESET + TEXT + "Status Bar 配置" + RESET + MUTED + " ─── " + RESET);
        sb.append(MUTED + "  ↑↓/jk  Space  Enter  Esc" + RESET);
        sb.append("\n\n");

        for (int i = 0; i < ALL_FIELDS.length; i++) {
            String field = ALL_FIELDS[i];
            boolean enabled = tempEnabled.contains(field);
            boolean isCursor = (i == cursor);

            String check = enabled ? GREEN + "[✔]" + RESET : MUTED + "[ ]" + RESET;
            String name = String.format("%-10s", capitalize(field));
            String desc = MUTED + FIELD_DESCRIPTIONS[i] + RESET;

            if (isCursor) {
                sb.append("  " + ACCENT + "▸ " + RESET + check + " " + ACCENT_BOLD + name + RESET + " " + desc);
            } else {
                String nameColor = enabled ? TEXT : MUTED;
                sb.append("    " + check + " " + nameColor + name + RESET + " " + desc);
            }
            sb.append("\n");
        }

        sb.append("\033[" + (ALL_FIELDS.length + 3) + "A");

        terminal.writer().write(sb.toString());
        terminal.flush();
    }

    private void clearConfigMenu() {
        terminal.writer().write("\r\033[J");
        terminal.flush();
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

    // ═══════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════

    static String formatDuration(long millis) {
        double seconds = millis / 1000.0;
        if (seconds < 60) {
            return String.format("%.1fs", seconds);
        }
        int mins = (int) (seconds / 60);
        double secs = seconds % 60;
        return String.format("%dm%.0fs", mins, secs);
    }

    /** 缩短路径显示（保留头尾） */
    private static String shortenPath(String path, int maxLen) {
        if (path == null || path.length() <= maxLen)
            return path;
        int headLen = maxLen / 3;
        int tailLen = maxLen - headLen - 3;
        return path.substring(0, headLen) + "..." + path.substring(path.length() - tailLen);
    }

    private static String repeat(char c, int count) {
        if (count <= 0)
            return "";
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty())
            return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
