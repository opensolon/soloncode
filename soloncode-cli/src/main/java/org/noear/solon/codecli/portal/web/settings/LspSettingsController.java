package org.noear.solon.codecli.portal.web.settings;

import org.noear.snack4.ONode;
import org.noear.solon.ai.talents.lsp.LspManager;
import org.noear.solon.ai.talents.lsp.LspServerParameters;
import org.noear.solon.ai.util.CmdUtil;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.entity.LspServerDo;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * @author noear 2026/7/23 created
 *
 */
public class LspSettingsController extends BaseSettingsController{
    /**
     * 日志记录器
     */
    private static final Logger LOG = LoggerFactory.getLogger(LspSettingsController.class);

    /**
     * 构造函数：支持自定义所有依赖。
     */
    public LspSettingsController(WorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    // ==================== 设置：LSP 服务器管理 ====================

    /**
     * 获取已配置的 LSP 服务器列表
     *
     * <p>数据源是引擎的运行时清单而非 settings.json：内置服务器不落盘，
     * settings.json 里只有用户的自定义与覆盖。有覆盖条目的报其 scope（user/workspace），
     * 否则报 {@code builtin}，前端据此禁止删除。
     */
    @Get
    @Mapping("/web/settings/lsp/servers")
    public Result<List<Map>> lspServers() throws Exception {
        Map<String, LspServerDo> overrides = settings().getLspServers();

        List<Map> list = new ArrayList<>();
        for (Map.Entry<String, LspServerParameters> entry : engine().getLspServers().entrySet()) {
            String name = entry.getKey();
            LspServerParameters params = entry.getValue();
            LspServerDo override = overrides.get(name);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("enabled", params.isEnabled());
            item.put("scope", resolveScope(override));
            item.put("command", params.getCommand());
            item.put("extensions", params.getExtensions());
            item.put("installed", isCommandInstalled(params.getCommand()));
            if (params.getEnv() != null && !params.getEnv().isEmpty()) {
                item.put("env", params.getEnv());
            }
            if (params.getInitialization() != null && !params.getInitialization().isEmpty()) {
                item.put("initialization", params.getInitialization());
            }
            list.add(item);
        }

        //被用户停用的服务器不在引擎清单里（停用即从引擎摘除），仍需展示以便重新开启
        for (Map.Entry<String, LspServerDo> entry : overrides.entrySet()) {
            if (engine().getLspServers().containsKey(entry.getKey())) {
                continue;
            }
            LspServerDo params = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry.getKey());
            item.put("enabled", params.isEnabled());
            item.put("scope", resolveScope(params));
            item.put("command", params.getCommand());
            item.put("extensions", params.getExtensions());
            item.put("installed", isCommandInstalled(params.getCommand()));
            list.add(item);
        }

        sortByName(list, "name");

        return Result.succeed(list);
    }

    /**
     * 无覆盖条目即为内置服务器
     */
    private static String resolveScope(LspServerDo override) {
        if (override == null) {
            return AgentFlags.SCOPE_BUILTIN;
        }
        return override.getScope() != null ? override.getScope() : AgentFlags.SCOPE_LOCAL;
    }

    /**
     * 检测 LSP 启动命令是否已安装。
     *
     * <p>直接扫 PATH 而不是 fork {@code which}：本接口一次要判定十几个服务器，
     * 逐个起进程的成本不可接受（结果在 LspManager 内进程级缓存）。
     */
    private boolean isCommandInstalled(List<String> command) {
        if (command == null || command.isEmpty()) return false;
        return LspManager.isCommandAvailable(command.get(0));
    }

    /**
     * 添加 LSP 服务器配置
     */
    @Post
    @Mapping("/web/settings/lsp/servers/add")
    public Result lspServersAdd(@Body String json) throws Exception {
        ONode root = ONode.ofJson(json);
        String name = root.get("name").getString();
        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }
        if (settings().getLspServers().containsKey(name)) {
            return Result.failure("Server name already exists: " + name);
        }

        boolean enabled = root.get("enabled").getBoolean(true);
        String scope = root.hasKey("scope") ? root.get("scope").getString() : AgentFlags.SCOPE_USER;
        if (Assert.isEmpty(scope) || (!AgentFlags.SCOPE_LOCAL.equals(scope))) {
            scope = AgentFlags.SCOPE_USER;
        }

        LspServerDo params = new LspServerDo();
        params.setScope(scope);

        // command
        if (root.hasKey("command")) {
            List<String> commandList = new ArrayList<>();
            if (root.get("command").isArray()) {
                for (ONode c : root.get("command").getArray()) {
                    commandList.add(c.getString());
                }
            } else {
                String cmd = root.get("command").getString();
                commandList.addAll(CmdUtil.parseArguments(cmd));
            }
            params.setCommand(commandList);
        }

        // extensions
        if (root.hasKey("extensions")) {
            List<String> extList = new ArrayList<>();
            for (ONode e : root.get("extensions").getArray()) {
                extList.add(e.getString());
            }
            params.setExtensions(extList);
        }

        // env
        if (root.hasKey("env")) {
            Map<String, String> envMap = new LinkedHashMap<>();
            for (Map.Entry<String, ONode> entry : root.get("env").getObject().entrySet()) {
                envMap.put(entry.getKey(), entry.getValue().getString());
            }
            params.setEnv(envMap);
        }

        settings().getLspServers().put(name, params);

        if (enabled) {
            engine().addLspServer(name, params);
        }

