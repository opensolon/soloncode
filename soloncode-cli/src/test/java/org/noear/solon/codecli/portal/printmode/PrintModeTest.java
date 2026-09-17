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
 * PrintMode 单元测试
 *
 * <p>测试 PrintMode 的纯逻辑部分：费用估算、JSON 提取、退出码常量等。
 * 不涉及 HarnessEngine 的集成测试（需要完整 Solon 容器环境）。</p>
 *
 * @author noear
 */
public class PrintModeTest {

    // ========== 退出码常量 ==========

    @Test
    @DisplayName("退出码: SUCCESS=0")
    public void testExitCodeSuccess() {
        assertEquals(0, PrintMode.EXIT_SUCCESS);
    }

    @Test
    @DisplayName("退出码: ERROR=1")
    public void testExitCodeError() {
        assertEquals(1, PrintMode.EXIT_ERROR);
    }

    @Test
    @DisplayName("退出码: MAX_TURNS=2")
    public void testExitCodeMaxTurns() {
        assertEquals(2, PrintMode.EXIT_MAX_TURNS);
    }

    @Test
    @DisplayName("退出码: NO_PROMPT=3")
    public void testExitCodeNoPrompt() {
        assertEquals(3, PrintMode.EXIT_NO_PROMPT);
    }

    @Test
    @DisplayName("退出码: BUDGET_EXCEEDED=4")
    public void testExitCodeBudgetExceeded() {
        assertEquals(4, PrintMode.EXIT_BUDGET_EXCEEDED);
    }

    @Test
    @DisplayName("退出码互不相同")
    public void testExitCodesDistinct() {
        assertNotEquals(PrintMode.EXIT_SUCCESS, PrintMode.EXIT_ERROR);
        assertNotEquals(PrintMode.EXIT_SUCCESS, PrintMode.EXIT_MAX_TURNS);
        assertNotEquals(PrintMode.EXIT_SUCCESS, PrintMode.EXIT_NO_PROMPT);
        assertNotEquals(PrintMode.EXIT_SUCCESS, PrintMode.EXIT_BUDGET_EXCEEDED);
        assertNotEquals(PrintMode.EXIT_ERROR, PrintMode.EXIT_MAX_TURNS);
        assertNotEquals(PrintMode.EXIT_ERROR, PrintMode.EXIT_NO_PROMPT);
        assertNotEquals(PrintMode.EXIT_ERROR, PrintMode.EXIT_BUDGET_EXCEEDED);
        assertNotEquals(PrintMode.EXIT_MAX_TURNS, PrintMode.EXIT_NO_PROMPT);
        assertNotEquals(PrintMode.EXIT_MAX_TURNS, PrintMode.EXIT_BUDGET_EXCEEDED);
        assertNotEquals(PrintMode.EXIT_NO_PROMPT, PrintMode.EXIT_BUDGET_EXCEEDED);
    }

    // ========== 费用估算 ==========

    @Test
    @DisplayName("estimateCostUsd: null metrics 返回 0")
    public void testEstimateCostNullMetrics() {
        assertEquals(0.0, PrintMode.estimateCostUsd(null), 0.000001);
    }

    @Test
    @DisplayName("estimateCostUsd: 1000 input + 1000 output = 0.018")
    public void testEstimateCostBasic() {
        // 1000 input * $0.003/1K + 1000 output * $0.015/1K = $0.003 + $0.015 = $0.018
        MockMetrics metrics = new MockMetrics(2000, 1000, 1000, 5000);
        double cost = PrintMode.estimateCostUsd(metrics);
        assertEquals(0.018, cost, 0.000001);
    }

    @Test
    @DisplayName("estimateCostUsd: 零 token 返回 0")
    public void testEstimateCostZeroTokens() {
        MockMetrics metrics = new MockMetrics(0, 0, 0, 0);
        assertEquals(0.0, PrintMode.estimateCostUsd(metrics), 0.000001);
    }

    @Test
    @DisplayName("estimateCostUsd: 大量 token")
    public void testEstimateCostLargeTokens() {
        // 100K input * $0.003/1K = $0.3
        // 50K output * $0.015/1K = $0.75
        // total = $1.05
        MockMetrics metrics = new MockMetrics(150000, 100000, 50000, 60000);
        double cost = PrintMode.estimateCostUsd(metrics);
        assertEquals(1.05, cost, 0.000001);
    }

    @Test
    @DisplayName("estimateCostUsd: 只有 input token")
    public void testEstimateCostInputOnly() {
        MockMetrics metrics = new MockMetrics(5000, 5000, 0, 1000);
        double cost = PrintMode.estimateCostUsd(metrics);
        // 5000 / 1000 * 0.003 = 0.015
        assertEquals(0.015, cost, 0.000001);
    }

