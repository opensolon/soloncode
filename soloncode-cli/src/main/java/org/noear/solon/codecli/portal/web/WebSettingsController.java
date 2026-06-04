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
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpClientProviders;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.ai.talents.mount.AgentMd;
import org.noear.solon.ai.talents.mount.SkillDir;
import org.noear.solon.ai.talents.openapi.ApiSource;
import org.noear.solon.ai.talents.openapi.ApiSourceClient;
import org.noear.solon.ai.talents.openapi.ApiTool;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.config.GeneralSettings;
import org.noear.solon.codecli.config.entity.ApiSourceDo;
import org.noear.solon.codecli.config.entity.McpServerDo;
import org.noear.solon.codecli.config.entity.ModelDo;
import org.noear.solon.codecli.config.entity.MountDo;
import org.noear.solon.codecli.portal.web.market.Market;
import org.noear.solon.codecli.portal.web.market.MarketManager;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
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
public class WebSettingsController {
    /**
     * 日志记录器
     */
    private static final Logger LOG = LoggerFactory.getLogger(WebSettingsController.class);

    /**
     * AI Agent 执行引擎，提供模型配置管理能力
     */
    private final HarnessEngine engine;

    /**
     * 技能市场适配器（通过构造函数注入，方便切换不同市场）
     */
    private final MarketManager marketManager;

    /**
     * 统一配置管理器，管理 LLM 模型、MCP 服务器、OpenApi 服务器的持久化数据
     */
    private final AgentSettings settings;

    /**
     * 构造函数：使用容器注入的 AgentSettings。
     *
     * @param engine   AI Agent 执行引擎
     * @param settings 统一配置管理器（由 App.initAgentSettings 创建并注册到容器）
     */
    public WebSettingsController(HarnessEngine engine, AgentSettings settings) {
        this(engine, settings, new MarketManager());
    }

    /**
     * 构造函数：支持自定义 MarketManager（用于测试）。
     *
     * @param engine        AI Agent 执行引擎
     * @param settings      统一配置管理器
     * @param marketManager 技能市场管理器
     */
    public WebSettingsController(HarnessEngine engine, AgentSettings settings, MarketManager marketManager) {
        this.engine = engine;
        this.settings = settings;
        this.marketManager = marketManager;
    }

    // ==================== 配置持久化 ====================

    /**
     * 将当前配置保存到 settings.json
     */
    private void saveSettings() {
        settings.saveToFile();
    }

    // ==================== 设置：General 通用配置 ====================

    /**
     * 获取通用配置
     */
    @Get
    @Mapping("/web/settings/general")
    public Result<GeneralSettings> generalGet() {
        return Result.succeed(settings.getGeneral());
    }

    /**
     * 保存通用配置
     */
    @Post
    @Mapping("/web/settings/general/save")
    public Result generalSave(@Body String json) throws Exception {
        GeneralSettings tmp = ONode.ofJson(json).toBean(GeneralSettings.class);
        if (tmp != null) {
            settings.setGeneral(tmp);

            engine.setCompressionThreshold(tmp.getSummaryWindowSize(), tmp.getSummaryWindowToken());
            engine.setSandboxMode(tmp.getSandboxMode());
            engine.setSessionWindowSize(tmp.getSessionWindowSize());

            engine.setModelRetries(tmp.getModelRetries());
            engine.setMcpRetries(tmp.getMcpRetries());
            engine.setApiRetries(tmp.getApiRetries());

        }

        saveSettings();
        return Result.succeed();
    }

    // ==================== 设置：LLM 模型管理 ====================

