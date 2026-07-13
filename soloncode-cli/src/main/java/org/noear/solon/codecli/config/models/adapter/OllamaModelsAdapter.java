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
        List<ModelInfo> result = new ArrayList<>();

        try {
            HttpUtils http = HttpUtils.http(modelsUrl)
                    .userAgent(userAgent)
                    .timeout(15);

            if (headers != null) {
                headers.forEach(http::header);
            }
            if (apiKey != null && !apiKey.isEmpty()) {
                http.header("Authorization", "Bearer " + apiKey);
            }

            String body = http.get();

            ONode root = ONode.ofJson(body);
            ONode models = root.get("models");
            if (models.isArray()) {
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
            }
        } catch (Exception e) {
            log.warn("[Ollama] Error fetching models from {}: {}", modelsUrl, e.getMessage());
        }

        return result;
    }
}