    @Test
    @DisplayName("estimateCostUsd: 只有 output token")
    public void testEstimateCostOutputOnly() {
        MockMetrics metrics = new MockMetrics(3000, 0, 3000, 2000);
        double cost = PrintMode.estimateCostUsd(metrics);
        // 3000 / 1000 * 0.015 = 0.045
        assertEquals(0.045, cost, 0.000001);
    }

    // ========== roundCost ==========

    @Test
    @DisplayName("roundCost: 精确到 6 位小数")
    public void testRoundCost() {
        assertEquals(0.018, PrintMode.roundCost(0.018), 0.0000001);
        assertEquals(0.015, PrintMode.roundCost(0.0150001), 0.0000001);
        assertEquals(1.05, PrintMode.roundCost(1.0500004), 0.0000001);
    }

    @Test
    @DisplayName("roundCost: 零")
    public void testRoundCostZero() {
        assertEquals(0.0, PrintMode.roundCost(0.0), 0.0000001);
    }

    @Test
    @DisplayName("roundCost: 超过 6 位的精度被截断")
    public void testRoundCostTruncation() {
        double original = 0.123456789;
        double rounded = PrintMode.roundCost(original);
        // 0.123457 (6 位)
        assertEquals(0.123457, rounded, 0.0000001);
    }

    // ========== extractJsonBlock ==========

    @Test
    @DisplayName("extractJsonBlock: ```json 代码块")
    public void testExtractJsonBlockCodeFence() {
        String text = "Here is the result:\n```json\n{\"name\":\"test\"}\n```\nDone.";
        String result = PrintMode.extractJsonBlock(text);
        assertEquals("{\"name\":\"test\"}", result);
    }

    @Test
    @DisplayName("extractJsonBlock: ``` 代码块（无语言标识）")
    public void testExtractJsonBlockPlainFence() {
        String text = "Result:\n```\n{\"a\":1}\n```";
        String result = PrintMode.extractJsonBlock(text);
        assertEquals("{\"a\":1}", result);
    }

    @Test
    @DisplayName("extractJsonBlock: 裸 JSON 对象")
    public void testExtractJsonBlockBareObject() {
        String text = "The answer is {\"key\":\"value\"} as shown.";
        String result = PrintMode.extractJsonBlock(text);
        assertEquals("{\"key\":\"value\"}", result);
    }

    @Test
    @DisplayName("extractJsonBlock: 裸 JSON 数组")
    public void testExtractJsonBlockBareArray() {
        String text = "Functions: [\"func1\",\"func2\",\"func3\"]";
        String result = PrintMode.extractJsonBlock(text);
        assertEquals("[\"func1\",\"func2\",\"func3\"]", result);
    }

    @Test
    @DisplayName("extractJsonBlock: 嵌套 JSON 对象")
    public void testExtractJsonBlockNested() {
        String text = "Result: {\"outer\":{\"inner\":\"value\"}} end";
        String result = PrintMode.extractJsonBlock(text);
        assertEquals("{\"outer\":{\"inner\":\"value\"}}", result);
    }

    @Test
    @DisplayName("extractJsonBlock: null 输入返回 null")
    public void testExtractJsonBlockNull() {
        assertNull(PrintMode.extractJsonBlock(null));
    }

    @Test
    @DisplayName("extractJsonBlock: 无 JSON 内容返回 null")
    public void testExtractJsonBlockNoJson() {
        assertNull(PrintMode.extractJsonBlock("Just plain text, no JSON here."));
    }

    @Test
    @DisplayName("extractJsonBlock: 空字符串返回 null")
    public void testExtractJsonBlockEmpty() {
        assertNull(PrintMode.extractJsonBlock(""));
    }

    @Test
    @DisplayName("extractJsonBlock: 数组优先于对象（对象在数组之前）")
    public void testExtractJsonBlockObjectBeforeArray() {
        // { 在 [ 之前，应提取对象
        String text = "First {\"a\":1} then [\"b\"]";
        String result = PrintMode.extractJsonBlock(text);
        assertEquals("{\"a\":1}", result);
    }

    @Test
    @DisplayName("extractJsonBlock: 数组优先于对象（数组在对象之前）")
    public void testExtractJsonBlockArrayBeforeObject() {
        // [ 在 { 之前，应提取数组
        String text = "First [1,2] then {\"a\":1}";
        String result = PrintMode.extractJsonBlock(text);
        assertEquals("[1,2]", result);
    }

    // ========== 成本常量 ==========

    @Test
    @DisplayName("成本常量: COST_PER_1K_INPUT_TOKENS > 0")
    public void testCostPerInputTokens() {
        assertTrue(PrintMode.COST_PER_1K_INPUT_TOKENS > 0);
    }

    @Test
    @DisplayName("成本常量: COST_PER_1K_OUTPUT_TOKENS > 0")
    public void testCostPerOutputTokens() {
        assertTrue(PrintMode.COST_PER_1K_OUTPUT_TOKENS > 0);
    }

