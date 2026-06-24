package org.noear.solon.codecli.util;

import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.core.util.Assert;

/**
 * Normalizes provider API URLs so UI can accept either BaseURL or full endpoint URLs.
 */
public final class AiApiUrlAdapter {
    private AiApiUrlAdapter() {
    }

    public static String normalizeProvider(String provider, String apiUrl) {
        String p = trimToEmpty(provider);
        String url = trimToEmpty(apiUrl);

        if ("claude".equalsIgnoreCase(p)) {
            return "anthropic";
        }

        if ("openai".equalsIgnoreCase(p) && isOpenAiResponsesUrl(url)) {
            return "openai-responses";
        }

        if (Assert.isEmpty(p) && isOpenAiResponsesUrl(url)) {
            return "openai-responses";
        }

        return p;
    }

    public static String normalizeChatApiUrl(String apiUrl, String provider) {
        String url = trimTrailingSlash(trimToEmpty(apiUrl));
        if (Assert.isEmpty(url)) {
            return url;
        }

        String p = normalizeProvider(provider, url);

        if ("openai-responses".equalsIgnoreCase(p)) {
            return ensureSuffix(deriveOpenAiBaseUrl(url), "/responses");
        }

        if ("openai".equalsIgnoreCase(p)) {
            return normalizeOpenAiUrl(url);
        }

        if ("anthropic".equalsIgnoreCase(p) || "claude".equalsIgnoreCase(p)) {
            return normalizeAnthropicUrl(url);
        }

        if ("ollama".equalsIgnoreCase(p)) {
            return normalizeOllamaUrl(url);
        }

        if (isOpenAiResponsesUrl(url)) {
            return ensureSuffix(deriveOpenAiBaseUrl(url), "/responses");
        }

        return url;
    }

    public static String deriveBaseUrl(String apiUrl, String provider) {
        String url = trimTrailingSlash(trimToEmpty(apiUrl));
        if (Assert.isEmpty(url)) {
            return url;
        }

        String p = normalizeProvider(provider, url);

        if ("openai".equalsIgnoreCase(p) || "openai-responses".equalsIgnoreCase(p)) {
            return deriveOpenAiBaseUrl(url);
        }

        if ("anthropic".equalsIgnoreCase(p) || "claude".equalsIgnoreCase(p)) {
            return deriveAnthropicBaseUrl(url);
        }

        if ("ollama".equalsIgnoreCase(p)) {
            return deriveOllamaBaseUrl(url);
        }

        return stripKnownEndpointSuffixes(url);
    }

    public static void normalize(ChatConfig config) {
        if (config == null) {
            return;
        }

        String provider = normalizeProvider(config.getProvider(), config.getApiUrl());
        String apiUrl = normalizeChatApiUrl(config.getApiUrl(), provider);

        config.setProvider(provider);
        config.setApiUrl(apiUrl);
    }

    private static String normalizeOpenAiUrl(String url) {
        if (endsWithAny(url, "/chat/completions", "/completions")) {
            return url;
        }

        if (isOpenAiResponsesUrl(url)) {
            return ensureSuffix(deriveOpenAiBaseUrl(url), "/responses");
        }

        return ensureOpenAiVersion(url);
    }

    private static String normalizeAnthropicUrl(String url) {
        if (url.endsWith("/v1/messages")) {
            return url;
        }

        return deriveAnthropicBaseUrl(url);
    }

    private static String normalizeOllamaUrl(String url) {
        return deriveOllamaBaseUrl(url);
    }

    private static String deriveOpenAiBaseUrl(String url) {
        String base = stripSuffixes(url,
                "/chat/completions",
                "/responses",
                "/models",
                "/images/generations",
                "/embeddings",
                "/completions");
        return ensureOpenAiVersion(base);
    }

    private static String deriveAnthropicBaseUrl(String url) {
        return stripSuffixes(url, "/v1/messages", "/v1/models", "/messages", "/models", "/v1");
    }

    private static String deriveOllamaBaseUrl(String url) {
        return stripSuffixes(url, "/api/chat", "/api/generate", "/api/tags", "/v1/chat/completions", "/chat/completions");
    }

    private static String stripKnownEndpointSuffixes(String url) {
        return stripSuffixes(url,
                "/api/anthropic/v1/messages",
                "/api/paas/v4/chat/completions",
                "/api/coding/paas/v4/chat/completions",
                "/api/anthropic",
                "/v1/chat/completions",
                "/v1/responses",
                "/chat/completions",
                "/responses",
                "/images/generations",
                "/embeddings",
                "/completions",
                "/models");
    }

    private static String ensureOpenAiVersion(String url) {
        if (url.endsWith("/v1")) {
            return url;
        }

        if (url.endsWith("/v1/")) {
            return trimTrailingSlash(url);
        }

        if (url.endsWith("api.openai.com")) {
            return url + "/v1";
        }

        return url;
    }

    private static String ensureSuffix(String base, String suffix) {
        String cleanBase = trimTrailingSlash(base);
        if (cleanBase.endsWith(suffix)) {
            return cleanBase;
        }
        return cleanBase + suffix;
    }

    private static boolean isOpenAiResponsesUrl(String url) {
        return trimTrailingSlash(url).endsWith("/v1/responses") || trimTrailingSlash(url).endsWith("/responses");
    }

    private static boolean endsWithAny(String value, String... suffixes) {
        for (String suffix : suffixes) {
            if (value.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static String stripSuffixes(String url, String... suffixes) {
        String result = trimTrailingSlash(url);
        boolean changed;
        do {
            changed = false;
            for (String suffix : suffixes) {
                if (result.endsWith(suffix)) {
                    result = result.substring(0, result.length() - suffix.length());
                    result = trimTrailingSlash(result);
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return result;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimTrailingSlash(String value) {
        String result = trimToEmpty(value);
        while (result.length() > "https://".length() && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
