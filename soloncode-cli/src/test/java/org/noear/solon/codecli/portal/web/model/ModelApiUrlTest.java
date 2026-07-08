package org.noear.solon.codecli.portal.web.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ModelApiUrlTest {
    @Test
    public void openAiStandardAndBaseUrl() {
        // normalizeStandard
        assertEquals("openai", ModelApiUrl.normalizeStandard("openai"));
        assertEquals("openai", ModelApiUrl.normalizeStandard("OpenAI"));

        // deriveBaseUrl with full endpoint
        assertEquals("https://api.openai.com/v1",
                ModelApiUrl.deriveBaseUrl("https://api.openai.com/v1/chat/completions", "openai"));
        assertEquals("https://api.openai.com/v1",
                ModelApiUrl.deriveBaseUrl("https://api.openai.com/v1/responses", "openai"));
        assertEquals("https://api.openai.com/v1",
                ModelApiUrl.deriveBaseUrl("https://api.openai.com/v1", "openai"));
    }

    @Test
    public void openAiResponsesBaseUrl() {
        assertEquals("openai-responses", ModelApiUrl.normalizeStandard("openai-responses"));

        assertEquals("https://api.openai.com/v1",
                ModelApiUrl.deriveBaseUrl("https://api.openai.com/v1/responses", "openai-responses"));
    }

    @Test
    public void anthropicStandardAndBaseUrl() {
        assertEquals("anthropic", ModelApiUrl.normalizeStandard("claude"));
        assertEquals("anthropic", ModelApiUrl.normalizeStandard("anthropic"));

        assertEquals("https://api.anthropic.com",
                ModelApiUrl.deriveBaseUrl("https://api.anthropic.com/v1/messages", "anthropic"));
    }

    @Test
    public void ollamaBaseUrl() {
        assertEquals("ollama", ModelApiUrl.normalizeStandard("ollama"));

        assertEquals("http://localhost:11434",
                ModelApiUrl.deriveBaseUrl("http://localhost:11434/api/tags", "ollama"));
    }
}