package org.noear.solon.codecli.portal.web.model;

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

    List<ModelInfo> fetchModels(String baseUrl, Map<String, String> headers, String apiKey);

    default String deriveBaseUrl(String apiUrl) {
        return ModelApiUrl.deriveBaseUrl(apiUrl, getStandard());
    }
}
