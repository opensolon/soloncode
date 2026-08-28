package org.noear.solon.codecli.config.models.adapter;

import lombok.extern.slf4j.Slf4j;
import org.noear.snack4.ONode;
import org.noear.solon.codecli.config.ProxyConfig;
import org.noear.solon.codecli.config.models.ModelApiUrl;
import org.noear.solon.codecli.config.models.ModelInfo;
import org.noear.solon.codecli.config.models.ModelsAdapter;
import org.noear.solon.net.http.HttpUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Google Models / Gemini 协议实现
 * 接口：GET {baseUrl}/v1beta/models?key={apiKey} 或 Authorization 头
 */
@Slf4j
public class GoogleModelsAdapter implements ModelsAdapter {

    @Override
    public String getStandard() {
        return "google";
    }

    @Override
    public String deriveBaseUrl(String apiUrl) {
        String url = ModelApiUrl.trimTrailingSlash(apiUrl == null ? "" : apiUrl.trim());
        int idx = url.indexOf("/models/");
        if (idx > 0) {
            url = url.substring(0, idx);
        }
        String base = ModelApiUrl.stripSuffixes(url,
                ":generateContent", ":streamGenerateContent",
                "/generateContent", "/streamGenerateContent",
                "/models", "/v1beta", "/v1");
        if (base.isEmpty()) {
            return "https://generativelanguage.googleapis.com";
        }
        return base;
    }

    @Override
    public String buildModelsUrl(String baseUrl) {
        String url = ModelApiUrl.trimTrailingSlash(baseUrl);
        if (url.endsWith("/v1beta") || url.endsWith("/v1")) {
            return url + "/models";
        }
        return url + "/v1beta/models";
    }

    @Override
    public List<ModelInfo> fetchModels(String userAgent, String baseUrl, Map<String, String> headers, String apiKey) {
        String modelsUrl = buildModelsUrl(baseUrl);
        if (apiKey != null && !apiKey.isEmpty()) {
            modelsUrl = modelsUrl + (modelsUrl.contains("?") ? "&" : "?") + "key=" + apiKey;
        }

        List<ModelInfo> result = new ArrayList<>();

        try {
            HttpUtils http = HttpUtils.http(modelsUrl)
                    .userAgent(userAgent)
                    .timeout(15);
            ProxyConfig.applyIfNeeded(http);

            if (headers != null) {
                headers.forEach(http::header);
            }
            if (apiKey != null && !apiKey.isEmpty()
                    && (headers == null || !headers.containsKey("x-goog-api-key"))) {
                http.header("x-goog-api-key", apiKey);
            }

            String body = http.get();

            ONode root = ONode.ofJson(body);
            ONode models = root.get("models");
            if (models.isArray()) {
                for (int i = 0; i < models.size(); i++) {
                    ONode item = models.get(i);
                    String name = item.get("name").getString();
                    if (name != null && name.startsWith("models/")) {
                        name = name.substring("models/".length());
                    }
                    String displayName = item.get("displayName").getString();
                    long inputLimit = item.get("inputTokenLimit").getLong();
                    long outputLimit = item.get("outputTokenLimit").getLong();

                    ModelInfo modelInfo = ModelInfo.builder()
                            .id(name)
                            .object("model")
                            .created(System.currentTimeMillis() / 1000)
                            .ownedBy("google")
                            .type("chat")
                            .displayName(displayName)
                            .maxInputTokens(inputLimit > 0 ? inputLimit : null)
                            .maxTokens(outputLimit > 0 ? outputLimit : null)
                            .build();
                    result.add(modelInfo);
                }
            }
        } catch (Exception e) {
            log.warn("[Google] Error fetching models from {}: {}", modelsUrl, e.getMessage(), e);
        }

        return result;
    }
}
