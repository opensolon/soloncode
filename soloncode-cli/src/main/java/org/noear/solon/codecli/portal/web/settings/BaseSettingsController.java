package org.noear.solon.codecli.portal.web.settings;

import org.noear.snack4.ONode;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.talents.lsp.LspManager;
import org.noear.solon.ai.talents.lsp.LspServerParameters;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.config.entity.ApiSourceDo;
import org.noear.solon.codecli.config.entity.LspServerDo;
import org.noear.solon.codecli.config.entity.McpServerDo;
import org.noear.solon.codecli.config.entity.ModelDo;
import org.noear.solon.codecli.config.entity.MountDo;
import org.noear.solon.codecli.config.models.ModelSpecService;
import org.noear.solon.codecli.config.models.ModelsAdapterManager;
import org.noear.solon.codecli.portal.FileWatchService;
import org.noear.solon.codecli.portal.web.WebGate;
import org.noear.solon.codecli.market.MarketManager;
import org.noear.solon.codecli.portal.web.service.SkinService;

import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 *
 * @author noear 2026/7/23 created
 *
 */
public class BaseSettingsController {
    private static final Logger LOG = LoggerFactory.getLogger(BaseSettingsController.class);

    private final WorkspaceManager workspaceManager;

    /**
     * 本地皮肤服务（Zip 安装 / 列表 / 资源代理）
     */
    protected final SkinService skinService;

    /**
     * 技能市场适配器（通过构造函数注入，方便切换不同市场）
     */
    protected final MarketManager marketManager;

    /**
     * 模型提供商工厂，用于拉取模型列表
     */
    protected final ModelsAdapterManager modelsAdapterManager;

    /**
     * 模型规格参考服务，用于从 models.json 获取上下文大小
     */
    protected final ModelSpecService modelSpecService;


    // fileWatchService()/webGate() 从当前工作区上下文动态提取；
    // 不再构造注入全局实例——注入字段从未被使用，且跨工作区场景下全局实例语义也是错的

    // 动态提取所属工作区的引擎和服务
    public WorkspaceContext currentContext() {
        Context ctx = Context.current();
        org.noear.solon.codecli.workspace.WorkspaceContext wctx = null;

        if (ctx != null) {
            wctx = ctx.attr("WORKSPACE_CTX");
        }

        if (wctx == null) {
            wctx = workspaceManager.getOrCreate(null);
        }
        return wctx;
    }

    protected HarnessEngine engine() { return currentContext().getEngine(); }
    protected AgentSettings settings() { return currentContext().getSettings(); }
    protected FileWatchService fileWatchService() { return currentContext().getFileWatchService(); }
    protected WebGate webGate() { return currentContext().getWebGate(); }

    protected WorkspaceManager workspaceManager() { return workspaceManager; }

    /**
     * 全部已加载工作区上下文。
     * <p>无 WorkspaceManager（轻量/单测上下文）时返回空集合：多工作区同步是「尽力而为」的增强，
     * 不应因缺少全局管理器而使当前工作区的保存失败。</p>
     */
    protected Iterable<WorkspaceContext> allContexts() {
        if (workspaceManager == null) {
            return Collections.emptyList();
        }
        return workspaceManager.getContexts();
    }

    /**
     * 返回所有已加载工作区的引擎（含默认工作区与当前工作区）。
     * <p>多工作区架构下，通用设置、工具权限等“全局”配置保存后必须热更新到全部引擎，
     * 而非仅当前 HTTP 请求所在工作区的引擎；否则其他已加载工作区的开关不会即时生效。</p>
     */
    protected List<HarnessEngine> engines() {
        List<HarnessEngine> list = new ArrayList<>();
        for (WorkspaceContext ctx : workspaceManager.getContexts()) {
            if (ctx != null && ctx.getEngine() != null) {
                list.add(ctx.getEngine());
            }
        }
        return list;
    }

    /**
     * 构造函数：支持自定义所有依赖。
     */
    public BaseSettingsController(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;

        this.skinService = SkinService.getInstance();
        this.marketManager = MarketManager.getInstance();
        this.modelsAdapterManager = ModelsAdapterManager.getInstance();
        this.modelSpecService = ModelSpecService.getInstance();
    }

    /**
     * 将当前配置保存到 settings.json
     */
    protected void saveSettings() {
        settings().saveToFile();
    }

