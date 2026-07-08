package org.noear.solon.codecli.portal.web.model;

import org.noear.solon.annotation.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * ModelProvider 工厂
 * 管理不同类型的模型提供商实现
 */
@Component
public class ModelsAdapterManager {
    private final Map<String, ModelsAdapter> adapterMap = new HashMap<>();
    private ModelsAdapter defaultAdapter;

    public ModelsAdapterManager() {
        OpenAIModelsAdapter openAIModelProvider = new OpenAIModelsAdapter();
        AnthropicModelsAdapter anthropicModelsAdapter = new AnthropicModelsAdapter();
        OllamaModelsAdapter ollamaModelsAdapter = new OllamaModelsAdapter();

        adapterMap.put(openAIModelProvider.getStandard(), openAIModelProvider);
        adapterMap.put("openai-responses", openAIModelProvider);
        adapterMap.put(anthropicModelsAdapter.getStandard(), anthropicModelsAdapter);
        adapterMap.put("claude", anthropicModelsAdapter);
        adapterMap.put(ollamaModelsAdapter.getStandard(), ollamaModelsAdapter);
        defaultAdapter = openAIModelProvider;
    }

    /**
     * 根据接口规范获取对应的 ModelsAdapter
     *
     * @param standard 接口规范（如 openai、ollama、anthropic 等）
     * @return 对应的 ModelsAdapter，如果不存在则返回默认的 OpenAI 提供商
     */
    public ModelsAdapter getAdapter(String standard) {
        String normalizedStandard = ModelApiUrl.normalizeStandard(standard);
        if (normalizedStandard == null || normalizedStandard.isEmpty()) {
            return defaultAdapter;
        }
        return adapterMap.getOrDefault(normalizedStandard, defaultAdapter);
    }

    /**
     * 注册自定义 ModelsAdapter
     *
     * @param adapter 要注册的提供商实现
     */
    public void registerAdapter(ModelsAdapter adapter) {
        adapterMap.put(adapter.getStandard(), adapter);
    }
}