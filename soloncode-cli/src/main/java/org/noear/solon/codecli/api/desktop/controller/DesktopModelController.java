/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.api.desktop.controller;

import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Post;
import org.noear.solon.codecli.loop.LoopScheduler;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.model.discovery.ModelApiUrl;
import org.noear.solon.codecli.model.discovery.ModelInfo;
import org.noear.solon.codecli.model.discovery.ModelsAdapter;
import org.noear.solon.codecli.model.discovery.ModelsAdapterManager;
import org.noear.solon.codecli.session.SessionManager;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Desktop 模型管理 Controller。
 *
 * <p>承载桌面端模型配置的完整生命周期：远程模型列表发现（fetch，含兼容早期
 * GET 客户端的 Legacy 路由）、动态添加（add，覆盖同名配置）、默认模型选择
 * （select）、动态移除（remove，主模型保护）。原 WsController 中的相应方法
 * 随领域拆分迁入，路由与返回结构逐字保留。</p>
 *
 * @author bai
 */
public class DesktopModelController extends AbstractDesktopController {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopModelController.class);

    private final AgentSettings settings;
    private final ModelsAdapterManager modelsAdapterManager;

    public DesktopModelController(HarnessEngine engine, AgentSettings settings, LoopScheduler loopScheduler,
                                  SessionManager sessionManager) {
        super(engine, loopScheduler, sessionManager);
        this.settings = settings;
        this.modelsAdapterManager = ModelsAdapterManager.getInstance();
    }

    /**
     * 通过 ModelsAdapterManager 从远程 API 获取可用模型列表
     */
    @Post
    @Mapping("/desktop/chat/models/fetch")
    public Result<List<Map>> fetchModels(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());
        return fetchModels(root.get("apiUrl").getString(),
                root.get("apiKey").getString(),
                root.get("provider").getString(),
                root.get("model").getString());
    }

    /** 兼容只支持 GET 的早期桌面客户端；新版客户端仍优先使用 POST，避免密钥出现在 URL。 */
    @Get
    @Mapping("/desktop/chat/models/fetch")
    public Result<List<Map>> fetchModelsLegacy(@Param("apiUrl") String apiUrl,
                                               @Param(value = "apiKey", required = false) String apiKey,
                                               @Param(value = "provider", required = false) String provider,
                                               @Param(value = "model", required = false) String model) throws Exception {
        return fetchModels(apiUrl, apiKey, provider, model);
    }

    private Result<List<Map>> fetchModels(String apiUrl, String apiKey, String provider, String model) throws Exception {
        if (Assert.isEmpty(apiUrl)) {
            return Result.failure("apiUrl is required");
        }
        if (apiUrl.length() > 2048 || (apiKey != null && apiKey.length() > 8192)
                || (provider != null && provider.length() > 64) || (model != null && model.length() > 256)) {
            return Result.failure(400, "Model configuration is too long");
        }
        try {
            URI uri = URI.create(apiUrl);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || Assert.isEmpty(uri.getHost())) {
                return Result.failure(400, "Invalid apiUrl");
            }
        } catch (RuntimeException e) {
            return Result.failure(400, "Invalid apiUrl");
        }

        ModelsAdapter modelsAdapter = modelsAdapterManager.getAdapter(provider);
        String baseUrl = modelsAdapter.deriveBaseUrl(apiUrl);
        List<ModelInfo> models = modelsAdapter.fetchModels(settings.getGeneral().getUserAgent(), baseUrl, null, apiKey);

        if (models.isEmpty() && Assert.isNotEmpty(model)) {
            ChatModel chatModel = ChatModel.of(apiUrl)
                    .apiKey(apiKey)
                    .standard(provider)
                    .model(model)
                    .build();
            chatModel.prompt("hi").call();

            models.add(ModelInfo.builder()
                    .id(model)
                    .object("model")
                    .created(System.currentTimeMillis() / 1000)
                    .ownedBy(Assert.isEmpty(provider) ? "openai-compatible" : provider)
                    .build());
        }

        List<Map> list = new ArrayList<>();
        for (ModelInfo mi : models) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", mi.getId());
            item.put("object", mi.getObject());
            item.put("displayName", mi.getDisplayName());
            item.put("ownedBy", mi.getOwnedBy());
            item.put("owned_by", mi.getOwnedBy());
            item.put("type", mi.getType());
            item.put("maxInputTokens", mi.getMaxInputTokens());
            item.put("max_input_tokens", mi.getMaxInputTokens());
            item.put("maxTokens", mi.getMaxTokens());
            item.put("max_tokens", mi.getMaxTokens());
            if (mi.getMaxInputTokens() != null && mi.getMaxInputTokens() > 0) {
                item.put("contextLength", mi.getMaxInputTokens());
                item.put("context_length", mi.getMaxInputTokens());
            } else if (mi.getMaxTokens() != null && mi.getMaxTokens() > 0) {
                item.put("contextLength", mi.getMaxTokens());
                item.put("context_length", mi.getMaxTokens());
            }

            ChatConfig config = new ChatConfig();
            config.setName(mi.getId());
            config.setApiUrl(apiUrl);
            config.setApiKey(apiKey);
            config.setModel(mi.getId());
            if (Assert.isNotEmpty(provider)) {
                config.setStandard(provider);
            }
            engine.removeModel(mi.getId());
            engine.addModel(config);
            list.add(item);
        }

        return Result.succeed(list);
    }

    /**
     * 动态添加模型配置
     */
    @Post
    @Mapping("/desktop/chat/models/add")
    public Result modelsAdd(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());

        String apiUrl = root.get("apiUrl").getString();
        String apiKey = root.get("apiKey").getString();
        String model = root.get("model").getString();
        String provider = root.get("provider").getString();

        if (Assert.isEmpty(apiUrl) || Assert.isEmpty(model)) {
            return Result.failure("apiUrl and model are required");
        }

        String name = root.get("name").getString();
        if (Assert.isEmpty(name)) {
            name = model;
        }

        ChatConfig config = new ChatConfig();
        config.setName(name);
        config.setApiUrl(apiUrl);
        config.setApiKey(apiKey);
        config.setModel(model);
        config.setStandard(provider);
        ModelApiUrl.normalize(config);
        if (Assert.isEmpty(config.getStandard())) {
            config.setStandard(null);
        }

        // timeout
        String timeout = root.get("timeout").getString();
        if (Assert.isNotEmpty(timeout)) {
            config.setTimeout(java.time.Duration.parse(timeout));
        }

        // userAgent
        String userAgent = root.get("userAgent").getString();
        if (Assert.isNotEmpty(userAgent)) {
            config.setUserAgent(userAgent);
        }
        engine.removeModel(model);
        engine.addModel(config);

        LOG.info("[Desktop] Model added: {}", name);
        return Result.succeed(name);
    }

    /**
     * 选择桌面端默认使用的模型。
     */
    @Post
    @Mapping("/desktop/chat/models/select")
    public Result modelsSelect(@Param("modelName") String modelName) throws Exception {
        if (Assert.isEmpty(modelName)) {
            return Result.failure("modelName is required");
        }

        if (engine.getModelOrNil(modelName) == null) {
            return Result.failure("Model not found: " + modelName);
        }

        engine.setDefaultModel(modelName);
        LOG.info("[Desktop] Model selected: {}", modelName);
        return Result.succeed();
    }

    /**
     * 动态移除模型配置
     */
    @Post
    @Mapping("/desktop/chat/models/remove")
    public Result modelsRemove(@Param("modelName") String modelName) throws Exception {
        if (Assert.isEmpty(modelName)) {
            return Result.failure("modelName is required");
        }

        if (modelName.equals(engine.getMainModel().getNameOrModel())) {
            return Result.failure("Cannot remove the active main model");
        }

        engine.removeModel(modelName);

        LOG.info("[Desktop] Model removed: {}", modelName);
        return Result.succeed();
    }
}
