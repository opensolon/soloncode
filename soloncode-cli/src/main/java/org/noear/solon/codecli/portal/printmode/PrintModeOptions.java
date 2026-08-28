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
package org.noear.solon.codecli.portal.printmode;

import org.noear.solon.core.util.MultiMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Print / Headless 模式选项
 *
 * <p>对齐 Claude Code 的 {@code claude -p} 无头模式，解析命令行参数并封装为选项对象。</p>
 *
 * <p>用法示例:
 * <pre>
 *   soloncode run "你的提示词" --output-format json --model sonnet --max-turns 10
 *   soloncode run --output-format stream-json --verbose "流式输出"
 *   cat error.txt | soloncode run --output-format json "分析这个错误"
 *   soloncode run "Review this PR" --allowedTools "Read,Grep,Glob,Bash(git log *)" --disallowedTools "Bash(rm *)"
 *   soloncode run "Extract functions" --output-format json --json-schema '{"type":"object","properties":{"functions":{"type":"array","items":{"type":"string"}}}}'
 *   soloncode run "Fix the bug" --max-budget-usd 5.0 --max-turns 20
 * </pre>
 *
 * @author noear
 */
public class PrintModeOptions {

    public enum OutputFormat {
        TEXT,
        JSON,
        STREAM_JSON
    }

    public enum PermissionMode {
        /** 默认：未授权操作会被自动拒绝 */
        DEFAULT,
        /** 自动接受文件编辑，其它操作仍需授权 */
        ACCEPT_EDITS,
        /** 仅分析并提议，不做任何文件修改 */
        PLAN,
        /** 非交互模式：任何需授权的操作自动拒绝（CI 推荐） */
        DONT_ASK,
        /** 跳过所有权限检查（仅限沙箱环境） */
        BYPASS_PERMISSIONS
    }

    /**
     * 工具规则规格：将 {@code Bash(rm *)} 解析为 toolName + pattern。
     * pattern 为 null 时表示纯工具名（无 glob 模式）。
     */
    public static class ToolRuleSpec {
        private final String toolName;
        private final String pattern;

        public ToolRuleSpec(String toolName, String pattern) {
            this.toolName = toolName;
            this.pattern = pattern;
        }

        public String getToolName() {
            return toolName;
        }

        public String getPattern() {
            return pattern;
        }

        public boolean hasPattern() {
            return pattern != null && !pattern.isEmpty();
        }

        @Override
        public String toString() {
            return hasPattern() ? toolName + "(" + pattern + ")" : toolName;
        }
    }

    // ========== 字段 ==========

    /** 输出格式 */
    private OutputFormat outputFormat = OutputFormat.TEXT;

    /** 选择的模型名称或别名 */
    private String model;

    /** 最大轮次限制 */
    private Integer maxTurns;

    /** 会话 ID（用于 --session-id） */
    private String sessionId;

    /** 恢复指定会话 ID（用于 --resume） */
    private String resumeSessionId;

    /** 是否继续最近的会话（用于 --continue） */
    private boolean continueSession;

    /** 允许的工具列表（纯工具名，不含 glob 模式） */
    private List<String> allowedTools = new ArrayList<>();

    /** 禁止的工具列表（纯工具名，不含 glob 模式） */
    private List<String> disallowedTools = new ArrayList<>();

    /** 允许的工具规则（含 glob 模式，如 Bash(git log *)） */
    private List<ToolRuleSpec> allowedToolRules = new ArrayList<>();

    /** 禁止的工具规则（含 glob 模式，如 Bash(rm *)） */
    private List<ToolRuleSpec> disallowedToolRules = new ArrayList<>();

    /** 权限模式 */
    private PermissionMode permissionMode = PermissionMode.DEFAULT;

    /** 是否输出详细流式事件（stream-json 需要） */
    private boolean verbose;

    /** 是否裸模式（跳过自动发现 hooks/skills/MCP 等） */
    private boolean bare;

    /** 额外工作目录 */
    private List<String> addDirs = new ArrayList<>();

    /** 回退模型 */
    private String fallbackModel;

    /** JSON Schema 约束（结构化输出） */
    private String jsonSchema;

    /** 费用硬上限（美元） */
    private Double maxBudgetUsd;

