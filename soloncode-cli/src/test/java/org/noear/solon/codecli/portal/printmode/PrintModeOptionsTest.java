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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.noear.solon.core.util.MultiMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PrintModeOptions 单元测试
 *
 * <p>覆盖所有选项的解析逻辑、工具规则语法、新增选项、组合参数及边界条件。</p>
 *
 * @author noear
 */
public class PrintModeOptionsTest {

    private MultiMap<String> buildArgx(String... args) {
        return MultiMap.from(args);
    }

    // ========== 基础提示词解析 ==========

    @Test
    @DisplayName("基础提示词解析")
    public void testBasicPromptParsing() {
        MultiMap<String> argx = buildArgx("run", "Hello world");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals("Hello world", opts.getPrompt());
        assertEquals(PrintModeOptions.OutputFormat.TEXT, opts.getOutputFormat());
        assertNull(opts.getModel());
        assertNull(opts.getMaxTurns());
    }

    @Test
    @DisplayName("无提示词时应标记需要读取 stdin")
    public void testNoPrompt() {
        MultiMap<String> argx = buildArgx("run");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertNull(opts.getPrompt());
        assertTrue(opts.shouldReadStdin());
    }

    @Test
    @DisplayName("以 -- 开头的 flag 不被识别为提示词")
    public void testPromptStartingWithDashIsNotPrompt() {
        MultiMap<String> argx = buildArgx("run", "--verbose");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertNull(opts.getPrompt());
        assertTrue(opts.isVerbose());
    }

    // ========== 输出格式 ==========

    @Test
    @DisplayName("output-format=json")
    public void testOutputFormatJson() {
        MultiMap<String> argx = buildArgx("run", "test prompt", "output-format=json");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(PrintModeOptions.OutputFormat.JSON, opts.getOutputFormat());
    }

    @Test
    @DisplayName("output-format=stream-json")
    public void testOutputFormatStreamJson() {
        MultiMap<String> argx = buildArgx("run", "test", "output-format=stream-json");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(PrintModeOptions.OutputFormat.STREAM_JSON, opts.getOutputFormat());
    }

    @Test
    @DisplayName("output-format=text (显式)")
    public void testOutputFormatText() {
        MultiMap<String> argx = buildArgx("run", "test", "output-format=text");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(PrintModeOptions.OutputFormat.TEXT, opts.getOutputFormat());
    }

    @Test
    @DisplayName("output-format 默认为 TEXT")
    public void testOutputFormatDefault() {
        MultiMap<String> argx = buildArgx("run", "test");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(PrintModeOptions.OutputFormat.TEXT, opts.getOutputFormat());
    }

    // ========== 模型与轮次 ==========

    @Test
    @DisplayName("model 选择")
    public void testModelSelection() {
        MultiMap<String> argx = buildArgx("run", "test", "model=sonnet");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals("sonnet", opts.getModel());
    }

    @Test
    @DisplayName("max-turns 有效值")
    public void testMaxTurns() {
        MultiMap<String> argx = buildArgx("run", "test", "max-turns=10");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(Integer.valueOf(10), opts.getMaxTurns());
    }

    @Test
    @DisplayName("max-turns 无效值返回 null")
    public void testMaxTurnsInvalid() {
        MultiMap<String> argx = buildArgx("run", "test", "max-turns=abc");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertNull(opts.getMaxTurns());
    }

    // ========== 会话续接 ==========

    @Test
    @DisplayName("session-id")
    public void testSessionId() {
        MultiMap<String> argx = buildArgx("run", "test", "session-id=abc-123");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals("abc-123", opts.getSessionId());
    }

    @Test
    @DisplayName("resume")
    public void testResume() {
        MultiMap<String> argx = buildArgx("run", "test", "resume=prev-session-id");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals("prev-session-id", opts.getResumeSessionId());
    }

    @Test
    @DisplayName("continue flag")
    public void testContinueFlag() {
        MultiMap<String> argx = buildArgx("run", "test", "continue");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertTrue(opts.isContinueSession());
    }

    // ========== 权限模式 ==========

    @Test
    @DisplayName("默认权限模式")
    public void testDefaultPermissionMode() {
        MultiMap<String> argx = buildArgx("run", "test");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(PrintModeOptions.PermissionMode.DEFAULT, opts.getPermissionMode());
    }

    @Test
    @DisplayName("acceptEdits 权限模式")
    public void testAcceptEditsPermissionMode() {
        MultiMap<String> argx = buildArgx("run", "test", "permission-mode=acceptEdits");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(PrintModeOptions.PermissionMode.ACCEPT_EDITS, opts.getPermissionMode());
    }

    @Test
    @DisplayName("plan 权限模式")
    public void testPermissionModePlan() {
        MultiMap<String> argx = buildArgx("run", "test", "permission-mode=plan");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(PrintModeOptions.PermissionMode.PLAN, opts.getPermissionMode());
    }

    @Test
    @DisplayName("dontAsk 权限模式")
    public void testPermissionModeDontAsk() {
        MultiMap<String> argx = buildArgx("run", "test", "permission-mode=dontAsk");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(PrintModeOptions.PermissionMode.DONT_ASK, opts.getPermissionMode());
    }