    /**
     * 获取所有模型配置列表（含启用状态，专供设置面板使用）
     */
    @Get
    @Mapping("/web/settings/llm/models")
    public Result<List<Map>> llmModelsList() {
        List<Map> list = new ArrayList<>();
        for (ChatConfig config : settings.getModels()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", config.getNameOrModel());
            item.put("model", config.getModel());
            item.put("provider", config.getProvider());
            item.put("apiUrl", config.getApiUrl());
            item.put("apiKey", config.getApiKey());
            item.put("enabled", config.isEnabled());
            list.add(item);
        }
        return Result.succeed(list);
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

        ChatConfig config = null;
        for (ChatConfig c : settings.getModels()) {
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
        item.put("provider", config.getProvider());
        if (config.getTimeout() != null) {
            item.put("timeout", config.getTimeout().toString());
        }
        if (config.getUserAgent() != null) {
            item.put("userAgent", config.getUserAgent());
        }
        if (config.getContextLength() > 0) {
            item.put("contextLength", String.valueOf(config.getContextLength()));
        }

        return Result.succeed(item);
    }

    /**
     * 测试模型连接 — 通过 ChatModel 发送 hello 提示语，验证连接可用性
     */
    @Post
    @Mapping("/web/settings/llm/models/fetch")
    public Result llmModelsFetch(String apiUrl, String apiKey, String provider, String model) {
        if (Assert.isEmpty(apiUrl)) {
            return Result.failure("apiUrl is required");
        }

        try {
            ChatModel chatModel = ChatModel.of(apiUrl)
                    .apiKey(apiKey)
                    .provider(provider)
                    .model(model)
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
    public Result llmModelsAdd(@Body ModelDo config) throws Exception {
        if (Assert.isEmpty(config.getApiUrl()) || Assert.isEmpty(config.getModel())) {
            return Result.failure("apiUrl and model are required");
        }

        engine.addModel(config);

        settings.getModels().removeIf(c -> c.getNameOrModel().equals(config.getNameOrModel()));
        settings.getModels().add(config);
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

        engine.removeModel(name);

        settings.getModels().removeIf(c -> name.equals(c.getNameOrModel()));
        saveSettings();

        LOG.info("[Settings] Model removed: {}", name);
        return Result.succeed();
    }

    /**
     * 更新模型配置（先删后加）
     */
    @Post
    @Mapping("/web/settings/llm/models/update")
    public Result llmModelsUpdate(@Param("originalName") String originalName, @Body ModelDo config) throws Exception {
        if (Assert.isEmpty(originalName)) {
            return Result.failure("originalName is required");
        }

        // 先移除旧配置
        engine.removeModel(originalName);
        engine.addModel(config);

        settings.getModels().removeIf(c -> originalName.equals(c.getNameOrModel()));
        settings.getModels().add(config);
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
        for (ChatConfig config : settings.getModels()) {
            if (name.equals(config.getNameOrModel())) {
                config.setEnabled(enabled);
                saveSettings();
                LOG.info("[Settings] Model {} {}", name, enabled ? "enabled" : "disabled");
                return Result.succeed();
            }
        }
        return Result.failure("Model not found: " + name);
    }

    // ==================== 设置：MCP 服务器管理 ====================

    /**
     * 获取已配置的 MCP 服务器列表
     */
    @Get
    @Mapping("/web/settings/mcp/servers")
    public Result<List<Map>> mcpServers() throws Exception {
        List<Map> list = new ArrayList<>();
        for (Map.Entry<String, McpServerDo> entry : settings.getMcpServers().entrySet()) {
            String name = entry.getKey();
            McpServerDo params = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("type", params.getType() != null ? params.getType() : "stdio");
            item.put("enabled", params.isEnabled());
            item.put("scope", params.getScope() != null ? params.getScope() : AgentFlags.SCOPE_GLOBAL);
            if ("stdio".equals(params.getType())) {
                item.put("command", params.getCommand());
                if (params.getArgs() != null) {
                    item.put("args", params.getArgs());
                }
                if (params.getEnv() != null) {
                    item.put("env", params.getEnv());
                }
            } else {
                item.put("url", params.getUrl());
                if (params.getHeaders() != null) {
                    item.put("headers", params.getHeaders());
                }
                if (params.getTimeout() != null) {
                    item.put("timeout", params.getTimeout().toString());
                }
            }
            list.add(item);
        }
        return Result.succeed(list);
    }

    /**
     * 添加 MCP 服务器配置
     */
    @Post
    @Mapping("/web/settings/mcp/servers/add")
    public Result mcpServersAdd(@Body String json) throws Exception {
        ONode root = ONode.ofJson(json);
        String name = root.get("name").getString();
        String type = root.get("type").getString();

        if (Assert.isEmpty(name) || Assert.isEmpty(type)) {
            return Result.failure("name and type are required");
        }

        // 检查重名
        if (settings.getMcpServers().containsKey(name)) {
            return Result.failure("Server name already exists: " + name);
        }

        boolean enabled = root.get("enabled").getBoolean(true);
        String scope = root.hasKey("scope") ? root.get("scope").getString() : AgentFlags.SCOPE_GLOBAL;
        if (Assert.isEmpty(scope) || (!AgentFlags.SCOPE_LOCAL.equals(scope))) {
            scope = AgentFlags.SCOPE_GLOBAL;
        }

        McpServerDo params = new McpServerDo();
        params.setType(type);
        params.setScope(scope);

        if ("stdio".equals(type)) {
            params.setCommand(root.get("command").getString());
            if (root.hasKey("args")) {
                List<String> argsList = new ArrayList<>();
                for (ONode a : root.get("args").getArray()) {
                    argsList.add(a.getString());
                }
                params.setArgs(argsList);
            }
            if (root.hasKey("env")) {
                Map<String, String> envMap = new LinkedHashMap<>();
                for (Map.Entry<String, ONode> entry : root.get("env").getObject().entrySet()) {
                    envMap.put(entry.getKey(), entry.getValue().getString());
                }
                params.setEnv(envMap);
            }
        } else if ("sse".equals(type) || "streamable".equals(type)) {
            params.setUrl(root.get("url").getString());
            if (root.hasKey("headers")) {
                Map<String, String> headersMap = new LinkedHashMap<>();
                for (Map.Entry<String, ONode> entry : root.get("headers").getObject().entrySet()) {
                    headersMap.put(entry.getKey(), entry.getValue().getString());
                }
                params.setHeaders(headersMap);
            }
            if (root.hasKey("timeout")) {
                params.setTimeout(Duration.parse(root.get("timeout").getString()));
            }
        } else {
            return Result.failure("Unsupported type: " + type);
        }

        settings.getMcpServers().put(name, params);

        // 如果启用，同步到引擎
        if (enabled) {
            engine.addMcpServer(name, params);
        }

        saveSettings();
        LOG.info("[Settings] MCP server added: {}", name);
        return Result.succeed();
    }

    /**
     * 移除 MCP 服务器配置
     */
    @Post
    @Mapping("/web/settings/mcp/servers/remove")
    public Result mcpServersRemove(@Body String json) throws Exception {
        ONode root = ONode.ofJson(json);
        String name = root.get("name").getString();

        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        settings.getMcpServers().remove(name);
        saveSettings();
        engine.removeMcpServer(name);
        LOG.info("[Settings] MCP server removed: {}", name);
        return Result.succeed();
    }

    /**
     * 更新 MCP 服务器配置
     */
    @Post
    @Mapping("/web/settings/mcp/servers/update")
    public Result mcpServersUpdate(@Body String json) throws Exception {
        ONode root = ONode.ofJson(json);
        String name = root.get("name").getString();
        String originalName = root.get("originalName").getString();

        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        // 如果 name 变了，使用 originalName 查找旧记录
        String lookupName = (originalName != null && !originalName.isEmpty()) ? originalName : name;

        McpServerDo existing = settings.getMcpServers().get(lookupName);
        if (existing == null) {
            return Result.failure("Server not found: " + lookupName);
        }

        // 如果名称变更，先从引擎移除旧名称
        if (!lookupName.equals(name)) {
            settings.getMcpServers().remove(lookupName);
            engine.removeMcpServer(lookupName);
        } else {
            // 名称没变，仍然先从引擎移除（稍后重新添加）
            engine.removeMcpServer(name);
        }

        // 构建新参数
        String type = root.hasKey("type") ? root.get("type").getString() : existing.getType();
        boolean enabled = root.hasKey("enabled") ? root.get("enabled").getBoolean(true) : true;
        String scope = root.hasKey("scope") ? root.get("scope").getString() : (existing.getScope() != null ? existing.getScope() : AgentFlags.SCOPE_GLOBAL);
        if (Assert.isEmpty(scope) || (!AgentFlags.SCOPE_LOCAL.equals(scope))) {
            scope = AgentFlags.SCOPE_GLOBAL;
        }

        McpServerDo params = new McpServerDo();
        params.setType(type);
        params.setScope(scope);

        if ("stdio".equals(type)) {
            params.setCommand(root.hasKey("command") ? root.get("command").getString() : existing.getCommand());
            if (root.hasKey("args")) {
                List<String> argsList = new ArrayList<>();
                for (ONode a : root.get("args").getArray()) {
                    argsList.add(a.getString());
                }
                params.setArgs(argsList);
            } else {
                params.setArgs(existing.getArgs());
            }
            if (root.hasKey("env")) {
                Map<String, String> envMap = new LinkedHashMap<>();
                for (Map.Entry<String, ONode> entry : root.get("env").getObject().entrySet()) {
                    envMap.put(entry.getKey(), entry.getValue().getString());
                }
                params.setEnv(envMap);
            } else {
                params.setEnv(existing.getEnv());
            }
        } else {
            params.setUrl(root.hasKey("url") ? root.get("url").getString() : existing.getUrl());
            if (root.hasKey("headers")) {
                Map<String, String> headersMap = new LinkedHashMap<>();
                for (Map.Entry<String, ONode> entry : root.get("headers").getObject().entrySet()) {
                    headersMap.put(entry.getKey(), entry.getValue().getString());
                }
                params.setHeaders(headersMap);
            } else {
                params.setHeaders(existing.getHeaders());
            }
            if (root.hasKey("timeout")) {
                params.setTimeout(Duration.parse(root.get("timeout").getString()));
            } else {
                params.setTimeout(existing.getTimeout());
            }
        }

        settings.getMcpServers().put(name, params);

        // 如果启用，同步到引擎
        if (enabled) {
            engine.addMcpServer(name, params);
        }

        saveSettings();
        LOG.info("[Settings] MCP server updated: {}", name);
        return Result.succeed();
    }

    /**
     * 切换 MCP 服务器启用/停用
     */
    @Post
    @Mapping("/web/settings/mcp/servers/toggle")
    public Result mcpServersToggle(@Body String json) throws Exception {
        ONode root = ONode.ofJson(json);
        String name = root.get("name").getString();
        boolean enabled = root.get("enabled").getBoolean();

        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        McpServerParameters params = settings.getMcpServers().get(name);
        if (params == null) {
            return Result.failure("Server not found: " + name);
        }

        if (enabled) {
            // 启用：添加到引擎
            engine.addMcpServer(name, params);
        } else {
            // 停用：从引擎移除
            engine.removeMcpServer(name);
        }

        params.setEnabled(enabled);

        saveSettings();
        LOG.info("[Settings] MCP server toggled: {} -> {}", name, enabled);
        return Result.succeed();
    }

    /**
     * 检测 MCP 服务器连接（不保存配置，仅测试）
     */
    @Post
    @Mapping("/web/settings/mcp/servers/check")
    public Result mcpServersCheck(Context ctx) {
        try {
            ONode root = ONode.ofJson(ctx.body());
            String type = root.get("type").getString();
            if (type == null || type.isEmpty()) type = "stdio";

            if ("stdio".equals(type)) {
                String command = root.get("command").getString();
                if (Assert.isEmpty(command)) {
                    return Result.failure("命令不能为空");
                }

                // 使用 McpClientProvider 进行真实的 MCP 初始化连接测试
                McpClientProvider.Builder builder = McpClientProvider.builder()
                        .channel(org.noear.solon.ai.mcp.McpChannel.STDIO)
                        .command(command);

                // 设置参数
                ONode argsNode = root.get("args");
                if (argsNode != null && argsNode.isArray()) {
                    List<String> argsList = new ArrayList<>();
                    for (ONode a : argsNode.getArray()) {
                        String arg = a.getString();
                        if (arg != null && !arg.isEmpty()) argsList.add(arg);
                    }
                    if (!argsList.isEmpty()) {
                        builder.args(argsList);
                    }
                }

                // 设置环境变量
                ONode envNode = root.get("env");
                if (envNode != null && envNode.isObject() && envNode.getObject().size() > 0) {
                    Map<String, String> envMap = new LinkedHashMap<>();
                    for (Map.Entry<String, ONode> entry : envNode.getObject().entrySet()) {
                        envMap.put(entry.getKey(), entry.getValue().getString());
                    }
                    builder.env(envMap);
                }

                McpClientProvider client = builder.build();
                try {
                    // 通过 getTools() 触发 MCP 初始化握手，验证连接有效性
                    client.getTools();
                    return Result.succeed("连接成功：MCP 初始化握手完成（stdio）");
                } finally {
                    client.close();
                }

            } else if ("sse".equals(type) || "streamable".equals(type)) {
                String url = root.get("url").getString();
                if (Assert.isEmpty(url)) {
                    return Result.failure("URL 不能为空");
                }

                // 使用 McpClientProvider 进行真实的 MCP 初始化连接测试
                String channel = "sse".equals(type)
                        ? org.noear.solon.ai.mcp.McpChannel.SSE
                        : org.noear.solon.ai.mcp.McpChannel.STREAMABLE;

                McpClientProvider.Builder builder = McpClientProvider.builder()
                        .channel(channel)
                        .url(url);

                // 设置自定义 headers
                ONode headersNode = root.get("headers");
                if (headersNode != null && headersNode.isObject()) {
                    Map<String, String> headersMap = new LinkedHashMap<>();
                    for (Map.Entry<String, ONode> entry : headersNode.getObject().entrySet()) {
                        headersMap.put(entry.getKey(), entry.getValue().getString());
                    }
                    builder.headers(headersMap);
                }

                McpClientProvider client = builder.build();
                try {
                    // 通过 getTools() 触发 MCP 初始化握手，验证连接有效性
                    client.getTools();
                    return Result.succeed("连接成功：MCP 初始化握手完成（" + type + "）");
                } finally {
                    client.close();
                }
            }

            return Result.failure("不支持检测的类型: " + type);
        } catch (java.net.ConnectException e) {
            return Result.failure("连接被拒绝，请检查地址和端口是否正确");
        } catch (java.net.SocketTimeoutException e) {
            return Result.failure("连接超时，请检查地址是否可达");
        } catch (java.io.IOException e) {
            return Result.failure("连接失败: " + e.getMessage());
        } catch (Exception e) {
            return Result.failure("检测失败: " + e.getMessage());
        }
    }

    /**
     * 批量导入 MCP 服务器配置
     * 支持 Map 格式: {"mcpServers":{"name1":{...},"name2":{...}}}
     * 支持数组格式: {"mcpServers":[{...},{...}]}
     */
    @Post
    @Mapping("/web/settings/mcp/servers/import")
    public Result mcpServersImport(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());
        ONode serversNode = root.get("mcpServers");
        if (serversNode == null) {
            return Result.failure("Invalid format: mcpServers not found");
        }

        int imported = 0;
        if (serversNode.isObject()) {
            // Map 格式: name -> config
            for (Map.Entry<String, ONode> entry : serversNode.getObject().entrySet()) {
                String name = entry.getKey();
                if (settings.getMcpServers().containsKey(name)) continue;
                ONode src = entry.getValue();
                McpServerDo params = new McpServerDo();
                params.setType(src.hasKey("type") ? src.get("type").getString() : "stdio");
                if (src.hasKey("command")) params.setCommand(src.get("command").getString());
                if (src.hasKey("args")) {
                    List<String> argsList = new ArrayList<>();
                    for (ONode a : src.get("args").getArray()) {
                        argsList.add(a.getString());
                    }
                    params.setArgs(argsList);
                }
                if (src.hasKey("env")) {
                    Map<String, String> envMap = new LinkedHashMap<>();
                    for (Map.Entry<String, ONode> e : src.get("env").getObject().entrySet()) {
                        envMap.put(e.getKey(), e.getValue().getString());
                    }
                    params.setEnv(envMap);
                }
                if (src.hasKey("url")) params.setUrl(src.get("url").getString());
                if (src.hasKey("headers")) {
                    Map<String, String> headersMap = new LinkedHashMap<>();
                    for (Map.Entry<String, ONode> e : src.get("headers").getObject().entrySet()) {
                        headersMap.put(e.getKey(), e.getValue().getString());
                    }
                    params.setHeaders(headersMap);
                }
                if (src.hasKey("timeout")) {
                    params.setTimeout(Duration.parse(src.get("timeout").getString()));
                }
                settings.getMcpServers().put(name, params);
                imported++;
            }
        } else if (serversNode.isArray()) {
            for (ONode src : serversNode.getArray()) {
                String name = src.get("name").getString();
                if (settings.getMcpServers().containsKey(name) || Assert.isEmpty(name)) continue;
                McpServerDo params = new McpServerDo();
                params.setType(src.hasKey("type") ? src.get("type").getString() : "stdio");
                if (src.hasKey("command")) params.setCommand(src.get("command").getString());
                if (src.hasKey("args")) {
                    List<String> argsList = new ArrayList<>();
                    for (ONode a : src.get("args").getArray()) {
                        argsList.add(a.getString());
                    }
                    params.setArgs(argsList);
                }
                if (src.hasKey("env")) {
                    Map<String, String> envMap = new LinkedHashMap<>();
                    for (Map.Entry<String, ONode> e : src.get("env").getObject().entrySet()) {
                        envMap.put(e.getKey(), e.getValue().getString());
                    }
                    params.setEnv(envMap);
                }
                if (src.hasKey("url")) params.setUrl(src.get("url").getString());
                if (src.hasKey("headers")) {
                    Map<String, String> headersMap = new LinkedHashMap<>();
                    for (Map.Entry<String, ONode> e : src.get("headers").getObject().entrySet()) {
                        headersMap.put(e.getKey(), e.getValue().getString());
                    }
                    params.setHeaders(headersMap);
                }
                if (src.hasKey("timeout")) {
                    params.setTimeout(Duration.parse(src.get("timeout").getString()));
                }
                settings.getMcpServers().put(name, params);
                imported++;
            }
        }

        saveSettings();
        LOG.info("[Settings] MCP servers imported: {} items", imported);
        return Result.succeed("Imported " + imported + " server(s)");
    }

    // ==================== 设置：MCP 工具权限管理 ====================

    /**
     * 获取指定 MCP 服务器的工具列表及权限状态
     */
    @Get
    @Mapping("/web/settings/mcp/servers/tools")
    public Result mcpServerTools(String name) throws IOException {
        McpServerParameters serverParameters = settings.getMcpServers().get(name);
        if (serverParameters == null) {
            return Result.failure("Server not found: " + name);
        }

        final Collection<FunctionTool> allTools;
        McpClientProvider provider = engine.getMcpServer(name);
        if (provider == null) {
            provider = McpClientProviders.fromMcpServer(serverParameters);
            try {
                allTools = provider.getTools();
            } finally {
                provider.close();
            }
        } else {
            allTools = provider.getTools();
        }

        List<Map<String, Object>> toolList = new ArrayList<>();
        for (FunctionTool tool : allTools) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.name());
            item.put("inputSchema", tool.inputSchema());
            item.put("description", tool.description());
            toolList.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serverName", name);
        data.put("connected", true);
        data.put("disallowedTools", serverParameters.getDisallowedTools());
        data.put("tools", toolList);
        return Result.succeed(data);
    }

    /**
     * 更新指定 MCP 服务器的工具权限（disallowedTools）
     * <p>通过 engine.refreshMcpServer 影子交换策略热重载，无需重启。</p>
     */
    @Post
    @Mapping("/web/settings/mcp/servers/tools/save")
    public Result mcpServerToolsSave(@Param("serverName") String serverName, @Param("disallowedTools") String[] disallowedTools) throws IOException {
        McpServerDo serverParameters = settings.getMcpServers().get(serverName);
        if (serverParameters == null) {
            return Result.failure("Server not found: " + serverName);
        }

        serverParameters.setDisallowedTools(Arrays.asList(disallowedTools));

        // 同步到引擎 provider 并热重载
        McpClientProvider provider = engine.getMcpServer(serverName);
        if (provider != null) {
            provider.setDisallowedTools(serverParameters.getDisallowedTools());
            engine.refreshMcpServer(serverName);
        }

        saveSettings();
        LOG.info("[Settings] MCP server tools permissions updated: {}", serverName);
        return Result.succeed();
    }

    // ==================== 设置：OpenApi 服务器管理 ====================

    /**
     * 获取已配置的 OpenApi 服务器列表
     */
    @Get
    @Mapping("/web/settings/openapi/servers")
    public Result<List<Map>> openapiServers() throws Exception {
        List<Map> list = new ArrayList<>();
        for (Map.Entry<String, ApiSourceDo> entry : settings.getApiServers().entrySet()) {
            String name = entry.getKey();
            ApiSourceDo source = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("apiBaseUrl", source.getApiBaseUrl());
            item.put("docUrl", source.getDocUrl());
            item.put("enabled", source.isEnabled());
            item.put("scope", source.getScope() != null ? source.getScope() : AgentFlags.SCOPE_GLOBAL);
            if (source.getHeaders() != null) {
                item.put("headers", source.getHeaders());
            }
            list.add(item);
        }
        return Result.succeed(list);
    }

    /**
     * 添加 OpenApi 服务器配置
     */
    @Post
    @Mapping("/web/settings/openapi/servers/add")
    public Result openapiServersAdd(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());
        String name = root.get("name").getString();
        String apiBaseUrl = root.get("apiBaseUrl").getString();

        if (Assert.isEmpty(name) || Assert.isEmpty(apiBaseUrl)) {
            return Result.failure("name and apiBaseUrl are required");
        }

        // 检查重名
        if (settings.getApiServers().containsKey(name)) {
            return Result.failure("Server name already exists: " + name);
        }

        boolean enabled = root.get("enabled").getBoolean(true);
        String scope = root.hasKey("scope") ? root.get("scope").getString() : AgentFlags.SCOPE_GLOBAL;
        if (Assert.isEmpty(scope) || (!AgentFlags.SCOPE_LOCAL.equals(scope))) {
            scope = AgentFlags.SCOPE_GLOBAL;
        }

        ApiSourceDo source = new ApiSourceDo();
        source.setApiBaseUrl(apiBaseUrl);
        source.setDocUrl(root.get("docUrl").getString());
        source.setScope(scope);
        if (root.hasKey("headers")) {
            Map<String, String> headersMap = new LinkedHashMap<>();
            for (Map.Entry<String, ONode> entry : root.get("headers").getObject().entrySet()) {
                headersMap.put(entry.getKey(), entry.getValue().getString());
            }
            source.setHeaders(headersMap);
        }

        settings.getApiServers().put(name, source);

        // 如果启用，同步到引擎
        if (enabled) {
            engine.addApiServer(source);
        }

        saveSettings();
        LOG.info("[Settings] OpenApi server added: {}", name);
        return Result.succeed();
    }

