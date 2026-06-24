package org.noear.solon.codecli.portal.desktop.provider;

import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Init;
import org.noear.solon.codecli.util.AiApiUrlAdapter;

import java.util.HashMap;
import java.util.Map;

/**
 * ModelProvider 工厂
 */
@Component
public class ModelProviderFactory {



    private final Map<String, ModelProvider> providerMap = new HashMap<>();
    private ModelProvider defaultProvider;

    @Init
    public void init() {
        OpenAIModelProvider openAIModelProvider = new OpenAIModelProvider();
        OllamaModelProvider ollamaModelProvider = new OllamaModelProvider();
        ZhiPuModelProvider zhiPuModelProvider = new ZhiPuModelProvider();
        AnthropicModelProvider anthropicModelProvider = new AnthropicModelProvider();
        providerMap.put(openAIModelProvider.getProviderName(), openAIModelProvider);
        providerMap.put("openai-responses", openAIModelProvider);
        providerMap.put(ollamaModelProvider.getProviderName(), ollamaModelProvider);
        providerMap.put(zhiPuModelProvider.getProviderName(), zhiPuModelProvider);
        providerMap.put(anthropicModelProvider.getProviderName(), anthropicModelProvider);
        providerMap.put("claude", anthropicModelProvider);
        defaultProvider = openAIModelProvider;
    }

    public ModelProvider getProvider(String providerName) {
        String provider = AiApiUrlAdapter.normalizeProvider(providerName, null);
        if (provider == null || provider.isEmpty()) {
            return defaultProvider;
        }
        return providerMap.getOrDefault(provider, defaultProvider);
    }
}
