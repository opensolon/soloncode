package org.noear.solon.codecli.portal.web.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ModelApiUrlTest {
    @Test
    public void openAiChatBaseUrlAndEndpoint() {
        assertEquals("https://api.openai.com/v1",
                ModelApiUrl.normalizeChatApiUrl("https://api.openai.com", "openai"));
        assertEquals("https://api.openai.com/v1",
                ModelApiUrl.normalizeChatApiUrl("https://api.openai.com/v1", "openai"));
        assertEquals("https://api.openai.com/v1/chat/completions",
                ModelApiUrl.normalizeChatApiUrl("https://api.openai.com/v1/chat/completions", "openai"));
        assertEquals("https://api.openai.com/v1",
                ModelApiUrl.deriveBaseUrl("https://api.openai.com/v1/chat/completions", "openai"));
    }

    @Test
    public void openAiResponsesEndpoint() {
        assertEquals("openai-responses",
                ModelApiUrl.normalizeStandard("openai", "https://api.openai.com/v1/responses"));
        assertEquals("https://api.openai.com/v1/responses",
                ModelApiUrl.normalizeChatApiUrl("https://api.openai.com/v1", "openai-responses"));
        assertEquals("https://api.openai.com/v1",
                ModelApiUrl.deriveBaseUrl("https://api.openai.com/v1/responses", "openai-responses"));
    }

    @Test
    public void anthropicBaseUrlAndEndpoint() {
        assertEquals("anthropic", ModelApiUrl.normalizeStandard("claude", null));
        assertEquals("https://api.anthropic.com",
                ModelApiUrl.normalizeChatApiUrl("https://api.anthropic.com/v1", "anthropic"));
        assertEquals("https://api.anthropic.com/v1/messages",
                ModelApiUrl.normalizeChatApiUrl("https://api.anthropic.com/v1/messages", "anthropic"));
        assertEquals("https://api.anthropic.com",
                ModelApiUrl.deriveBaseUrl("https://api.anthropic.com/v1/messages", "anthropic"));
    }

    @Test
    public void ollamaBaseUrlAndEndpoint() {
        assertEquals("http://localhost:11434",
                ModelApiUrl.normalizeChatApiUrl("http://localhost:11434/api/chat", "ollama"));
        assertEquals("http://localhost:11434",
                ModelApiUrl.deriveBaseUrl("http://localhost:11434/api/tags", "ollama"));
    }
}
