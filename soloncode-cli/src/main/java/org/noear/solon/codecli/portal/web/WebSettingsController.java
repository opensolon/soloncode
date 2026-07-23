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
package org.noear.solon.codecli.portal.web;

import org.noear.snack4.ONode;
import org.noear.solon.codecli.portal.web.settings.BaseSettingsController;
import org.noear.solon.core.handle.UploadedFile;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.config.entity.GeneralGroupDo;
import org.noear.solon.codecli.config.entity.LoopGroupDo;
import org.noear.solon.codecli.config.entity.PermissionGroupDo;
import org.noear.solon.codecli.config.entity.ApiSourceDo;
import org.noear.solon.codecli.config.entity.LspServerDo;
import org.noear.solon.codecli.config.entity.McpServerDo;
import org.noear.solon.codecli.config.entity.ModelDo;
import org.noear.solon.codecli.config.entity.MountDo;
import org.noear.solon.codecli.portal.FileWatchService;
import org.noear.solon.codecli.portal.web.market.Market;
import org.noear.solon.codecli.portal.web.service.SkinService;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;

/**
 * Web 设置控制器 —— SolonCode Web UI 的设置管理 HTTP 入口。
 *
 * <p>职责：管理 LLM 模型配置（增删改查、导入导出）、MCP 服务器配置（增删改查、连接检测）和 OpenApi 服务器配置。</p>
 *
 * <h3>主要功能分组</h3>
 * <ul>
 *   <li><b>LLM 模型管理</b>：从远程拉取模型列表、动态添加/移除/更新模型、设置默认模型、导入导出</li>
 *   <li><b>MCP 服务器管理</b>：服务器列表查询、添加/移除/更新、启用停用、连接检测、批量导入</li>
 *   <li><b>OpenApi 服务器管理</b>：服务器列表查询、添加/移除/更新、启用停用、连接检测、批量导入</li>
 *   <li><b>技能市场</b>：通过 {@link Market} 接口代理技能浏览、搜索和安装（委派给具体适配器）</li>
 * </ul>
 *
 * <p>所有配置统一通过 {@link AgentSettings} 持久化到单一文件 {@code settings.json}。</p>
 *
 * @author oisin 2026-3-13
 * @author noear 2026-4-18
 * @see WebController Web 主控制器
 * @see Market 技能市场接口
 * @see AgentSettings 统一配置管理
 */
public class WebSettingsController extends BaseSettingsController {
    /**
     * 日志记录器
     */
    private static final Logger LOG = LoggerFactory.getLogger(WebSettingsController.class);

    public WebSettingsController(HarnessEngine engine, AgentSettings settings, FileWatchService fileWatchService, WebGate webGate) {
        super(engine, settings, fileWatchService, webGate);
    }


    // ==================== 配置持久化 ====================


