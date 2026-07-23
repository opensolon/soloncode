package org.noear.solon.codecli.portal.web.settings;

import org.noear.snack4.ONode;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.talents.gateway.openapi.ApiSource;
import org.noear.solon.ai.talents.gateway.openapi.ApiSourceClient;
import org.noear.solon.ai.talents.gateway.openapi.ApiTool;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.config.entity.ApiSourceDo;
import org.noear.solon.codecli.config.entity.LspServerDo;
import org.noear.solon.codecli.portal.FileWatchService;
import org.noear.solon.codecli.portal.web.WebGate;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.*;

/**
 *
 * @author noear 2026/7/23 created
 *
 */
public class OpenapiSettingsController extends BaseSettingsController{
    /**
     * 日志记录器
     */
    private static final Logger LOG = LoggerFactory.getLogger(OpenapiSettingsController.class);

    /**
     * 构造函数：支持自定义所有依赖。
     */
    public OpenapiSettingsController(HarnessEngine engine, AgentSettings settings, FileWatchService fileWatchService, WebGate webGate) {
        super(engine, settings, fileWatchService, webGate);
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
            item.put("scope", source.getScope() != null ? source.getScope() : AgentFlags.SCOPE_USER);
            if (source.getHeaders() != null) {
                item.put("headers", source.getHeaders());
            }
            list.add(item);
        }

        sortByName(list, "name");

        return Result.succeed(list);
    }

    /**
     * 添加 OpenApi 服务器配置
     */
    @Post
    @Mapping("/web/settings/openapi/servers/add")
    public Result openapiServersAdd(@Body String json) throws Exception {
        ONode root = ONode.ofJson(json);
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
        String scope = root.hasKey("scope") ? root.get("scope").getString() : AgentFlags.SCOPE_USER;
        if (Assert.isEmpty(scope) || (!AgentFlags.SCOPE_LOCAL.equals(scope))) {
            scope = AgentFlags.SCOPE_USER;
        }

        ApiSourceDo source = new ApiSourceDo();
        source.setApiBaseUrl(apiBaseUrl);
        String docUrl = root.get("docUrl").getString();
        if (Assert.isNotEmpty(docUrl)) {
            String ssrfError = validateExternalUrl(docUrl);
            if (ssrfError != null) {
                return Result.failure("文档地址 " + ssrfError);
            }
            source.setDocUrl(docUrl);
        }
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
    public Result openapiServersUpdate(@Body String json) throws Exception {
        ONode root = ONode.ofJson(json);
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
        String scope = root.hasKey("scope") ? root.get("scope").getString() : (existing.getScope() != null ? existing.getScope() : AgentFlags.SCOPE_USER);
        if (Assert.isEmpty(scope) || (!AgentFlags.SCOPE_LOCAL.equals(scope))) {
            scope = AgentFlags.SCOPE_USER;
        }
        ApiSourceDo source = new ApiSourceDo();
        source.setApiBaseUrl(root.hasKey("apiBaseUrl") ? root.get("apiBaseUrl").getString() : existing.getApiBaseUrl());
        // 文档地址 SSRF 校验
        String docUrlVal = root.hasKey("docUrl") ? root.get("docUrl").getString() : existing.getDocUrl();
        if (Assert.isNotEmpty(docUrlVal)) {
            String ssrfError = validateExternalUrl(docUrlVal);
            if (ssrfError != null) {
                return Result.failure("文档地址 " + ssrfError);
            }
            source.setDocUrl(docUrlVal);
        }
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

        ApiSourceDo source = settings.getApiServers().get(name);
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
    public Result openapiServersToggle(@Param("name") String name, @Param("enabled") Boolean enabled) throws Exception {
        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        ApiSource source = settings.getApiServers().get(name);
        if (source == null) {
            return Result.failure("Server not found: " + name);
        } else {
            source.setEnabled(enabled);
        }

        if (enabled) {
            // 启用：添加到引擎
            engine.addApiServer(source);
        } else {
            // 停用：从引擎移除
            engine.removeApiServer(source.getDocUrl());
        }

        saveSettings();
        LOG.info("[Settings] OpenApi server toggled: {} -> {}", name, enabled);
        return Result.succeed();
    }

    /**
     * 检测 OpenApi 服务器连接（HTTP HEAD/GET 请求测试）
     */
    @Post
    @Mapping("/web/settings/openapi/servers/check")
    public Result openapiServersCheck(@Body ApiSourceDo sourceDo) {
        try {
            if (Assert.isEmpty(sourceDo.getApiBaseUrl())) {
                return Result.failure("API 基地址不能为空");
            }

            if (Assert.isEmpty(sourceDo.getDocUrl())) {
                return Result.failure("API 文档地址不能为空");
            }

            // SSRF 防护：校验 URL 合法性
            String ssrfError = validateExternalUrl(sourceDo.getDocUrl());
            if (ssrfError != null) {
                return Result.failure(ssrfError);
            }

            // 构建HTTP连接测试
            java.net.URL url = new java.net.URL(sourceDo.getDocUrl());
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);

            // 设置自定义 headers
            if (Assert.isNotEmpty(sourceDo.getHeaders())) {
                for (Map.Entry<String, String> entry : sourceDo.getHeaders().entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            int responseCode = conn.getResponseCode();
            conn.disconnect();

            if (responseCode >= 200 && responseCode < 400) {
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
     * SSRF 防护：校验外部 URL 是否合法，防止访问内部网络
     *
     * @param urlStr 待校验的 URL 字符串
     * @return null 表示合法，非 null 为错误描述
     */
    private String validateExternalUrl(String urlStr) {
        if (Assert.isEmpty(urlStr)) {
            return "URL 不能为空";
        }

        try {
            URL url = new URL(urlStr);
            String protocol = url.getProtocol();
            if (!"http".equals(protocol) && !"https".equals(protocol)) {
                return "仅支持 http/https 协议";
            }

            String host = url.getHost();
            if (host == null || host.isEmpty()) {
                return "URL 缺少主机名";
            }

            // 拒绝内部主机名
            String hostLower = host.toLowerCase();
            if (hostLower.equals("localhost") ||
                    hostLower.equals("127.0.0.1") ||
                    hostLower.equals("::1") ||
                    hostLower.equals("[::1]") ||
                    hostLower.endsWith(".local") ||
                    hostLower.endsWith(".localhost")) {
                return "不允许访问内部网络地址";
            }

            // 尝试解析 IP 并拒绝私有地址段
            InetAddress inet = InetAddress.getByName(host);
            if (inet.isLoopbackAddress() ||
                    inet.isSiteLocalAddress() ||
                    inet.isLinkLocalAddress()) {
                return "不允许访问内部网络地址";
            }

            return null;
        } catch (MalformedURLException e) {
            return "URL 格式错误: " + e.getMessage();
        } catch (UnknownHostException e) {
            // 无法解析的主机名不阻止（可能临时不可达），直接放行
            return null;
        }
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
}
