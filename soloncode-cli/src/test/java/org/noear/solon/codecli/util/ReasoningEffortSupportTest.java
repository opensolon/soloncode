package org.noear.solon.codecli.util;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatConfig;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReasoningEffortSupport 语义与能力探测回归。
 */
public class ReasoningEffortSupportTest {

    @Test
    public void normalizeEffort_autoAndEmpty() {
        assertNull(ReasoningEffortSupport.normalizeEffort(null));
        assertNull(ReasoningEffortSupport.normalizeEffort(""));
        assertNull(ReasoningEffortSupport.normalizeEffort("  "));
        assertNull(ReasoningEffortSupport.normalizeEffort("auto"));
        assertNull(ReasoningEffortSupport.normalizeEffort("AUTO"));
        assertEquals("high", ReasoningEffortSupport.normalizeEffort("HIGH"));
        assertNull(ReasoningEffortSupport.normalizeEffort("ultra"));
    }

    @Test
    public void resolveEffectiveEffort_autoDoesNotInjectDefault() {
        ReasoningEffortSupport.ModelCapability cap = new ReasoningEffortSupport.ModelCapability();
        cap.supportsReasoning = true;
        cap.reasoningEfforts = Arrays.asList("low", "medium", "high", "max");
        cap.defaultReasoningEffort = "high";

        String effort = ReasoningEffortSupport.resolveEffectiveEffort(
                null, null, cap, false);
        assertNull(effort, "auto must not inject capability default");
    }

    @Test
    public void resolveEffectiveEffort_sessionUser() {
        ReasoningEffortSupport.ModelCapability cap = new ReasoningEffortSupport.ModelCapability();
        cap.supportsReasoning = true;
        cap.reasoningEfforts = Arrays.asList("low", "medium", "high", "max");

        String effort = ReasoningEffortSupport.resolveEffectiveEffort(
                null, "high", cap, false);
        assertEquals("high", effort);
    }

    @Test
    public void resolveEffectiveEffort_requestPresentEmptyMeansAuto() {
        ReasoningEffortSupport.ModelCapability cap = new ReasoningEffortSupport.ModelCapability();
        cap.supportsReasoning = true;
        cap.reasoningEfforts = Arrays.asList("low", "medium", "high");
        cap.defaultReasoningEffort = "medium";

        // request 显式空 = auto，即使 session 有 high
        String effort = ReasoningEffortSupport.resolveEffectiveEffort(
                "", "high", cap, true);
        assertNull(effort);
    }

    @Test
    public void clampEffort_maxToHighForThreeTier() {
        ReasoningEffortSupport.ModelCapability cap = new ReasoningEffortSupport.ModelCapability();
        cap.supportsReasoning = true;
        cap.reasoningEfforts = Arrays.asList("low", "medium", "high");
        assertEquals("high", ReasoningEffortSupport.clampEffort("max", cap));
    }

    @Test
    public void looksLikeReasoningModel_tightened() {
        assertTrue(ReasoningEffortSupport.looksLikeReasoningModel("anthropic claude-sonnet-4"));
        assertTrue(ReasoningEffortSupport.looksLikeReasoningModel("openai-responses gpt-5"));
        assertTrue(ReasoningEffortSupport.looksLikeReasoningModel("deepseek-r1"));
        assertFalse(ReasoningEffortSupport.looksLikeReasoningModel("deepseek-chat"));
        // 裸 r1 不再匹配
        assertFalse(ReasoningEffortSupport.looksLikeReasoningModel("my-r1-bot"));
        // haiku 默认不支持
        assertFalse(ReasoningEffortSupport.looksLikeReasoningModel("anthropic claude-3-haiku"));
        // 普通 gpt-4o
        assertFalse(ReasoningEffortSupport.looksLikeReasoningModel("openai gpt-4o"));
        // 仅 standard=anthropic、无具体型号名 → 不误报
        assertFalse(ReasoningEffortSupport.looksLikeReasoningModel("anthropic"));
        // 别名 sonnet + anthropic
        assertTrue(ReasoningEffortSupport.looksLikeReasoningModel("anthropic sonnet-4.6"));
    }

    @Test
    public void resolveCapability_defaultsSupportsReasoningTrue() {
        ChatConfig plain = new ChatConfig();
        plain.setName("plain");
        plain.setModel("gpt-4o");
        plain.setStandard("openai");
        
        ReasoningEffortSupport.ModelCapability cap = ReasoningEffortSupport.resolveCapability(plain);
        assertTrue(cap.supportsReasoning, "supportsReasoning defaults to true for any configured model");
        assertTrue(cap.reasoningEfforts.contains("low"));
        assertTrue(cap.reasoningEfforts.contains("max"));
        assertEquals("medium", cap.defaultReasoningEffort);
        
        ReasoningEffortSupport.ModelCapability nullCap = ReasoningEffortSupport.resolveCapability((ChatConfig) null);
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
    
        ReasoningEffortSupport.ModelCapability cap = ReasoningEffortSupport.resolveCapability(config);
        assertTrue(cap.supportsReasoning);
        assertEquals("high", cap.defaultReasoningEffort);
    }
    
    @Test
    public void resolveCapability_fromNameModelStandardSnapshot() {
        Map<String, Object> opts = new LinkedHashMap<String, Object>();
        opts.put("thinking", true);
        ReasoningEffortSupport.ModelCapability cap = ReasoningEffortSupport.resolveCapability(
                "plain", "gpt-4o", "openai", opts);
        assertTrue(cap.supportsReasoning);
    
        // 无 options 的普通模型也默认 supportsReasoning=true
        ReasoningEffortSupport.ModelCapability noOpt = ReasoningEffortSupport.resolveCapability(
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

        assertSame(a, ReasoningEffortSupport.findEngineConfig(Arrays.asList(a, b), "sonnet"));
        assertSame(a, ReasoningEffortSupport.findEngineConfig(Arrays.asList(a, b), "claude-sonnet-4.6"));
        assertNull(ReasoningEffortSupport.findEngineConfig(Arrays.asList(a, b), "missing"));
    }

    @Test
    public void resolveForUi_userOrAuto() {
        ReasoningEffortSupport.ModelCapability cap = new ReasoningEffortSupport.ModelCapability();
        cap.supportsReasoning = true;
        cap.reasoningEfforts = Arrays.asList("low", "medium", "high", "max");
        cap.defaultReasoningEffort = "high";

        assertEquals("high", ReasoningEffortSupport.resolveForUi("high", cap));
        assertNull(ReasoningEffortSupport.resolveForUi(null, cap));
        assertNull(ReasoningEffortSupport.resolveForUi("", cap));
    }

    @Test
    public void dialectMappingTables_anthropicAndResponses() {
        assertEquals(4000, ReasoningEffortSupport.mapAnthropicBudgetTokens("low"));
        assertEquals(10000, ReasoningEffortSupport.mapAnthropicBudgetTokens("medium"));
        assertEquals(20000, ReasoningEffortSupport.mapAnthropicBudgetTokens("high"));
        assertEquals(32000, ReasoningEffortSupport.mapAnthropicBudgetTokens("max"));
        assertEquals(-1, ReasoningEffortSupport.mapAnthropicBudgetTokens("auto"));

        assertEquals("low", ReasoningEffortSupport.mapResponsesReasoningEffort("low"));
        assertEquals("high", ReasoningEffortSupport.mapResponsesReasoningEffort("max"));
        assertNull(ReasoningEffortSupport.mapResponsesReasoningEffort("auto"));
    }
}
