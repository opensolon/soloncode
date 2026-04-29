package org.noear.solon.codecli.provider;

import lombok.extern.slf4j.Slf4j;
import org.noear.snack4.ONode;
import org.noear.solon.net.http.HttpUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class ZhiPuModelProvider implements ModelProvider{
    @Override
    public String getProviderName() {
        return "zhipu";
    }

    @Override
    public List<ModelInfo> fetchModels(String baseUrl, Map<String, String> headers, String apiKey) {
        String modelsUrl = baseUrl + "/api/paas/v4/models";
        List<ModelInfo> result = new ArrayList<>();

        try {
            HttpUtils http = HttpUtils.http(modelsUrl).timeout(15);

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
