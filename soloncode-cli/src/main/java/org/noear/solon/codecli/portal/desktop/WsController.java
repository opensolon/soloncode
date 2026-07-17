package org.noear.solon.codecli.portal.desktop;

import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.config.models.ModelApiUrl;
import org.noear.solon.codecli.config.models.ModelInfo;
import org.noear.solon.codecli.config.models.ModelsAdapter;
import org.noear.solon.codecli.config.models.ModelsAdapterManager;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Desktop Controller
 *
 * @author bai
 */
public class WsController {
    private static final Logger LOG = LoggerFactory.getLogger(WsController.class);

    private final HarnessEngine engine;
    private final AgentSettings settings;
    private final ModelsAdapterManager modelProviderFactory;

    public WsController(HarnessEngine engine, AgentSettings settings, ModelsAdapterManager modelProviderFactory) {
        this.engine = engine;
        this.settings = settings;
        this.modelProviderFactory = modelProviderFactory;
    }

    /**
     * 获取消息详细记录信息
     */
    @Get
    @Mapping("/desktop/version")
    public Result<Map> version() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("version", AgentFlags.getVersion());
        data.put("workspace", engine.getWorkspace());
        return Result.succeed(data);
    }

    /**
     * 重新扫描桌面端指定的挂载池，使新创建的 Skill/Agent 立即进入运行时。
     */
    @Post
    @Mapping("/desktop/settings/mounts/refresh")
    public Result mountsRefresh(@Param("alias") String alias) {
        if (Assert.isEmpty(alias)) {
            return Result.failure("alias is required");
        }

        MountDir mountDir = engine.getMount(alias);
        if (mountDir == null) {
            return Result.failure("挂载池不存在: " + alias);
        }
        if (!mountDir.isEnabled()) {
            return Result.failure("挂载池未启用: " + alias);
        }

        try {
            engine.refreshMount(alias);
            return Result.succeed("刷新成功");
        } catch (Exception e) {
            LOG.warn("[Desktop] Failed to refresh mount {}: {}", alias, e.getMessage());
            return Result.failure("刷新挂载池失败");
        }
    }


    /**
     * 通过 ModelsAdapterManager 从远程 API 获取可用模型列表
     */
    @Get
    @Mapping("/desktop/chat/models/fetch")
    public Result<List<Map>> fetchModels(@Param("apiUrl") String apiUrl, @Param("apiKey") String apiKey, @Param("provider") String provider, @Param("model") String model) throws Exception {
        if (Assert.isEmpty(apiUrl)) {
            return Result.failure("apiUrl is required");
        }

        ModelsAdapter modelsAdapter = modelProviderFactory.getAdapter(provider);
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
