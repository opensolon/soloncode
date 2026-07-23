package org.noear.solon.codecli.portal.web.settings;

import org.noear.snack4.ONode;
import org.noear.snack4.codec.TypeRef;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpClientProviders;
import org.noear.solon.ai.mcp.client.McpServerParameters;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.config.McpTypeResolver;
import org.noear.solon.codecli.config.entity.McpServerDo;
import org.noear.solon.codecli.config.models.ModelSpecService;
import org.noear.solon.codecli.config.models.ModelsAdapterManager;
import org.noear.solon.codecli.portal.FileWatchService;
import org.noear.solon.codecli.portal.web.WebGate;
import org.noear.solon.codecli.portal.web.market.MarketManager;
import org.noear.solon.codecli.portal.web.service.SkinService;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.IoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 * @author noear 2026/7/23 created
 *
 */
public class McpSettingsController extends BaseSettingsController{
    /**
     * 日志记录器
     */
    private static final Logger LOG = LoggerFactory.getLogger(McpSettingsController.class);

    /**
     * 构造函数：支持自定义所有依赖。
     */
    public McpSettingsController(HarnessEngine engine, AgentSettings settings, FileWatchService fileWatchService, WebGate webGate) {
        super(engine, settings, fileWatchService, webGate);
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
            item.put("type", params.getTypeOrTransport() != null ? params.getTypeOrTransport() : "stdio");
            item.put("enabled", params.isEnabled());
            item.put("scope", params.getScope() != null ? params.getScope() : AgentFlags.SCOPE_USER);
            if (McpTypeResolver.isStdio(params.getTypeOrTransport())) {
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
                    item.put("timeout", params.getTimeout().getSeconds() + "s");
                }
            }
            list.add(item);
        }


        sortByName(list, "name");

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
        String rawType = root.get("type").getString();
        String type = McpTypeResolver.standardize(rawType);

        if (Assert.isEmpty(name) || type == null) {
            return Result.failure("name and type are required");
        }

        // 校验类型合法性
        if (!McpTypeResolver.isValid(type)) {
            return Result.failure("Unsupported type: " + rawType);
        }

        // 检查重名
        if (settings.getMcpServers().containsKey(name)) {
            return Result.failure("Server name already exists: " + name);
        }

        boolean enabled = root.get("enabled").getBoolean(true);
        String scope = root.hasKey("scope") ? root.get("scope").getString() : AgentFlags.SCOPE_USER;
        if (Assert.isEmpty(scope) || (!AgentFlags.SCOPE_LOCAL.equals(scope))) {
            scope = AgentFlags.SCOPE_USER;
        }

        McpServerDo params = new McpServerDo();
        params.setType(type);
        params.setScope(scope);

        if (McpTypeResolver.isStdio(type)) {
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
        } else if (McpTypeResolver.isHttpType(type)) {
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
        String rawType = root.hasKey("type") ? root.get("type").getString() : existing.getTypeOrTransport();
        String type = McpTypeResolver.standardize(rawType);
        if (type == null) {
            type = McpTypeResolver.standardize(existing.getTypeOrTransport());
        }
        boolean enabled = root.hasKey("enabled") ? root.get("enabled").getBoolean(true) : true;
        String scope = root.hasKey("scope") ? root.get("scope").getString() : (existing.getScope() != null ? existing.getScope() : AgentFlags.SCOPE_USER);
        if (Assert.isEmpty(scope) || (!AgentFlags.SCOPE_LOCAL.equals(scope))) {
            scope = AgentFlags.SCOPE_USER;
        }

        McpServerDo params = new McpServerDo();
        params.setType(type);
        params.setScope(scope);

        if (McpTypeResolver.isStdio(type)) {
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
        } else if (McpTypeResolver.isHttpType(type)) {
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
    public Result mcpServersToggle(@Param("name") String name, @Param("enabled") boolean enabled) throws Exception {
        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        McpServerParameters params = settings.getMcpServers().get(name);
        if (params == null) {
            return Result.failure("Server not found: " + name);
        } else {
            params.setEnabled(enabled);
        }

        if (enabled) {
            // 启用：添加到引擎
            engine.addMcpServer(name, params);
        } else {
            // 停用：从引擎移除
            engine.removeMcpServer(name);
        }

        saveSettings();
        LOG.info("[Settings] MCP server toggled: {} -> {}", name, enabled);
        return Result.succeed();
    }

    /**
     * 检测 MCP 服务器连接（不保存配置，仅测试）
     */
    @Post
    @Mapping("/web/settings/mcp/servers/check")
    public Result mcpServersCheck(@Body String json) {
        try {
            ONode root = ONode.ofJson(json);
            String rawType = root.get("type").getString();
            String type = McpTypeResolver.standardize(rawType);
            if (type == null) type = McpTypeResolver.TYPE_STDIO;

            if (McpTypeResolver.isStdio(type)) {
                String command = root.get("command").getString();
                if (Assert.isEmpty(command)) {
                    return Result.failure("命令不能为空");
                }

                // 使用 McpClientProvider 进行真实的 MCP 初始化连接测试
                McpClientProvider.Builder builder = McpClientProvider.builder()
                        .channel(McpChannel.STDIO)
                        .command(command);

                // 设置参数
                List<String> argsList = root.get("args").toBean(TypeRef.listOf(String.class));
                if (Assert.isNotEmpty(argsList)) {
                    builder.args(argsList);
                }

                // 设置环境变量
                Map<String, String> envMap = root.get("env").toBean(TypeRef.mapOf(String.class, String.class));
                if (Assert.isNotEmpty(envMap)) {
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

            } else if (McpTypeResolver.isHttpType(type)) {
                String url = root.get("url").getString();
                if (Assert.isEmpty(url)) {
                    return Result.failure("URL 不能为空");
                }

                // 使用 McpClientProvider 进行真实的 MCP 初始化连接测试
                String channel = McpTypeResolver.toChannel(type);

                McpClientProvider.Builder builder = McpClientProvider.builder()
                        .channel(channel)
                        .url(url);

                // 设置自定义 headers
                Map<String, String> headersMap = root.get("headers").toBean(TypeRef.mapOf(String.class, String.class));
                if (Assert.isNotEmpty(headersMap)) {
                    builder.headers(headersMap);
                }

                McpClientProvider client = builder.build();
                AtomicReference<Throwable> errorRef = new AtomicReference<>();
                try {
                    // 通过 getTools() 触发 MCP 初始化握手，验证连接有效性
                    client.getClient().listTools()
                            .doOnError(err -> {
                                errorRef.set(err);
                            })
                            .block();

                    return Result.succeed("连接成功：MCP 初始化握手完成（" + type + "）");
                } catch (Exception e) {
                    if (errorRef.get() != null) {
                        throw errorRef.get();
                    } else {
                        throw e;
                    }
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
        } catch (Throwable e) {
            return Result.failure("检测失败: " + e.getMessage());
        }
    }

    // ==================== 设置：MCP 导入解析（后端解析） ====================

    /**
     * 解析 MCP 导入配置文件
     *
     * <p>接受上传的 JSON 文件，调用共享解析方法 {@link #parseMcpConfigNode(ONode)} 处理。</p>
     *
     * @param ctx Solon 上下文，通过 {@code ctx.file("file")} 获取上传文件
     * @return 包含格式类型与服务器列表的结构化数据
     */
    @Post
    @Mapping("/web/settings/mcp/import/parse")
    public Result mcpImportParse(Context ctx) throws Exception {
        UploadedFile file = ctx.file("file");
        if (file == null) {
            return Result.failure("请上传文件");
        }

        String content = IoUtil.transferToString(file.getContent(), "utf-8");

        ONode root;
        try {
            root = ONode.ofJson(content);
        } catch (Exception e) {
            return Result.failure("文件解析失败: " + e.getMessage());
        }

        return parseMcpConfigNode(root);
    }

    /**
     * 解析 MCP 导入 JSON 字符串
     *
     * <p>接收前端 POST 的 JSON 字符串 body，调用共享解析方法 {@link #parseMcpConfigNode(ONode)} 处理。
     * 与文件导入端点复用同一套解析逻辑，避免前端维护 type 兼容性判断。</p>
     *
     * @param json MCP 配置 JSON 字符串
     * @return 包含格式类型与服务器列表的结构化数据
     */
    @Post
    @Mapping("/web/settings/mcp/import/parse/string")
    public Result mcpImportParseString(@Body String json) {
        if (Assert.isEmpty(json)) {
            return Result.failure("JSON 字符串不能为空");
        }

        ONode root;
        try {
            root = ONode.ofJson(json);
        } catch (Exception e) {
            return Result.failure("JSON 解析失败: " + e.getMessage());
        }

        return parseMcpConfigNode(root);
    }

    /**
     * MCP 配置解析核心逻辑（文件导入与字符串导入共享）
     *
     * <p>检测 OpenCode（{@code $schema} 识别）、通用 {@code mcpServers}、显式 {@code format=mcp} 三种格式，
     * 将每个服务器配置标准化为可直接用于 {@code /mcp/servers/add} 的请求体格式
     *（含 {@code enabled}、{@code scope}、{@code type} 等字段）。</p>
     *
     * <p>type 的别名标准化统一由 {@link McpTypeResolver} 处理，前端无需关心类型兼容性。</p>
     *
     * @param root 已通过 ONode.ofJson 解析的根节点
     * @return 包含格式类型与服务器列表的结构化数据
     */
    private Result parseMcpConfigNode(ONode root) {
        if (root.isObject() == false) {
            return Result.failure("内容不是有效的 JSON 对象");
        }

        // 检测格式
        ONode mcpServersNode = null;
        String format = null;

        // 1. OpenCode 格式（$schema + mcp 字段）
        if (root.hasKey("$schema")
                && root.get("$schema").getString() != null
                && root.get("$schema").getString().contains("opencode.ai/config")
                && root.hasKey("mcp")
                && root.get("mcp").isObject()) {
            mcpServersNode = root.get("mcp");
            format = "OpenCode";
        }
        // 2. 通用 mcpServers 格式（Claude Desktop, Cursor 等）
        else if (root.hasKey("mcpServers") && root.get("mcpServers").isObject()) {
            mcpServersNode = root.get("mcpServers");
            format = "mcpServers";
        }
        // 3. 显式格式声明
        else if ("mcp".equals(root.get("format").getString())
                && root.hasKey("servers")
                && root.get("servers").isObject()) {
            mcpServersNode = root.get("servers");
            format = "explicit";
        }

        if (mcpServersNode == null) {
            return Result.failure("无法识别的配置格式: 期望 OpenCode 或 mcpServers 格式");
        }

        // 转换为统一结构返回（含 enabled、scope，可直接用于 /mcp/servers/add）
        List<Map<String, Object>> servers = new ArrayList<>();
        for (Map.Entry<String, ONode> entry : mcpServersNode.getObject().entrySet()) {
            String name = entry.getKey();
            ONode cfg = entry.getValue();

            if (!cfg.isObject()) {
                continue;
            }

            Map<String, Object> server = new LinkedHashMap<>();
            server.put("name", name);
            server.put("enabled", true);
            server.put("scope", "user");

            // 检测服务器类型
            String type = cfg.get("type").getString();
            String serverType;
            if (type == null || type.isEmpty()) {
                // 根据 command/url 推断
                if (cfg.hasKey("command")) {
                    serverType = McpTypeResolver.TYPE_STDIO;
                } else if (cfg.hasKey("url")) {
                    serverType = McpTypeResolver.TYPE_SSE;
                } else {
                    server.put("error", "无法识别服务器类型，缺少 command 或 url");
                    servers.add(server);
                    continue;
                }
            } else {
                serverType = McpTypeResolver.standardize(type);
                if (serverType == null) {
                    serverType = type; // 无法标准化时原样保留
                }
            }
            server.put("type", serverType);

            if (McpTypeResolver.isStdio(serverType)) {
                // 处理 command（可能为字符串或数组）
                ONode cmdNode = cfg.get("command");
                StringBuilder detailBuilder = new StringBuilder();
                if (cmdNode.isArray()) {
                    List<String> cmdParts = new ArrayList<>();
                    for (ONode c : cmdNode.getArray()) {
                        cmdParts.add(c.getString());
                    }
                    if (!cmdParts.isEmpty()) {
                        server.put("command", cmdParts.get(0));
                        detailBuilder.append(cmdParts.get(0));
                        if (cmdParts.size() > 1) {
                            server.put("args", new ArrayList<>(cmdParts.subList(1, cmdParts.size())));
                            for (int i = 1; i < cmdParts.size(); i++) {
                                detailBuilder.append(" ").append(cmdParts.get(i));
                            }
                        }
                    }
                } else {
                    String cmdStr = cmdNode.getString();
                    server.put("command", cmdStr);
                    detailBuilder.append(cmdStr);
                }

                // args（显式声明）
                if (cfg.hasKey("args")) {
                    List<String> args = new ArrayList<>();
                    for (ONode a : cfg.get("args").getArray()) {
                        args.add(a.getString());
                    }
                    server.put("args", args);
                    // 更新 detail 包含完整 command + args
                    detailBuilder.setLength(0);
                    if (server.containsKey("command")) {
                        detailBuilder.append(server.get("command"));
                    }
                    for (String a : args) {
                        detailBuilder.append(" ").append(a);
                    }
                }
                server.put("detail", detailBuilder.toString());

                // 环境变量：兼容多种命名
                Map<String, String> env = null;
                if (cfg.hasKey("env")) {
                    env = oNodeToStringMap(cfg.get("env"));
                } else if (cfg.hasKey("environment")) {
                    env = oNodeToStringMap(cfg.get("environment"));
                } else if (cfg.hasKey("envVars")) {
                    env = oNodeToStringMap(cfg.get("envVars"));
                } else if (cfg.hasKey("environmentVariables")) {
                    env = oNodeToStringMap(cfg.get("environmentVariables"));
                }
                if (env != null && !env.isEmpty()) {
                    server.put("env", env);
                }
            } else if (McpTypeResolver.isHttpType(serverType)) {
                // sse / streamable / streamable_stateless
                String url = cfg.get("url").getString();
                server.put("url", url);
                server.put("detail", url);

                if (cfg.hasKey("headers")) {
                    server.put("headers", oNodeToStringMap(cfg.get("headers")));
                }
                if (cfg.hasKey("timeout")) {
                    ONode timeoutNode = cfg.get("timeout");
                    if (timeoutNode.isNumber()) {
                        server.put("timeout", timeoutNode.getLong());
                    } else {
                        server.put("timeout", timeoutNode.getString());
                    }
                }
            } else {
                // 未知类型
                server.put("error", "不支持的服务器类型: " + serverType);
            }

            servers.add(server);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("format", format);
        data.put("servers", servers);

        return Result.succeed(data);
    }

    /**
     * 将 ONode 对象转为 Map<String, String>
     */
    private Map<String, String> oNodeToStringMap(ONode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, ONode> entry : node.getObject().entrySet()) {
            map.put(entry.getKey(), entry.getValue().getString());
        }
        return map;
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
}