    @Test
    @DisplayName("成本常量: output 比 input 贵")
    public void testOutputMoreExpensive() {
        assertTrue(PrintMode.COST_PER_1K_OUTPUT_TOKENS > PrintMode.COST_PER_1K_INPUT_TOKENS);
    }

    // ========== Mock Metrics ==========

    /**
     * 简单的 Metrics mock，用于费用估算测试
     */
    private static class MockMetrics extends org.noear.solon.ai.agent.trace.Metrics {
        private final long totalTokens;
        private final long promptTokens;
        private final long completionTokens;
        private final long totalDuration;

        MockMetrics(long totalTokens, long promptTokens, long completionTokens, long totalDuration) {
            this.totalTokens = totalTokens;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalDuration = totalDuration;
        }

        @Override
        public long getTotalTokens() {
            return totalTokens;
        }

        @Override
        public long getPromptTokens() {
            return promptTokens;
        }

        @Override
        public long getCompletionTokens() {
            return completionTokens;
        }

        @Override
        public long getTotalDuration() {
            return totalDuration;
        }
    }

    // ========== clearThink ==========

    @Test
    @DisplayName("clearThink: null 输入返回 null")
    public void testClearThinkNull() {
        assertNull(PrintMode.clearThink(null));
    }

    @Test
    @DisplayName("clearThink: 无 think 标签原样返回")
    public void testClearThinkNoTags() {
        assertEquals("Hello world", PrintMode.clearThink("Hello world"));
    }

    @Test
    @DisplayName("clearThink: 移除 <think></think> 标签（保留内容）")
    public void testClearThinkOpenCloseTags() {
        String input = "<think>internal reasoning</think>Final answer";
        // clearThink 只移除标签本身，不移除标签间内容
        assertEquals("internal reasoningFinal answer", PrintMode.clearThink(input));
    }

    @Test
    @DisplayName("clearThink: 移除 </think> 闭合标签")
    public void testClearThinkCloseTagOnly() {
        String input = "Some text</think>rest";
        assertEquals("Some textrest", PrintMode.clearThink(input));
    }

    @Test
    @DisplayName("clearThink: 移除 <think> 开标签")
    public void testClearThinkOpenTagOnly() {
        String input = "Some text<think>rest";
        assertEquals("Some textrest", PrintMode.clearThink(input));
    }

    @Test
    @DisplayName("clearThink: 标签带空格 < think > 和 < /think>")
    public void testClearThinkTagsWithSpaces() {
        // 正则 <\s*/?think\s*> 支持 < think > 和 < /think>，但不支持 < / think >
        String input = "< think >internal< /think>Final";
        assertEquals("internalFinal", PrintMode.clearThink(input));
    }

    @Test
    @DisplayName("clearThink: 多行 think 内容（标签移除, 内容保留）")
    public void testClearThinkMultiLine() {
        String input = "<think>line1\nline2\nline3</think>Answer";
        assertEquals("line1\nline2\nline3Answer", PrintMode.clearThink(input));
    }

    @Test
    @DisplayName("clearThink: 多组 think 标签（内容保留）")
    public void testClearThinkMultipleTags() {
        String input = "<think>a</think>mid<think>b</think>end";
        assertEquals("amidbend", PrintMode.clearThink(input));
    }

    @Test
    @DisplayName("clearThink: 空字符串")
    public void testClearThinkEmptyString() {
        assertEquals("", PrintMode.clearThink(""));
    }

    @Test
    @DisplayName("clearThink: 只有 think 标签")
    public void testClearThinkOnlyTags() {
        assertEquals("", PrintMode.clearThink("<think></think>"));
    }

    // ========== extractJsonBlock 边界 ==========

    @Test
    @DisplayName("extractJsonBlock: 不完整的 ```json 代码块（无闭合）")
    public void testExtractJsonBlockIncompleteFence() {
        String text = "Result:\n```json\n{\"name\":\"test\"}";
        String result = PrintMode.extractJsonBlock(text);
        // 没有闭合 ```，回退到裸 JSON 提取
        assertNotNull(result);
        assertTrue(result.contains("\"name\""));
    }