    /**
     * 更新 OpenApi 服务器配置
     */
    @Post
    @Mapping("/web/settings/openapi/servers/update")
    public Result openapiServersUpdate(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());
        String name = root.get("name").getString();
        String originalName = root.get("originalName").getString();

        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        // 如果 name 变了，使用 originalName 查找旧记录
        String lookupName = (originalName != null && !originalName.isEmpty()) ? originalName : name;

        ApiSourceDo existing = settings.getApiServers().get(lookupName);
        if (existing == null) {
            return Result.failure("Server not found: " + lookupName);
        }

        // 从引擎移除旧的
        engine.removeApiServer(existing.getDocUrl());

        // 如果名称变更，移除旧 key
        if (!lookupName.equals(name)) {
            settings.getApiServers().remove(lookupName);
        }

        boolean enabled = root.hasKey("enabled") ? root.get("enabled").getBoolean(true) : true;

        // 构建新配置
        String scope = root.hasKey("scope") ? root.get("scope").getString() : (existing.getScope() != null ? existing.getScope() : AgentFlags.SCOPE_GLOBAL);
        if (Assert.isEmpty(scope) || (!AgentFlags.SCOPE_LOCAL.equals(scope))) {
            scope = AgentFlags.SCOPE_GLOBAL;
        }
        ApiSourceDo source = new ApiSourceDo();
        source.setApiBaseUrl(root.hasKey("apiBaseUrl") ? root.get("apiBaseUrl").getString() : existing.getApiBaseUrl());
        source.setDocUrl(root.hasKey("docUrl") ? root.get("docUrl").getString() : existing.getDocUrl());
        source.setScope(scope);
        if (root.hasKey("headers")) {
            Map<String, String> headersMap = new LinkedHashMap<>();
            for (Map.Entry<String, ONode> entry : root.get("headers").getObject().entrySet()) {
                headersMap.put(entry.getKey(), entry.getValue().getString());
            }
            source.setHeaders(headersMap);
        } else {
            source.setHeaders(existing.getHeaders());
        }