    /** 提示词（来自位置参数） */
    private String prompt;

    /** 提示词是否来自 stdin */
    private boolean promptFromStdin;

    // ========== Getter ==========

    public OutputFormat getOutputFormat() {
        return outputFormat;
    }

    public String getModel() {
        return model;
    }

    public Integer getMaxTurns() {
        return maxTurns;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getResumeSessionId() {
        return resumeSessionId;
    }

    public boolean isContinueSession() {
        return continueSession;
    }

    public List<String> getAllowedTools() {
        return allowedTools;
    }

    public List<String> getDisallowedTools() {
        return disallowedTools;
    }

    public List<ToolRuleSpec> getAllowedToolRules() {
        return allowedToolRules;
    }

    public List<ToolRuleSpec> getDisallowedToolRules() {
        return disallowedToolRules;
    }

    public PermissionMode getPermissionMode() {
        return permissionMode;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public boolean isBare() {
        return bare;
    }

    public List<String> getAddDirs() {
        return addDirs;
    }

    public String getFallbackModel() {
        return fallbackModel;
    }

    public String getJsonSchema() {
        return jsonSchema;
    }

    public Double getMaxBudgetUsd() {
        return maxBudgetUsd;
    }

    public String getPrompt() {
        return prompt;
    }

    public boolean isPromptFromStdin() {
        return promptFromStdin;
    }

    // ========== 解析逻辑 ==========

    /**
     * 用于解析工具规则语法的正则：{@code ToolName(pattern)}
     * <p>例如 {@code Bash(rm *)} → group(1)="Bash", group(2)="rm *"</p>
     */
    private static final Pattern TOOL_RULE_REGEX = Pattern.compile("^(\\w+)\\((.+)\\)$");

    /**
     * 从 Solon argx 解析 Print 模式选项。
     *
     * <p>flagAt(0) 是 "run"，flagAt(1) 是提示词（或第一个 flag），
     * 其余参数通过 key=value 或 flag 形式传入。</p>
     *
     * @param argx Solon 启动参数
     * @return 解析后的选项
     */
    public static PrintModeOptions parse(MultiMap<String> argx) {
        PrintModeOptions opts = new PrintModeOptions();

        // flags 列表: [0]=run, [1]=prompt 或第一个 flag
        List<String> flags = argx.flags();

        // 提取提示词：flagAt(1) 如果不是已知的选项参数，则为提示词
        if (flags.size() > 1) {
            String second = flags.get(1);
            if (!isKnownOption(second)) {
                opts.prompt = second;
            }
        }

        // 解析 key=value 参数
        parseValueArg(argx, "output-format", val -> {
            if ("json".equalsIgnoreCase(val)) {
                opts.outputFormat = OutputFormat.JSON;
            } else if ("stream-json".equalsIgnoreCase(val)) {
                opts.outputFormat = OutputFormat.STREAM_JSON;
            } else {
                opts.outputFormat = OutputFormat.TEXT;
            }
        });

        parseValueArg(argx, "model", val -> opts.model = val);
        parseValueArg(argx, "max-turns", val -> opts.maxTurns = parseInt(val));
        parseValueArg(argx, "session-id", val -> opts.sessionId = val);
        parseValueArg(argx, "resume", val -> opts.resumeSessionId = val);
        // add-dir 支持重复参数（用 getAll 而非 get）
        List<String> addDirValues = argx.getAll("add-dir");
        if (addDirValues != null) {
            for (String val : addDirValues) {
                if (val != null && !val.isEmpty()) {
                    opts.addDirs.add(val);
                }
            }
        }
        parseValueArg(argx, "fallback-model", val -> opts.fallbackModel = val);
        parseValueArg(argx, "json-schema", val -> opts.jsonSchema = val);
        parseValueArg(argx, "max-budget-usd", val -> opts.maxBudgetUsd = parseDouble(val));
        parseValueArg(argx, "permission-mode", val -> {
            if ("acceptEdits".equalsIgnoreCase(val)) {
                opts.permissionMode = PermissionMode.ACCEPT_EDITS;
            } else if ("plan".equalsIgnoreCase(val)) {
                opts.permissionMode = PermissionMode.PLAN;
            } else if ("dontAsk".equalsIgnoreCase(val)) {
                opts.permissionMode = PermissionMode.DONT_ASK;
            } else if ("bypassPermissions".equalsIgnoreCase(val)) {
                opts.permissionMode = PermissionMode.BYPASS_PERMISSIONS;
            } else {
                opts.permissionMode = PermissionMode.DEFAULT;
            }
        });

        // 解析 allowedTools / disallowedTools（支持工具规则语法）
        parseToolListArg(argx, "allowedTools", opts.allowedTools, opts.allowedToolRules);
        parseToolListArg(argx, "disallowedTools", opts.disallowedTools, opts.disallowedToolRules);

        // 解析 flag 类型参数
        if (argx.flags().contains("verbose")) {
            opts.verbose = true;
        }
        if (argx.flags().contains("bare")) {
            opts.bare = true;
        }
        if (argx.flags().contains("continue")) {
            opts.continueSession = true;
        }

        return opts;
    }

    /**
     * 解析工具列表参数，将纯工具名和带 glob 模式的规则分离。
     *
     * @param argx       参数映射
     * @param key        参数名（allowedTools 或 disallowedTools）
     * @param plainTools 纯工具名列表（输出）
     * @param toolRules  工具规则列表（输出）
     */
    private static void parseToolListArg(MultiMap<String> argx, String key,
                                         List<String> plainTools,
                                         List<ToolRuleSpec> toolRules) {
        List<String> values = argx.getAll(key);
        if (values == null) return;

        for (String v : values) {
            for (String entry : parseCsv(v)) {
                ToolRuleSpec spec = parseToolRule(entry);
                if (spec == null) continue;

                if (spec.hasPattern()) {
                    // 带 glob 模式的规则
                    toolRules.add(spec);
                } else {
                    // 纯工具名
                    if (!plainTools.contains(spec.getToolName())) {
                        plainTools.add(spec.getToolName());
                    }
                }
            }
        }
    }

    /**
     * 解析单个工具规则条目。
     * <p>支持两种格式：
     * <ul>
     *   <li>{@code Read} → ToolRuleSpec("Read", null)</li>
     *   <li>{@code Bash(rm *)} → ToolRuleSpec("Bash", "rm *")</li>
     * </ul>
     *
     * @param entry 工具规则字符串
     * @return 解析后的 ToolRuleSpec，null 表示输入为空
     */
    public static ToolRuleSpec parseToolRule(String entry) {
        if (entry == null || entry.trim().isEmpty()) return null;
        entry = entry.trim();
        Matcher matcher = TOOL_RULE_REGEX.matcher(entry);
        if (matcher.matches()) {
            return new ToolRuleSpec(matcher.group(1), matcher.group(2));
        }
        return new ToolRuleSpec(entry, null);
    }

    // ========== Setter ==========

    /**
     * 设置提示词（可能来自 stdin）
     */
    public void setPrompt(String prompt, boolean fromStdin) {
        this.prompt = prompt;
        this.promptFromStdin = fromStdin;
    }

    /**
     * 判断是否需要从 stdin 读取提示词
     */
    public boolean shouldReadStdin() {
        return prompt == null || prompt.isEmpty();
    }

    // ========== 内部工具方法 ==========

    private static void parseValueArg(MultiMap<String> argx, String key, java.util.function.Consumer<String> consumer) {
        String val = argx.get(key);
        if (val != null && !val.isEmpty()) {
            consumer.accept(val);
        }
    }

    private static Integer parseInt(String val) {
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String val) {
        try {
            return Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> parseCsv(String val) {
        List<String> result = new ArrayList<>();
        if (val == null || val.trim().isEmpty()) {
            return result;
        }
        for (String part : val.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * 已知的选项名称集合（flags 中不带 - 前缀）
     */
    private static final Set<String> KNOWN_OPTIONS = new HashSet<>(Arrays.asList(
            "output-format", "model", "max-turns", "session-id", "resume",
            "allowedTools", "disallowedTools", "permission-mode",
            "verbose", "bare", "continue", "add-dir", "fallback-model",
            "json-schema", "max-budget-usd"
    ));

    /**
     * 判断 flag 是否为已知选项
     */
    private static boolean isKnownOption(String flag) {
        return KNOWN_OPTIONS.contains(flag);
    }
}
