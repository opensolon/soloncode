package org.noear.solon.codecli.config.models.adapter;

import lombok.extern.slf4j.Slf4j;
import org.noear.snack4.ONode;
import org.noear.solon.codecli.config.ProxyConfig;
import org.noear.solon.codecli.config.models.ModelApiUrl;
import org.noear.solon.codecli.config.models.ModelInfo;
import org.noear.solon.codecli.config.models.ModelsAdapter;
import org.noear.solon.codecli.config.models.ModelsFetchException;
import org.noear.solon.codecli.config.models.ModelsFetchReason;
import org.noear.solon.codecli.config.models.ModelsHttp;
import org.noear.solon.net.http.HttpUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ollama 协议实现
 * 接口：GET {baseUrl}/api/tags
 */
@Slf4j
public class OllamaModelsAdapter implements ModelsAdapter {

    @Override
    public String getStandard() {
        return "ollama";
    }

    @Override
    public String deriveBaseUrl(String apiUrl) {
        return ModelApiUrl.stripSuffixes(
                ModelApiUrl.trimTrailingSlash(apiUrl == null ? "" : apiUrl.trim()),
                "/api/chat", "/api/generate", "/api/tags",
                "/v1/chat/completions", "/chat/completions");
    }

    @Override
    public List<ModelInfo> fetchModels(String userAgent, String baseUrl, Map<String, String> headers, String apiKey) {
        String modelsUrl = baseUrl + "/api/tags";
        // 地址非法时立即失败，不必白等一轮连接超时
        ModelsHttp.requireHttpUrl(modelsUrl, "Ollama");
        List<ModelInfo> result = new ArrayList<>();

        try {
            HttpUtils http = ModelsHttp.create(modelsUrl, userAgent);
            ProxyConfig.applyIfNeeded(http);

            if (headers != null) {
                headers.forEach(http::header);
            }
            if (apiKey != null && !apiKey.isEmpty()) {
                http.header("Authorization", "Bearer " + apiKey);
            }

            String body = ModelsHttp.getBody(http, "Ollama");

            ONode root = ModelsHttp.parseJson(body, "Ollama");
            ONode models = root.get("models");
            if (!models.isArray()) {
                // 状态码正常但结构不是模型列表，多为地址或协议选错
                throw new ModelsFetchException("Ollama model list response has no models array",
                        ModelsFetchReason.INVALID_RESPONSE, 200, null);
            }
            for (int i = 0; i < models.size(); i++) {
                ONode item = models.get(i);
                String name = item.get("name").getString();
                long created = System.currentTimeMillis() / 1000;
                if (item.exists("modified_at")) {
                    try {
                        created = java.time.Instant.parse(item.get("modified_at").getString()).getEpochSecond();
                    } catch (Exception ignored) {
                    }
                }
                result.add(ModelInfo.builder()
                        .id(name)
                        .object("model")
                        .created(created)
                        .ownedBy("ollama")
                        .type("chat")
                        .build());
            }
        } catch (ModelsFetchException e) {
            log.warn("[Ollama] Failed to fetch model list: reason={}, status={}", e.getReason(), e.getStatus());
            throw e;
        } catch (Exception e) {
            log.warn("[Ollama] Failed to fetch model list");
            throw new ModelsFetchException("Ollama model list request failed", ModelsFetchReason.UNKNOWN, e);
        }

        return result;
    }
}
