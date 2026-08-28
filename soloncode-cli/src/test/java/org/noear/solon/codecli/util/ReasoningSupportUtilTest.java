package org.noear.solon.codecli.util;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReasoningEffortSupport 语义与能力探测回归。
 */
public class ReasoningSupportUtilTest {

    @Test
    public void normalizeEffort_autoAndEmpty() {
        assertNull(ReasoningSupportUtil.normalizeEffort(null));
        assertNull(ReasoningSupportUtil.normalizeEffort(""));
        assertNull(ReasoningSupportUtil.normalizeEffort("  "));
        assertNull(ReasoningSupportUtil.normalizeEffort("auto"));
        assertNull(ReasoningSupportUtil.normalizeEffort("AUTO"));
        assertEquals("high", ReasoningSupportUtil.normalizeEffort("HIGH"));
        assertEquals("none", ReasoningSupportUtil.normalizeEffort("none"));
        assertEquals("none", ReasoningSupportUtil.normalizeEffort("off"));
        assertEquals("none", ReasoningSupportUtil.normalizeEffort("false"));
        assertNull(ReasoningSupportUtil.normalizeEffort("ultra"));
    }

    @Test
    public void resolveEffectiveEffort_autoDoesNotInjectDefault() {
        ReasoningSupportUtil.ModelCapability cap = new ReasoningSupportUtil.ModelCapability();
        cap.supportsReasoning = true;
        cap.reasoningEfforts = Arrays.asList("low", "medium", "high", "max");
        cap.defaultReasoningEffort = "high";

        String effort = ReasoningSupportUtil.resolveEffectiveEffort(
                null, null, cap, false);
        assertNull(effort, "auto must not inject capability default");
    }

    @Test
    public void resolveEffectiveEffort_sessionUser() {
        ReasoningSupportUtil.ModelCapability cap = new ReasoningSupportUtil.ModelCapability();
        cap.supportsReasoning = true;
        cap.reasoningEfforts = Arrays.asList("low", "medium", "high", "max");

        String effort = ReasoningSupportUtil.resolveEffectiveEffort(
                null, "high", cap, false);
        assertEquals("high", effort);
    }

    @Test
    public void resolveEffectiveEffort_requestPresentEmptyMeansAuto() {
        ReasoningSupportUtil.ModelCapability cap = new ReasoningSupportUtil.ModelCapability();
        cap.supportsReasoning = true;
        cap.reasoningEfforts = Arrays.asList("low", "medium", "high");
        cap.defaultReasoningEffort = "medium";

        // request 显式空 = auto，即使 session 有 high
        String effort = ReasoningSupportUtil.resolveEffectiveEffort(
                "", "high", cap, true);
        assertNull(effort);
    }

    @Test
    public void clampEffort_maxToHighForThreeTier() {
        ReasoningSupportUtil.ModelCapability cap = new ReasoningSupportUtil.ModelCapability();
        cap.supportsReasoning = true;
        cap.reasoningEfforts = Arrays.asList("low", "medium", "high");
        assertEquals("high", ReasoningSupportUtil.clampEffort("max", cap));
    }

    @Test
    public void looksLikeReasoningModel_tightened() {
        assertTrue(ReasoningSupportUtil.looksLikeReasoningModel("anthropic claude-sonnet-4"));
        assertTrue(ReasoningSupportUtil.looksLikeReasoningModel("openai-responses gpt-5"));
        assertTrue(ReasoningSupportUtil.looksLikeReasoningModel("deepseek-r1"));
        assertFalse(ReasoningSupportUtil.looksLikeReasoningModel("deepseek-chat"));
        // 裸 r1 不再匹配
        assertFalse(ReasoningSupportUtil.looksLikeReasoningModel("my-r1-bot"));
        // haiku 默认不支持
        assertFalse(ReasoningSupportUtil.looksLikeReasoningModel("anthropic claude-3-haiku"));
        // 普通 gpt-4o
        assertFalse(ReasoningSupportUtil.looksLikeReasoningModel("openai gpt-4o"));
        // 仅 standard=anthropic、无具体型号名 → 不误报
        assertFalse(ReasoningSupportUtil.looksLikeReasoningModel("anthropic"));
        // 别名 sonnet + anthropic
        assertTrue(ReasoningSupportUtil.looksLikeReasoningModel("anthropic sonnet-4.6"));
    }

