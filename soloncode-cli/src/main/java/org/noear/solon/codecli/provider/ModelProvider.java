package org.noear.solon.codecli.provider;

import java.util.List;
import java.util.Map;

/**
 * AI 模型 Provider 抽象接口
 * 每个 API 协议（OpenAI、Ollama 等）对应一个实现类
 */
public interface ModelProvider {

    String getProviderName();

    List<ModelInfo> fetchModels(String baseUrl, Map<String, String> headers, String apiKey);

    default String deriveBaseUrl(String apiUrl){
        String[] suffixes = {"/chat/completions", "/images/generations", "/embeddings", "/completions", "/api/paas/v4"};
        String url = apiUrl;
        for (String suffix : suffixes) {
            if (url.endsWith(suffix)) {
                url = url.substring(0, url.length() - suffix.length());
                break;
            }
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
