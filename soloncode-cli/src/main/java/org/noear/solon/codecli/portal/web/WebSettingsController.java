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
import org.noear.solon.ai.skills.restapi.RestApiSkill;
import org.noear.solon.ai.skills.toolgateway.ToolGatewaySkill;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.portal.web.market.ClawhubMarket;
import org.noear.solon.codecli.portal.web.market.Market;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Web 设置控制器 —— SolonCode Web UI 的设置管理 HTTP 入口。
 *
 * <p>职责：管理 LLM 模型配置（增删改查、导入导出）和 MCP 服务器配置（增删改查、连接检测）。</p>
 *
 * <h3>主要功能分组</h3>
 * <ul>
 *   <li><b>LLM 模型管理</b>：从远程拉取模型列表、动态添加/移除/更新模型、设置默认模型、导入导出</li>
 *   <li><b>MCP 服务器管理</b>：服务器列表查询、添加/移除/更新、启用停用、连接检测、批量导入</li>
 *   <li><b>技能市场</b>：通过 {@link Market} 接口代理技能浏览、搜索和安装（委派给具体适配器）</li>
 * </ul>
 *
 * @author oisin 2026-3-13
 * @author noear 2026-4-18
 * @see WebController Web 主控制器
 * @see Market 技能市场接口
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
    private final Market market;

    /**
     * 构造函数：初始化核心依赖。
     *
     * @param engine AI Agent 执行引擎
     */
    public WebSettingsController(HarnessEngine engine) {
        this(engine, new ClawhubMarket());
    }

    /**
     * 构造函数：支持自定义 Market 适配器（用于测试或切换市场）。
     *
     * @param engine AI Agent 执行引擎
     * @param market 技能市场适配器
     */
    public WebSettingsController(HarnessEngine engine, Market market) {
        this.engine = engine;
        this.market = market;
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
        config.setUserAgent("Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; SolonCode/2.0; +https://solon.noear.org/)");

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

        engine.getProps().removeModel(model);
        engine.getProps().addModel(config);

        saveLlmModelsToFile();
        LOG.info("[Settings] Model added: {}", name);
        return Result.succeed(name);
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

        saveLlmModelsToFile();
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
        config.setUserAgent("Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; SolonCode/2.0; +https://solon.noear.org/)");

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

        saveLlmModelsToFile();
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
        saveLlmModelsToFile();
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
        for (ChatConfig config : engine.getProps().getModels()) {
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

            engine.getProps().removeModel(model);
            engine.getProps().addModel(config);
            count++;
        }

        saveLlmModelsToFile();
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
        java.nio.file.Path mcpFile = getMcpServersFile();
        List<Map> list = new ArrayList<>();
        if (!java.nio.file.Files.exists(mcpFile)) {
            return Result.succeed(list);
        }

        String content = new String(java.nio.file.Files.readAllBytes(mcpFile), "UTF-8");
        ONode root = ONode.ofJson(content);
        ONode servers = root.get("mcpServers");
        if (servers != null && servers.isArray()) {
            for (ONode node : servers.getArray()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", node.get("name").getString());
                item.put("type", node.get("type").getString());
                item.put("enabled", node.get("enabled").getBoolean(true));
                if ("stdio".equals(node.get("type").getString())) {
                    item.put("command", node.get("command").getString());
                    ONode argsNode = node.get("args");
                    if (argsNode != null && argsNode.isArray()) {
                        List<String> argsList = new ArrayList<>();
                        for (ONode a : argsNode.getArray()) {
                            argsList.add(a.getString());
                        }
                        item.put("args", argsList);
                    }
                    ONode envNode = node.get("env");
                    if (envNode != null && envNode.isObject()) {
                        Map<String, String> envMap = new LinkedHashMap<>();
                        for (Map.Entry<String, ONode> entry : envNode.getObject().entrySet()) {
                            envMap.put(entry.getKey(), entry.getValue().getString());
                        }
                        item.put("env", envMap);
                    }
                } else {
                    item.put("url", node.get("url").getString());
                    ONode headersNode = node.get("headers");
                    if (headersNode != null && headersNode.isObject()) {
                        Map<String, String> headersMap = new LinkedHashMap<>();
                        for (Map.Entry<String, ONode> entry : headersNode.getObject().entrySet()) {
                            headersMap.put(entry.getKey(), entry.getValue().getString());
                        }
                        item.put("headers", headersMap);
                    }
                    item.put("timeout", node.get("timeout").getString());
                }
                list.add(item);
            }
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

        java.nio.file.Path mcpFile = getMcpServersFile();
        ONode config = loadMcpConfig(mcpFile);
        ONode servers = config.getOrNew("mcpServers").asArray();

        // 检查重名
        for (ONode s : servers.getArray()) {
            if (name.equals(s.get("name").getString())) {
                return Result.failure("Server name already exists: " + name);
            }
        }

        ONode newServer = new ONode();
        newServer.set("name", name);
        newServer.set("type", type);
        newServer.set("enabled", root.get("enabled").getBoolean(true));

        if ("stdio".equals(type)) {
            newServer.set("command", root.get("command").getString());
            if (root.hasKey("args")) newServer.set("args", root.get("args"));
            if (root.hasKey("env")) newServer.set("env", root.get("env"));
        } else if ("sse".equals(type) || "streamable-http".equals(type)) {
            newServer.set("url", root.get("url").getString());
            if (root.hasKey("headers")) newServer.set("headers", root.get("headers"));
            if (root.hasKey("timeout")) newServer.set("timeout", root.get("timeout").getString());
        } else {
            return Result.failure("Unsupported type: " + type);
        }

        servers.add(newServer);
        saveMcpConfig(mcpFile, config);

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

        java.nio.file.Path mcpFile = getMcpServersFile();
        ONode config = loadMcpConfig(mcpFile);
        ONode servers = config.get("mcpServers");

        if (servers != null && servers.isArray()) {
            servers.getArray().removeIf(s -> name.equals(s.get("name").getString()));
        }

        saveMcpConfig(mcpFile, config);
        LOG.info("[Settings] MCP server removed: {}", name);
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

        java.nio.file.Path mcpFile = getMcpServersFile();
        ONode config = loadMcpConfig(mcpFile);
        ONode servers = config.get("mcpServers");

        if (servers != null && servers.isArray()) {
            for (ONode s : servers.getArray()) {
                if (name.equals(s.get("name").getString())) {
                    s.set("enabled", enabled);
                    break;
                }
            }
        }

        saveMcpConfig(mcpFile, config);
        LOG.info("[Settings] MCP server toggled: {} -> {}", name, enabled);
        return Result.succeed();
    }

    private java.nio.file.Path getMcpServersFile() {
        return java.nio.file.Paths.get(engine.getProps().getWorkspace(), ".soloncode", "mcp-servers.json");
    }

    // ==================== LLM 模型持久化 ====================

    /**
     * 获取 LLM 模型持久化文件路径
     */
    private Path getLlmModelsFile() {
        return Paths.get(engine.getProps().getWorkspace(), ".soloncode", "llm-models.json");
    }

    /**
     * 从持久化文件加载模型列表
     */
    private ONode loadLlmModelsConfig(Path file) throws Exception {
        if (!Files.exists(file)) {
            ONode root = new ONode();
            root.getOrNew("models").asArray();
            return root;
        }
        String content = new String(Files.readAllBytes(file), "UTF-8");
        return ONode.ofJson(content);
    }

    /**
     * 将内存中的模型列表持久化到文件
     */
    private void saveLlmModelsToFile() {
        try {
            Path file = getLlmModelsFile();
            ONode root = new ONode();
            ONode modelsArray = root.getOrNew("models").asArray();

            for (ChatConfig config : engine.getProps().getModels()) {
                ONode node = new ONode();
                node.set("name", config.getName());
                node.set("apiUrl", config.getApiUrl());
                node.set("apiKey", config.getApiKey());
                node.set("model", config.getModel());
                node.set("provider", config.getProvider());
                if (config.getTimeout() != null) {
                    node.set("timeout", config.getTimeout().toString());
                }
                if (config.getUserAgent() != null) {
                    node.set("userAgent", config.getUserAgent());
                }
                if (config.getContextLength() > 0) {
                    node.set("contextLength", config.getContextLength());
                }
                modelsArray.add(node);
            }

            Files.createDirectories(file.getParent());
            Files.write(file, root.toJson().getBytes("UTF-8"));
        } catch (Exception e) {
            LOG.warn("[Settings] Failed to persist LLM models: {}", e.getMessage());
        }
    }

    /**
     * 从持久化文件加载模型到引擎（启动时调用）
     */
    public void loadPersistedModels() {
        try {
            Path file = getLlmModelsFile();
            if (!Files.exists(file)) {
                return;
            }

            ONode root = loadLlmModelsConfig(file);
            ONode models = root.get("models");
            if (models == null || !models.isArray()) {
                return;
            }

            for (ONode node : models.getArray()) {
                String model = node.get("model").getString();
                String apiUrl = node.get("apiUrl").getString();
                if (model == null || model.isEmpty() || apiUrl == null || apiUrl.isEmpty()) {
                    continue;
                }

                String name = node.get("name").getString();
                if (name == null || name.isEmpty()) {
                    name = model;
                }

                ChatConfig config = new ChatConfig();
                config.setName(name);
                config.setApiUrl(apiUrl);
                config.setApiKey(node.get("apiKey").getString());
                config.setModel(model);

                String provider = node.get("provider").getString();
                if (provider != null && !provider.isEmpty()) {
                    config.setProvider(provider);
                }

                String timeout = node.get("timeout").getString();
                if (timeout != null && !timeout.isEmpty()) {
                    config.setTimeout(java.time.Duration.parse(timeout));
                }

                String userAgent = node.get("userAgent").getString();
                if (userAgent != null && !userAgent.isEmpty()) {
                    config.setUserAgent(userAgent);
                }

                Integer contextLength = node.get("contextLength").getInt();
                if (contextLength != null && contextLength > 0) {
                    config.setContextLength(contextLength);
                }

                engine.getProps().removeModel(model);
                engine.getProps().addModel(config);
            }

            LOG.info("[Settings] Loaded persisted LLM models from: {}", file);
        } catch (Exception e) {
            LOG.warn("[Settings] Failed to load persisted LLM models: {}", e.getMessage());
        }
    }

    /**
     * 更新 MCP 服务器配置
     */
    @Post
    @Mapping("/web/settings/mcp/servers/update")
    public Result mcpServersUpdate(Context ctx) throws Exception {
        ONode root = ONode.ofJson(ctx.body());
        String name = root.get("name").getString();

        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        java.nio.file.Path mcpFile = getMcpServersFile();
        ONode config = loadMcpConfig(mcpFile);
        ONode servers = config.get("mcpServers");

        if (servers != null && servers.isArray()) {
            for (ONode s : servers.getArray()) {
                if (name.equals(s.get("name").getString())) {
                    if (root.hasKey("type")) s.set("type", root.get("type").getString());
                    if (root.hasKey("enabled")) s.set("enabled", root.get("enabled").getBoolean());
                    String type = root.hasKey("type") ? root.get("type").getString() : s.get("type").getString();
                    if ("stdio".equals(type)) {
                        if (root.hasKey("command")) s.set("command", root.get("command").getString());
                        if (root.hasKey("args")) s.set("args", root.get("args"));
                        if (root.hasKey("env")) s.set("env", root.get("env"));
                        s.remove("url");
                        s.remove("headers");
                        s.remove("timeout");
                    } else {
                        if (root.hasKey("url")) s.set("url", root.get("url").getString());
                        if (root.hasKey("headers")) s.set("headers", root.get("headers"));
                        if (root.hasKey("timeout")) s.set("timeout", root.get("timeout").getString());
                        s.remove("command");
                        s.remove("args");
                        s.remove("env");
                    }
                    saveMcpConfig(mcpFile, config);
                    LOG.info("[Settings] MCP server updated: {}", name);
                    return Result.succeed();
                }
            }
        }

        return Result.failure("Server not found: " + name);
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

            } else if ("sse".equals(type) || "streamable-http".equals(type)) {
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

        java.nio.file.Path mcpFile = getMcpServersFile();
        ONode config = loadMcpConfig(mcpFile);
        ONode servers = config.getOrNew("mcpServers").asArray();
        java.util.Set<String> existingNames = new java.util.HashSet<>();
        for (ONode s : servers.getArray()) {
            existingNames.add(s.get("name").getString());
        }

        int imported = 0;
        if (serversNode.isObject()) {
            // Map 格式: name -> config
            for (Map.Entry<String, ONode> entry : serversNode.getObject().entrySet()) {
                String name = entry.getKey();
                if (existingNames.contains(name)) continue;
                ONode src = entry.getValue();
                ONode newServer = new ONode();
                newServer.set("name", name);
                newServer.set("type", src.hasKey("type") ? src.get("type").getString() : "stdio");
                newServer.set("enabled", true);
                if (src.hasKey("command")) newServer.set("command", src.get("command").getString());
                if (src.hasKey("args")) newServer.set("args", src.get("args"));
                if (src.hasKey("env")) newServer.set("env", src.get("env"));
                if (src.hasKey("url")) newServer.set("url", src.get("url").getString());
                if (src.hasKey("headers")) newServer.set("headers", src.get("headers"));
                if (src.hasKey("timeout")) newServer.set("timeout", src.get("timeout").getString());
                servers.add(newServer);
                imported++;
            }
        } else if (serversNode.isArray()) {
            for (ONode src : serversNode.getArray()) {
                String name = src.get("name").getString();
                if (existingNames.contains(name)) continue;
                servers.add(src);
                imported++;
            }
        }

        saveMcpConfig(mcpFile, config);
        LOG.info("[Settings] MCP servers imported: {} items", imported);
        return Result.succeed("Imported " + imported + " server(s)");
    }

    private ONode loadMcpConfig(java.nio.file.Path file) throws Exception {
        if (!java.nio.file.Files.exists(file)) {
            ONode root = new ONode();
            root.getOrNew("mcpServers").asArray();
            return root;
        }
        String content = new String(java.nio.file.Files.readAllBytes(file), "UTF-8");
        return ONode.ofJson(content);
    }

    private void saveMcpConfig(java.nio.file.Path file, ONode config) throws Exception {
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.write(file, config.toJson().getBytes("UTF-8"));
    }

    // ==================== 设置：OpenApi 服务器管理 ====================

    /**
     * 获取已配置的 OpenApi 服务器列表
     */
    @Get
    @Mapping("/web/settings/webapi/servers")
    public Result<List<Map>> webapiServers() throws Exception {
        java.nio.file.Path webapiFile = getWebapiServersFile();
        List<Map> list = new ArrayList<>();
        if (!java.nio.file.Files.exists(webapiFile)) {
            return Result.succeed(list);
        }

        String content = new String(java.nio.file.Files.readAllBytes(webapiFile), "UTF-8");
        ONode root = ONode.ofJson(content);
        ONode servers = root.get("apiServers");
        if (servers != null && servers.isArray()) {
            for (ONode node : servers.getArray()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", node.get("name").getString());
                item.put("apiBaseUrl", node.get("apiBaseUrl").getString());
                item.put("docUrl", node.get("docUrl").getString());
                item.put("enabled", node.get("enabled").getBoolean(true));
                ONode headersNode = node.get("headers");
                if (headersNode != null && headersNode.isObject()) {
                    Map<String, String> headersMap = new LinkedHashMap<>();
                    for (Map.Entry<String, ONode> entry : headersNode.getObject().entrySet()) {
                        headersMap.put(entry.getKey(), entry.getValue().getString());
                    }
                    item.put("headers", headersMap);
                }
                list.add(item);
            }
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

        java.nio.file.Path webapiFile = getWebapiServersFile();
        ONode config = loadWebapiConfig(webapiFile);
        ONode servers = config.getOrNew("apiServers").asArray();

        // 检查重名
        for (ONode s : servers.getArray()) {
            if (name.equals(s.get("name").getString())) {
                return Result.failure("Server name already exists: " + name);
            }
        }

        ONode newServer = new ONode();
        newServer.set("name", name);
        newServer.set("apiBaseUrl", apiBaseUrl);
        newServer.set("docUrl", root.get("docUrl").getString());
        newServer.set("enabled", root.get("enabled").getBoolean(true));
        if (root.hasKey("headers")) newServer.set("headers", root.get("headers"));

        servers.add(newServer);
        saveWebapiConfig(webapiFile, config);

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

        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        java.nio.file.Path webapiFile = getWebapiServersFile();
        ONode config = loadWebapiConfig(webapiFile);
        ONode servers = config.get("apiServers");

        if (servers != null && servers.isArray()) {
            for (ONode s : servers.getArray()) {
                if (name.equals(s.get("name").getString())) {
                    if (root.hasKey("apiBaseUrl")) s.set("apiBaseUrl", root.get("apiBaseUrl").getString());
                    if (root.hasKey("docUrl")) s.set("docUrl", root.get("docUrl").getString());
                    if (root.hasKey("enabled")) s.set("enabled", root.get("enabled").getBoolean());
                    if (root.hasKey("headers")) s.set("headers", root.get("headers"));
                    saveWebapiConfig(webapiFile, config);
                    LOG.info("[Settings] OpenApi server updated: {}", name);
                    return Result.succeed();
                }
            }
        }

        return Result.failure("Server not found: " + name);
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

        java.nio.file.Path webapiFile = getWebapiServersFile();
        ONode config = loadWebapiConfig(webapiFile);
        ONode servers = config.get("apiServers");

        if (servers != null && servers.isArray()) {
            servers.getArray().removeIf(s -> name.equals(s.get("name").getString()));
        }

        saveWebapiConfig(webapiFile, config);
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

        java.nio.file.Path webapiFile = getWebapiServersFile();
        ONode config = loadWebapiConfig(webapiFile);
        ONode servers = config.get("apiServers");

        if (servers != null && servers.isArray()) {
            for (ONode s : servers.getArray()) {
                if (name.equals(s.get("name").getString())) {
                    s.set("enabled", enabled);
                    break;
                }
            }
        }

        saveWebapiConfig(webapiFile, config);
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

        java.nio.file.Path webapiFile = getWebapiServersFile();
        ONode config = loadWebapiConfig(webapiFile);
        ONode servers = config.getOrNew("apiServers").asArray();
        java.util.Set<String> existingNames = new java.util.HashSet<>();
        for (ONode s : servers.getArray()) {
            existingNames.add(s.get("name").getString());
        }

        int imported = 0;
        if (serversNode.isObject()) {
            // Map 格式: name -> config
            for (Map.Entry<String, ONode> entry : serversNode.getObject().entrySet()) {
                String name = entry.getKey();
                if (existingNames.contains(name)) continue;
                ONode src = entry.getValue();
                ONode newServer = new ONode();
                newServer.set("name", name);
                newServer.set("apiBaseUrl", src.hasKey("apiBaseUrl") ? src.get("apiBaseUrl").getString() : "");
                newServer.set("docUrl", src.hasKey("docUrl") ? src.get("docUrl").getString() : "");
                newServer.set("enabled", true);
                if (src.hasKey("headers")) newServer.set("headers", src.get("headers"));
                servers.add(newServer);
                imported++;
            }
        } else if (serversNode.isArray()) {
            for (ONode src : serversNode.getArray()) {
                String name = src.get("name").getString();
                if (existingNames.contains(name) || Assert.isEmpty(name)) continue;
                servers.add(src);
                imported++;
            }
        }

        saveWebapiConfig(webapiFile, config);
        LOG.info("[Settings] OpenApi servers imported: {} items", imported);
        return Result.succeed("Imported " + imported + " server(s)");
    }

    private java.nio.file.Path getWebapiServersFile() {
        return java.nio.file.Paths.get(engine.getProps().getWorkspace(), ".soloncode", "webapi-servers.json");
    }

    private ONode loadWebapiConfig(java.nio.file.Path file) throws Exception {
        if (!java.nio.file.Files.exists(file)) {
            ONode root = new ONode();
            root.getOrNew("apiServers").asArray();
            return root;
        }
        String content = new String(java.nio.file.Files.readAllBytes(file), "UTF-8");
        return ONode.ofJson(content);
    }

    private void saveWebapiConfig(java.nio.file.Path file, ONode config) throws Exception {
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.write(file, config.toJson().getBytes("UTF-8"));
    }

    /**
     * 从持久化文件加载 MCP 服务器到引擎（启动时调用）
     */
    public void loadPersistedMcpServers() {
        try {
            java.nio.file.Path mcpFile = getMcpServersFile();
            if (!java.nio.file.Files.exists(mcpFile)) {
                return;
            }

            ONode config = loadMcpConfig(mcpFile);
            ONode servers = config.get("mcpServers");
            if (servers == null || !servers.isArray()) {
                return;
            }

            for (ONode node : servers.getArray()) {
                boolean enabled = node.get("enabled").getBoolean(true);
                if (!enabled) continue;

                String name = node.get("name").getString();
                String type = node.get("type").getString();
                if (name == null || name.isEmpty()) continue;

                try {
                    McpClientProvider.Builder builder = McpClientProvider.builder();
                    if ("stdio".equals(type)) {
                        String command = node.get("command").getString();
                        if (command == null || command.isEmpty()) continue;
                        builder.channel(org.noear.solon.ai.mcp.McpChannel.STDIO).command(command);

                        ONode argsNode = node.get("args");
                        if (argsNode != null && argsNode.isArray()) {
                            List<String> argsList = new ArrayList<>();
                            for (ONode a : argsNode.getArray()) {
                                String arg = a.getString();
                                if (arg != null && !arg.isEmpty()) argsList.add(arg);
                            }
                            if (!argsList.isEmpty()) builder.args(argsList);
                        }

                        ONode envNode = node.get("env");
                        if (envNode != null && envNode.isObject() && envNode.getObject().size() > 0) {
                            Map<String, String> envMap = new LinkedHashMap<>();
                            for (Map.Entry<String, ONode> entry : envNode.getObject().entrySet()) {
                                envMap.put(entry.getKey(), entry.getValue().getString());
                            }
                            builder.env(envMap);
                        }
                    } else {
                        String url = node.get("url").getString();
                        if (url == null || url.isEmpty()) continue;
                        String channel = "sse".equals(type)
                                ? org.noear.solon.ai.mcp.McpChannel.SSE
                                : org.noear.solon.ai.mcp.McpChannel.STREAMABLE;
                        builder.channel(channel).url(url);

                        ONode headersNode = node.get("headers");
                        if (headersNode != null && headersNode.isObject()) {
                            Map<String, String> headersMap = new LinkedHashMap<>();
                            for (Map.Entry<String, ONode> entry : headersNode.getObject().entrySet()) {
                                headersMap.put(entry.getKey(), entry.getValue().getString());
                            }
                            builder.headers(headersMap);
                        }
                    }

                    McpClientProvider client = builder.build();
                    ToolGatewaySkill mcpGateway = engine.getMcpGatewaySkill();
                    if (mcpGateway != null) {
                        mcpGateway.addTool(name, client);
                    }
                    LOG.info("[Settings] Loaded persisted MCP server: {} ({})", name, type);
                } catch (Exception e) {
                    LOG.warn("[Settings] Failed to load MCP server '{}': {}", name, e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warn("[Settings] Failed to load persisted MCP servers: {}", e.getMessage());
        }
    }

    /**
     * 从持久化文件加载 WebApi 服务器到引擎（启动时调用）
     */
    public void loadPersistedWebapiServers() {
        try {
            java.nio.file.Path webapiFile = getWebapiServersFile();
            if (!java.nio.file.Files.exists(webapiFile)) {
                return;
            }
            ONode config = loadWebapiConfig(webapiFile);
            ONode servers = config.get("apiServers");
            if (servers == null || !servers.isArray()) {
                return;
            }
            RestApiSkill restApi = engine.getRestApiSkill();
            for (ONode node : servers.getArray()) {
                boolean enabled = node.get("enabled").getBoolean(true);
                if (!enabled) continue;
                String name = node.get("name").getString();
                String apiBaseUrl = node.get("apiBaseUrl").getString();
                String docUrl = node.get("docUrl").getString();
                if (apiBaseUrl == null || apiBaseUrl.isEmpty()) continue;

                Map<String, String> headersMap = null;
                ONode headersNode = node.get("headers");
                if (headersNode != null && headersNode.isObject()) {
                    headersMap = new LinkedHashMap<>();
                    for (Map.Entry<String, ONode> entry : headersNode.getObject().entrySet()) {
                        headersMap.put(entry.getKey(), entry.getValue().getString());
                    }
                }

                if (restApi != null) {
                    restApi.addApi(docUrl, apiBaseUrl, headersMap);
                }
                LOG.info("[Settings] Loaded persisted WebApi server: {} -> {}", name, apiBaseUrl);
            }
        } catch (Exception e) {
            LOG.warn("[Settings] Failed to load persisted WebApi servers: {}", e.getMessage());
        }
    }

    // ==================== 设置：Skills 市场（委派给 Market 接口） ====================

    /**
     * 技能市场代理接口 — 获取热门技能或搜索技能。
     * <p>所有外部 API 调用均由后端 Market 适配器完成，前端不直接访问外部服务。</p>
     *
     * @param action "trending" 获取热门 | "search" 搜索
     * @param query  搜索关键词（action=search 时使用）
     * @param limit  返回数量限制
     */
    @Get
    @Mapping("/web/settings/skills/proxy")
    public Result skillsProxy(Context ctx, @Param(value = "action", defaultValue = "trending") String action,
                              @Param(value = "q", defaultValue = "") String query,
                              @Param(value = "limit", defaultValue = "50") int limit,
                              @Param(value = "per_page", defaultValue = "50") int perPage) {
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
    public Result skillsInstall(Context ctx, @Param("slug") String slug) {
        if (Assert.isEmpty(slug)) {
            return Result.failure("slug is required");
        }

        java.nio.file.Path skillsDir = java.nio.file.Paths.get(engine.getProps().getWorkspace(), "skills");
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