    /**
     * 按 Map 中指定 key 进行不区分大小写排序
     */
    protected void sortByName(List<? extends Map> list, String key) {
        list.sort((a, b) -> {
            String nameA = (String) a.getOrDefault(key, "");
            String nameB = (String) b.getOrDefault(key, "");
            return nameA.compareToIgnoreCase(nameB);
        });
    }

    // ==================== 多工作区同步：公用（全局）设置 ====================

    /**
     * 资产快照：供「重载前 / 重载后」差分使用。
     * <p>Map 均做浅拷贝：{@link AgentSettings#copyFrom} 是原地 clear + putAll，
     * 直接持有原 Map 引用会被重载结果覆盖，差分也就失去意义。</p>
     */
    protected static final class AssetSnapshot {
        final String defaultModel;
        final Map<String, ModelDo> models;
        final Map<String, McpServerDo> mcpServers;
        final Map<String, ApiSourceDo> apiServers;
        final Map<String, LspServerDo> lspServers;
        final Map<String, MountDo> mountPools;
        final List<String> disallowedSkills;

        AssetSnapshot(AgentSettings settings) {
            this.defaultModel = settings.getDefaultModel();
            this.models = new LinkedHashMap<>(settings.getModels());
            this.mcpServers = new LinkedHashMap<>(settings.getMcpServers());
            this.apiServers = new LinkedHashMap<>(settings.getApiServers());
            this.lspServers = new LinkedHashMap<>(settings.getLspServers());
            this.mountPools = new LinkedHashMap<>(settings.getMountPools());
            this.disallowedSkills = new ArrayList<>(settings.getPermission().getDisallowedSkills());
        }
    }

    /**
     * 单个其它工作区的同步上下文：其引擎 + 重载前/后的资产视图。
     */
    protected static final class AssetSyncContext {
        public final WorkspaceContext workspace;
        public final HarnessEngine engine;
        public final AssetSnapshot before;
        public final AssetSnapshot after;
        public final List<String> warnings;

        AssetSyncContext(WorkspaceContext workspace, HarnessEngine engine,
                         AssetSnapshot before, AssetSnapshot after, List<String> warnings) {
            this.workspace = workspace;
            this.engine = engine;
            this.before = before;
            this.after = after;
            this.warnings = warnings;
        }
    }

    /**
     * 带 scope 的「公用」资产设置（models / providers 关联模型 / mcpServers / apiServers /
     * lspServers / mountPools）保存后，同步到其它已加载工作区的上下文与引擎。
     *
     * <p>这些配置与 general / permission 一样具有全局语义：保存只写一次磁盘，但运行期每个工作区
     * 各有独立的 {@link AgentSettings} 与 {@link HarnessEngine}。若只更新当前工作区，
     * 其它工作区必须手动「从磁盘重新加载」或重启才能看到变更——这正是需要补齐的缺口。</p>
     *
     * <p>实现与 {@code applyPermissionToAllEngines} 同构：其它工作区先按工作区视角重新读盘
     * （global + 各自 local 覆盖），再用重载前后的差分热更新各自引擎。
     * 因此 {@code scope=workspace} 的条目不会外泄，各工作区的 local 覆盖也不会被破坏。</p>
     *
     * <p>必须在 {@link #saveSettings()} 之后调用：其它工作区 reload 需读到最新磁盘值。</p>
     */
    protected void syncAssetsToOtherWorkspaces(String tag, Consumer<AssetSyncContext> syncer) {
        WorkspaceContext cur = currentContext();
        List<String> warnings = new ArrayList<>();

        for (WorkspaceContext ctx : allContexts()) {
            if (ctx == null || ctx == cur || ctx.getEngine() == null) {
                continue;
            }
            try {
                AgentSettings ws = ctx.getSettings();
                AssetSnapshot before = new AssetSnapshot(ws);
                ws.reloadInPlace();
                AssetSnapshot after = new AssetSnapshot(ws);
                syncer.accept(new AssetSyncContext(ctx, ctx.getEngine(), before, after, warnings));
            } catch (Exception e) {
                warnings.add(tag + " sync to workspace " + ctx.getMeta().getId() + " failed: " + e.getMessage());
            }
        }

        if (warnings.isEmpty() == false) {
            LOG.warn("[Settings] {} broadcast warnings: {}", tag, warnings);
        }
    }

