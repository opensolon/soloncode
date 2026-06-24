package org.noear.solon.codecli.portal.desktop.provider;

import org.noear.solon.codecli.util.AiApiUrlAdapter;

import java.util.List;
import java.util.Map;

/**
 * AI 模型 Provider 抽象接口
 * 每个 API 协议（OpenAI、Ollama 等）对应一个实现类
 */
public interface ModelProvider {

    String getProviderName();

    List<ModelInfo> fetchModels(String baseUrl, Map<String, String> headers, String apiKey);

    default String deriveBaseUrl(String apiUrl) {
        return AiApiUrlAdapter.deriveBaseUrl(apiUrl, getProviderName());
    }
}