    @Test
    @DisplayName("bypassPermissions 权限模式")
    public void testPermissionModeBypass() {
        MultiMap<String> argx = buildArgx("run", "test", "permission-mode=bypassPermissions");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(PrintModeOptions.PermissionMode.BYPASS_PERMISSIONS, opts.getPermissionMode());
    }

    // ========== verbose / bare ==========

    @Test
    @DisplayName("verbose flag")
    public void testVerboseFlag() {
        MultiMap<String> argx = buildArgx("run", "test", "verbose");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertTrue(opts.isVerbose());
    }

    @Test
    @DisplayName("bare flag")
    public void testBareFlag() {
        MultiMap<String> argx = buildArgx("run", "test", "bare");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertTrue(opts.isBare());
    }

    @Test
    @DisplayName("bare 默认为 false")
    public void testBareDefaultFalse() {
        MultiMap<String> argx = buildArgx("run", "test");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertFalse(opts.isBare());
    }

    // ========== add-dir ==========

    @Test
    @DisplayName("add-dir 单个目录")
    public void testAddDir() {
        MultiMap<String> argx = buildArgx("run", "test", "add-dir=/path/to/extra");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(1, opts.getAddDirs().size());
        assertEquals("/path/to/extra", opts.getAddDirs().get(0));
    }

    @Test
    @DisplayName("add-dir 多个目录（重复参数）")
    public void testAddDirMultiple() {
        MultiMap<String> argx = buildArgx("run", "test",
                "add-dir=/path/a", "add-dir=/path/b", "add-dir=/path/c");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(3, opts.getAddDirs().size());
        assertEquals("/path/a", opts.getAddDirs().get(0));
        assertEquals("/path/b", opts.getAddDirs().get(1));
        assertEquals("/path/c", opts.getAddDirs().get(2));
    }

    @Test
    @DisplayName("add-dir 默认为空")
    public void testAddDirDefaultEmpty() {
        MultiMap<String> argx = buildArgx("run", "test");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertTrue(opts.getAddDirs().isEmpty());
    }

    // ========== fallback-model ==========

    @Test
    @DisplayName("fallback-model")
    public void testFallbackModel() {
        MultiMap<String> argx = buildArgx("run", "test", "fallback-model=opus");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals("opus", opts.getFallbackModel());
    }

    @Test
    @DisplayName("fallback-model 默认为 null")
    public void testFallbackModelDefaultNull() {
        MultiMap<String> argx = buildArgx("run", "test");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertNull(opts.getFallbackModel());
    }

    // ========== json-schema ==========