    /**
     * 让其它已加载工作区重新读盘（global + 各自 local 覆盖），不写盘、不改引擎。
     * <p>适用于 loop / general 中的纯界面字段 / 皮肤等「全局但无引擎对象」的设置：
     * 其它工作区只需把最新磁盘值叠加到自身 local 覆盖之上。</p>
     */
    protected void reloadOtherWorkspaces() {
        WorkspaceContext cur = currentContext();
        for (WorkspaceContext ctx : allContexts()) {
            if (ctx == null || ctx == cur || ctx.getEngine() == null) {
                continue;
            }
            try {
                ctx.getSettings().reloadInPlace();
            } catch (Exception e) {
                LOG.warn("[Settings] reload workspace {} failed: {}", ctx.getMeta().getId(), e.getMessage());
            }
        }
    }

    /**
     * 刷新其它工作区的子代理清单（用户级智能体文件为公用资产）。
     */
    protected void refreshAgentsInOtherWorkspaces(String alias) {
        WorkspaceContext cur = currentContext();
        for (WorkspaceContext ctx : allContexts()) {
            if (ctx == null || ctx == cur || ctx.getEngine() == null) {
                continue;
            }
            try {
                ctx.getEngine().getAgentManager().refreshByMountAlias(alias);
            } catch (Exception e) {
                LOG.warn("[Settings] refresh agents {} in workspace {} failed: {}",
                        alias, ctx.getMeta().getId(), e.getMessage());
            }
        }
    }

    /**
     * 刷新其它工作区的挂载池内容（共享挂载下的技能/智能体变更）。
     */
    protected void refreshMountInOtherWorkspaces(String alias) {
        WorkspaceContext cur = currentContext();
        for (WorkspaceContext ctx : allContexts()) {
            if (ctx == null || ctx == cur || ctx.getEngine() == null) {
                continue;
            }
            try {
                ctx.getEngine().refreshMount(alias);
            } catch (Exception e) {
                LOG.warn("[Settings] refresh mount {} in workspace {} failed: {}",
                        alias, ctx.getMeta().getId(), e.getMessage());
            }
        }
    }

    // ==================== 多工作区同步：各资产 ====================

    /**
     * 模型（含默认模型）保存后同步到其它工作区。
     */
    protected void syncModelsToOtherWorkspaces() {
        syncAssetsToOtherWorkspaces("models", sync -> {
            applyModelsDiff(sync.engine, sync.before.models, sync.after.models, new ArrayList<>(), sync.warnings);
            if (Objects.equals(sync.before.defaultModel, sync.after.defaultModel) == false) {
                applyDefaultModel(sync.engine, sync.after.defaultModel, sync.after.models, new ArrayList<>(), sync.warnings);
            }
        });
    }

    /**
     * MCP 服务器保存后同步到其它工作区。
     */
    protected void syncMcpServersToOtherWorkspaces() {
        syncAssetsToOtherWorkspaces("mcpServers", sync ->
                applyMcpDiff(sync.engine, sync.before.mcpServers, sync.after.mcpServers, new ArrayList<>(), sync.warnings));
    }

    /**
     * OpenApi 服务器保存后同步到其它工作区。
     */
    protected void syncApiServersToOtherWorkspaces() {
        syncAssetsToOtherWorkspaces("apiServers", sync ->
                applyApiDiff(sync.engine, sync.before.apiServers, sync.after.apiServers, new ArrayList<>(), sync.warnings));
    }

    /**
     * LSP 服务器保存后同步到其它工作区。
     */
    protected void syncLspServersToOtherWorkspaces() {
        syncAssetsToOtherWorkspaces("lspServers", sync ->
                applyLspDiff(sync.engine, sync.before.lspServers, sync.after.lspServers, new ArrayList<>(), sync.warnings));
    }

    /**
     * 挂载池保存后同步到其它工作区。
     */
    protected void syncMountPoolsToOtherWorkspaces() {
        syncAssetsToOtherWorkspaces("mountPools", sync ->
                applyMountsDiff(sync.engine,
                        workspaceVisibleMounts(sync.workspace.getMeta().isDefault(), sync.before.mountPools),
                        workspaceVisibleMounts(sync.workspace.getMeta().isDefault(), sync.after.mountPools),
                        new ArrayList<>(), sync.warnings));
    }

