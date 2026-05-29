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
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.ai.skills.openapi.ApiSource;
import org.noear.solon.ai.skills.openapi.OpenApiSkill;
import org.noear.solon.ai.skills.toolgateway.McpGatewaySkill;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.config.AgentProperties;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.portal.web.market.Market;
import org.noear.solon.codecli.portal.web.market.MarketManager;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;

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
     * 统一配置文件路径
     */
    private final Path settingsFile;

    /**
     * 构造函数：使用容器注入的 AgentSettings。
     *
     * @param engine AI Agent 执行引擎
     * @param settings 统一配置管理器（由 App.initAgentSettings 创建并注册到容器）
     */
    public WebSettingsController(HarnessEngine engine, AgentSettings settings) {
        this(engine, settings, new MarketManager());
    }

    /**
     * 构造函数：支持自定义 MarketManager（用于测试）。
     *
     * @param engine AI Agent 执行引擎
     * @param settings 统一配置管理器
     * @param marketManager 技能市场管理器
     */
    public WebSettingsController(HarnessEngine engine, AgentSettings settings, MarketManager marketManager) {
        this.engine = engine;
        this.settings = settings;
        this.marketManager = marketManager;
        this.settingsFile = Paths.get(AgentProperties.getUserHome(), ".soloncode", "settings.json");
    }

    // ==================== 配置持久化 ====================

    /**
     * 将当前配置保存到 settings.json
     */
    private void saveSettings() {
        settings.saveToFile(settingsFile);
    }

    // ==================== 设置：LLM 模型管理 ====================

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
    public Result llmModelsAdd(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());

        String apiUrl = root.get("apiUrl").getString();
        String apiKey = root.get("apiKey").getString();
        String model = root.get("model").getString();
        String name = root.get("name").getString();

        if (Assert.isEmpty(apiUrl) || Assert.isEmpty(model)) {
            return Result.failure("apiUrl and model are required");
        }

        ChatConfig config = new ChatConfig();
        config.setName(name);
        config.setApiUrl(apiUrl);
        config.setApiKey(apiKey);
        config.setModel(model);
        config.setUserAgent(engine.getProps().getUserAgent());

        String timeout = root.get("timeout").getString();
        if (Assert.isNotEmpty(timeout)) {
            config.setTimeout(java.time.Duration.parse(timeout));
        }
        String userAgent = root.get("userAgent").getString();
        if (Assert.isNotEmpty(userAgent)) {
            config.setUserAgent(userAgent);
        }
        Integer contextLength = root.get("contextLength").getInt();
        if (contextLength != null && contextLength > 0) {
            config.setContextLength(contextLength);
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
    public Result llmModelsRemove(@Param("modelName") String modelName) throws Exception {
        if (Assert.isEmpty(modelName)) {
            return Result.failure("modelName is required");
        }
        if (modelName.equals(engine.getMainModel().getNameOrModel())) {
            return Result.failure("Cannot remove the active main model");
        }

        engine.getProps().removeModel(modelName);

        settings.getModels().removeIf(c -> modelName.equals(c.getName()) || modelName.equals(c.getModel()));
        saveSettings();

        LOG.info("[Settings] Model removed: {}", modelName);
        return Result.succeed();
    }

    /**
     * 更新模型配置（先删后加）
     */
    @Post
    @Mapping("/web/settings/llm/models/update")
    public Result llmModelsUpdate(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());

        String originalModel = root.get("originalModel").getString();
        if (Assert.isEmpty(originalModel)) {
            return Result.failure("originalModel is required");
        }

        // 先移除旧配置
        engine.getProps().removeModel(originalModel);

        // 复用 add 逻辑构建新配置
        String apiUrl = root.get("apiUrl").getString();
        String apiKey = root.get("apiKey").getString();
        String model = root.get("model").getString();

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
        config.setUserAgent(engine.getProps().getUserAgent());

        String provider = root.get("provider").getString();
        if (Assert.isNotEmpty(provider)) {
            config.setProvider(provider);
        }

        String timeout = root.get("timeout").getString();
        if (Assert.isNotEmpty(timeout)) {
            config.setTimeout(java.time.Duration.parse(timeout));
        }
        String userAgent = root.get("userAgent").getString();
        if (Assert.isNotEmpty(userAgent)) {
            config.setUserAgent(userAgent);
        }
        Integer contextLength = root.get("contextLength").getInt();
        if (contextLength != null && contextLength > 0) {
            config.setContextLength(contextLength);
        }

        engine.getProps().addModel(config);

        settings.getModels().removeIf(c -> originalModel.equals(c.getName()) || originalModel.equals(c.getModel()));
        settings.getModels().add(config);
        saveSettings();

        LOG.info("[Settings] Model updated: {} -> {}", originalModel, name);
        return Result.succeed(name);
    }

    /**
     * 将指定模型设为默认主模型
     */
    @Post
    @Mapping("/web/settings/llm/models/setDefault")
    public Result llmModelsSetDefault(@Param("modelName") String modelName) throws Exception {
        if (Assert.isEmpty(modelName)) {
            return Result.failure("modelName is required");
        }
        engine.switchMainModel(modelName);
        saveSettings();
        LOG.info("[Settings] Default model set to: {}", modelName);
        return Result.succeed();
    }

    /**
     * 导出所有模型配置（API Key 脱敏）
     */
    @Get
    @Mapping("/web/settings/llm/models/export")
    public Result<List<Map>> llmModelsExport() throws Exception {
        List<Map> list = new ArrayList<>();
        for (ChatConfig config : settings.getModels()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("apiUrl", config.getApiUrl());
            item.put("model", config.getModel());
            item.put("name", config.getName());
            item.put("provider", config.getProvider());
            if (config.getContextLength() > 0) {
                item.put("contextLength", String.valueOf(config.getContextLength()));
            }
            // API Key 脱敏：仅保留前后4位
            String apiKey = config.getApiKey();
            if (apiKey != null && apiKey.length() > 8) {
                item.put("apiKey", apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4));
            }
            list.add(item);
        }
        return Result.succeed(list);
    }

    /**
     * 批量导入模型配置
     */
    @Post
    @Mapping("/web/settings/llm/models/import")
    public Result llmModelsImport(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());
        ONode models = root.get("models");
        if (models == null || !models.isArray()) {
            return Result.failure("Invalid format: expected {models:[...]}");
        }

        int count = 0;
        for (ONode node : models.getArray()) {
            String apiUrl = node.get("apiUrl").getString();
            String model = node.get("model").getString();
            if (Assert.isEmpty(apiUrl) || Assert.isEmpty(model)) continue;

            String name = node.get("name").getString();
            if (Assert.isEmpty(name)) name = model;

            ChatConfig config = new ChatConfig();
            config.setName(name);
            config.setApiUrl(apiUrl);
            config.setApiKey(node.get("apiKey").getString());
            config.setModel(model);

            String provider = node.get("provider").getString();
            if (Assert.isNotEmpty(provider)) {
                config.setProvider(provider);
            }

            Integer contextLength = node.get("contextLength").getInt();
            if (contextLength != null && contextLength > 0) {
                config.setContextLength(contextLength);
            }

            final String fName = name;
            final String fModel = model;

            engine.getProps().removeModel(fModel);
            engine.getProps().addModel(config);

            settings.getModels().removeIf(c -> fName.equals(c.getName()) || fModel.equals(c.getModel()));
            settings.getModels().add(config);
            count++;
        }

        saveSettings();
        LOG.info("[Settings] Models imported: {}", count);
        return Result.succeed(count);
    }

    // ==================== 设置：MCP 服务器管理 ====================

    /**
     * 获取已配置的 MCP 服务器列表
     */
    @Get
    @Mapping("/web/settings/mcp/servers")
    public Result<List<Map>> mcpServers() throws Exception {
        List<Map> list = new ArrayList<>();
        for (Map.Entry<String, McpServerParameters> entry : settings.getMcpServers().entrySet()) {
            String name = entry.getKey();
            McpServerParameters params = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("type", params.getType() != null ? params.getType() : "stdio");
            item.put("enabled", params.isEnabled());
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
    public Result mcpServersAdd(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());
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
        McpServerParameters params = new McpServerParameters();
        params.setType(type);

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
            McpGatewaySkill mcpGateway = engine.getMcpGatewaySkill();
            if (mcpGateway != null) {
                mcpGateway.addMcpServer(name, params);
            }
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
    public Result mcpServersRemove(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());
        String name = root.get("name").getString();

        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        // 从引擎移除
        McpGatewaySkill mcpGateway = engine.getMcpGatewaySkill();
        if (mcpGateway != null) {
            mcpGateway.removeMcpServer(name);
        }

        settings.getMcpServers().remove(name);
        saveSettings();
        LOG.info("[Settings] MCP server removed: {}", name);
        return Result.succeed();
    }

    /**
     * 更新 MCP 服务器配置
     */
    @Post
    @Mapping("/web/settings/mcp/servers/update")
    public Result mcpServersUpdate(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());
        String name = root.get("name").getString();
        String originalName = root.get("originalName").getString();

        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        // 如果 name 变了，使用 originalName 查找旧记录
        String lookupName = (originalName != null && !originalName.isEmpty()) ? originalName : name;

        McpServerParameters existing = settings.getMcpServers().get(lookupName);
        if (existing == null) {
            return Result.failure("Server not found: " + lookupName);
        }

        // 如果名称变更，先从引擎移除旧名称
        if (!lookupName.equals(name)) {
            McpGatewaySkill mcpGateway = engine.getMcpGatewaySkill();
            if (mcpGateway != null) {
                mcpGateway.removeMcpServer(lookupName);
            }
            settings.getMcpServers().remove(lookupName);
        } else {
            // 名称没变，仍然先从引擎移除（稍后重新添加）
            McpGatewaySkill mcpGateway = engine.getMcpGatewaySkill();
            if (mcpGateway != null) {
                mcpGateway.removeMcpServer(name);
            }
        }

        // 构建新参数
        String type = root.hasKey("type") ? root.get("type").getString() : existing.getType();
        boolean enabled = root.hasKey("enabled") ? root.get("enabled").getBoolean(true) : true;

        McpServerParameters params = new McpServerParameters();
        params.setType(type);

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
            McpGatewaySkill mcpGateway = engine.getMcpGatewaySkill();
            if (mcpGateway != null) {
                mcpGateway.addMcpServer(name, params);
            }
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
    public Result mcpServersToggle(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());
        String name = root.get("name").getString();
        boolean enabled = root.get("enabled").getBoolean();

        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        McpServerParameters params = settings.getMcpServers().get(name);
        if (params == null) {
            return Result.failure("Server not found: " + name);
        }

        McpGatewaySkill mcpGateway = engine.getMcpGatewaySkill();
        if (enabled) {
            // 启用：添加到引擎
            if (mcpGateway != null) {
                mcpGateway.addMcpServer(name, params);
            }
        } else {
            // 停用：从引擎移除
            if (mcpGateway != null) {
                mcpGateway.removeMcpServer(name);
            }
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
                McpServerParameters params = new McpServerParameters();
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
                McpServerParameters params = new McpServerParameters();
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

    // ==================== 设置：OpenApi 服务器管理 ====================

    /**
     * 获取已配置的 OpenApi 服务器列表
     */
    @Get
    @Mapping("/web/settings/webapi/servers")
    public Result<List<Map>> webapiServers() throws Exception {
        List<Map> list = new ArrayList<>();
        for (Map.Entry<String, ApiSource> entry : settings.getApiServers().entrySet()) {
            String name = entry.getKey();
            ApiSource source = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("apiBaseUrl", source.getApiBaseUrl());
            item.put("docUrl", source.getDocUrl());
            item.put("enabled", source.isEnabled());
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
    @Mapping("/web/settings/webapi/servers/add")
    public Result webapiServersAdd(Context ctx) throws Exception {
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

        ApiSource source = new ApiSource();
        source.setApiBaseUrl(apiBaseUrl);
        source.setDocUrl(root.get("docUrl").getString());
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
            OpenApiSkill restApi = engine.getOpenApiSkill();
            if (restApi != null) {
                restApi.addApi(source);
            }
        }

        saveSettings();
        LOG.info("[Settings] OpenApi server added: {}", name);
        return Result.succeed();
    }

    /**
     * 更新 OpenApi 服务器配置
     */
    @Post
    @Mapping("/web/settings/webapi/servers/update")
    public Result webapiServersUpdate(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());
        String name = root.get("name").getString();
        String originalName = root.get("originalName").getString();

        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        // 如果 name 变了，使用 originalName 查找旧记录
        String lookupName = (originalName != null && !originalName.isEmpty()) ? originalName : name;

        ApiSource existing = settings.getApiServers().get(lookupName);
        if (existing == null) {
            return Result.failure("Server not found: " + lookupName);
        }

        // 从引擎移除旧的
        OpenApiSkill restApi = engine.getOpenApiSkill();
        if (restApi != null) {
            restApi.removeApi(existing.getDocUrl());
        }

        // 如果名称变更，移除旧 key
        if (!lookupName.equals(name)) {
            settings.getApiServers().remove(lookupName);
        }

        boolean enabled = root.hasKey("enabled") ? root.get("enabled").getBoolean(true) : true;

        // 构建新配置
        ApiSource source = new ApiSource();
        source.setApiBaseUrl(root.hasKey("apiBaseUrl") ? root.get("apiBaseUrl").getString() : existing.getApiBaseUrl());
        source.setDocUrl(root.hasKey("docUrl") ? root.get("docUrl").getString() : existing.getDocUrl());
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
            if (restApi != null) {
                restApi.addApi(source);
            }
        }

        saveSettings();
        LOG.info("[Settings] OpenApi server updated: {}", name);
        return Result.succeed();
    }

    /**
     * 移除 OpenApi 服务器配置
     */
    @Post
    @Mapping("/web/settings/webapi/servers/remove")
    public Result webapiServersRemove(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());
        String name = root.get("name").getString();

        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        ApiSource source = settings.getApiServers().get(name);
        if (source != null) {
            // 从引擎移除
            OpenApiSkill restApi = engine.getOpenApiSkill();
            if (restApi != null) {
                restApi.removeApi(source.getDocUrl());
            }
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
    @Mapping("/web/settings/webapi/servers/toggle")
    public Result webapiServersToggle(Context ctx) throws Exception {
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

        OpenApiSkill restApi = engine.getOpenApiSkill();
        if (enabled) {
            // 启用：添加到引擎
            if (restApi != null) {
                restApi.addApi(source);
            }
        } else {
            // 停用：从引擎移除
            if (restApi != null) {
                restApi.removeApi(source.getDocUrl());
            }
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
    @Mapping("/web/settings/webapi/servers/check")
    public Result webapiServersCheck(Context ctx) {
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
    @Mapping("/web/settings/webapi/servers/import")
    public Result webapiServersImport(Context ctx) throws Exception {
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
                ApiSource source = new ApiSource();
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
                ApiSource source = new ApiSource();
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
     * @param action    "trending" 获取热门 | "search" 搜索
     * @param query     搜索关键词（action=search 时使用）
     * @param limit     返回数量限制
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
     * @param slug 技能 slug（必填）
     */
    @Post
    @Mapping("/web/settings/skills/install")
    public Result skillsInstall(Context ctx, @Param("slug") String slug,
                                @Param(value = "marketName", defaultValue = "") String marketName) {
        if (Assert.isEmpty(slug)) {
            return Result.failure("slug is required");
        }

        Market market = marketManager.getMarketByName(marketName);
        Path skillsDir = Paths.get(engine.getProps().getWorkspace(), "skills");
        Result<String> result = market.install(slug, skillsDir);

        // 安装成功后刷新技能池
        if (result.getCode() == 200) {
            try {
                engine.getPoolManager().refresh();
            } catch (Exception e) {
                LOG.warn("[Settings] Skill pool refresh error after install: {}", e.getMessage());
            }
        }

        return result;
    }
}
