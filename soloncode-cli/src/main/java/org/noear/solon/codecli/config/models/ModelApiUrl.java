package org.noear.solon.codecli.config.models;

import org.noear.solon.ai.chat.ChatConfig;

/**
 * Shared model API normalization rules for web and desktop provider adapters.
 *
 * <p>URL path normalization is handled by each ChatDialect's own
 * {@code getApiUrl()} method at request time. This class only
 * provides standard normalization.</p>
 */
public final class ModelApiUrl {
    private ModelApiUrl() {
    }

    public static String normalizeStandard(String standard) {
        String value = standard == null ? "" : standard.trim();
        if ("claude".equalsIgnoreCase(value)) {
            return "anthropic";
        }
        return value.toLowerCase();
    }

    public static void normalize(ChatConfig config) {
        if (config == null) {
            return;
        }

        config.setStandard(normalizeStandard(config.getStandardOrProvider()));
    }

    public static String trimTrailingSlash(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        String result = value;
        while (result.length() > "https://".length() && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    public static String stripSuffixes(String url, String... suffixes) {
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
}