    /**
     * 从磁盘显式重载 settings.json 到当前实例，并差分应用到引擎。
     * <p>用于多实例共享 {@code ~/.soloncode/settings.json} 时，其它实例手动拉齐内存/引擎状态。</p>
     * <p>不会写回磁盘。mountPools / lspServers 仅更新内存，运行时完全生效可能需重启。</p>
     *
     * @param apply 是否应用引擎侧变更（默认 true）；false 时仅刷新内存，便于调试
     */
    @Post
    @Mapping("/web/settings/reload")
    public Result settingsReload(@Param(value = "apply", defaultValue = "true") boolean apply) {
        try {
            // 1) 快照旧状态（供差分；与 settings 同一 monitor，降低并发交错）
            String oldDefaultModel;
            String oldGeneralFp;
            String oldPermissionFp;
            String oldLoopFp;
            String oldProvidersFp;
            Map<String, ModelDo> oldModels;
            Map<String, McpServerDo> oldMcp;
            Map<String, ApiSourceDo> oldApi;
            Map<String, MountDo> oldMounts;
            Map<String, LspServerDo> oldLsp;
            boolean changed;

            synchronized (settings) {
                oldDefaultModel = settings.getDefaultModel();
                oldGeneralFp = configFingerprint(settings.getGeneral());
                oldPermissionFp = configFingerprint(settings.getPermission());
                oldLoopFp = configFingerprint(settings.getLoop());
                oldProvidersFp = configFingerprint(settings.getProviders());
                oldModels = new LinkedHashMap<>(settings.getModels());
                oldMcp = new LinkedHashMap<>(settings.getMcpServers());
                oldApi = new LinkedHashMap<>(settings.getApiServers());
                oldMounts = new LinkedHashMap<>(settings.getMountPools());
                oldLsp = new LinkedHashMap<>(settings.getLspServers());

                // 2) 读盘 in-place（parse 成功后才 mutate；含 null→默认回落）
                changed = settings.reloadInPlace();
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reloaded", changed);
            data.put("source", buildReloadSourceInfo());

            if (!changed) {
                data.put("changed", Collections.emptyMap());
                data.put("applied", Collections.emptyList());
                data.put("warnings", Collections.emptyList());
                return Result.succeed(data);
            }

            // 3) 变更摘要（按内容指纹；group 也按指纹，避免无变仍强制 apply）
            boolean generalChanged = !Objects.equals(oldGeneralFp, configFingerprint(settings.getGeneral()));
            boolean permissionChanged = !Objects.equals(oldPermissionFp, configFingerprint(settings.getPermission()));
            boolean loopChanged = !Objects.equals(oldLoopFp, configFingerprint(settings.getLoop()));
            boolean providersChanged = !Objects.equals(oldProvidersFp, configFingerprint(settings.getProviders()));
            boolean defaultModelChanged = !Objects.equals(oldDefaultModel, settings.getDefaultModel());

            Map<String, Object> changedMap = new LinkedHashMap<>();
            changedMap.put("general", generalChanged);
            changedMap.put("permission", permissionChanged);
            changedMap.put("loop", loopChanged);
            changedMap.put("defaultModel", defaultModelChanged);
            List<String> modelChanges = diffConfigMap(oldModels, settings.getModels());
            List<String> mcpChanges = diffConfigMap(oldMcp, settings.getMcpServers());
            List<String> apiChanges = diffConfigMap(oldApi, settings.getApiServers());
            List<String> mountChanges = diffConfigMap(oldMounts, settings.getMountPools());
            List<String> lspChanges = diffConfigMap(oldLsp, settings.getLspServers());
            changedMap.put("models", modelChanges);
            changedMap.put("mcpServers", mcpChanges);
            changedMap.put("apiServers", apiChanges);
            changedMap.put("mountPools", mountChanges);
            changedMap.put("lspServers", lspChanges);
            changedMap.put("providers", providersChanged); // providers 无 engine 对象，仅内存
            data.put("changed", changedMap);

            List<String> applied = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            // 4) apply engine（仅对真正变更的分组）
            if (apply) {
                if (generalChanged) {
                    applyGeneralToEngine(settings.getGeneral(), applied, warnings);
                }
                if (permissionChanged) {
                    applyPermissionToEngine(settings.getPermission(), applied, warnings);
                }
                if (loopChanged) {
                    applied.add("loop"); // loop 仅内存，LoopScheduler 读 settings
                }

                if (defaultModelChanged) {
                    applyDefaultModel(settings.getDefaultModel(), settings.getModels(), applied, warnings);
                }

                applyModelsDiff(oldModels, settings.getModels(), applied, warnings);
                applyMcpDiff(oldMcp, settings.getMcpServers(), applied, warnings);
                applyApiDiff(oldApi, settings.getApiServers(), applied, warnings);

                if (!mountChanges.isEmpty()) {
                    warnings.add("mountPools changed; memory updated, restart recommended for full runtime effect");
                }
                if (!lspChanges.isEmpty()) {
                    warnings.add("lspServers changed; memory updated, restart recommended for full runtime effect");
                }
            }

            data.put("applied", applied);
            data.put("warnings", warnings);

            // 5) 通知前端
            if (webGate != null) {
                try {
                    ONode evt = new ONode().asObject()
                            .set("type", "settings_reloaded")
                            .set("changed", changedMap);
                    webGate.broadcastRaw(evt.toJson());
                } catch (Exception e) {
                    LOG.debug("[Settings] broadcast settings_reloaded failed: {}", e.getMessage());
                }
            }

            LOG.info("[Settings] Reloaded from disk: applied={}, warnings={}", applied, warnings);
            return Result.succeed(data);
        } catch (Exception e) {
            LOG.warn("[Settings] Reload failed: {}", e.getMessage());
            return Result.failure("reload failed: " + e.getMessage());
        }
    }

    private Map<String, Object> buildReloadSourceInfo() {
        Path globalFile = Paths.get(AgentFlags.getUserHome(), ".soloncode", "settings.json").toAbsolutePath();
        Path localFile = Paths.get(AgentFlags.getUserDir(), ".soloncode", "settings.json").toAbsolutePath();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("global", globalFile.toString());
        source.put("local", localFile.toString());
        source.put("globalExists", Files.exists(globalFile));
        source.put("localExists", Files.exists(localFile) && !localFile.toString().equals(globalFile.toString()));
        return source;
    }

    /**
     * 按内容指纹做 map 差分：+ 新增，- 删除，~ 内容变更。内容相同则不列入。
     */
    private static <V> List<String> diffConfigMap(Map<String, V> oldMap, Map<String, V> newMap) {
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

    private static String configFingerprint(Object value) {
        if (value == null) {
            return "";
        }
        return ONode.ofBean(value).toJson();
    }

    private void applyDefaultModel(String defaultModel, Map<String, ModelDo> models,
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
     * 将 general 配置热应用到引擎（对齐 generalSave + 启动期可热更新字段）。
     * 调用前 settings 已 fillRuntimeDefaults，关键字段通常非 null。
     */
    private void applyGeneralToEngine(GeneralGroupDo g, List<String> applied, List<String> warnings) {
        try {
            engine.setCompressionThreshold(g.getSummaryWindowSize(), g.getSummaryWindowToken());
            engine.setSessionWindowSize(g.getSessionWindowSize());
            engine.setModelRetries(g.getModelRetries());
            engine.setMcpRetries(g.getMcpRetries());
            engine.setApiRetries(g.getApiRetries());
            engine.setSandboxEnabled(g.getSandboxMode());
            engine.setSandboxAllowUserHome(g.getSandboxAllowUserHome());
            engine.setSandboxSystemRestrict(g.getSandboxSystemRestrict());
            engine.setBashAsyncEnabled(g.getBashAsyncEnabled());
            engine.setMemoryEnabled(g.getMemoryEnabled());
            engine.setSubagentEnabled(g.getSubagentEnabled());
            engine.setMaxTurns(g.getMaxTurns());
            engine.setHitlEnabled(g.getHitlEnabled());

            if (engine.getMcpGatewayTalent() != null) {
                engine.getMcpGatewayTalent().setEnabled(g.getMcpEnabled());
            }
            if (engine.getOpenApiGatewayTalent() != null) {
                engine.getOpenApiGatewayTalent().setEnabled(g.getOpenApiEnabled());
            }
            if (engine.getLspTalent() != null) {
                engine.getLspTalent().setEnabled(g.getLspEnabled());
            }

            // goalsEnabled：热更新 GoalTalent
            try {
                boolean goalsEnabled = g.getGoalsEnabled() != null ? g.getGoalsEnabled() : true;
                for (org.noear.solon.ai.harness.HarnessExtension ext : engine.getExtensions()) {
                    if (ext instanceof org.noear.solon.codecli.command.builtin.GoalExtension) {
                        ((org.noear.solon.codecli.command.builtin.GoalExtension) ext)
                                .getGoalTalent().setEnabled(goalsEnabled);
                        break;
                    }
                }
            } catch (Exception e) {
                warnings.add("goalsEnabled apply failed: " + e.getMessage());
            }

            if (g.getLogLevel() != null && !g.getLogLevel().isEmpty()) {
                ch.qos.logback.classic.Level level = ch.qos.logback.classic.Level.toLevel(g.getLogLevel(), null);
                if (level != null) {
                    ch.qos.logback.classic.Logger rootLogger =
                            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
                    rootLogger.setLevel(level);
                }
            }

            applied.add("general");

            // 无公开热更新 API 的字段：仅提示一次
            if (g.getAutoRethink() != null || g.getMemoryIsolation() != null
                    || g.getLogFileMaxSize() != null || g.getLogMaxHistory() != null) {
                warnings.add("autoRethink / memoryIsolation / log rotation: memory updated; restart recommended for full runtime effect");
            }
        } catch (Exception e) {
            warnings.add("general apply failed: " + e.getMessage());
        }
    }

    private void applyPermissionToEngine(PermissionGroupDo p, List<String> applied, List<String> warnings) {
        try {
            // 白名单 + 黑名单一次重置，只重建一次主 Agent（避免双次 createMainAgent）
            try {
                engine.toolPermissionReset(p.getTools(), p.getDisallowedTools());
            } catch (NoSuchMethodError | AbstractMethodError err) {
                // 兼容尚未升级 harness 的运行环境
                engine.allowToolReset(p.getTools());
                engine.disallowToolReset(p.getDisallowedTools());
            }
            applied.add("permission");
        } catch (Exception e) {
            warnings.add("permission apply failed: " + e.getMessage());
        }
    }

    /**
     * 模型差分应用。
     * <p>与 {@code llmModelsToggle} 对齐：仅 enabled/visibled 变化时只改内存标志、不卸引擎模型；
     * 连接参数等实质内容变更才 remove+add；删除/新增按需处理。</p>
     */
    private void applyModelsDiff(Map<String, ModelDo> oldModels, Map<String, ModelDo> newModels,
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
    private static String modelRuntimeFingerprint(ModelDo m) {
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

    private void applyMcpDiff(Map<String, McpServerDo> oldMap, Map<String, McpServerDo> newMap,
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

    private void applyApiDiff(Map<String, ApiSourceDo> oldMap, Map<String, ApiSourceDo> newMap,
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

    // ==================== 设置：General 通用配置 ====================

    /**
     * 获取通用配置
     */
    @Get
    @Mapping("/web/settings/general")
    public Result<GeneralGroupDo> generalGet() {
        return Result.succeed(settings.getGeneral());
    }

    /**
     * 保存通用配置
     */
    @Post
    @Mapping("/web/settings/general/save")
    public Result generalSave(@Body String json) throws Exception {
        ONode tmp = ONode.ofJson(json);
        if (tmp.isObject()) {
            tmp.bindTo(settings.getGeneral());

            // 处理 webAuthUser/webAuthPass 清空：bindTo 遇到 null 值会跳过，需要手动处理
            if (tmp.get("webAuthUser").isNull()) {
                settings.getGeneral().setWebAuthUser(null);
            }
            if (tmp.get("webAuthPass").isNull()) {
                settings.getGeneral().setWebAuthPass(null);
            }

            engine.setCompressionThreshold(settings.getGeneral().getSummaryWindowSize(), settings.getGeneral().getSummaryWindowToken());
            engine.setSessionWindowSize(settings.getGeneral().getSessionWindowSize());

            engine.setModelRetries(settings.getGeneral().getModelRetries());
            engine.setMcpRetries(settings.getGeneral().getMcpRetries());
            engine.setApiRetries(settings.getGeneral().getApiRetries());

            engine.setSandboxEnabled(settings.getGeneral().getSandboxMode());
            engine.setSandboxAllowUserHome(settings.getGeneral().getSandboxAllowUserHome());
            engine.setSandboxSystemRestrict(settings.getGeneral().getSandboxSystemRestrict());

            engine.setBashAsyncEnabled(settings.getGeneral().getBashAsyncEnabled());
            engine.setMemoryEnabled(settings.getGeneral().getMemoryEnabled());
            engine.setSubagentEnabled(settings.getGeneral().getSubagentEnabled());


            engine.getMcpGatewayTalent().setEnabled(settings.getGeneral().getMcpEnabled());
            engine.getOpenApiGatewayTalent().setEnabled(settings.getGeneral().getOpenApiEnabled());
            engine.getLspTalent().setEnabled(settings.getGeneral().getLspEnabled());

            // 动态应用日志级别
            if (tmp.hasKey("logLevel") && !tmp.get("logLevel").isNull()) {
                String logLevel = tmp.get("logLevel").getString();
                if (logLevel != null && !logLevel.isEmpty()) {
                    ch.qos.logback.classic.Level level = ch.qos.logback.classic.Level.toLevel(logLevel, null);
                    if (level != null) {
                        ch.qos.logback.classic.Logger rootLogger =
                                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                                        org.slf4j.Logger.ROOT_LOGGER_NAME);
                        rootLogger.setLevel(level);
                    }
                }
            }
        }

        saveSettings();
        return Result.succeed();
    }

    // ==================== 设置：皮肤 Skin ====================

    /**
     * 皮肤列表：预置 + 本地安装，并返回当前激活皮肤
     */
    @Get
    @Mapping("/web/settings/skins/list")
    public Result skinsList() {
        List<Map<String, Object>> skins = new ArrayList<>();

        // 预置
        String[][] builtins = new String[][]{
                {"default", "默认", "纯净默认外观"},
                {"eyecare", "护眼", "柔和暖绿，长时间阅读更舒适"},
                {"contrast", "高对比", "强化可读性，无装饰背景"}
        };
        String active = normalizeActiveSkin(settings.getGeneral().getActiveSkin());
        for (String[] b : builtins) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", b[0]);
            item.put("displayName", b[1]);
            item.put("description", b[2]);
            item.put("source", "builtin");
            item.put("active", b[0].equals(active));
            item.put("hasPreview", false);
            skins.add(item);
        }

        // 本地
        for (Map<String, Object> local : skinService.listInstalled()) {
            local.put("active", String.valueOf(local.get("name")).equals(active));
            skins.add(local);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activeSkin", active);
        data.put("skins", skins);
        return Result.succeed(data);
    }

    /**
     * 安装本地 zip 皮肤。
     *
     * <p>两种方式：</p>
     * <ul>
     *   <li>multipart 上传：表单字段 {@code file}</li>
     *   <li>工作区路径：查询参数 {@code file=xxx.zip}（相对当前 workspace，供聊天一键安装链接）</li>
     * </ul>
     */
    @Post
    @Mapping("/web/settings/skins/install")
    public Result skinsInstall(Context ctx, String file) throws Exception {
        try {
            String name;
            if (!Assert.isEmpty(file)) {
                // 聊天一键安装：从工作区相对路径读取 zip
                name = installSkinFromWorkspaceFile(file);
            } else {
                UploadedFile uploaded = ctx.file("file");
                if (uploaded == null) {
                    return Result.failure("请上传皮肤 zip 文件，或提供 file 工作区路径参数");
                }
                String filename = uploaded.getName();
                if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    return Result.failure("仅支持 .zip 皮肤包");
                }
                name = skinService.installZip(uploaded.getContent(), filename);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", name);
            return Result.succeed(data);
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            LOG.warn("Install skin failed: {}", e.getMessage());
            return Result.failure("安装失败: " + e.getMessage());
        }
    }

    /**
     * 从当前 workspace 相对路径安装皮肤 zip（防路径穿越，仅 .zip）。
     */
    private String installSkinFromWorkspaceFile(String relativeFile) throws Exception {
        if (Assert.isEmpty(relativeFile)) {
            throw new IllegalArgumentException("缺少 file 参数");
        }
        String rel = relativeFile.trim().replace('\\', '/');
        while (rel.startsWith("./")) {
            rel = rel.substring(2);
        }
        if (rel.startsWith("/") || rel.contains(":") || rel.contains("..")) {
            throw new IllegalArgumentException("非法文件路径");
        }
        if (!rel.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("仅支持 .zip 皮肤包");
        }

        Path workspace = Paths.get(engine.getWorkspace()).toAbsolutePath().normalize();
        Path zipPath = workspace.resolve(rel).normalize();
        if (!zipPath.startsWith(workspace)) {
            throw new IllegalArgumentException("非法文件路径");
        }
        if (!Files.isRegularFile(zipPath)) {
            throw new IllegalArgumentException("皮肤 zip 不存在: " + rel);
        }

        return skinService.installZipFile(zipPath);
    }

    /**
     * 激活皮肤（name 为空或 default 表示恢复默认）
     */
    @Post
    @Mapping("/web/settings/skins/activate")
    public Result skinsActivate(@Body String json) {
        ONode root = ONode.ofJson(json == null ? "{}" : json);
        String name = root.get("name").getString();
        name = normalizeActiveSkin(name);

        if (!"default".equals(name) && !SkinService.builtinNames().contains(name) && !skinService.isInstalled(name)) {
            return Result.failure("皮肤不存在: " + name);
        }

        settings.getGeneral().setActiveSkin("default".equals(name) ? null : name);
        saveSettings();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activeSkin", name);
        return Result.succeed(data);
    }

    /**
     * 卸载本地皮肤；若卸载的是当前激活皮肤则回退默认
     */
    @Post
    @Mapping("/web/settings/skins/uninstall")
    public Result skinsUninstall(@Body String json) throws Exception {
        ONode root = ONode.ofJson(json == null ? "{}" : json);
        String name = root.get("name").getString();
        if (Assert.isEmpty(name)) {
            return Result.failure("缺少皮肤 name");
        }
        try {
            skinService.uninstall(name);
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }

        String active = normalizeActiveSkin(settings.getGeneral().getActiveSkin());
        if (name.equals(active)) {
            settings.getGeneral().setActiveSkin(null);
            active = "default";
            saveSettings();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activeSkin", active);
        return Result.succeed(data);
    }

    /**
     * 导出本地皮肤为 zip（便于分享给朋友再导入）
     */
    @Get
    @Mapping("/web/settings/skins/export")
    public void skinsExport(Context ctx, String name) throws Exception {
        if (Assert.isEmpty(name)) {
            ctx.status(400);
            ctx.output("missing name");
            return;
        }
        try {
            byte[] zip = skinService.exportZip(name);
            String safeName = name.replaceAll("[^a-zA-Z0-9_-]", "_");
            if (safeName.isEmpty()) {
                safeName = "skin";
            }
            ctx.contentType("application/zip");
            ctx.headerSet("Content-Disposition", "attachment; filename=\"" + safeName + ".zip\"");
            ctx.headerSet("Cache-Control", "no-cache");
            ctx.output(zip);
        } catch (IllegalArgumentException e) {
            ctx.status(400);
            ctx.output(e.getMessage());
        } catch (Exception e) {
            LOG.warn("Export skin failed: {}", e.getMessage());
            ctx.status(500);
            ctx.output("export failed: " + e.getMessage());
        }
    }

    /**
     * 代理读取本地皮肤文件（skin.css / preview / assets/*）
     */
    @Get
    @Mapping("/web/settings/skins/file")
    public void skinsFile(Context ctx, String name, String file) throws Exception {
        if (Assert.isEmpty(name) || Assert.isEmpty(file)) {
            ctx.status(400);
            ctx.output("missing name or file");
            return;
        }
        // CSS 需要改写相对 url
        if ("skin.css".equals(file)) {
            String css = skinService.loadCssWithRewrittenUrls(name);
            if (css == null) {
                ctx.status(404);
                ctx.output("skin css not found");
                return;
            }
            ctx.contentType("text/css; charset=utf-8");
            ctx.headerSet("Cache-Control", "no-cache");
            ctx.output(css);
            return;
        }

        Path path = skinService.resolveSkinFile(name, file);
        if (path == null) {
            ctx.status(404);
            ctx.output("file not found");
            return;
        }
        byte[] bytes = Files.readAllBytes(path);
        ctx.contentType(skinService.guessContentType(file));
        ctx.headerSet("Cache-Control", "private, max-age=3600");
        ctx.output(bytes);
    }

    private String normalizeActiveSkin(String name) {
        if (Assert.isEmpty(name) || "default".equals(name)) {
            return "default";
        }
        if (!skinService.isValidSkinName(name)) {
            return "default";
        }
        if (SkinService.builtinNames().contains(name)) {
            return name;
        }
        if (skinService.isInstalled(name)) {
            return name;
        }
        return "default";
    }

    // ==================== 设置：Loop Goal 配置 ====================

    /**
     * 获取 Loop Goal 配置
     */
    @Get
    @Mapping("/web/settings/loop")
    public Result<LoopGroupDo> loopGet() {
        return Result.succeed(settings.getLoop());
    }

    /**
     * 保存 Loop Goal 配置
     */
    @Post
    @Mapping("/web/settings/loop/save")
    public Result loopSave(@Body String json) throws Exception {
        ONode tmp = ONode.ofJson(json);
        if (tmp.isObject()) {
            tmp.bindTo(settings.getLoop());
        }
        saveSettings();
        return Result.succeed();
    }

    // ==================== 设置：Permission 工具权限配置 ====================

    /**
     * 获取全局工具权限配置（白名单 tools / 黑名单 disallowedTools）
     */
    @Get
    @Mapping("/web/settings/permission")
    public Result<PermissionGroupDo> permissionGet() {
        return Result.succeed(settings.getPermission());
    }

    /**
     * 保存全局工具权限配置。
     * <p>tools 为允许白名单（支持通配，如 {@code **}、{@code mcp__*}），留空等价于放开全部；
     * disallowedTools 为禁用黑名单。保存后通过 engine.allowToolReset / disallowToolReset 热更新，
     * 引擎会重建主 Agent 即时生效，无需重启。</p>
     */
    @Post
    @Mapping("/web/settings/permission/save")
    public Result permissionSave(@Body String json) throws Exception {
        ONode root = ONode.ofJson(json);
        if (root.isObject() == false) {
            return Result.failure("invalid body");
        }

        List<String> disallowedTools = new ArrayList<>();
        if (root.hasKey("disallowedTools") && root.get("disallowedTools").isArray()) {
            for (ONode item : root.get("disallowedTools").getArray()) {
                String v = item.getString();
                if (Assert.isNotEmpty(v) && disallowedTools.contains(v) == false) {
                    disallowedTools.add(v.trim());
                }
            }
        }

        // 先清空再写入，避免 final List 叠加导致重复
        settings.getPermission().getDisallowedTools().clear();
        settings.getPermission().getDisallowedTools().addAll(disallowedTools);

        // 热更新到引擎（会重建主 Agent 即时生效）
        engine.disallowToolReset(settings.getPermission().getDisallowedTools());

        saveSettings();
        LOG.info("[Settings] Permission updated: disallowedTools={}", disallowedTools);
        return Result.succeed();
    }
}