    @Test
    public void resolveCapability_defaultsSupportsReasoningTrue() {
        ChatConfig plain = new ChatConfig();
        plain.setName("plain");
        plain.setModel("gpt-4o");
        plain.setStandard("openai");
        
        ReasoningSupportUtil.ModelCapability cap = ReasoningSupportUtil.resolveCapability(plain);
        assertTrue(cap.supportsReasoning, "supportsReasoning defaults to true for any configured model");
        assertTrue(cap.reasoningEfforts.contains("low"));
        assertTrue(cap.reasoningEfforts.contains("max"));
        assertEquals("medium", cap.defaultReasoningEffort);
        
        ReasoningSupportUtil.ModelCapability nullCap = ReasoningSupportUtil.resolveCapability((ChatConfig) null);
        assertFalse(nullCap.supportsReasoning, "null config remains unsupported");
    }
    
    @Test
    public void resolveCapability_fromDefaultOptions() {
        ChatConfig config = new ChatConfig();
        config.setName("plain");
        config.setModel("gpt-4o");
        config.setStandard("openai");
        Map<String, Object> opts = new LinkedHashMap<String, Object>();
        opts.put("thinking", new LinkedHashMap<String, Object>());
        opts.put("reasoning_effort", "high");
        config.getModelOptions().optionSet(opts);
    
        ReasoningSupportUtil.ModelCapability cap = ReasoningSupportUtil.resolveCapability(config);
        assertTrue(cap.supportsReasoning);
        assertEquals("high", cap.defaultReasoningEffort);
    }
    
    @Test
    public void resolveCapability_fromNameModelStandardSnapshot() {
        Map<String, Object> opts = new LinkedHashMap<String, Object>();
        opts.put("thinking", true);
        ReasoningSupportUtil.ModelCapability cap = ReasoningSupportUtil.resolveCapability(
                "plain", "gpt-4o", "openai", opts);
        assertTrue(cap.supportsReasoning);
    
        // 无 options 的普通模型也默认 supportsReasoning=true
        ReasoningSupportUtil.ModelCapability noOpt = ReasoningSupportUtil.resolveCapability(
                "plain", "gpt-4o", "openai", null);
        assertTrue(noOpt.supportsReasoning);
        assertEquals("medium", noOpt.defaultReasoningEffort);
    }

    @Test
    public void findEngineConfig_byNameOrModel() {
        ChatConfig a = new ChatConfig();
        a.setName("sonnet");
        a.setModel("claude-sonnet-4.6");
        ChatConfig b = new ChatConfig();
        b.setName("flash");
        b.setModel("deepseek-v4-flash");

        assertSame(a, ReasoningSupportUtil.findEngineConfig(Arrays.asList(a, b), "sonnet"));
        assertSame(a, ReasoningSupportUtil.findEngineConfig(Arrays.asList(a, b), "claude-sonnet-4.6"));
        assertNull(ReasoningSupportUtil.findEngineConfig(Arrays.asList(a, b), "missing"));
    }

    @Test
    public void resolveForUi_userOrAuto() {
        ReasoningSupportUtil.ModelCapability cap = new ReasoningSupportUtil.ModelCapability();
        cap.supportsReasoning = true;
        cap.reasoningEfforts = Arrays.asList("low", "medium", "high", "max");
        cap.defaultReasoningEffort = "high";

        assertEquals("high", ReasoningSupportUtil.resolveForUi("high", cap));
        assertNull(ReasoningSupportUtil.resolveForUi(null, cap));
        assertNull(ReasoningSupportUtil.resolveForUi("", cap));
    }

    @Test
    public void dialectMappingTables_anthropicAndResponses() {
        assertEquals(4000, ReasoningSupportUtil.mapAnthropicBudgetTokens("low"));
        assertEquals(10000, ReasoningSupportUtil.mapAnthropicBudgetTokens("medium"));
        assertEquals(20000, ReasoningSupportUtil.mapAnthropicBudgetTokens("high"));
        assertEquals(32000, ReasoningSupportUtil.mapAnthropicBudgetTokens("max"));
        assertEquals(-1, ReasoningSupportUtil.mapAnthropicBudgetTokens("auto"));

        assertEquals("low", ReasoningSupportUtil.mapResponsesReasoningEffort("low"));
        assertEquals("high", ReasoningSupportUtil.mapResponsesReasoningEffort("max"));
        assertNull(ReasoningSupportUtil.mapResponsesReasoningEffort("auto"));
    }

