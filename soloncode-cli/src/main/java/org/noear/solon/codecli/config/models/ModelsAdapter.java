package org.noear.solon.codecli.config.models;

import java.util.List;
import java.util.Map;

/**
 * AI 模型列表适配器接口
 * 每个 API 协议（OpenAI、Ollama 等）对应一个实现类
 */
public interface ModelsAdapter {

    /**
     * 获取接口规范标识（如 openai、ollama、anthropic）
     */
    String getStandard();

    List<ModelInfo> fetchModels(String userAgent, String baseUrl, Map<String, String> headers, String apiKey);

    String deriveBaseUrl(String apiUrl);

    default String buildModelsUrl(String baseUrl) {
        String url = ModelApiUrl.trimTrailingSlash(baseUrl);
        if (url.endsWith("/v1")) {
            return url + "/models";
        }
        return url + "/v1/models";
    }
}
