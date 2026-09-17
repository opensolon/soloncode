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
 * Google Models / Gemini 协议实现
 * 接口：GET {baseUrl}/v1beta/models（密钥通过 x-goog-api-key 头传递，避免出现在 URL 与日志中）
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
        // 密钥只走请求头，不拼进 URL：URL 可能被日志、异常消息或代理记录下来
        String modelsUrl = buildModelsUrl(baseUrl);
        // 地址非法时立即失败，不必白等一轮连接超时
        ModelsHttp.requireHttpUrl(modelsUrl, "Google");

        List<ModelInfo> result = new ArrayList<>();

        try {
            HttpUtils http = ModelsHttp.create(modelsUrl, userAgent);
            ProxyConfig.applyIfNeeded(http);

            if (headers != null) {
                headers.forEach(http::header);
            }
            if (apiKey != null && !apiKey.isEmpty()
                    && (headers == null || !headers.containsKey("x-goog-api-key"))) {
                http.header("x-goog-api-key", apiKey);
            }

            String body = ModelsHttp.getBody(http, "Google");

            ONode root = ModelsHttp.parseJson(body, "Google");
            ONode models = root.get("models");
            if (!models.isArray()) {
                // 状态码正常但结构不是模型列表，多为地址或协议选错
                throw new ModelsFetchException("Google model list response has no models array",
                        ModelsFetchReason.INVALID_RESPONSE, 200, null);
            }
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
        } catch (ModelsFetchException e) {
            log.warn("[Google] Failed to fetch model list: reason={}, status={}", e.getReason(), e.getStatus());
            throw e;
        } catch (Exception e) {
            log.warn("[Google] Failed to fetch model list");
            throw new ModelsFetchException("Google model list request failed", ModelsFetchReason.UNKNOWN, e);
        }

        return result;
    }
}