    /**
     * 技能禁用集（permission.disallowedSkills）保存后同步到其它工作区。
     */
    protected void syncSkillsToOtherWorkspaces() {
        syncAssetsToOtherWorkspaces("skills", sync -> {
            if (Objects.equals(sync.before.disallowedSkills, sync.after.disallowedSkills)) {
                return;
            }
            try {
                sync.engine.disallowSkillReset(sync.after.disallowedSkills);
            } catch (Exception e) {
                sync.warnings.add("skills apply failed: " + e.getMessage());
            }
        });
    }

    /**
     * 工作区可见的挂载视图：与 {@link AgentSettings#loadForWorkspace} 的隔离规则保持一致——
     * 非默认工作区不继承全局 {@code scope=user} 的 FILES 数据目录挂载，
     * 否则会把它人项目目录泄漏进本工作区文件树。SKILLS / AGENTS 属于能力注入，保留继承。
     */
    protected static Map<String, MountDo> workspaceVisibleMounts(boolean defaultWorkspace, Map<String, MountDo> mounts) {
        if (defaultWorkspace || mounts.isEmpty()) {
            return mounts;
        }

        Map<String, MountDo> visible = new LinkedHashMap<>();
        for (Map.Entry<String, MountDo> entry : mounts.entrySet()) {
            MountDo mount = entry.getValue();
            if (mount.getType() == MountType.FILES && AgentFlags.SCOPE_LOCAL.equals(mount.getScope()) == false) {
                continue;
            }
            visible.put(entry.getKey(), mount);
        }
        return visible;
    }

    // ==================== 资产差分应用（引擎为参数，支持任意工作区） ====================

    /**
     * 默认模型应用（仅当目标模型存在时）。
     */
    protected void applyDefaultModel(HarnessEngine engine, String defaultModel, Map<String, ModelDo> models,
                                     List<String> applied, List<String> warnings) {
        try {
            if (Assert.isEmpty(defaultModel)) {
                warnings.add("defaultModel cleared in settings; engine default kept (no empty default API)");
                return;
            }
            if (models == null || !models.containsKey(defaultModel)) {
                warnings.add("defaultModel points to missing model: " + defaultModel);
                return;
            }
            engine.setDefaultModel(defaultModel);
            applied.add("defaultModel");
        } catch (Exception e) {
            warnings.add("defaultModel apply failed: " + e.getMessage());
        }
    }

    /**
     * 模型差分应用。
     * <p>与 {@code llmModelsToggle} 对齐：仅 enabled/visibled 变化时只改内存标志、不卸引擎模型；
     * 连接参数等实质内容变更才 remove+add；删除/新增按需处理。</p>
     */
    protected void applyModelsDiff(HarnessEngine engine, Map<String, ModelDo> oldModels, Map<String, ModelDo> newModels,
                                   List<String> applied, List<String> warnings) {
        try {
            boolean any = false;

            for (String name : oldModels.keySet()) {
                if (!newModels.containsKey(name)) {
                    try {
                        engine.removeModel(name);
                        any = true;
                    } catch (Exception e) {
                        warnings.add("remove model " + name + " failed: " + e.getMessage());
                    }
                }
            }

            for (Map.Entry<String, ModelDo> e : newModels.entrySet()) {
                String name = e.getKey();
                ModelDo config = e.getValue();
                ModelDo old = oldModels.get(name);
                try {
                    if (old == null) {
                        // 新增：与启动路径一致，始终 add（引擎按 enabled 过滤使用）
                        engine.addModel(config);
                        any = true;
                    } else if (!modelRuntimeFingerprint(old).equals(modelRuntimeFingerprint(config))) {
                        // 连接/身份等实质内容变更：先删后加
                        engine.removeModel(name);
                        engine.addModel(config);
                        any = true;
                    }
                    // 仅 enabled/visibled/scope 等 UI 标志变化：与 toggle 一致，不 rebuild
                } catch (Exception ex) {
                    warnings.add("apply model " + name + " failed: " + ex.getMessage());
                }
            }
            if (any) {
                applied.add("models");
            }
        } catch (Exception e) {
            warnings.add("models diff failed: " + e.getMessage());
        }
    }