    @Test
    @DisplayName("json-schema 解析")
    public void testJsonSchema() {
        String schema = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}";
        MultiMap<String> argx = buildArgx("run", "test", "json-schema=" + schema);
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(schema, opts.getJsonSchema());
    }

    @Test
    @DisplayName("json-schema 默认为 null")
    public void testJsonSchemaDefaultNull() {
        MultiMap<String> argx = buildArgx("run", "test");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertNull(opts.getJsonSchema());
    }

    @Test
    @DisplayName("json-schema 复杂嵌套结构")
    public void testJsonSchemaComplex() {
        String schema = "{\"type\":\"object\",\"properties\":{\"functions\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"functions\"]}";
        MultiMap<String> argx = buildArgx("run", "test", "json-schema=" + schema);
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertNotNull(opts.getJsonSchema());
        assertTrue(opts.getJsonSchema().contains("functions"));
        assertTrue(opts.getJsonSchema().contains("array"));
    }

    // ========== max-budget-usd ==========

    @Test
    @DisplayName("max-budget-usd 整数值")
    public void testMaxBudgetUsdInteger() {
        MultiMap<String> argx = buildArgx("run", "test", "max-budget-usd=5");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(Double.valueOf(5.0), opts.getMaxBudgetUsd());
    }

    @Test
    @DisplayName("max-budget-usd 小数值")
    public void testMaxBudgetUsdDecimal() {
        MultiMap<String> argx = buildArgx("run", "test", "max-budget-usd=0.50");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(Double.valueOf(0.50), opts.getMaxBudgetUsd());
    }

    @Test
    @DisplayName("max-budget-usd 无效值返回 null")
    public void testMaxBudgetUsdInvalid() {
        MultiMap<String> argx = buildArgx("run", "test", "max-budget-usd=abc");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertNull(opts.getMaxBudgetUsd());
    }

    @Test
    @DisplayName("max-budget-usd 默认为 null")
    public void testMaxBudgetUsdDefaultNull() {
        MultiMap<String> argx = buildArgx("run", "test");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertNull(opts.getMaxBudgetUsd());
    }

    // ========== 工具规则语法 parseToolRule ==========

    @Test
    @DisplayName("parseToolRule: 纯工具名 Read")
    public void testParseToolRulePlainName() {
        PrintModeOptions.ToolRuleSpec spec = PrintModeOptions.parseToolRule("Read");

        assertNotNull(spec);
        assertEquals("Read", spec.getToolName());
        assertNull(spec.getPattern());
        assertFalse(spec.hasPattern());
    }

    @Test
    @DisplayName("parseToolRule: Bash(rm *) → toolName=Bash, pattern=rm *")
    public void testParseToolRuleBashRm() {
        PrintModeOptions.ToolRuleSpec spec = PrintModeOptions.parseToolRule("Bash(rm *)");

        assertNotNull(spec);
        assertEquals("Bash", spec.getToolName());
        assertEquals("rm *", spec.getPattern());
        assertTrue(spec.hasPattern());
    }

    @Test
    @DisplayName("parseToolRule: Bash(git log *) → toolName=Bash, pattern=git log *")
    public void testParseToolRuleBashGitLog() {
        PrintModeOptions.ToolRuleSpec spec = PrintModeOptions.parseToolRule("Bash(git log *)");

        assertNotNull(spec);
        assertEquals("Bash", spec.getToolName());
        assertEquals("git log *", spec.getPattern());
        assertTrue(spec.hasPattern());
    }

    @Test
    @DisplayName("parseToolRule: Bash(git diff *) → toolName=Bash, pattern=git diff *")
    public void testParseToolRuleBashGitDiff() {
        PrintModeOptions.ToolRuleSpec spec = PrintModeOptions.parseToolRule("Bash(git diff *)");

        assertNotNull(spec);
        assertEquals("Bash", spec.getToolName());
        assertEquals("git diff *", spec.getPattern());
    }

    @Test
    @DisplayName("parseToolRule: 空输入返回 null")
    public void testParseToolRuleEmpty() {
        assertNull(PrintModeOptions.parseToolRule(null));
        assertNull(PrintModeOptions.parseToolRule(""));
        assertNull(PrintModeOptions.parseToolRule("   "));
    }

    @Test
    @DisplayName("parseToolRule: 带空格的输入自动 trim")
    public void testParseToolRuleTrim() {
        PrintModeOptions.ToolRuleSpec spec = PrintModeOptions.parseToolRule("  Read  ");

        assertNotNull(spec);
        assertEquals("Read", spec.getToolName());
        assertFalse(spec.hasPattern());
    }

    @Test
    @DisplayName("parseToolRule: 嵌套括号 Bash(echo (test))")
    public void testParseToolRuleNestedParens() {
        PrintModeOptions.ToolRuleSpec spec = PrintModeOptions.parseToolRule("Bash(echo (test))");

        assertNotNull(spec);
        assertEquals("Bash", spec.getToolName());
        assertEquals("echo (test)", spec.getPattern());
        assertTrue(spec.hasPattern());
    }

    @Test
    @DisplayName("ToolRuleSpec.toString() 带 pattern")
    public void testToolRuleSpecToStringWithPattern() {
        PrintModeOptions.ToolRuleSpec spec = new PrintModeOptions.ToolRuleSpec("Bash", "rm *");
        assertEquals("Bash(rm *)", spec.toString());
    }

    @Test
    @DisplayName("ToolRuleSpec.toString() 不带 pattern")
    public void testToolRuleSpecToStringWithoutPattern() {
        PrintModeOptions.ToolRuleSpec spec = new PrintModeOptions.ToolRuleSpec("Read", null);
        assertEquals("Read", spec.toString());
    }

    // ========== allowedTools / disallowedTools 混合规则 ==========

    @Test
    @DisplayName("allowedTools 纯工具名列表")
    public void testAllowedToolsPlain() {
        MultiMap<String> argx = buildArgx("run", "test", "allowedTools=Read,Grep,Glob");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(3, opts.getAllowedTools().size());
        assertTrue(opts.getAllowedTools().contains("Read"));
        assertTrue(opts.getAllowedTools().contains("Grep"));
        assertTrue(opts.getAllowedTools().contains("Glob"));
        assertTrue(opts.getAllowedToolRules().isEmpty());
    }

    @Test
    @DisplayName("allowedTools 包含工具规则 Bash(git log *)")
    public void testAllowedToolsWithRule() {
        MultiMap<String> argx = buildArgx("run", "test",
                "allowedTools=Read,Grep,Bash(git log *)");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        // 纯工具名
        assertEquals(2, opts.getAllowedTools().size());
        assertTrue(opts.getAllowedTools().contains("Read"));
        assertTrue(opts.getAllowedTools().contains("Grep"));
        assertFalse(opts.getAllowedTools().contains("Bash"));

        // 工具规则
        assertEquals(1, opts.getAllowedToolRules().size());
        PrintModeOptions.ToolRuleSpec rule = opts.getAllowedToolRules().get(0);
        assertEquals("Bash", rule.getToolName());
        assertEquals("git log *", rule.getPattern());
    }

    @Test
    @DisplayName("allowedTools 多个工具规则")
    public void testAllowedToolsMultipleRules() {
        MultiMap<String> argx = buildArgx("run", "test",
                "allowedTools=Bash(git log *),Bash(git diff *),Read");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(1, opts.getAllowedTools().size());
        assertTrue(opts.getAllowedTools().contains("Read"));

        assertEquals(2, opts.getAllowedToolRules().size());
    }

    @Test
    @DisplayName("disallowedTools 包含工具规则 Bash(rm *)")
    public void testDisallowedToolsWithRule() {
        MultiMap<String> argx = buildArgx("run", "test",
                "disallowedTools=Write,Bash(rm *)");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        // 纯工具名
        assertEquals(1, opts.getDisallowedTools().size());
        assertTrue(opts.getDisallowedTools().contains("Write"));

        // 工具规则
        assertEquals(1, opts.getDisallowedToolRules().size());
        PrintModeOptions.ToolRuleSpec rule = opts.getDisallowedToolRules().get(0);
        assertEquals("Bash", rule.getToolName());
        assertEquals("rm *", rule.getPattern());
    }

    @Test
    @DisplayName("disallowedTools 纯工具名列表")
    public void testDisallowedToolsPlain() {
        MultiMap<String> argx = buildArgx("run", "test", "disallowedTools=Bash,Write");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(2, opts.getDisallowedTools().size());
        assertTrue(opts.getDisallowedTools().contains("Bash"));
        assertTrue(opts.getDisallowedTools().contains("Write"));
        assertTrue(opts.getDisallowedToolRules().isEmpty());
    }

    @Test
    @DisplayName("allowedTools + disallowedTools 同时包含规则")
    public void testBothToolListsWithRules() {
        MultiMap<String> argx = buildArgx("run", "test",
                "allowedTools=Read,Bash(git log *)",
                "disallowedTools=Write,Bash(rm *)");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        // allowed
        assertEquals(1, opts.getAllowedTools().size());
        assertTrue(opts.getAllowedTools().contains("Read"));
        assertEquals(1, opts.getAllowedToolRules().size());
        assertEquals("Bash", opts.getAllowedToolRules().get(0).getToolName());
        assertEquals("git log *", opts.getAllowedToolRules().get(0).getPattern());

        // disallowed
        assertEquals(1, opts.getDisallowedTools().size());
        assertTrue(opts.getDisallowedTools().contains("Write"));
        assertEquals(1, opts.getDisallowedToolRules().size());
        assertEquals("Bash", opts.getDisallowedToolRules().get(0).getToolName());
        assertEquals("rm *", opts.getDisallowedToolRules().get(0).getPattern());
    }

    @Test
    @DisplayName("allowedTools 重复参数去重")
    public void testAllowedToolsDedup() {
        MultiMap<String> argx = buildArgx("run", "test",
                "allowedTools=Read,Grep", "allowedTools=Read,Glob");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        // Read 不应重复
        long readCount = opts.getAllowedTools().stream().filter(t -> t.equals("Read")).count();
        assertEquals(1, readCount);
        assertEquals(3, opts.getAllowedTools().size());
    }

    @Test
    @DisplayName("allowedTools 默认为空")
    public void testAllowedToolsDefaultEmpty() {
        MultiMap<String> argx = buildArgx("run", "test");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertTrue(opts.getAllowedTools().isEmpty());
        assertTrue(opts.getAllowedToolRules().isEmpty());
    }

    // ========== 组合参数 ==========

    @Test
    @DisplayName("组合参数：全部基础选项")
    public void testCombinedOptions() {
        MultiMap<String> argx = buildArgx(
                "run", "Fix the bug", "output-format=json", "model=sonnet",
                "max-turns=8", "allowedTools=Read,Grep", "verbose");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals("Fix the bug", opts.getPrompt());
        assertEquals(PrintModeOptions.OutputFormat.JSON, opts.getOutputFormat());
        assertEquals("sonnet", opts.getModel());
        assertEquals(Integer.valueOf(8), opts.getMaxTurns());
        assertEquals(2, opts.getAllowedTools().size());
        assertTrue(opts.isVerbose());
    }

    @Test
    @DisplayName("组合参数：新增选项 + 基础选项")
    public void testCombinedWithNewOptions() {
        MultiMap<String> argx = buildArgx(
                "run", "Extract functions", "output-format=json",
                "model=sonnet", "max-turns=15",
                "allowedTools=Read,Grep,Glob,Bash(git log *)",
                "disallowedTools=Bash(rm *)",
                "fallback-model=opus",
                "json-schema={\"type\":\"object\",\"properties\":{\"functions\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}",
                "max-budget-usd=2.5",
                "permission-mode=dontAsk");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals("Extract functions", opts.getPrompt());
        assertEquals(PrintModeOptions.OutputFormat.JSON, opts.getOutputFormat());
        assertEquals("sonnet", opts.getModel());
        assertEquals(Integer.valueOf(15), opts.getMaxTurns());
        assertEquals("opus", opts.getFallbackModel());
        assertEquals(Double.valueOf(2.5), opts.getMaxBudgetUsd());
        assertEquals(PrintModeOptions.PermissionMode.DONT_ASK, opts.getPermissionMode());

        // 工具规则
        assertEquals(3, opts.getAllowedTools().size()); // Read, Grep, Glob
        assertEquals(1, opts.getAllowedToolRules().size()); // Bash(git log *)
        assertEquals(0, opts.getDisallowedTools().size()); // no plain disallowed
        assertEquals(1, opts.getDisallowedToolRules().size()); // Bash(rm *)

        // json-schema
        assertNotNull(opts.getJsonSchema());
        assertTrue(opts.getJsonSchema().contains("functions"));
    }

    @Test
    @DisplayName("CI 安全模式组合：只读工具 + dontAsk + 预算限制")
    public void testCiSafeModeCombination() {
        MultiMap<String> argx = buildArgx(
                "run", "Review this PR", "output-format=json",
                "allowedTools=Read,Grep,Glob",
                "permission-mode=dontAsk",
                "max-budget-usd=1.0",
                "max-turns=10",
                "model=sonnet");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals("Review this PR", opts.getPrompt());
        assertEquals(3, opts.getAllowedTools().size());
        assertEquals(PrintModeOptions.PermissionMode.DONT_ASK, opts.getPermissionMode());
        assertEquals(Double.valueOf(1.0), opts.getMaxBudgetUsd());
        assertEquals(Integer.valueOf(10), opts.getMaxTurns());
    }

    @Test
    @DisplayName("bare 模式组合")
    public void testBareModeCombination() {
        MultiMap<String> argx = buildArgx(
                "run", "Quick task", "bare",
                "output-format=json",
                "model=sonnet");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertTrue(opts.isBare());
        assertEquals("Quick task", opts.getPrompt());
        assertEquals(PrintModeOptions.OutputFormat.JSON, opts.getOutputFormat());
    }

    @Test
    @DisplayName("多 add-dir + fallback-model 组合")
    public void testAddDirAndFallbackCombination() {
        MultiMap<String> argx = buildArgx(
                "run", "Cross-repo analysis",
                "add-dir=/repo/a", "add-dir=/repo/b",
                "fallback-model=haiku",
                "output-format=stream-json", "verbose");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(2, opts.getAddDirs().size());
        assertEquals("/repo/a", opts.getAddDirs().get(0));
        assertEquals("/repo/b", opts.getAddDirs().get(1));
        assertEquals("haiku", opts.getFallbackModel());
        assertTrue(opts.isVerbose());
        assertEquals(PrintModeOptions.OutputFormat.STREAM_JSON, opts.getOutputFormat());
    }

    @Test
    @DisplayName("plan 模式 + 只读工具规则组合")
    public void testPlanModeWithReadRules() {
        MultiMap<String> argx = buildArgx(
                "run", "Analyze the codebase",
                "permission-mode=plan",
                "allowedTools=Read,Grep,Glob,Bash(git log *)",
                "disallowedTools=Bash(rm *)",
                "output-format=json");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(PrintModeOptions.PermissionMode.PLAN, opts.getPermissionMode());
        assertEquals(3, opts.getAllowedTools().size());
        assertEquals(1, opts.getAllowedToolRules().size());
        assertEquals(1, opts.getDisallowedToolRules().size());
    }

    @Test
    @DisplayName("acceptEdits 模式组合")
    public void testAcceptEditsCombination() {
        MultiMap<String> argx = buildArgx(
                "run", "Fix the linting errors",
                "permission-mode=acceptEdits",
                "max-turns=20",
                "output-format=json");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(PrintModeOptions.PermissionMode.ACCEPT_EDITS, opts.getPermissionMode());
        assertEquals(Integer.valueOf(20), opts.getMaxTurns());
    }

    // ========== output-format 边界 ==========

    @Test
    @DisplayName("output-format 未知值回退为 TEXT")
    public void testOutputFormatUnknownValue() {
        MultiMap<String> argx = buildArgx("run", "test", "output-format=xml");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(PrintModeOptions.OutputFormat.TEXT, opts.getOutputFormat());
    }

    @Test
    @DisplayName("output-format 大写 JSON 不敏感")
    public void testOutputFormatCaseInsensitiveJson() {
        MultiMap<String> argx = buildArgx("run", "test", "output-format=JSON");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(PrintModeOptions.OutputFormat.JSON, opts.getOutputFormat());
    }

    @Test
    @DisplayName("output-format 大写 STREAM-JSON 不敏感")
    public void testOutputFormatCaseInsensitiveStreamJson() {
        MultiMap<String> argx = buildArgx("run", "test", "output-format=STREAM-JSON");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(PrintModeOptions.OutputFormat.STREAM_JSON, opts.getOutputFormat());
    }

    // ========== permission-mode 边界 ==========

    @Test
    @DisplayName("permission-mode 未知值回退为 DEFAULT")
    public void testPermissionModeUnknownValue() {
        MultiMap<String> argx = buildArgx("run", "test", "permission-mode=unknownMode");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(PrintModeOptions.PermissionMode.DEFAULT, opts.getPermissionMode());
    }

    @Test
    @DisplayName("permission-mode 大写不敏感 PLAN")
    public void testPermissionModeCaseInsensitivePlan() {
        MultiMap<String> argx = buildArgx("run", "test", "permission-mode=PLAN");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(PrintModeOptions.PermissionMode.PLAN, opts.getPermissionMode());
    }

    @Test
    @DisplayName("permission-mode 大写不敏感 ACCEPT_EDITS")
    public void testPermissionModeCaseInsensitiveAcceptEdits() {
        MultiMap<String> argx = buildArgx("run", "test", "permission-mode=ACCEPTEDITS");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(PrintModeOptions.PermissionMode.ACCEPT_EDITS, opts.getPermissionMode());
    }

    // ========== shouldReadStdin / setPrompt ==========

    @Test
    @DisplayName("shouldReadStdin: 空字符串提示词应返回 true")
    public void testShouldReadStdinEmptyString() {
        PrintModeOptions opts = new PrintModeOptions();
        opts.setPrompt("", false);

        assertTrue(opts.shouldReadStdin());
    }

    @Test
    @DisplayName("shouldReadStdin: 非空提示词应返回 false")
    public void testShouldReadStdinNonEmpty() {
        PrintModeOptions opts = new PrintModeOptions();
        opts.setPrompt("hello", false);

        assertFalse(opts.shouldReadStdin());
    }

    @Test
    @DisplayName("setPrompt + isPromptFromStdin: 标记来自 stdin")
    public void testSetPromptFromStdin() {
        PrintModeOptions opts = new PrintModeOptions();
        opts.setPrompt("piped content", true);

        assertEquals("piped content", opts.getPrompt());
        assertTrue(opts.isPromptFromStdin());
    }

    @Test
    @DisplayName("setPrompt + isPromptFromStdin: 标记不来自 stdin")
    public void testSetPromptNotFromStdin() {
        PrintModeOptions opts = new PrintModeOptions();
        opts.setPrompt("arg content", false);

        assertEquals("arg content", opts.getPrompt());
        assertFalse(opts.isPromptFromStdin());
    }

    // ========== CSV 解析边界 ==========

    @Test
    @DisplayName("allowedTools CSV 中间空条目被忽略")
    public void testAllowedToolsCsvEmptyEntries() {
        MultiMap<String> argx = buildArgx("run", "test", "allowedTools=Read,,Grep");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(2, opts.getAllowedTools().size());
        assertTrue(opts.getAllowedTools().contains("Read"));
        assertTrue(opts.getAllowedTools().contains("Grep"));
    }

    @Test
    @DisplayName("allowedTools CSV 尾逗号被忽略")
    public void testAllowedToolsCsvTrailingComma() {
        MultiMap<String> argx = buildArgx("run", "test", "allowedTools=Read,");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(1, opts.getAllowedTools().size());
        assertTrue(opts.getAllowedTools().contains("Read"));
    }

    @Test
    @DisplayName("allowedTools CSV 前逗号被忽略")
    public void testAllowedToolsCsvLeadingComma() {
        MultiMap<String> argx = buildArgx("run", "test", "allowedTools=,Read");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(1, opts.getAllowedTools().size());
        assertTrue(opts.getAllowedTools().contains("Read"));
    }

    @Test
    @DisplayName("allowedTools 全空 CSV 返回空列表")
    public void testAllowedToolsCsvAllEmpty() {
        MultiMap<String> argx = buildArgx("run", "test", "allowedTools=,,");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertTrue(opts.getAllowedTools().isEmpty());
        assertTrue(opts.getAllowedToolRules().isEmpty());
    }

    // ========== disallowedTools 去重和默认值 ==========

    @Test
    @DisplayName("disallowedTools 重复参数去重")
    public void testDisallowedToolsDedup() {
        MultiMap<String> argx = buildArgx("run", "test",
                "disallowedTools=Write,Bash", "disallowedTools=Write,Edit");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        long writeCount = opts.getDisallowedTools().stream().filter(t -> t.equals("Write")).count();
        assertEquals(1, writeCount);
        assertEquals(3, opts.getDisallowedTools().size());
    }

    @Test
    @DisplayName("disallowedTools 默认为空")
    public void testDisallowedToolsDefaultEmpty() {
        MultiMap<String> argx = buildArgx("run", "test");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertTrue(opts.getDisallowedTools().isEmpty());
        assertTrue(opts.getDisallowedToolRules().isEmpty());
    }

    // ========== parseToolRule 边界 ==========

    @Test
    @DisplayName("parseToolRule: Bash() 空括号 → 正则不匹配, 整体作为工具名")
    public void testParseToolRuleEmptyParens() {
        PrintModeOptions.ToolRuleSpec spec = PrintModeOptions.parseToolRule("Bash()");

        assertNotNull(spec);
        // 正则 ^ (\w+)\((.+)\)$ 要求括号内至少一个字符，空括号不匹配
        assertEquals("Bash()", spec.getToolName());
        assertNull(spec.getPattern());
        assertFalse(spec.hasPattern());
    }

    @Test
    @DisplayName("parseToolRule: Bash(rm 无闭合括号 → 整体作为工具名")
    public void testParseToolRuleNoClosingParen() {
        PrintModeOptions.ToolRuleSpec spec = PrintModeOptions.parseToolRule("Bash(rm");

        assertNotNull(spec);
        assertEquals("Bash(rm", spec.getToolName());
        assertNull(spec.getPattern());
        assertFalse(spec.hasPattern());
    }

    // ========== flag 默认值 ==========

    @Test
    @DisplayName("verbose 默认为 false")
    public void testVerboseDefaultFalse() {
        MultiMap<String> argx = buildArgx("run", "test");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertFalse(opts.isVerbose());
    }

    @Test
    @DisplayName("continue 默认为 false")
    public void testContinueDefaultFalse() {
        MultiMap<String> argx = buildArgx("run", "test");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertFalse(opts.isContinueSession());
    }

    @Test
    @DisplayName("model 默认为 null")
    public void testModelDefaultNull() {
        MultiMap<String> argx = buildArgx("run", "test");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertNull(opts.getModel());
    }

    // ========== 数值边界 ==========

    @Test
    @DisplayName("max-turns=0")
    public void testMaxTurnsZero() {
        MultiMap<String> argx = buildArgx("run", "test", "max-turns=0");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(Integer.valueOf(0), opts.getMaxTurns());
    }

    @Test
    @DisplayName("max-turns 负值")
    public void testMaxTurnsNegative() {
        MultiMap<String> argx = buildArgx("run", "test", "max-turns=-1");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(Integer.valueOf(-1), opts.getMaxTurns());
    }

    @Test
    @DisplayName("max-budget-usd=0")
    public void testMaxBudgetUsdZero() {
        MultiMap<String> argx = buildArgx("run", "test", "max-budget-usd=0");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(Double.valueOf(0.0), opts.getMaxBudgetUsd());
    }

    @Test
    @DisplayName("max-budget-usd 负值")
    public void testMaxBudgetUsdNegative() {
        MultiMap<String> argx = buildArgx("run", "test", "max-budget-usd=-1.5");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(Double.valueOf(-1.5), opts.getMaxBudgetUsd());
    }

    @Test
    @DisplayName("max-turns 带空格的值")
    public void testMaxTurnsWithSpaces() {
        MultiMap<String> argx = buildArgx("run", "test", "max-turns= 5 ");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(Integer.valueOf(5), opts.getMaxTurns());
    }

    @Test
    @DisplayName("max-budget-usd 带空格的值")
    public void testMaxBudgetUsdWithSpaces() {
        MultiMap<String> argx = buildArgx("run", "test", "max-budget-usd= 3.5 ");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(Double.valueOf(3.5), opts.getMaxBudgetUsd());
    }

    // ========== 会话选项优先级 ==========

    @Test
    @DisplayName("会话优先级: resume + session-id 同时设置 → 两者都保留")
    public void testSessionResumeAndSessionIdBoth() {
        MultiMap<String> argx = buildArgx("run", "test",
                "resume=r-001", "session-id=s-002");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals("r-001", opts.getResumeSessionId());
        assertEquals("s-002", opts.getSessionId());
    }

    @Test
    @DisplayName("会话: continue + resume + session-id 三者同时设置")
    public void testSessionAllThreeSet() {
        MultiMap<String> argx = buildArgx("run", "test",
                "continue", "resume=r-001", "session-id=s-002");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertTrue(opts.isContinueSession());
        assertEquals("r-001", opts.getResumeSessionId());
        assertEquals("s-002", opts.getSessionId());
    }

    // ========== 提示词边界 ==========

    @Test
    @DisplayName("空字符串提示词")
    public void testEmptyStringPrompt() {
        MultiMap<String> argx = buildArgx("run", "");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        // 空字符串作为 prompt 被接受，但 shouldReadStdin 应返回 true
        assertTrue(opts.shouldReadStdin());
    }

    @Test
    @DisplayName("提示词含特殊字符")
    public void testPromptWithSpecialChars() {
        MultiMap<String> argx = buildArgx("run", "Fix the \"bug\" in function()'s code");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertNotNull(opts.getPrompt());
        assertTrue(opts.getPrompt().contains("\"bug\""));
        assertTrue(opts.getPrompt().contains("function()"));
    }

    // ========== 工具规则边界 ==========

    @Test
    @DisplayName("allowedTools 同一工具既有纯名又有规则 → 纯名不被添加到 plainTools")
    public void testAllowedToolsSameToolPlainAndRule() {
        MultiMap<String> argx = buildArgx("run", "test",
                "allowedTools=Bash,Bash(git log *)");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        // 纯工具名 Bash
        assertEquals(1, opts.getAllowedTools().size());
        assertTrue(opts.getAllowedTools().contains("Bash"));
        // 规则 Bash(git log *)
        assertEquals(1, opts.getAllowedToolRules().size());
        assertEquals("Bash", opts.getAllowedToolRules().get(0).getToolName());
        assertEquals("git log *", opts.getAllowedToolRules().get(0).getPattern());
    }

    @Test
    @DisplayName("allowedTools + disallowedTools 同工具规则交叉")
    public void testAllowedAndDisallowedCrossRules() {
        MultiMap<String> argx = buildArgx("run", "test",
                "allowedTools=Bash(git log *),Bash(git diff *)",
                "disallowedTools=Bash(rm *),Bash(git push *)");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertEquals(0, opts.getAllowedTools().size());
        assertEquals(2, opts.getAllowedToolRules().size());
        assertEquals(0, opts.getDisallowedTools().size());
        assertEquals(2, opts.getDisallowedToolRules().size());
    }

    // ========== 空参数边界 ==========

    @Test
    @DisplayName("仅 run 一个参数，无任何选项")
    public void testOnlyRunArg() {
        MultiMap<String> argx = buildArgx("run");
        PrintModeOptions opts = PrintModeOptions.parse(argx);

        assertNull(opts.getPrompt());
        assertTrue(opts.shouldReadStdin());
        assertEquals(PrintModeOptions.OutputFormat.TEXT, opts.getOutputFormat());
        assertEquals(PrintModeOptions.PermissionMode.DEFAULT, opts.getPermissionMode());
        assertFalse(opts.isVerbose());
        assertFalse(opts.isBare());
        assertFalse(opts.isContinueSession());
        assertNull(opts.getModel());
        assertNull(opts.getMaxTurns());
        assertNull(opts.getSessionId());
        assertNull(opts.getResumeSessionId());
        assertNull(opts.getFallbackModel());
        assertNull(opts.getJsonSchema());
        assertNull(opts.getMaxBudgetUsd());
        assertTrue(opts.getAllowedTools().isEmpty());
        assertTrue(opts.getDisallowedTools().isEmpty());
        assertTrue(opts.getAddDirs().isEmpty());
    }

    // ========== ToolRuleSpec equals/hashCode ==========

    @Test
    @DisplayName("ToolRuleSpec: hasPattern() 空字符串 pattern 返回 false")
    public void testToolRuleSpecHasPatternEmptyString() {
        PrintModeOptions.ToolRuleSpec spec = new PrintModeOptions.ToolRuleSpec("Bash", "");
        assertFalse(spec.hasPattern());
    }

    @Test
    @DisplayName("ToolRuleSpec: hasPattern() null 返回 false")
    public void testToolRuleSpecHasPatternNull() {
        PrintModeOptions.ToolRuleSpec spec = new PrintModeOptions.ToolRuleSpec("Bash", null);
        assertFalse(spec.hasPattern());
    }

    @Test
    @DisplayName("ToolRuleSpec: hasPattern() 非空 pattern 返回 true")
    public void testToolRuleSpecHasPatternNonEmpty() {
        PrintModeOptions.ToolRuleSpec spec = new PrintModeOptions.ToolRuleSpec("Bash", "rm *");
        assertTrue(spec.hasPattern());
    }

    // ========== 输入格式 / 常驻模式 ==========

    @Test
    @DisplayName("默认输入格式为 text，不是常驻模式")
    public void testInputFormatDefaultsToText() {
        PrintModeOptions opts = PrintModeOptions.parse(buildArgx("run", "hi"));

        assertEquals(PrintModeOptions.InputFormat.TEXT, opts.getInputFormat());
        assertFalse(opts.isStreamJsonInput());
        assertFalse(opts.isReplayUserMessages());
    }

    @Test
    @DisplayName("--input-format stream-json 被解析（run 下由 PrintMode 拒绝）")
    public void testInputFormatStreamJsonParsed() {
        PrintModeOptions opts = PrintModeOptions.parse(
                buildArgx("run", "--input-format", "stream-json"));

        assertEquals(PrintModeOptions.InputFormat.STREAM_JSON, opts.getInputFormat());
        assertTrue(opts.isStreamJsonInput());
    }

    @Test
    @DisplayName("--input-format 未知值回落 text")
    public void testInputFormatUnknownFallsBackToText() {
        PrintModeOptions opts = PrintModeOptions.parse(
                buildArgx("run", "--input-format", "yaml"));

        assertEquals(PrintModeOptions.InputFormat.TEXT, opts.getInputFormat());
    }

    @Test
    @DisplayName("--replay-user-messages 标志")
    public void testReplayUserMessagesFlag() {
        PrintModeOptions opts = PrintModeOptions.parse(
                buildArgx("stream", "--replay-user-messages"));

        assertTrue(opts.isReplayUserMessages());
    }

    @Test
    @DisplayName("parseStream 强制 JSONL 输入与输出")
    public void testParseStreamForcesJsonl() {
        PrintModeOptions opts = PrintModeOptions.parseStream(buildArgx("stream"));

        assertEquals(PrintModeOptions.InputFormat.STREAM_JSON, opts.getInputFormat());
        assertEquals(PrintModeOptions.OutputFormat.STREAM_JSON, opts.getOutputFormat());
        assertTrue(opts.isStreamJsonInput());
    }

    @Test
    @DisplayName("parseStream 忽略 --output-format text（text 无法表达轮次边界）")
    public void testParseStreamIgnoresTextOutputFormat() {
        PrintModeOptions opts = PrintModeOptions.parseStream(
                buildArgx("stream", "--output-format", "text"));

        assertEquals(PrintModeOptions.OutputFormat.STREAM_JSON, opts.getOutputFormat());
    }

    @Test
    @DisplayName("parseStream 丢弃位置提示词（每轮都来自 stdin）")
    public void testParseStreamDropsPositionalPrompt() {
        PrintModeOptions opts = PrintModeOptions.parseStream(buildArgx("stream", "你好"));

        assertNull(opts.getPrompt());
        assertTrue(opts.shouldReadStdin());
    }

    @Test
    @DisplayName("parseStream 保留其余选项语义（模型/权限/预算/会话）")
    public void testParseStreamKeepsOtherOptions() {
        PrintModeOptions opts = PrintModeOptions.parseStream(buildArgx(
                "stream", "--model", "sonnet", "--permission-mode", "dontAsk",
                "--max-budget-usd", "5.0", "--session-id", "s-1",
                "--max-turns", "12", "--verbose"));

        assertEquals("sonnet", opts.getModel());
        assertEquals(PrintModeOptions.PermissionMode.DONT_ASK, opts.getPermissionMode());
        assertEquals(5.0, opts.getMaxBudgetUsd(), 0.0001);
        assertEquals("s-1", opts.getSessionId());
        assertEquals(12, opts.getMaxTurns());
        assertTrue(opts.isVerbose());
    }
}
