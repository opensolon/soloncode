package org.noear.solon.codecli.portal.web.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for ModelApiUrl.
 *
 * <p>deriveBaseUrl tests belong to each ModelsAdapter implementation's
 * own test class (e.g. OpenAIModelsAdapterTest).</p>
 */
public class ModelApiUrlTest {
    @Test
    public void normalizeStandard_passedThrough() {
        assertEquals("openai", ModelApiUrl.normalizeStandard("openai"));
        assertEquals("openai", ModelApiUrl.normalizeStandard("OpenAI"));
        assertEquals("openai-responses", ModelApiUrl.normalizeStandard("openai-responses"));
        assertEquals("ollama", ModelApiUrl.normalizeStandard("ollama"));
    }

    @Test
    public void normalizeStandard_claudeMappedToAnthropic() {
        assertEquals("anthropic", ModelApiUrl.normalizeStandard("claude"));
        assertEquals("anthropic", ModelApiUrl.normalizeStandard("Claude"));
        assertEquals("anthropic", ModelApiUrl.normalizeStandard("CLAUDE"));
        assertEquals("anthropic", ModelApiUrl.normalizeStandard("anthropic"));
    }

    @Test
    public void normalizeStandard_emptyAndNull() {
        assertEquals("", ModelApiUrl.normalizeStandard(""));
        assertEquals("", ModelApiUrl.normalizeStandard(null));
    }
}