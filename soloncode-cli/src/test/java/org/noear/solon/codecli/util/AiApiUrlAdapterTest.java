package org.noear.solon.codecli.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AiApiUrlAdapterTest {
    @Test
    public void openAiChatBaseUrlAndEndpoint() {
        assertEquals("https://api.openai.com/v1",
                AiApiUrlAdapter.normalizeChatApiUrl("https://api.openai.com", "openai"));
        assertEquals("https://api.openai.com/v1",
                AiApiUrlAdapter.normalizeChatApiUrl("https://api.openai.com/v1", "openai"));
        assertEquals("https://api.openai.com/v1/chat/completions",
                AiApiUrlAdapter.normalizeChatApiUrl("https://api.openai.com/v1/chat/completions", "openai"));
        assertEquals("https://api.openai.com/v1",
                AiApiUrlAdapter.deriveBaseUrl("https://api.openai.com/v1/chat/completions", "openai"));
    }

    @Test
    public void openAiResponsesEndpoint() {
        assertEquals("openai-responses",
                AiApiUrlAdapter.normalizeProvider("openai", "https://api.openai.com/v1/responses"));
        assertEquals("https://api.openai.com/v1/responses",
                AiApiUrlAdapter.normalizeChatApiUrl("https://api.openai.com/v1", "openai-responses"));
        assertEquals("https://api.openai.com/v1",
                AiApiUrlAdapter.deriveBaseUrl("https://api.openai.com/v1/responses", "openai-responses"));
    }

    @Test
    public void anthropicBaseUrlAndEndpoint() {
        assertEquals("anthropic", AiApiUrlAdapter.normalizeProvider("claude", null));
        assertEquals("https://api.anthropic.com",
                AiApiUrlAdapter.normalizeChatApiUrl("https://api.anthropic.com/v1", "anthropic"));
        assertEquals("https://api.anthropic.com/v1/messages",
                AiApiUrlAdapter.normalizeChatApiUrl("https://api.anthropic.com/v1/messages", "anthropic"));
        assertEquals("https://api.anthropic.com",
                AiApiUrlAdapter.deriveBaseUrl("https://api.anthropic.com/v1/messages", "anthropic"));
    }

    @Test
    public void ollamaBaseUrlAndEndpoint() {
        assertEquals("http://localhost:11434",
                AiApiUrlAdapter.normalizeChatApiUrl("http://localhost:11434/api/chat", "ollama"));
        assertEquals("http://localhost:11434",
                AiApiUrlAdapter.deriveBaseUrl("http://localhost:11434/api/tags", "ollama"));
    }
}
