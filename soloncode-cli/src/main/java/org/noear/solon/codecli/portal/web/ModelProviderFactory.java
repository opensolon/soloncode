package org.noear.solon.codecli.portal.web;

import java.util.HashMap;
import java.util.Map;

/**
 * ModelProvider 工厂
 * 管理不同类型的模型提供商实现
 */
public class ModelProviderFactory {
    private final Map<String, ModelProvider> providerMap = new HashMap<>();
    private ModelProvider defaultProvider;

    public ModelProviderFactory() {
        OpenAIModelProvider openAIModelProvider = new OpenAIModelProvider();
        providerMap.put(openAIModelProvider.getProviderName(), openAIModelProvider);
        defaultProvider = openAIModelProvider;
    }

    /**
     * 根据提供商名称获取对应的 ModelProvider
     * @param providerName 提供商名称（如 openai、ollama、zhipu 等）
     * @return 对应的 ModelProvider，如果不存在则返回默认的 OpenAI 提供商
     */
    public ModelProvider getProvider(String providerName) {
        if (providerName == null || providerName.isEmpty()) {
            return defaultProvider;
        }
        return providerMap.getOrDefault(providerName.toLowerCase(), defaultProvider);
    }

    /**
     * 注册自定义 ModelProvider
     * @param provider 要注册的提供商实现
     */
    public void registerProvider(ModelProvider provider) {
        providerMap.put(provider.getProviderName(), provider);
    }
}
