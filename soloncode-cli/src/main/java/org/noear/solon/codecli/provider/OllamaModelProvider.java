package org.noear.solon.codecli.provider;

import lombok.extern.slf4j.Slf4j;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Component;
import org.noear.solon.net.http.HttpUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ollama 协议实现
 * 接口：GET {baseUrl}/api/tags
 */
@Slf4j
@Component
public class OllamaModelProvider implements ModelProvider {

    @Override
    public String getProviderName() {
        return "ollama";
    }

    @Override
    public List<ModelInfo> fetchModels(String baseUrl, Map<String, String> headers, String apiKey) {
        String modelsUrl = baseUrl + "/api/tags";
        List<ModelInfo> result = new ArrayList<>();

        try {
            HttpUtils http = HttpUtils.http(modelsUrl).timeout(15);

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
                            .build());
                }
            }
        } catch (Exception e) {
            log.warn("[Ollama] Error fetching models from {}: {}", modelsUrl, e.getMessage());
        }

        return result;
    }

}