    /**
     * 模型“引擎重建”指纹：忽略 enabled/visibled 等仅影响列表展示的字段，
     * 与 llmModelsToggle（只改 enabled、不卸引擎）语义对齐。
     */
    protected static String modelRuntimeFingerprint(ModelDo m) {
        if (m == null) {
            return "";
        }
        ONode n = ONode.ofBean(m);
        if (n.isObject()) {
            n.remove("enabled");
            n.remove("visibled");
            n.remove("scope");
        }
        return n.toJson();
    }

    /**
     * MCP 服务器差分应用。
     */
    protected void applyMcpDiff(HarnessEngine engine, Map<String, McpServerDo> oldMap, Map<String, McpServerDo> newMap,
                                List<String> applied, List<String> warnings) {
        try {
            boolean any = false;

            for (String name : oldMap.keySet()) {
                if (!newMap.containsKey(name)) {
                    try {
                        engine.removeMcpServer(name);
                        any = true;
                    } catch (Exception e) {
                        warnings.add("remove mcp " + name + " failed: " + e.getMessage());
                    }
                }
            }

            for (Map.Entry<String, McpServerDo> e : newMap.entrySet()) {
                String name = e.getKey();
                McpServerDo params = e.getValue();
                McpServerDo old = oldMap.get(name);
                try {
                    if (old == null) {
                        if (params.isEnabled()) {
                            engine.addMcpServer(name, params);
                            any = true;
                        }
                    } else if (!configFingerprint(old).equals(configFingerprint(params))) {
                        engine.removeMcpServer(name);
                        if (params.isEnabled()) {
                            engine.addMcpServer(name, params);
                        }
                        any = true;
                    }
                } catch (Exception ex) {
                    warnings.add("apply mcp " + name + " failed: " + ex.getMessage());
                }
            }
            if (any) {
                applied.add("mcpServers");
            }
        } catch (Exception e) {
            warnings.add("mcpServers diff failed: " + e.getMessage());
        }
    }

    /**
     * OpenApi 服务器差分应用（引擎按 docUrl 标识）。
     */
    protected void applyApiDiff(HarnessEngine engine, Map<String, ApiSourceDo> oldMap, Map<String, ApiSourceDo> newMap,
                                List<String> applied, List<String> warnings) {
        try {
            boolean any = false;

            for (Map.Entry<String, ApiSourceDo> e : oldMap.entrySet()) {
                String name = e.getKey();
                if (!newMap.containsKey(name)) {
                    try {
                        ApiSourceDo src = e.getValue();
                        if (src != null && Assert.isNotEmpty(src.getDocUrl())) {
                            engine.removeApiServer(src.getDocUrl());
                            any = true;
                        }
                    } catch (Exception ex) {
                        warnings.add("remove api " + name + " failed: " + ex.getMessage());
                    }
                }
            }

            for (Map.Entry<String, ApiSourceDo> e : newMap.entrySet()) {
                String name = e.getKey();
                ApiSourceDo source = e.getValue();
                ApiSourceDo old = oldMap.get(name);
                try {
                    if (old == null) {
                        if (source != null && source.isEnabled()) {
                            engine.addApiServer(source);
                            any = true;
                        }
                    } else if (!configFingerprint(old).equals(configFingerprint(source))) {
                        if (Assert.isNotEmpty(old.getDocUrl())) {
                            engine.removeApiServer(old.getDocUrl());
                        }
                        if (source != null && source.isEnabled()) {
                            engine.addApiServer(source);
                        }
                        any = true;
                    }
                } catch (Exception ex) {
                    warnings.add("apply api " + name + " failed: " + ex.getMessage());
                }
            }
            if (any) {
                applied.add("apiServers");
            }
        } catch (Exception e) {
            warnings.add("apiServers diff failed: " + e.getMessage());
        }
    }