        settings.getApiServers().put(name, source);

        // 如果启用，同步到引擎
        if (enabled) {
            engine.addApiServer(source);
        }

        saveSettings();
        LOG.info("[Settings] OpenApi server updated: {}", name);
        return Result.succeed();
    }

    /**
     * 移除 OpenApi 服务器配置
     */
    @Post
    @Mapping("/web/settings/openapi/servers/remove")
    public Result openapiServersRemove(@Param("name") String name) throws Exception {
        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        ApiSource source = settings.getApiServers().get(name);
        if (source != null) {
            // 从引擎移除
            engine.removeApiServer(source.getDocUrl());
        }

        settings.getApiServers().remove(name);
        saveSettings();
        LOG.info("[Settings] OpenApi server removed: {}", name);
        return Result.succeed();
    }

    /**
     * 切换 OpenApi 服务器启用/停用
     */
    @Post
    @Mapping("/web/settings/openapi/servers/toggle")
    public Result openapiServersToggle(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());
        String name = root.get("name").getString();
        boolean enabled = root.get("enabled").getBoolean();

        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        ApiSource source = settings.getApiServers().get(name);
        if (source == null) {
            return Result.failure("Server not found: " + name);
        }

        if (enabled) {
            // 启用：添加到引擎
            engine.addApiServer(source);
        } else {
            // 停用：从引擎移除
            engine.removeApiServer(source.getDocUrl());
        }

        source.setEnabled(enabled);

        saveSettings();
        LOG.info("[Settings] OpenApi server toggled: {} -> {}", name, enabled);
        return Result.succeed();
    }

    /**
     * 检测 OpenApi 服务器连接（HTTP HEAD/GET 请求测试）
     */
    @Post
    @Mapping("/web/settings/openapi/servers/check")
    public Result openapiServersCheck(Context ctx) {
        try {
            ONode root = ONode.ofJson(ctx.body());
            String baseUrl = root.get("baseUrl").getString();
            if (Assert.isEmpty(baseUrl)) {
                return Result.failure("API 基地址不能为空");
            }

            // 构建HTTP连接测试
            java.net.URL url = new java.net.URL(baseUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);

            // 设置自定义 headers
            ONode headersNode = root.get("headers");
            if (headersNode != null && headersNode.isObject()) {
                for (Map.Entry<String, ONode> entry : headersNode.getObject().entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue().getString());
                }
            }

            int responseCode = conn.getResponseCode();
            conn.disconnect();

            if (responseCode >= 200 && responseCode < 500) {
                return Result.succeed("连接成功：HTTP " + responseCode);
            } else {
                return Result.failure("连接失败：HTTP " + responseCode);
            }
        } catch (java.net.ConnectException e) {
            return Result.failure("连接被拒绝，请检查地址和端口是否正确");
        } catch (java.net.SocketTimeoutException e) {
            return Result.failure("连接超时，请检查地址是否可达");
        } catch (java.io.IOException e) {
            return Result.failure("连接失败: " + e.getMessage());
        } catch (Exception e) {
            return Result.failure("检测失败: " + e.getMessage());
        }
    }

    /**
     * 批量导入 OpenApi 服务器配置
     * 支持 Map 格式: {"apiServers":{"name1":{...},"name2":{...}}}
     * 支持数组格式: {"apiServers":[{...},{...}]}
     */
    @Post
    @Mapping("/web/settings/openapi/servers/import")
    public Result openapiServersImport(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());
        ONode serversNode = root.get("apiServers");
        if (serversNode == null) {
            return Result.failure("Invalid format: apiServers not found");
        }

        int imported = 0;
        if (serversNode.isObject()) {
            // Map 格式: name -> config
            for (Map.Entry<String, ONode> entry : serversNode.getObject().entrySet()) {
                String name = entry.getKey();
                if (settings.getApiServers().containsKey(name)) continue;
                ONode src = entry.getValue();
                ApiSourceDo source = new ApiSourceDo();
                source.setApiBaseUrl(src.hasKey("apiBaseUrl") ? src.get("apiBaseUrl").getString() : "");
                source.setDocUrl(src.hasKey("docUrl") ? src.get("docUrl").getString() : "");
                if (src.hasKey("headers")) {
                    Map<String, String> headersMap = new LinkedHashMap<>();
                    for (Map.Entry<String, ONode> e : src.get("headers").getObject().entrySet()) {
                        headersMap.put(e.getKey(), e.getValue().getString());
                    }
                    source.setHeaders(headersMap);
                }
                settings.getApiServers().put(name, source);
                imported++;
            }
        } else if (serversNode.isArray()) {
            for (ONode src : serversNode.getArray()) {
                String name = src.get("name").getString();
                if (settings.getApiServers().containsKey(name) || Assert.isEmpty(name)) continue;
                ApiSourceDo source = new ApiSourceDo();
                source.setApiBaseUrl(src.hasKey("apiBaseUrl") ? src.get("apiBaseUrl").getString() : "");
                source.setDocUrl(src.hasKey("docUrl") ? src.get("docUrl").getString() : "");
                if (src.hasKey("headers")) {
                    Map<String, String> headersMap = new LinkedHashMap<>();
                    for (Map.Entry<String, ONode> e : src.get("headers").getObject().entrySet()) {
                        headersMap.put(e.getKey(), e.getValue().getString());
                    }
                    source.setHeaders(headersMap);
                }
                settings.getApiServers().put(name, source);
                imported++;
            }
        }

        saveSettings();
        LOG.info("[Settings] OpenApi servers imported: {} items", imported);
        return Result.succeed("Imported " + imported + " server(s)");
    }

    // ==================== 设置：OpenApi 工具权限管理 ====================

    /**
     * 获取指定 OpenApi 服务器的 API 列表及权限状态
     */
    @Get
    @Mapping("/web/settings/openapi/servers/apis")
    public Result openapiServerApis(@Param("name") String name) {
        ApiSource source = settings.getApiServers().get(name);
        if (source == null) {
            return Result.failure("Server not found: " + name);
        }

        ApiSourceClient client = engine.getApiServer(source.getDocUrl());
        if (client == null) {
            // 服务器未启用或未加载
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("serverName", name);
            data.put("connected", false);
            data.put("apis", Collections.emptyList());
            return Result.succeed(data);
        }

        Collection<ApiTool> allTools = client.getTools();
        List<Map<String, Object>> apiList = new ArrayList<>();
        for (ApiTool tool : allTools) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.getName());
            item.put("method", tool.getMethod());
            item.put("path", tool.getPath());
            item.put("description", tool.getDescription());
            apiList.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serverName", name);
        data.put("disallowedTools", source.getDisallowedTools());
        data.put("connected", true);
        data.put("apis", apiList);
        return Result.succeed(data);
    }

    /**
     * 更新指定 OpenApi 服务器的 API 权限（allowedTools）
     * <p>通过 engine.refreshApiServer 影子交换策略热重载，无需重启。</p>
     */
    @Post
    @Mapping("/web/settings/openapi/servers/apis/save")
    public Result openapiServerApisSave(@Param("serverName") String serverName, @Param("disallowedTools") String[] disallowedTools) {
        ApiSource source = settings.getApiServers().get(serverName);
        if (source == null) {
            return Result.failure("Server not found: " + serverName);
        }

        // disallowedTools
        source.setDisallowedTools(Arrays.asList(disallowedTools));

        // 同步到引擎 client 并热重载
        ApiSourceClient client = engine.getApiServer(source.getDocUrl());
        if (client != null) {
            client.setDisallowedTools(source.getDisallowedTools());
            engine.refreshApiServer(source.getDocUrl());
        }

        saveSettings();
        LOG.info("[Settings] OpenApi server apis permissions updated: {}", serverName);
        return Result.succeed();
    }


    // ==================== 设置：Skills 市场（委派给 Market 接口） ====================

    /**
     * 获取所有可用市场列表
     */
    @Get
    @Mapping("/web/settings/skills/markets")
    public Result skillsMarkets(Context ctx) {
        return Result.succeed(marketManager.getMarketInfos());
    }

    /**
     * 技能市场代理接口 — 获取热门技能或搜索技能。
     * <p>所有外部 API 调用均由后端 Market 适配器完成，前端不直接访问外部服务。</p>
     *
     * @param action     "trending" 获取热门 | "search" 搜索
     * @param query      搜索关键词（action=search 时使用）
     * @param limit      返回数量限制
     * @param marketName 市场名字（可选，默认使用 ClawHub）
     */
    @Get
    @Mapping("/web/settings/skills/proxy")
    public Result skillsProxy(Context ctx, @Param(value = "action", defaultValue = "trending") String action,
                              @Param(value = "q", defaultValue = "") String query,
                              @Param(value = "limit", defaultValue = "50") int limit,
                              @Param(value = "per_page", defaultValue = "50") int perPage,
                              @Param(value = "marketName", defaultValue = "") String marketName) {
        Market market = marketManager.getMarketByName(marketName);
        if ("search".equals(action) && query != null && !query.isEmpty()) {
            return market.search(query, limit);
        } else {
            return market.trending(limit);
        }
    }

    /**
     * 安装技能 — 委派给 Market 适配器完成下载、解压，然后刷新技能池。
     *
     * @param slug       技能 slug（必填）
     * @param marketName 市场名称（可选）
     * @param mountAlias 挂载点别名（可选，默认安装到 workspace/skills）
     */
    @Post
    @Mapping("/web/settings/skills/install")
    public Result skillsInstall(Context ctx, @Param("slug") String slug,
                                @Param(value = "marketName", defaultValue = "") String marketName,
                                @Param(value = "mountAlias", defaultValue = "") String mountAlias) {
        if (Assert.isEmpty(slug)) {
            return Result.failure("slug is required");
        }

        Market market = marketManager.getMarketByName(marketName);

        // 确定安装目标目录：若指定了挂载别名，则安装到对应池目录；否则默认 workspace/skills
        Path skillsDir;
        if (!Assert.isEmpty(mountAlias)) {
            MountDir poolDir = engine.getMount(mountAlias);
            if (poolDir == null) {
                return Result.failure("挂载池不存在: " + mountAlias);
            }

            skillsDir = poolDir.getRealPath();
        } else {
            skillsDir = Paths.get(engine.getWorkspace(), "skills");
        }

        Result<String> result = market.install(slug, skillsDir);

        // 安装成功后刷新技能池
        if (result.getCode() == 200) {
            engine.refreshMount(mountAlias);
        }

        return result;
    }

    // ==================== 设置：挂载池管理 ====================

    /**
     * 获取所有挂载池列表（含系统池标记）
     */
    @Get
    @Mapping("/web/settings/mounts")
    public Result mountsList(Context ctx) {
        List<Map<String, Object>> list = new ArrayList<>();

        for (MountDir entry : engine.getMounts()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("alias", entry.getAlias());
            item.put("type", entry.getType());
            item.put("path", entry.getPath());
            item.put("enabled", entry.isEnabled());
            item.put("system", entry.isPrimary());
            item.put("writeable", entry.isWriteable());
            item.put("realPath", entry.getRealPath() != null ? entry.getRealPath().toString() : "");
            item.put("description", entry.getDescription());


            MountDo mountDo = settings.getMountPools().get(entry.getAlias());
            if (mountDo == null) {
                item.put("scope", AgentFlags.SCOPE_GLOBAL);
            } else {
                item.put("scope", mountDo.getScope());
            }

            list.add(item);
        }
        return Result.succeed(list);
    }

    /**
     * 添加挂载池
     */
    @Post
    @Mapping("/web/settings/mounts/add")
    public Result mountsAdd(Context ctx, @Param("description") String description, @Param("alias") String alias, @Param("path") String path, @Param("type") MountType type, @Param("writeable") boolean writeable, @Param("scope") String scope) {
        if (Assert.isEmpty(alias) || Assert.isEmpty(path)) return Result.failure("参数不完整");
        if (!alias.startsWith("@")) return Result.failure("别名必须以 @ 开头");
        if (engine.hasMount(alias)) return Result.failure("别名已存在");

        if (type == null) {
            type = MountType.SKILLS;
        }

        if (Assert.isEmpty(scope) || (!AgentFlags.SCOPE_LOCAL.equals(scope))) {
            scope = AgentFlags.SCOPE_GLOBAL;
        }

        MountDo mountDo = new MountDo(
                scope,
                description,
                type,
                path,
                false, true, writeable);
        settings.getMountPools().put(alias, mountDo);
        saveSettings();
        engine.addMount(MountDir.builder()
                .alias(alias)
                .type(type)
                .path(path)
                .writeable(writeable)
                .build());
        return Result.succeed("添加成功");
    }

    /**
     * 更新挂载池（只允许修改描述和可写属性）
     */
    @Post
    @Mapping("/web/settings/mounts/update")
    public Result mountsUpdate(Context ctx, @Param("alias") String alias, @Param("description") String description, @Param("writeable") boolean writeable) {
        if (Assert.isEmpty(alias)) return Result.failure("参数不完整");
        if (!engine.hasMount(alias)) return Result.failure("挂载池不存在");

        // 更新配置中的数据
        MountDo mountDo = settings.getMountPools().get(alias);
        if (mountDo != null) {
            mountDo.setDescription(description);
            mountDo.setWriteable(writeable);
        }

        // 更新运行时挂载
        for (MountDir entry : engine.getMounts()) {
            if (alias.equals(entry.getAlias())) {
                entry.setDescription(description);
                entry.setWriteable(writeable);
                break;
            }
        }

        saveSettings();
        return Result.succeed("更新成功");
    }

    /**
     * 切换挂载池启用/停用
     */
    @Post
    @Mapping("/web/settings/mounts/toggle")
    public Result mountsToggle(@Body String json) {
        ONode root = ONode.ofJson(json);
        String alias = root.get("alias").getString();
        boolean enabled = root.get("enabled").getBoolean();

        if (Assert.isEmpty(alias)) {
            return Result.failure("alias is required");
        }

        MountDir mountDir = engine.getMount(alias);
        if (mountDir == null) {
            return Result.failure("挂载池不存在: " + alias);
        }

        // 更新配置
        MountDo mountDo = settings.getMountPools().get(alias);
        if (mountDo != null) {
            mountDo.setEnabled(enabled);
        }

        // 更新运行时
        mountDir.setEnabled(enabled);

        saveSettings();
        LOG.info("[Settings] Mount toggled: {} -> {}", alias, enabled);
        return Result.succeed();
    }

    /**
     * 移除挂载池
     */
    @Post
    @Mapping("/web/settings/mounts/remove")
    public Result mountsRemove(@Param("alias") String alias) {
        MountDir mountDir = engine.getMount(alias);
        if (mountDir == null) {
            return Result.failure("挂载池不存在");
        }

        if (mountDir.isPrimary()) {
            return Result.failure("系统挂载池不可移除");
        }

        settings.getMountPools().remove(alias);
        saveSettings();
        engine.removeMount(alias);
        return Result.succeed("移除成功");
    }

    /**
     * 获取某挂载池内的内容列表（根据类型分发）
     */
    @Get
    @Mapping("/web/settings/mounts/content")
    public Result mountsContent(@Param("alias") String alias, @Param("type") String type) {
        if (engine.hasMount(alias) == false) {
            return Result.failure("挂载池不存在: " + alias);
        }

        if ("AGENTS".equals(type)) {
            return loadAgentsContent(alias);
        } else if ("FILES".equals(type)) {
            return Result.succeed(Collections.emptyList());
        } else {
            return loadSkillsContent(alias);
        }
    }

    private Result loadSkillsContent(String alias) {
        Collection<SkillDir> skillDirList = engine.getSkillsByMount(alias);
        List<Map<String, String>> skills = new ArrayList<>();

        for (SkillDir subDir : skillDirList) {
            Map<String, String> skillItem = new LinkedHashMap<>();
            skillItem.put("name", subDir.getName());
            skillItem.put("description", subDir.getDescription());
            skillItem.put("realPath", subDir.getRealPath() != null ? subDir.getRealPath().toString() : "");
            skillItem.put("path", subDir.getRealPath() != null ? subDir.getRealPath().toString().replace('\\', '/') : "");
            skills.add(skillItem);
        }

        return Result.succeed(skills);
    }

    private Result loadAgentsContent(String alias) {
        Collection<AgentMd> agentList = engine.getAgentsByMount(alias);
        List<Map<String, String>> agents = new ArrayList<>();

        for (AgentMd agent : agentList) {
            Map<String, String> agentItem = new LinkedHashMap<>();
            agentItem.put("name", agent.getName());
            agentItem.put("filePath", agent.getFilePath() != null ? agent.getFilePath().toString() : "");
            agents.add(agentItem);
        }

        return Result.succeed(agents);
    }

    /**
     * 打开挂载池的真实目录
     */
    @Get
    @Mapping("/web/settings/mounts/open")
    public Result mountsOpen(@Param("path") String path) {
        if (Assert.isEmpty(path)) return Result.failure("路径为空");
        try {
            File dir = new File(path);
            if (!dir.exists()) return Result.failure("目录不存在: " + path);

            // 优先尝试 Desktop.open，失败时 fallback 到系统命令
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(dir);
                    return Result.succeed("已打开");
                }
            } catch (Exception ignored) {
                // Desktop.open 失败，尝试 fallback
            }

            // Fallback: 使用系统命令打开目录
            String os = System.getProperty("os.name", "").toLowerCase();
            String[] cmd;
            if (os.contains("mac")) {
                cmd = new String[]{"open", dir.getAbsolutePath()};
            } else if (os.contains("win")) {
                cmd = new String[]{"explorer", dir.getAbsolutePath()};
            } else {
                cmd = new String[]{"xdg-open", dir.getAbsolutePath()};
            }
            new ProcessBuilder(cmd).start();
            return Result.succeed("已打开");
        } catch (Exception e) {
            return Result.failure("打开失败: " + e.getMessage());
        }
    }

    /**
     * 删除挂载池内的技能包
     */
    @Post
    @Mapping("/web/settings/mounts/skills/remove")
    public Result mountsSkillsRemove(@Param("alias") String alias, @Param("skillName") String skillName) {
        MountDir mountDir = engine.getMount(alias);
        if (mountDir == null) return Result.failure("挂载池不存在: " + alias);


        Path skillDir = mountDir.getRealPath().resolve(skillName);
        if (!Files.exists(skillDir)) return Result.failure("技能包不存在: " + skillName);

        // 安全校验：防止路径穿越
        if (!skillDir.normalize().startsWith(mountDir.getRealPath())) {
            return Result.failure("非法路径");
        }

        try {
            deleteRecursively(skillDir);
            engine.refreshMount(alias);
            return Result.succeed("删除成功");
        } catch (Exception e) {
            LOG.warn("[Settings] Failed to delete skill: {}", e.getMessage());
            return Result.failure("删除失败: " + e.getMessage());
        }
    }

    /**
     * 计算工具的权限状态
     *
     * @param toolName       工具名
     * @param allowedTools   允许列表（空或null表示全部允许）
     * @param disallowedTools 禁止列表（优先级高于允许列表）
     * @return "allowed" 或 "disallowed"
     */
    private String computeToolStatus(String toolName, List<String> allowedTools, List<String> disallowedTools) {
        // disallow 优先级最高
        if (disallowedTools != null && disallowedTools.contains(toolName)) {
            return "disallowed";
        }
        // 有白名单但不在其中
        if (allowedTools != null && !allowedTools.isEmpty() && !allowedTools.contains(toolName)) {
            return "disallowed";
        }
        return "allowed";
    }

    /**
     * 递归删除目录
     */
    private void deleteRecursively(Path path) throws Exception {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path child : stream) deleteRecursively(child);
            }
        }
        Files.deleteIfExists(path);
    }
}