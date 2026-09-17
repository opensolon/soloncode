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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic 协议实现
 * 接口：GET {baseUrl}/v1/models
 */
@Slf4j
public class AnthropicModelsAdapter implements ModelsAdapter {

    @Override
    public String getStandard() {
        return "anthropic";
    }

    @Override
    public String deriveBaseUrl(String apiUrl) {
        return ModelApiUrl.stripSuffixes(
                ModelApiUrl.trimTrailingSlash(apiUrl == null ? "" : apiUrl.trim()),
                "/v1/messages", "/v1/models",
                "/messages", "/models", "/v1");
    }

    @Override
    public List<ModelInfo> fetchModels(String userAgent, String baseUrl, Map<String, String> headers, String apiKey) {
        final String modelsUrl = buildModelsUrl(baseUrl);
        // 地址非法时立即失败，不必白等一轮连接超时
        ModelsHttp.requireHttpUrl(modelsUrl, "Anthropic");

        List<ModelInfo> result = new ArrayList<>();

        try {
            HttpUtils http = ModelsHttp.create(modelsUrl, userAgent);
            ProxyConfig.applyIfNeeded(http);

            if (headers != null) {
                headers.forEach(http::header);
            }
            if (apiKey != null && !apiKey.isEmpty()
                    && (headers == null || !headers.containsKey("x-api-key"))) {
                http.header("x-api-key", apiKey);
            }
            // Anthropic 需要 anthropic-version 头部
            if (headers == null || !headers.containsKey("anthropic-version")) {
                http.header("anthropic-version", "2023-06-01");
            }

            String body = ModelsHttp.getBody(http, "Anthropic");

            ONode root = ModelsHttp.parseJson(body, "Anthropic");
            ONode data = root.get("data");
            if (!data.isArray()) {
                // 状态码正常但结构不是模型列表，多为地址或协议选错
                throw new ModelsFetchException("Anthropic model list response has no data array",
                        ModelsFetchReason.INVALID_RESPONSE, 200, null);
            }
            for (ONode item : data.getArray()) {
                ModelInfo modelInfo = ModelInfo.builder()
                        .id(item.get("id").getString())
                        .object(item.get("type").getString())
                        .created(parseCreatedAt(item.get("created_at").getString()))
                        .ownedBy("anthropic")
                        .type("chat")
                        .displayName(item.get("display_name").getString())
                        .maxInputTokens(item.get("max_input_tokens").getLong())
                        .maxTokens(item.get("max_tokens").getLong())
                        .capabilities(parseCapabilities(item.get("capabilities")))
                        .build();
                result.add(modelInfo);
            }
        } catch (ModelsFetchException e) {
            log.warn("[Anthropic] Failed to fetch model list: reason={}, status={}", e.getReason(), e.getStatus());
            throw e;
        } catch (Exception e) {
            log.warn("[Anthropic] Failed to fetch model list");
            throw new ModelsFetchException("Anthropic model list request failed", ModelsFetchReason.UNKNOWN, e);
        }

        return result;
    }

    private long parseCreatedAt(String createdAt) {
        if (createdAt == null || createdAt.isEmpty()) {
            return System.currentTimeMillis() / 1000;
        }
        try {
            return java.time.Instant.parse(createdAt).getEpochSecond();
        } catch (Exception e) {
            return System.currentTimeMillis() / 1000;
        }
    }

    private Map<String, Object> parseCapabilities(ONode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        Map<String, Object> caps = new HashMap<>();
        node.getObject().forEach((key, val) -> {
            if (val.isObject()) {
                caps.put(key, parseCapabilities(val));
            } else if (val.isBoolean()) {
                caps.put(key, val.getBoolean());
            } else if (val.isNumber()) {
                caps.put(key, val.getLong());
            } else {
                caps.put(key, val.getString());
            }
        });
        return caps;
    }
}