    /**
     * LSP 服务器差分应用。
     * <p>内置服务器不落 settings.json：覆盖条目的新增/变更新增覆盖，删除覆盖等价于「恢复内置默认」，
     * 因此移除时若存在同名内置服务器需重建内置条目，而不是把该服务器从引擎摘掉。</p>
     */
    protected void applyLspDiff(HarnessEngine engine, Map<String, LspServerDo> oldMap, Map<String, LspServerDo> newMap,
                                List<String> applied, List<String> warnings) {
        try {
            boolean any = false;
            Map<String, LspServerParameters> builtins = LspManager.buildLspServers();

            for (String name : oldMap.keySet()) {
                if (newMap.containsKey(name)) {
                    continue;
                }
                try {
                    LspServerParameters builtin = builtins.get(name);
                    if (builtin == null) {
                        engine.removeLspServer(name);
                    } else {
                        builtin.setEnabled(isLspCommandInstalled(builtin.getCommand()));
                        engine.addLspServer(name, builtin);
                    }
                    any = true;
                } catch (Exception e) {
                    warnings.add("remove lsp " + name + " failed: " + e.getMessage());
                }
            }

            for (Map.Entry<String, LspServerDo> e : newMap.entrySet()) {
                String name = e.getKey();
                LspServerDo params = e.getValue();
                LspServerDo old = oldMap.get(name);
                try {
                    if (old == null) {
                        if (params.isEnabled()) {
                            engine.addLspServer(name, params);
                            any = true;
                        }
                    } else if (!configFingerprint(old).equals(configFingerprint(params))) {
                        engine.removeLspServer(name);
                        if (params.isEnabled()) {
                            engine.addLspServer(name, params);
                        }
                        any = true;
                    }
                } catch (Exception ex) {
                    warnings.add("apply lsp " + name + " failed: " + ex.getMessage());
                }
            }
            if (any) {
                applied.add("lspServers");
            }
        } catch (Exception e) {
            warnings.add("lspServers diff failed: " + e.getMessage());
        }
    }

    /**
     * 挂载池差分应用。变更条目按「先删后加」重建，保证 writeable / description 等字段生效。
     */
    protected void applyMountsDiff(HarnessEngine engine, Map<String, MountDo> oldMap, Map<String, MountDo> newMap,
                                   List<String> applied, List<String> warnings) {
        try {
            boolean any = false;

            for (String alias : oldMap.keySet()) {
                if (newMap.containsKey(alias)) {
                    continue;
                }
                try {
                    MountDir current = engine.getMount(alias);
                    if (current != null && current.isPrimary()) {
                        continue; // 系统挂载池不可移除
                    }
                    engine.removeMount(alias);
                    any = true;
                } catch (Exception e) {
                    warnings.add("remove mount " + alias + " failed: " + e.getMessage());
                }
            }

            for (Map.Entry<String, MountDo> e : newMap.entrySet()) {
                String alias = e.getKey();
                MountDo mount = e.getValue();
                MountDo old = oldMap.get(alias);
                try {
                    if (old == null) {
                        engine.addMount(toMountDir(alias, mount));
                        any = true;
                    } else if (!configFingerprint(old).equals(configFingerprint(mount))) {
                        engine.removeMount(alias);
                        engine.addMount(toMountDir(alias, mount));
                        any = true;
                    }
                } catch (Exception ex) {
                    warnings.add("apply mount " + alias + " failed: " + ex.getMessage());
                }
            }
            if (any) {
                applied.add("mountPools");
            }
        } catch (Exception e) {
            warnings.add("mountPools diff failed: " + e.getMessage());
        }
    }

    private static MountDir toMountDir(String alias, MountDo mount) {
        return MountDir.builder()
                .alias(alias)
                .description(mount.getDescription())
                .type(mount.getType())
                .path(mount.getPath())
                .primary(mount.isPrimary())
                .enabled(mount.isEnabled())
                .writeable(mount.isWriteable())
                .build();
    }

    private static boolean isLspCommandInstalled(List<String> command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        return LspManager.isCommandAvailable(command.get(0));
    }

    /**
     * 按内容指纹做 map 差分：+ 新增，- 删除，~ 内容变更。内容相同则不列入。
     */
    protected static <V> List<String> diffConfigMap(Map<String, V> oldMap, Map<String, V> newMap) {
        List<String> changes = new ArrayList<>();
        Set<String> oldKeys = oldMap != null ? oldMap.keySet() : Collections.emptySet();
        Set<String> newKeys = newMap != null ? newMap.keySet() : Collections.emptySet();

        for (String k : oldKeys) {
            if (!newKeys.contains(k)) {
                changes.add("-" + k);
            }
        }
        for (String k : newKeys) {
            if (!oldKeys.contains(k)) {
                changes.add("+" + k);
            } else if (!configFingerprint(oldMap.get(k)).equals(configFingerprint(newMap.get(k)))) {
                changes.add("~" + k);
            }
        }
        return changes;
    }

    protected static String configFingerprint(Object value) {
        if (value == null) {
            return "";
        }
        return ONode.ofBean(value).toJson();
    }
}
