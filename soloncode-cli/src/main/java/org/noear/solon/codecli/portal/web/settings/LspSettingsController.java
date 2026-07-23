package org.noear.solon.codecli.portal.web.settings;

import org.noear.snack4.ONode;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.talents.lsp.LspServerParameters;
import org.noear.solon.ai.util.CmdUtil;
import org.noear.solon.annotation.*;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.config.entity.LspServerDo;
import org.noear.solon.codecli.portal.FileWatchService;
import org.noear.solon.codecli.portal.web.WebGate;
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
    public LspSettingsController(HarnessEngine engine, AgentSettings settings, FileWatchService fileWatchService, WebGate webGate) {
        super(engine, settings, fileWatchService, webGate);
    }

    // ==================== 设置：LSP 服务器管理 ====================

    /**
     * 获取已配置的 LSP 服务器列表
     */
    @Get
    @Mapping("/web/settings/lsp/servers")
    public Result<List<Map>> lspServers() throws Exception {
        List<Map> list = new ArrayList<>();
        for (Map.Entry<String, LspServerDo> entry : settings.getLspServers().entrySet()) {
            String name = entry.getKey();
            LspServerDo params = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("enabled", params.isEnabled());
            item.put("scope", params.getScope() != null ? params.getScope() : AgentFlags.SCOPE_LOCAL);
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

        sortByName(list, "name");

        return Result.succeed(list);
    }

    /**
     * 检测 LSP 启动命令是否已安装（通过 which 检测可执行文件是否存在）
     */
    private boolean isCommandInstalled(List<String> command) {
        if (command == null || command.isEmpty()) return false;
        String cmd = command.get(0);
        if (cmd == null || cmd.isEmpty()) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder("which", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exitCode = p.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            LOG.warn("[LSP] Failed to check command: {}", cmd);
            return false;
        }
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
        if (settings.getLspServers().containsKey(name)) {
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

        settings.getLspServers().put(name, params);

        if (enabled) {
            engine.addLspServer(name, params);
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
        LspServerDo existing = settings.getLspServers().get(lookupName);
        if (existing == null) {
            return Result.failure("Server not found: " + lookupName);
        }

        if (!lookupName.equals(name)) {
            settings.getLspServers().remove(lookupName);
            engine.removeLspServer(lookupName);
        } else {
            engine.removeLspServer(name);
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

        settings.getLspServers().put(name, params);

        if (enabled) {
            engine.addLspServer(name, params);
        }

        saveSettings();
        LOG.info("[Settings] LSP server updated: {}", name);
        return Result.succeed();
    }

    /**
     * 移除 LSP 服务器配置
     */
    @Post
    @Mapping("/web/settings/lsp/servers/remove")
    public Result lspServersRemove(@Body String json) throws Exception {
        ONode root = ONode.ofJson(json);
        String name = root.get("name").getString();
        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }
        LspServerDo params = settings.getLspServers().get(name);
        settings.getLspServers().remove(name);
        saveSettings();
        engine.removeLspServer(name);
        LOG.info("[Settings] LSP server removed: {}", name);
        return Result.succeed();
    }

    /**
     * 切换 LSP 服务器启用/停用
     */
    @Post
    @Mapping("/web/settings/lsp/servers/toggle")
    public Result lspServersToggle(@Param("name") String name, @Param("enabled") Boolean enabled) throws Exception {
        if (Assert.isEmpty(name)) {
            return Result.failure("name is required");
        }

        LspServerParameters params = settings.getLspServers().get(name);
        if (params == null) {
            return Result.failure("Server not found: " + name);
        } else {
            params.setEnabled(enabled);
        }

        if (enabled) {
            engine.addLspServer(name, params);
        } else {
            engine.removeLspServer(name);
        }

        saveSettings();
        LOG.info("[Settings] LSP server toggled: {} -> {}", name, enabled);
        return Result.succeed();
    }
}
