package org.noear.solon.codecli.config.models.adapter;

import lombok.extern.slf4j.Slf4j;
import org.noear.snack4.ONode;
import org.noear.solon.codecli.config.models.ModelApiUrl;
import org.noear.solon.codecli.config.models.ModelInfo;
import org.noear.solon.codecli.config.models.ModelsAdapter;
import org.noear.solon.net.http.HttpUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议实现
 * 接口：GET {baseUrl}/models
 */
@Slf4j
public class OpenAIModelsAdapter implements ModelsAdapter {

    @Override
    public String getStandard() {
        return "openai";
    }

    @Override
    public String deriveBaseUrl(String apiUrl) {
        String url = ModelApiUrl.trimTrailingSlash(apiUrl == null ? "" : apiUrl.trim());
        String base = ModelApiUrl.stripSuffixes(url,
                "/chat/completions", "/responses",
                "/models", "/images/generations",
                "/embeddings", "/completions");
        // Ensure OpenAI base URL ends with /v1
        if (base.endsWith("/v1")) {
            return base;
        }
        if (base.endsWith("api.openai.com")) {
            return base + "/v1";
        }
        return base;
    }

    @Override
    public List<ModelInfo> fetchModels(String userAgent, String baseUrl, Map<String, String> headers, String apiKey) {
        final String modelsUrl = buildModelsUrl(baseUrl);

        List<ModelInfo> result = new ArrayList<>();

        try {
            HttpUtils http = HttpUtils.http(modelsUrl)
                    .userAgent(userAgent)
                    .timeout(15);

            if (headers != null) {
                headers.forEach(http::header);
            }
            if (apiKey != null && !apiKey.isEmpty()
                    && (headers == null || !headers.containsKey("Authorization"))) {
                http.header("Authorization", "Bearer " + apiKey);
            }

            String body = http.get();

            ONode root = ONode.ofJson(body);
            ONode data = root.get("data");
            if (data.isArray()) {
                for (ONode item : data.getArray()) {
                    result.add(item.toBean(ModelInfo.class));
                }
            }
        } catch (Exception e) {
            log.warn("[OpenAI] Error fetching models from {}: {}", modelsUrl, e.getMessage());
        }

        return result;
    }
}
