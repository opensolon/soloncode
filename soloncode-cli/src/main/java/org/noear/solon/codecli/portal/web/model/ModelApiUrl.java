package org.noear.solon.codecli.portal.web.model;

import org.noear.solon.ai.chat.ChatConfig;

/**
 * Shared model API URL rules for web and desktop provider adapters.
 *
 * <p>URL normalization (path auto-completion) is handled by each ChatDialect's
 * own {@code getApiUrl()} method at request time. This class only provides
 * standard normalization and base URL derivation (for model listing).</p>
 */
public final class ModelApiUrl {
    private ModelApiUrl() {
    }

    public static String normalizeStandard(String standard) {
        String value = trimToEmpty(standard);
        if ("claude".equalsIgnoreCase(value)) {
            return "anthropic";
        }
        return value.toLowerCase();
    }

    public static String deriveBaseUrl(String apiUrl, String standard) {
        String url = trimTrailingSlash(trimToEmpty(apiUrl));
        if (url.isEmpty()) {
            return url;
        }

        String value = trimToEmpty(standard);

        if ("openai".equals(value) || "openai-responses".equals(value)) {
            return deriveOpenAiBaseUrl(url);
        }

        if ("anthropic".equals(value)) {
            return deriveAnthropicBaseUrl(url);
        }

        if ("ollama".equals(value)) {
            return deriveOllamaBaseUrl(url);
        }

        // Fallback for unknown standard: strip common endpoint suffixes only
        return stripSuffixes(url,
                "/v1/chat/completions", "/v1/responses",
                "/chat/completions", "/responses",
                "/images/generations", "/embeddings",
                "/completions", "/models", "/v1");
    }

    public static void normalize(ChatConfig config) {
        if (config == null) {
            return;
        }

        config.setStandard(normalizeStandard(config.getStandardOrProvider()));
        // apiUrl normalization is handled by each ChatDialect's getApiUrl() at request time
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