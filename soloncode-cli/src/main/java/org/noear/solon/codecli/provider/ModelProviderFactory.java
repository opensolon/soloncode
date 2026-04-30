package org.noear.solon.codecli.provider;

import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;

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
        providerMap.put(openAIModelProvider.getProviderName(), openAIModelProvider);
        providerMap.put(ollamaModelProvider.getProviderName(), ollamaModelProvider);
        providerMap.put(zhiPuModelProvider.getProviderName(), zhiPuModelProvider);
        defaultProvider = openAIModelProvider;
    }

    public ModelProvider getProvider(String providerName) {
        if (providerName == null || providerName.isEmpty()) {
            return defaultProvider;
        }
        return providerMap.getOrDefault(providerName, defaultProvider);
    }
}
