package org.noear.solon.codecli.portal.web.settings;

import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.config.entity.ModelDo;
import org.noear.solon.codecli.config.entity.ProviderDo;
import org.noear.solon.codecli.config.models.ModelApiUrl;
import org.noear.solon.codecli.config.models.ModelInfo;
import org.noear.solon.codecli.config.models.ModelsAdapter;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author noear 2026/7/23 created
 *
 */
public class LlmSettingController extends BaseSettingsController {
    /**
     * 日志记录器
     */
    private static final Logger LOG = LoggerFactory.getLogger(LlmSettingController.class);

    /** 模型名上下文长度后缀正则，如 glm5.2[1m]、glm5.2[256k] */
    private static final Pattern CONTEXT_SUFFIX_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)?)([km])$", Pattern.CASE_INSENSITIVE);

    /**
     * 构造函数：支持自定义所有依赖。
     */
    public LlmSettingController(WorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    // ==================== 设置：LLM 模型管理 ====================

    /**
     * 获取所有模型配置列表（含启用状态，专供设置面板使用）
     */
    @Get
    @Mapping("/web/settings/llm/models")
    public Result<Map<String, Object>> llmModelsList() {
        Map<String, Object> data = new LinkedHashMap<>();

        List<Map> list = new ArrayList<>();
        for (ModelDo config : settings().getModels().values()) {
            if (config.isVisibled() && config.isSelected()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", config.getNameOrModel());
                item.put("model", config.getModel());
                item.put("standard", config.getStandardOrProvider());
                item.put("apiUrl", config.getApiUrl());
                item.put("apiKey", config.getApiKey());
                item.put("contextLength", config.getContextLength());
                item.put("enabled", config.isEnabled());
                item.put("scope", config.getScope() != null ? config.getScope() : AgentFlags.SCOPE_USER);
                item.put("provider", config.getProvider());  // 所属供应商
                list.add(item);
            }
        }

        sortByName(list, "name");

        data.put("list", list);
        data.put("default", settings().getDefaultModel());

        return Result.succeed(data);
    }

    /**
     * 获取单个模型配置详情（用于编辑/复制时填充表单）
     */
    @Get
    @Mapping("/web/settings/llm/models/get")
    public Result<Map> llmModelsGet(@Param("name") String name) throws Exception {
        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        ModelDo config = null;
        for (ModelDo c : settings().getModels().values()) {
            if (name.equals(c.getNameOrModel())) {
                config = c;
                break;
            }
        }

        if (config == null) {
            return Result.failure("Model not found: " + name);
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("apiUrl", config.getApiUrl());
        item.put("model", config.getModel());
        item.put("name", config.getNameOrModel());
        item.put("apiKey", config.getApiKey());
        item.put("standard", config.getStandardOrProvider());
        item.put("scope", config.getScope() != null ? config.getScope() : AgentFlags.SCOPE_USER);
        item.put("provider", config.getProvider());  // 所属供应商
        if (config.getTimeout() != null) {
            item.put("timeout", config.getTimeout().getSeconds() + "s");
        }
        if (config.getUserAgent() != null) {
            item.put("userAgent", config.getUserAgent());
        }
        if (config.getContextLength() > 0) {
            item.put("contextLength", String.valueOf(config.getContextLength()));
        }
        item.put("isDefault", settings().getDefaultModel() != null && settings().getDefaultModel().equals(config.getNameOrModel()));

        return Result.succeed(item);
    }

    /**
     * 测试模型连接 — 通过 ChatModel 发送 hello 提示语，验证连接可用性
     */
    @Post
    @Mapping("/web/settings/llm/models/fetch")
    public Result llmModelsFetch(String apiUrl, String apiKey, String standard, String model) {
        if (Assert.isEmpty(apiUrl)) {
            return Result.failure("apiUrl is required");
        }

        try {
            ChatModel chatModel = ChatModel.of(apiUrl)
                    .apiKey(apiKey)
                    .standard(standard)
                    .model(model)
                    .userAgent(settings().getGeneral().getUserAgent())
                    .build();

            chatModel.prompt("hi").call();

            return Result.succeed("连接成功：模型服务可用");
        } catch (Exception e) {
            LOG.warn("[Settings] LLM test connection failed: {}", e.getMessage());
            return Result.failure("连接失败: " + e.getMessage());
        }
    }

    /**
     * 动态添加模型配置
     */
    @Post
    @Mapping("/web/settings/llm/models/add")
    public Result llmModelsAdd(@Body ModelDo config, boolean isDefaultModel) throws Exception {
        if (Assert.isEmpty(config.getApiUrl()) || Assert.isEmpty(config.getModel())) {
            return Result.failure("apiUrl and model are required");
        }
        // 模型名带 [1m]/[256k] 后缀时，服务端统一剥离并回填上下文长度
        applyModelContextSuffix(config);
        ModelApiUrl.normalize(config);

        engine().addModel(config);

        if (isDefaultModel) {
            settings().setDefaultModel(config.getNameOrModel());
        }

        settings().getModels().put(config.getNameOrModel(), config);
        saveSettings();

        LOG.info("[Settings] Model added: {}", config.getNameOrModel());
        return Result.succeed(config.getNameOrModel());
    }

    /**
     * 动态移除模型配置
     */
    @Post
    @Mapping("/web/settings/llm/models/remove")
    public Result llmModelsRemove(@Param("name") String name) throws Exception {
        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        engine().removeModel(name);

        settings().getModels().remove(name);
        saveSettings();

        LOG.info("[Settings] Model removed: {}", name);
        return Result.succeed();
    }

    /**
     * 更新模型配置（先删后加）
     */
    @Post
    @Mapping("/web/settings/llm/models/update")
    public Result llmModelsUpdate(@Param("originalName") String originalName, @Body ModelDo config, boolean isDefaultModel) throws Exception {
        if (Assert.isEmpty(originalName)) {
            return Result.failure("originalName is required");
        }

        // 模型名带 [1m]/[256k] 后缀时，服务端统一剥离并回填上下文长度
        applyModelContextSuffix(config);

        // 编辑时保持 provider 关联（防止前端遗漏 provider 字段）
        if (config.getProvider() == null) {
            ChatConfig oldConfig = settings().getModels().get(originalName);
            if (oldConfig != null && oldConfig.getProvider() != null) {
                config.setProvider(oldConfig.getProvider());
            }
        }

        // 先移除旧配置
        engine().removeModel(originalName);
        engine().addModel(config);

        settings().getModels().remove(originalName);
        settings().getModels().put(config.getNameOrModel(), config);
        if (isDefaultModel) {
            settings().setDefaultModel(config.getNameOrModel());
            engine().setDefaultModel(config.getNameOrModel());
        }
        saveSettings();

        LOG.info("[Settings] Model updated: {} -> {}", originalName, config.getNameOrModel());
        return Result.succeed(config.getNameOrModel());
    }

    /**
     * 切换模型启用/禁用状态
     */
    @Post
    @Mapping("/web/settings/llm/models/toggle")
    public Result llmModelsToggle(@Param("name") String name, @Param("enabled") Boolean enabled) throws Exception {
        if (Assert.isEmpty(name) || enabled == null) {
            return Result.failure("name and enabled are required");
        }
        for (ChatConfig config : settings().getModels().values()) {
            if (name.equals(config.getNameOrModel())) {
                config.setEnabled(enabled);
                saveSettings();
                LOG.info("[Settings] Model {} {}", name, enabled ? "enabled" : "disabled");
                return Result.succeed();
            }
        }
        return Result.failure("Model not found: " + name);
    }

    // ==================== 设置：供应商管理 ====================

    /**
     * 获取所有供应商列表
     */
    @Get
    @Mapping("/web/settings/llm/providers")
    public Result<List<Map>> providersList() {
        List<Map> list = new ArrayList<>();
        for (Map.Entry<String, ProviderDo> entry : settings().getProviders().entrySet()) {
            String name = entry.getKey();
            ProviderDo provider = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("standard", provider.getStandard());
            item.put("apiUrl", provider.getApiUrl());
            item.put("apiKey", maskApiKey(provider.getApiKey()));
            item.put("enabled", provider.isEnabled());
            item.put("scope", provider.getScope() != null ? provider.getScope() : AgentFlags.SCOPE_USER);
            item.put("models", provider.getModels());
            list.add(item);
        }

        sortByName(list, "name");
        return Result.succeed(list);
    }

    /**
     * 获取单个供应商详情
     */
    @Get
    @Mapping("/web/settings/llm/providers/get")
    public Result<Map> providersGet(@Param("name") String name) {
        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        ProviderDo provider = settings().getProviders().get(name);
        if (provider == null) {
            return Result.failure("Provider not found: " + name);
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("standard", provider.getStandard());
        item.put("apiUrl", provider.getApiUrl());
        item.put("apiKey", provider.getApiKey());
        item.put("enabled", provider.isEnabled());
        item.put("scope", provider.getScope() != null ? provider.getScope() : AgentFlags.SCOPE_USER);
        item.put("models", provider.getModels());
        return Result.succeed(item);
    }

    /**
     * 添加供应商
     */
    @Post
    @Mapping("/web/settings/llm/providers/add")
    public Result providersAdd(@Body String json) throws Exception {
        ONode root = ONode.ofJson(json);
        String name = root.get("name").getString();

        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        // 检查重名
        if (settings().getProviders().containsKey(name)) {
            return Result.failure("Provider name already exists: " + name);
        }

        ProviderDo provider = new ProviderDo();
        provider.setName(name);
        provider.setStandard(root.get("standard").getString("openai"));
        provider.setApiUrl(root.get("apiUrl").getString());
        provider.setApiKey(root.get("apiKey").getString());
        provider.setEnabled(root.get("enabled").getBoolean(true));
        provider.setScope(root.hasKey("scope") ? root.get("scope").getString() : AgentFlags.SCOPE_USER);
        provider.setModels(parseProviderModels(root));

        // 解析模型列表（直接存储 ModelInfo）
        settings().getProviders().put(name, provider);
        saveSettings();
        LOG.info("[Settings] Provider added: {}", name);
        return Result.succeed();
    }

    /**
     * 更新供应商
     */
    @Post
    @Mapping("/web/settings/llm/providers/update")
    public Result providersUpdate(@Body String json) throws Exception {
        ONode root = ONode.ofJson(json);
        String name = root.get("name").getString();
        String originalName = root.get("originalName").getString();

        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        String lookupName = (originalName != null && !originalName.isEmpty()) ? originalName : name;
        ProviderDo existing = settings().getProviders().get(lookupName);
        if (existing == null) {
            return Result.failure("Provider not found: " + lookupName);
        }

        // 如果名称变更，移除旧 key
        if (!lookupName.equals(name)) {
            settings().getProviders().remove(lookupName);
        }

        ProviderDo provider = new ProviderDo();
        provider.setName(name);
        provider.setStandard(root.hasKey("standard") ? root.get("standard").getString() : existing.getStandard());
        provider.setApiUrl(root.hasKey("apiUrl") ? root.get("apiUrl").getString() : existing.getApiUrl());
        provider.setApiKey(root.hasKey("apiKey") ? root.get("apiKey").getString() : existing.getApiKey());
        provider.setEnabled(root.hasKey("enabled") ? root.get("enabled").getBoolean(true) : existing.isEnabled());
        provider.setScope(root.hasKey("scope") ? root.get("scope").getString() : (existing.getScope() != null ? existing.getScope() : AgentFlags.SCOPE_USER));

        // 解析模型列表（直接存储 ModelInfo）
        if (root.hasKey("models") && root.get("models").isArray()) {
            List<ModelInfo> models = parseProviderModels(root);
            // 防御性：补回前端可能遗漏的手动模型
            if (existing.getModels() != null) {
                Set<String> newModelIds = new HashSet<>();
                for (ModelInfo mi : models) {
                    newModelIds.add(mi.getId());
                }
                for (ModelInfo oldModel : existing.getModels()) {
                    if (oldModel.isManual() && !newModelIds.contains(oldModel.getId())) {
                        models.add(oldModel);
                    }
                }
            }
            provider.setModels(models);
        } else {
            provider.setModels(existing.getModels());
        }

        settings().getProviders().put(name, provider);
        saveSettings();
        LOG.info("[Settings] Provider updated: {}", name);
        return Result.succeed();
    }

    /**
     * 删除供应商（并级联删除所有关联模型）
     */
    @Post
    @Mapping("/web/settings/llm/providers/remove")
    public Result providersRemove(@Param("name") String name) throws Exception {
        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        // 级联删除该供应商下的所有模型
        int removedModels = 0;
        List<String> modelNamesToRemove = new ArrayList<>();
        for (Map.Entry<String, ModelDo> entry : settings().getModels().entrySet()) {
            ModelDo model = entry.getValue();
            if (name.equals(model.getProvider())) {
                modelNamesToRemove.add(entry.getKey());
            }
        }
        for (String modelName : modelNamesToRemove) {
            engine().removeModel(modelName);
            settings().getModels().remove(modelName);
            removedModels++;
        }

        // 若默认模型属于该供应商，一并清空
        String defaultModel = settings().getDefaultModel();
        if (Assert.isNotEmpty(defaultModel) && modelNamesToRemove.contains(defaultModel)) {
            settings().setDefaultModel(null);
        }

        settings().getProviders().remove(name);
        saveSettings();
        LOG.info("[Settings] Provider removed: {}, cascaded models: {}", name, removedModels);
        return Result.succeed();
    }

    /**
     * 切换供应商启用/禁用状态
     */
    @Post
    @Mapping("/web/settings/llm/providers/toggle")
    public Result providersToggle(@Param("name") String name, @Param("enabled") Boolean enabled) throws Exception {
        if (Assert.isEmpty(name) || enabled == null) {
            return Result.failure("name and enabled are required");
        }

        ProviderDo provider = settings().getProviders().get(name);
        if (provider == null) {
            return Result.failure("Provider not found: " + name);
        }

        provider.setEnabled(enabled);

        // 同步关联模型的启用状态
        for (ModelDo model : settings().getModels().values()) {
            if (name.equals(model.getProvider())) {
                model.setVisibled(enabled);
            }
        }

        saveSettings();
        LOG.info("[Settings] Provider {} {}", name, enabled ? "enabled" : "disabled");
        return Result.succeed();
    }

    /**
     * 拉取供应商模型列表
     */
    @Post
    @Mapping("/web/settings/llm/providers/fetch")
    public Result providersFetch(@Param("apiUrl") String apiUrl, @Param("apiKey") String apiKey, @Param("standard") String standard) {
        if (Assert.isEmpty(apiUrl)) {
            return Result.failure("apiUrl is required");
        }

        try {
            // 使用 ModelsAdapterManager 获取对应的提供商
            ModelsAdapter provider = modelsAdapterManager.getAdapter(standard);
            String baseUrl = provider.deriveBaseUrl(apiUrl);

            // 构建请求头
            Map<String, String> headers = new HashMap<>();
            if (apiKey != null && !apiKey.isEmpty()) {
                headers.put("Authorization", "Bearer " + apiKey);
            }

            // 调用提供商获取模型列表
            List<ModelInfo> models = provider.fetchModels(settings().getGeneral().getUserAgent(), baseUrl, headers, apiKey);

            // 按 id 排序，保证每次返回顺序一致
            models.sort(Comparator.comparing(ModelInfo::getId, Comparator.nullsLast(String::compareTo)));

            // 转换为前端需要的格式
            List<Map<String, Object>> modelList = new ArrayList<>();
            for (ModelInfo model : models) {
                // 剥离模型名中的 [1m]/[256k] 后缀，避免影响后续请求
                String modelId = stripContextLengthSuffix(model.getId());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", modelId);
                item.put("object", model.getObject());
                item.put("created", model.getCreated());
                item.put("ownedBy", model.getOwnedBy());
                item.put("owned_by", model.getOwnedBy());
                item.put("type", model.getType());
                item.put("displayName", model.getDisplayName());
                item.put("display_name", model.getDisplayName());
                item.put("maxInputTokens", model.getMaxInputTokens());
                item.put("max_input_tokens", model.getMaxInputTokens());
                item.put("maxTokens", model.getMaxTokens());
                item.put("max_tokens", model.getMaxTokens());
                long contextLength = resolveContextLength(model);
                if (contextLength > 0) {
                    item.put("contextLength", contextLength);
                    item.put("context_length", contextLength);
                }
                modelList.add(item);
            }

            modelList.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(String.valueOf(a.get("id")), String.valueOf(b.get("id"))));

            return Result.succeed(modelList);
        } catch (Exception e) {
            LOG.warn("[Settings] Failed to fetch models: {}", e.getMessage());
            return Result.failure("拉取模型列表失败: " + e.getMessage());
        }
    }

    /**
     * 同步供应商模型到 LLM 模型配置
     */
    @Post
    @Mapping("/web/settings/llm/providers/sync-models")
    public Result providersSyncModels(@Body String json) throws Exception {
        ONode root = ONode.ofJson(json);
        String providerName = root.get("providerName").getString();

        if (Assert.isEmpty(providerName)) {
            return Result.failure("providerName is required");
        }

        ProviderDo provider = settings().getProviders().get(providerName);
        if (provider == null) {
            return Result.failure("Provider not found: " + providerName);
        }

        // 获取供应商的模型列表（现在是 ModelInfo 类型）
        List<ModelInfo> providerModels = provider.getModels();
        if (providerModels == null) {
            providerModels = new ArrayList<>();
        }

        int syncCount = 0;
        String prefix = providerName + "-";

        // 本次期望保留的模型名集合（用于对账清理孤儿模型）
        Set<String> expectedNames = new HashSet<>();

        for (ModelInfo modelInfo : providerModels) {
            // 剥离模型名中的 [1m]/[256k] 后缀，实际请求使用纯模型 id
            String modelId = stripContextLengthSuffix(modelInfo.getId());
            if (Assert.isEmpty(modelId)) {
                continue;
            }

            String modelName = prefix + modelId;
            expectedNames.add(modelName);

            // 如果模型不存在，创建新模型配置
            if (!settings().getModels().containsKey(modelName)) {
                ModelDo modelDo = new ModelDo();
                modelDo.setName(modelName);
                modelDo.setModel(modelId);
                modelDo.setStandard(provider.getStandard());
                modelDo.setApiUrl(provider.getApiUrl());
                modelDo.setApiKey(provider.getApiKey());
                modelDo.setScope(provider.getScope());
                modelDo.setProvider(providerName);
                modelDo.setVisibled(provider.isEnabled());
                modelDo.setSelected(modelInfo.isSelected());

                // 设置 contextLength：优先模型名后缀，其次 maxInputTokens，再次 maxTokens，最后从 models.json 查询
                long suffixLength = parseContextLengthSuffix(modelInfo.getId());
                if (suffixLength > 0) {
                    modelDo.setContextLength(suffixLength);
                } else if (modelInfo.getMaxInputTokens() != null && modelInfo.getMaxInputTokens() > 0) {
                    modelDo.setContextLength(modelInfo.getMaxInputTokens());
                } else if (modelInfo.getMaxTokens() != null && modelInfo.getMaxTokens() > 0) {
                    modelDo.setContextLength(modelInfo.getMaxTokens());
                } else {
                    // 从 models.json 查询上下文大小
                    Long contextLength = modelSpecService.getContextLength(modelId);
                    if (contextLength != null) {
                        modelDo.setContextLength(contextLength);
                    }
                }

                settings().getModels().put(modelName, modelDo);
                engine().addModel(modelDo);
                syncCount++;
            } else {
                // 模型已存在，检查是否需要同步状态
                ModelDo existingModel = settings().getModels().get(modelName);
                if (existingModel != null) { //providerName.equals(existingModel.getProvider())
                    syncCount++;

                    existingModel.setVisibled(provider.isEnabled());
                    existingModel.setSelected(modelInfo.isSelected());

                    // 更新 contextLength：优先模型名后缀，其次 maxInputTokens，再次 maxTokens，最后从 models.json 查询
                    long newContextLength = 0;
                    long suffixLength = parseContextLengthSuffix(modelInfo.getId());
                    if (suffixLength > 0) {
                        newContextLength = suffixLength;
                    } else if (modelInfo.getMaxInputTokens() != null && modelInfo.getMaxInputTokens() > 0) {
                        newContextLength = modelInfo.getMaxInputTokens();
                    } else if (modelInfo.getMaxTokens() != null && modelInfo.getMaxTokens() > 0) {
                        newContextLength = modelInfo.getMaxTokens();
                    } else {
                        // 从 models.json 查询上下文大小
                        Long contextLength = modelSpecService.getContextLength(modelId);
                        if (contextLength != null) {
                            newContextLength = contextLength;
                        }
                    }
                    if (newContextLength > 0 && existingModel.getContextLength() != newContextLength) {
                        existingModel.setContextLength(newContextLength);
                    }

                    existingModel.setStandard(provider.getStandard());
                    existingModel.setApiUrl(provider.getApiUrl());
                    existingModel.setApiKey(provider.getApiKey());
                    existingModel.setScope(provider.getScope());
                }
            }
        }

        // 对账清理：该 provider 名下、属于 sync 命名格式、但已不在供应商模型列表中的孤儿模型，
        // 不物理删除，仅将 selected 置为 false（保留历史配置，从可选列表中隐藏）
        int deselectCount = 0;
        for (Map.Entry<String, ModelDo> entry : settings().getModels().entrySet()) {
            String name = entry.getKey();
            ModelDo m = entry.getValue();
            if (providerName.equals(m.getProvider())
                    && name.startsWith(prefix)
                    && !expectedNames.contains(name)
                    && m.isSelected()) {
                m.setSelected(false);
                deselectCount++;
            }
        }

        if (syncCount > 0 || deselectCount > 0) {
            saveSettings();
        }

        LOG.info("[Settings] Synced {} models from provider: {} (deselected {} orphans)", syncCount, providerName, deselectCount);
        // 返回变更总数（新增/更新 + 取消勾选），使前端在纯取消勾选时也能感知并刷新
        return Result.succeed(syncCount + deselectCount);
    }

    /**
     * API 密钥脱敏处理
     */
    private List<ModelInfo> parseProviderModels(ONode root) {
        List<ModelInfo> models = new ArrayList<>();
        if (root.hasKey("models") == false || root.get("models").isArray() == false) {
            return models;
        }

        for (ONode modelNode : root.get("models").getArray()) {
            ModelInfo modelInfo = new ModelInfo();
            // 剥离模型名中的 [1m]/[256k] 后缀，并自动填充上下文长度
            String modelId = stripContextLengthSuffix(modelNode.get("id").getString());
            long suffixLength = parseContextLengthSuffix(modelNode.get("id").getString());
            modelInfo.setId(modelId);
            modelInfo.setOwnedBy(modelNode.get("ownedBy").getString(modelNode.get("owned_by").getString()));
            modelInfo.setType(modelNode.get("type").getString());
            modelInfo.setObject(modelNode.get("object").getString());
            modelInfo.setCreated(modelNode.get("created").getLong());
            modelInfo.setDisplayName(modelNode.get("displayName").getString(modelNode.get("display_name").getString()));
            if (modelNode.hasKey("maxTokens")) {
                modelInfo.setMaxTokens(modelNode.get("maxTokens").getLong());
            }
            if (modelNode.hasKey("maxInputTokens")) {
                modelInfo.setMaxInputTokens(modelNode.get("maxInputTokens").getLong());
            }
            if (modelNode.hasKey("manual")) {
                modelInfo.setManual(modelNode.get("manual").getBoolean());
            }
            // 勾选状态：默认选中
            modelInfo.setSelected(modelNode.hasKey("selected") ? modelNode.get("selected").getBoolean() : true);

            // 模型名带 [1m]/[256k] 后缀时，自动用后缀解析值填充 maxInputTokens（用户显式指定，优先级最高）
            if (suffixLength > 0) {
                modelInfo.setMaxInputTokens(suffixLength);
            }

            if (Assert.isNotEmpty(modelInfo.getId())) {
                models.add(modelInfo);
            }
        }

        return models;
    }

    private long resolveContextLength(ModelInfo modelInfo) {
        if (modelInfo == null || Assert.isEmpty(modelInfo.getId())) {
            return 0;
        }
        // 优先解析模型名中的 [1m]/[256k] 后缀（用户显式指定，优先级最高）
        long suffixLength = parseContextLengthSuffix(modelInfo.getId());
        if (suffixLength > 0) {
            return suffixLength;
        }
        if (modelInfo.getMaxInputTokens() != null && modelInfo.getMaxInputTokens() > 0) {
            return modelInfo.getMaxInputTokens();
        }
        if (modelInfo.getMaxTokens() != null && modelInfo.getMaxTokens() > 0) {
            return modelInfo.getMaxTokens();
        }

        Long contextLength = modelSpecService.getContextLength(stripContextLengthSuffix(modelInfo.getId()));
        return contextLength == null ? 0 : contextLength;
    }

    /**
     * 解析模型名中的上下文长度后缀，如 glm5.2[1m] -> 1000000，glm5.2[256k] -> 256000
     * 支持 k（千）和 m（百万）单位；无有效后缀返回 -1
     */
    private long parseContextLengthSuffix(String modelId) {
        if (Assert.isEmpty(modelId)) {
            return -1;
        }
        int idx = modelId.lastIndexOf('[');
        if (idx < 0 || modelId.endsWith("]") == false) {
            return -1;
        }
        String suffix = modelId.substring(idx + 1, modelId.length() - 1).trim();
        Matcher m = CONTEXT_SUFFIX_PATTERN.matcher(suffix);
        if (m.matches() == false) {
            return -1;
        }
        double value = Double.parseDouble(m.group(1));
        String unit = m.group(2).toLowerCase();
        return (long) Math.round(value * ("m".equals(unit) ? 1_000_000 : 1_000));
    }

    /**
     * 剥离模型名中的上下文长度后缀，如 glm5.2[1m] -> glm5.2；无有效后缀原样返回
     */
    private String stripContextLengthSuffix(String modelId) {
        if (Assert.isEmpty(modelId) || parseContextLengthSuffix(modelId) < 0) {
            return modelId;
        }
        int idx = modelId.lastIndexOf('[');
        return modelId.substring(0, idx);
    }

    /**
     * 处理单个模型配置的模型名后缀（Claude Code 风格）：
     * 若 model 带 [1m]/[256k] 后缀，则剥离为纯 id，并用后缀解析值设置上下文长度。
     * 后缀为用户在模型名中显式指定，优先级最高，会覆盖已有的上下文长度。
     */
    private void applyModelContextSuffix(ModelDo config) {
        if (config == null) {
            return;
        }
        String rawModel = config.getModel();
        long suffixLength = parseContextLengthSuffix(rawModel);
        if (suffixLength <= 0) {
            return;
        }
        config.setModel(stripContextLengthSuffix(rawModel));
        config.setContextLength(suffixLength);
    }


    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "";
        }
        if (apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }


    /**
     * 从供应商生成模型配置
     */
    @Post
    @Mapping("/web/settings/llm/providers/generate")
    public Result providersGenerate(@Body String json) throws Exception {
        ONode root = ONode.ofJson(json);
        String providerName = root.get("providerName").getString();
        String standard = root.get("standard").getString("openai");
        String apiUrl = root.get("apiUrl").getString();
        String apiKey = root.get("apiKey").getString();
        String scope = root.get("scope").getString(AgentFlags.SCOPE_USER);

        // 解析模型列表
        ONode modelsNode = root.get("models");
        if (!modelsNode.isArray() || modelsNode.getArrayUnsafe().isEmpty()) {
            return Result.failure("请选择要生成的模型");
        }

        // 解析生成选项
        ONode optionsNode = root.get("options");
        String prefix = optionsNode.get("prefix").getString(providerName + "-");
        int timeout = optionsNode.get("timeout").getInt(120);
        boolean setDefault = optionsNode.get("setDefault").getBoolean(false);

        // 生成模型配置
        List<Map<String, Object>> generatedModels = new ArrayList<>();
        for (ONode modelNode : modelsNode.getArray()) {
            String modelId = stripContextLengthSuffix(modelNode.get("id").getString());
            long suffixLength = parseContextLengthSuffix(modelNode.get("id").getString());
            if (Assert.isEmpty(modelId)) {
                continue;
            }

            // 生成模型名称
            String modelName = prefix + modelId;

            // 检查是否已存在同名模型
            if (settings().getModels().containsKey(modelName)) {
                LOG.warn("[Settings] Model already exists, skipping: {}", modelName);
                continue;
            }

            // 创建模型配置
            ModelDo modelDo = new ModelDo();
            modelDo.setName(modelName);
            modelDo.setModel(modelId);
            modelDo.setStandard(standard);
            modelDo.setApiUrl(apiUrl);
            modelDo.setApiKey(apiKey);
            modelDo.setScope(scope);
            modelDo.setProvider(providerName);  // 设置所属供应商

            // 模型名带 [1m]/[256k] 后缀时，自动设置上下文长度（用户显式指定，优先级最高）
            if (suffixLength > 0) {
                modelDo.setContextLength(suffixLength);
            }

            // 设置超时时间
            if (timeout > 0) {
                modelDo.setTimeout(java.time.Duration.ofSeconds(timeout));
            }

            // 保存模型配置
            settings().getModels().put(modelName, modelDo);

            // 注入运行时引擎（即时生效，无需重启）
            engine().addModel(modelDo);

            // 记录生成的模型信息
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", modelName);
            item.put("model", modelId);
            item.put("standard", standard);
            item.put("scope", scope);
            generatedModels.add(item);

            LOG.info("[Settings] Model generated: {} (from provider: {})", modelName, providerName);
        }

        // 如果设置了默认模型，更新默认模型
        if (setDefault && !generatedModels.isEmpty()) {
            String firstModelName = (String) generatedModels.get(0).get("name");
            settings().setDefaultModel(firstModelName);
            LOG.info("[Settings] Default model set to: {}", firstModelName);
        }

        // 保存配置
        saveSettings();

        return Result.succeed(generatedModels);
    }
}