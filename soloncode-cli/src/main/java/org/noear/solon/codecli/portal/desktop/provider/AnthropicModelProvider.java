package org.noear.solon.codecli.portal.desktop.provider;

import lombok.extern.slf4j.Slf4j;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Component;
import org.noear.solon.net.http.HttpUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Anthropic protocol implementation.
 * Endpoint: GET {baseUrl}/v1/models
 */
@Slf4j
@Component
public class AnthropicModelProvider implements ModelProvider {
    @Override
    public String getProviderName() {
        return "anthropic";
    }

    @Override
    public List<ModelInfo> fetchModels(String baseUrl, Map<String, String> headers, String apiKey) {
        String modelsUrl = baseUrl + "/v1/models";
        List<ModelInfo> result = new ArrayList<>();

        try {
            HttpUtils http = HttpUtils.http(modelsUrl).timeout(15);

            if (headers != null) {
                headers.forEach(http::header);
            }
            if (apiKey != null && !apiKey.isEmpty()) {
                http.header("x-api-key", apiKey);
            }
            http.header("anthropic-version", "2023-06-01");

            String body = http.get();

            ONode root = ONode.ofJson(body);
            ONode data = root.get("data");
            if (data.isArray()) {
                for (ONode item : data.getArray()) {
                    String id = item.get("id").getString();
                    result.add(ModelInfo.builder()
                            .id(id)
                            .object(item.get("type").getString())
                            .created(System.currentTimeMillis() / 1000)
                            .ownedBy("anthropic")
                            .build());
                }
            }
        } catch (Exception e) {
            log.warn("[Anthropic] Error fetching models from {}: {}", modelsUrl, e.getMessage());
        }

        return result;
    }
}