    @Test
    @DisplayName("extractJsonBlock: 空的 ```json 代码块")
    public void testExtractJsonBlockEmptyFence() {
        String text = "Result:\n```json\n```\nDone.";
        String result = PrintMode.extractJsonBlock(text);
        // 内容为空字符串
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("extractJsonBlock: 多个代码块取第一个")
    public void testExtractJsonBlockMultipleFences() {
        String text = "First:\n```json\n{\"a\":1}\n```\nSecond:\n```json\n{\"b\":2}\n```";
        String result = PrintMode.extractJsonBlock(text);
        assertEquals("{\"a\":1}", result);
    }

    @Test
    @DisplayName("extractJsonBlock: 代码块后有多余文本")
    public void testExtractJsonBlockTextAfterFence() {
        String text = "```json\n{\"x\":1}\n```\nThis is trailing text.";
        String result = PrintMode.extractJsonBlock(text);
        assertEquals("{\"x\":1}", result);
    }

    @Test
    @DisplayName("extractJsonBlock: 只有空白字符")
    public void testExtractJsonBlockWhitespaceOnly() {
        assertNull(PrintMode.extractJsonBlock("   \n\t  \n  "));
    }

    @Test
    @DisplayName("extractJsonBlock: 深度嵌套 JSON")
    public void testExtractJsonBlockDeeplyNested() {
        String json = "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":\"deep\"}}}}}";
        String text = "Result: " + json + " end";
        String result = PrintMode.extractJsonBlock(text);
        assertEquals(json, result);
    }

    @Test
    @DisplayName("extractJsonBlock: JSON 含字符串中的花括号（已知限制：简单计数法）")
    public void testExtractJsonBlockBraceInString() {
        // 简单花括号计数法无法区分字符串内的 }，这是已知限制
        String text = "Output: {\"msg\":\"contains } char\"}";
        String result = PrintMode.extractJsonBlock(text);
        // 提取到第一个 } 匹配处为止
        assertNotNull(result);
        assertTrue(result.startsWith("{\"msg\""));
    }

    @Test
    @DisplayName("extractJsonBlock: ``` 无语言标识行")
    public void testExtractJsonBlockPlainFenceNoLang() {
        String text = "Result:\n```\n{\"plain\":true}\n```";
        String result = PrintMode.extractJsonBlock(text);
        assertEquals("{\"plain\":true}", result);
    }

    @Test
    @DisplayName("extractJsonBlock: ``` 后直接换行有 JSON")
    public void testExtractJsonBlockFenceImmediateNewline() {
        String text = "```\n[1, 2, 3]\n```";
        String result = PrintMode.extractJsonBlock(text);
        assertEquals("[1, 2, 3]", result);
    }

    // ========== estimateCostUsd 边界 ==========

    @Test
    @DisplayName("estimateCostUsd: 负 promptTokens（异常 metrics）")
    public void testEstimateCostNegativePromptTokens() {
        MockMetrics metrics = new MockMetrics(-1000, -1000, 0, 0);
        double cost = PrintMode.estimateCostUsd(metrics);
        // -1000/1000 * 0.003 = -0.003
        assertEquals(-0.003, cost, 0.000001);
    }

    // ========== roundCost 边界 ==========

    @Test
    @DisplayName("roundCost: 负值")
    public void testRoundCostNegative() {
        double result = PrintMode.roundCost(-0.123456789);
        assertEquals(-0.123457, result, 0.0000001);
    }

    @Test
    @DisplayName("roundCost: 大数值（不进位）")
    public void testRoundCostLargeValue() {
        // 999.999999 * 1000000 = 999999999.0, round = 999999999, / 1000000 = 999.999999
        double result = PrintMode.roundCost(999.999999);
        assertEquals(999.999999, result, 0.0000001);
    }

    // ========== run 的单次语义边界 ==========

    @Test
    @DisplayName("run 拒绝 --input-format stream-json，且在碰到引擎前就拒")
    public void testRunRejectsStreamJsonInput() {
        PrintModeOptions opts = PrintModeOptions.parse(
                MultiMap.from(new String[]{"run", "hi", "--input-format", "stream-json"}));

        // engine/settings 传 null：若校验不在最前面，这里会 NPE 而不是返回退出码
        int exitCode = new PrintMode(null, null, opts).execute();

        assertEquals(PrintMode.EXIT_ERROR, exitCode);
    }

    @Test
    @DisplayName("退出码推导优先级：预算 > 错误 > 轮次超限 > 成功")
    public void testExitCodePrecedence() {
        assertEquals(PrintMode.EXIT_SUCCESS, PrintMode.exitCodeOf(result(false, false, false)));
        assertEquals(PrintMode.EXIT_MAX_TURNS, PrintMode.exitCodeOf(result(false, false, true)));
        assertEquals(PrintMode.EXIT_ERROR, PrintMode.exitCodeOf(result(false, true, true)));
        assertEquals(PrintMode.EXIT_BUDGET_EXCEEDED, PrintMode.exitCodeOf(result(true, true, true)));
    }

    private static PrintMode.PrintResult result(boolean budgetExceeded, boolean error, boolean maxTurns) {
        PrintMode.PrintResult r = new PrintMode.PrintResult();
        r.budgetExceeded = budgetExceeded;
        r.error = error ? new IllegalStateException("boom") : null;
        r.maxTurnsExceeded = maxTurns;
        return r;
    }
}