    @Test
    public void normalizeThinkingMode_variants() {
        assertNull(ReasoningSupportUtil.normalizeThinkingMode(null));
        assertNull(ReasoningSupportUtil.normalizeThinkingMode(""));
        assertNull(ReasoningSupportUtil.normalizeThinkingMode("  "));
        assertNull(ReasoningSupportUtil.normalizeThinkingMode("auto"));
        assertNull(ReasoningSupportUtil.normalizeThinkingMode("AUTO"));
        assertEquals("on", ReasoningSupportUtil.normalizeThinkingMode("on"));
        assertEquals("on", ReasoningSupportUtil.normalizeThinkingMode("ON"));
        assertEquals("on", ReasoningSupportUtil.normalizeThinkingMode("enabled"));
        assertEquals("on", ReasoningSupportUtil.normalizeThinkingMode("true"));
        assertEquals("on", ReasoningSupportUtil.normalizeThinkingMode("1"));
        assertEquals("off", ReasoningSupportUtil.normalizeThinkingMode("off"));
        assertEquals("off", ReasoningSupportUtil.normalizeThinkingMode("OFF"));
        assertEquals("off", ReasoningSupportUtil.normalizeThinkingMode("disabled"));
        assertEquals("off", ReasoningSupportUtil.normalizeThinkingMode("false"));
        assertEquals("off", ReasoningSupportUtil.normalizeThinkingMode("0"));
        assertNull(ReasoningSupportUtil.normalizeThinkingMode("maybe"));
    }

    private ReActOptionsAmend newAmend() {
        ChatConfig config = new ChatConfig();
        config.setModel("gpt-4o");
        config.setApiUrl("https://api.openai.com/v1");
        ChatModel model = new ChatModel(config);
        return new ReActOptionsAmend(new ReActOptions(model));
    }

    @Test
    public void applyToOptions_thinkingOffAndEffortIndependent() {
        // thinkingMode 与 reasoningEffort 完全独立：两者各自写入 options
        ReActOptionsAmend amend = newAmend();
        ReasoningSupportUtil.applyToOptions(amend, "off", "high");
        Map<String, Object> opts = amend.options();
        assertEquals(false, opts.get("thinking"), "off 必须写 thinking(false)");
        assertEquals("high", opts.get("reasoning_effort"), "thinkingMode 与 effort 独立，effort 同样写入");
    }

    @Test
    public void applyToOptions_thinkingOnWithEffort() {
        // thinkingMode=on + effort：写 thinking(true) + reasoning_effort
        ReActOptionsAmend amend = newAmend();
        ReasoningSupportUtil.applyToOptions(amend, "on", "high");
        Map<String, Object> opts = amend.options();
        assertEquals(true, opts.get("thinking"), "on 必须写 thinking(true)");
        assertEquals("high", opts.get("reasoning_effort"));
    }

    @Test
    public void applyToOptions_thinkingOnIgnoresNoneEffort() {
        // thinkingMode=on + 旧 none effort：写 thinking(true)，忽略非法 effort
        ReActOptionsAmend amend = newAmend();
        ReasoningSupportUtil.applyToOptions(amend, "on", "none");
        Map<String, Object> opts = amend.options();
        assertEquals(true, opts.get("thinking"));
        assertNull(opts.get("reasoning_effort"), "none 不是合法 effort，不得写入");
    }

    @Test
    public void applyToOptions_noneEffortIgnored() {
        ReActOptionsAmend amend = newAmend();
        ReasoningSupportUtil.applyToOptions(amend, null, "none");
        Map<String, Object> opts = amend.options();
        assertNull(opts.get("reasoning_effort"), "none 不是合法 effort，不得写入");
    }

    @Test
    public void applyToOptions_effortOnlyImplicitThinking() {
        // 无 thinkingMode：仅写 effort（方言层因 effort 隐式开启思考）
        ReActOptionsAmend amend = newAmend();
        ReasoningSupportUtil.applyToOptions(amend, null, "medium");
        Map<String, Object> opts = amend.options();
        assertEquals("medium", opts.get("reasoning_effort"));
        assertFalse(opts.containsKey("thinking"), "未设置思考模式时不写 thinking 键");
    }

    @Test
    public void applyToOptions_invalidEffortIgnored() {
        // none 不是合法 effort：ModelOptionsAmend 会忽略非法值，避免污染请求
        ReActOptionsAmend amend = newAmend();
        ReasoningSupportUtil.applyToOptions(amend, null, "none");
        assertNull(amend.options().get("reasoning_effort"), "none 不得写入 reasoning_effort");
    }
}