        saveSettings();
        LOG.info("[Settings] LSP server added: {}", name);
        return Result.succeed();
    }

    /**
     * 更新 LSP 服务器配置
     */
    @Post
    @Mapping("/web/settings/lsp/servers/update")
    public Result lspServersUpdate(@Body String json) throws Exception {
        ONode root = ONode.ofJson(json);
        String name = root.get("name").getString();
        String originalName = root.get("originalName").getString();
        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        String lookupName = (originalName != null && !originalName.isEmpty()) ? originalName : name;
        LspServerDo existing = settings().getLspServers().get(lookupName);
        if (existing == null) {
            //内置服务器不落 settings.json：以引擎里的运行时默认为基线，本次修改生成一条覆盖条目
            LspServerParameters runtime = engine().getLspServers().get(lookupName);
            if (runtime == null) {
                return Result.failure("Server not found: " + lookupName);
            }
            existing = new LspServerDo();
            existing.setCommand(runtime.getCommand());
            existing.setExtensions(runtime.getExtensions());
            existing.setEnabled(runtime.isEnabled());
        }

        if (!lookupName.equals(name)) {
            settings().getLspServers().remove(lookupName);
            engine().removeLspServer(lookupName);
        } else {
            engine().removeLspServer(name);
        }

        boolean enabled = root.hasKey("enabled") ? root.get("enabled").getBoolean(true) : true;
        String scope = root.hasKey("scope") ? root.get("scope").getString() : (existing.getScope() != null ? existing.getScope() : AgentFlags.SCOPE_USER);
        if (Assert.isEmpty(scope) || (!AgentFlags.SCOPE_LOCAL.equals(scope))) {
            scope = AgentFlags.SCOPE_USER;
        }

        LspServerDo params = new LspServerDo();
        params.setScope(scope);

        // command
        if (root.hasKey("command")) {
            List<String> commandList = new ArrayList<>();
            if (root.get("command").isArray()) {
                for (ONode c : root.get("command").getArray()) {
                    commandList.add(c.getString());
                }
            } else {
                String cmd = root.get("command").getString();
                commandList.addAll(CmdUtil.parseArguments(cmd));
            }
            params.setCommand(commandList);
        } else {
            params.setCommand(existing.getCommand());
        }

        // extensions
        if (root.hasKey("extensions")) {
            List<String> extList = new ArrayList<>();
            for (ONode e : root.get("extensions").getArray()) {
                extList.add(e.getString());
            }
            params.setExtensions(extList);
        } else {
            params.setExtensions(existing.getExtensions());
        }

        // env
        if (root.hasKey("env")) {
            Map<String, String> envMap = new LinkedHashMap<>();
            for (Map.Entry<String, ONode> entry : root.get("env").getObject().entrySet()) {
                envMap.put(entry.getKey(), entry.getValue().getString());
            }
            params.setEnv(envMap);
        } else {
            params.setEnv(existing.getEnv());
        }

        settings().getLspServers().put(name, params);

        if (enabled) {
            engine().addLspServer(name, params);
        }

        saveSettings();
        LOG.info("[Settings] LSP server updated: {}", name);
        return Result.succeed();
    }

    /**
     * 移除 LSP 服务器配置
     */
    /**
     * 删除 LSP 服务器配置
     *
     * <p>内置服务器不存在于 settings.json，不可删除；删除一个内置名下的覆盖条目
     * 意为「恢复内置默认」，而不是把该服务器从引擎里摘掉。
     */
    @Post
    @Mapping("/web/settings/lsp/servers/remove")
    public Result lspServersRemove(@Body String json) throws Exception {
        ONode root = ONode.ofJson(json);
        String name = root.get("name").getString();
        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        LspServerParameters builtin = LspManager.buildLspServers().get(name);
        if (settings().getLspServers().containsKey(name) == false && builtin != null) {
            return Result.failure("Built-in server can not be removed: " + name);
        }

        settings().getLspServers().remove(name);
        saveSettings();

        if (builtin == null) {
            engine().removeLspServer(name);
            LOG.info("[Settings] LSP server removed: {}", name);
        } else {
            builtin.setEnabled(isCommandInstalled(builtin.getCommand()));
            engine().addLspServer(name, builtin);
            LOG.info("[Settings] LSP server override removed, built-in default restored: {}", name);
        }
        return Result.succeed();
    }

    /**
     * 切换 LSP 服务器启用/停用
     *
     * <p>内置服务器本身不落盘，因此首次切换时为它生成一条覆盖条目，
     * 让「用户有意停用」这个意图能跨重启保留。
     */
    @Post
    @Mapping("/web/settings/lsp/servers/toggle")
    public Result lspServersToggle(@Param("name") String name, @Param("enabled") Boolean enabled) throws Exception {
        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        LspServerDo params = settings().getLspServers().get(name);
        if (params == null) {
            LspServerParameters runtime = engine().getLspServers().get(name);
            if (runtime == null) {
                return Result.failure("Server not found: " + name);
            }
            params = new LspServerDo();
            params.setCommand(runtime.getCommand());
            params.setExtensions(runtime.getExtensions());
            params.setScope(AgentFlags.SCOPE_LOCAL);
            settings().getLspServers().put(name, params);
        }
        params.setEnabled(enabled);

        if (enabled) {
            engine().addLspServer(name, params);
        } else {
            engine().removeLspServer(name);
        }

        saveSettings();
        LOG.info("[Settings] LSP server toggled: {} -> {}", name, enabled);
        return Result.succeed();
    }
}
