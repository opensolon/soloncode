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
        // 地址非法时立即失败，不必白等一轮连接超时
        ModelsHttp.requireHttpUrl(modelsUrl, "OpenAI");

        List<ModelInfo> result = new ArrayList<>();

        try {
            HttpUtils http = ModelsHttp.create(modelsUrl, userAgent);
            ProxyConfig.applyIfNeeded(http);

            if (headers != null) {
                headers.forEach(http::header);
            }
            if (apiKey != null && !apiKey.isEmpty()
                    && (headers == null || !headers.containsKey("Authorization"))) {
                http.header("Authorization", "Bearer " + apiKey);
            }

            String body = ModelsHttp.getBody(http, "OpenAI");

            ONode root = ModelsHttp.parseJson(body, "OpenAI");
            ONode data = root.get("data");
            if (data.isArray()) {
                for (ONode item : data.getArray()) {
                    result.add(item.toBean(ModelInfo.class));
                }
            } else {
                // 状态码正常但结构不是模型列表，多为地址或协议选错
                throw new ModelsFetchException("OpenAI model list response has no data array",
                        ModelsFetchReason.INVALID_RESPONSE, 200, null);
            }
        } catch (ModelsFetchException e) {
            log.warn("[OpenAI] Failed to fetch model list: reason={}, status={}", e.getReason(), e.getStatus());
            throw e;
        } catch (Exception e) {
            log.warn("[OpenAI] Failed to fetch model list");
            throw new ModelsFetchException("OpenAI model list request failed", ModelsFetchReason.UNKNOWN, e);
        }

        return result;
    }
}
