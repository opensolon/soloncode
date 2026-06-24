package org.noear.solon.codecli.portal.web.model;

import java.util.HashMap;
import java.util.Map;

/**
 * ModelProvider 工厂
 * 管理不同类型的模型提供商实现
 */
public class ModelsAdapterManager {
    private final Map<String, ModelsAdapter> providerMap = new HashMap<>();
    private ModelsAdapter defaultProvider;

public ModelsAdapterManager() {
        OpenAIModelsAdapter openAIModelProvider = new OpenAIModelsAdapter();
        AnthropicModelsAdapter anthropicModelsAdapter = new AnthropicModelsAdapter();
        OllamaModelsAdapter ollamaModelsAdapter = new OllamaModelsAdapter();
        
        providerMap.put(openAIModelProvider.getStandard(), openAIModelProvider);
        providerMap.put(anthropicModelsAdapter.getStandard(), anthropicModelsAdapter);
        providerMap.put(ollamaModelsAdapter.getStandard(), ollamaModelsAdapter);
        defaultProvider = openAIModelProvider;
    }

/**
     * 根据接口规范获取对应的 ModelProvider
     * @param standard 接口规范（如 openai、ollama、anthropic 等）
     * @return 对应的 ModelProvider，如果不存在则返回默认的 OpenAI 提供商
     */
    public ModelsAdapter getProvider(String standard) {
        if (standard == null || standard.isEmpty()) {
            return defaultProvider;
        }
        return providerMap.getOrDefault(standard.toLowerCase(), defaultProvider);
    }

    /**
     * 注册自定义 ModelProvider
     * @param provider 要注册的提供商实现
     */
    public void registerProvider(ModelsAdapter provider) {
        providerMap.put(provider.getStandard(), provider);
    }
}
