package org.noear.solon.codecli.config;

import lombok.Getter;
import lombok.Setter;
import org.noear.solon.Solon;
import org.noear.solon.codecli.auth.UserAuthConfig;
import org.noear.solon.codecli.config.entity.*;
import org.noear.solon.core.Props;
import org.noear.solon.core.util.Assert;
import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.solon.ai.talents.mount.MountType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对应 ~/.soloncode/settings.json
 * <p>统一管理 LLM 模型、MCP 服务器、OpenApi 服务器的持久化配置。</p>
 *
 * @author noear 2026/5/29 created
 */
@Getter
@Setter
public class AgentSettings implements Serializable {
    private static final Logger LOG = LoggerFactory.getLogger(AgentSettings.class);

    //general 常规
    private final GeneralGroupDo general = new GeneralGroupDo();
    //permission 权限
    private final PermissionGroupDo permission = new PermissionGroupDo();
    //用户认证配置
    private final UserAuthConfig userAuth = new UserAuthConfig();
    //loop Goal 配置
    private final LoopGroupDo loop = new LoopGroupDo();

    //defaultModel
    private String defaultModel;
    //models
    private Map<String, ModelDo> models = new LinkedHashMap<>();
    //挂载
    private Map<String, MountDo> mountPools = new LinkedHashMap<>();

    //mcp集
    private Map<String, McpServerDo> mcpServers = new LinkedHashMap<>();
    //api集
    private Map<String, ApiSourceDo> apiServers = new LinkedHashMap<>();
    //lsp集
    private Map<String, LspServerDo> lspServers = new LinkedHashMap<>();
    //供应商集
    private Map<String, ProviderDo> providers = new LinkedHashMap<>();

    /**
     * 本实例所属工作区的物理目录（local settings.json 落盘根）。
     * <p>多工作区隔离关键：默认取启动目录 user.dir；由 {@link #loadForWorkspace(String)}
     * 按工作区目录赋值，确保 scope=workspace 的配置写回其所属工作区目录，而非启动目录。</p>
     * <p>transient：不参与 JSON 序列化，仅运行期路由用。</p>
     */
    private transient String workspaceDir = AgentFlags.getUserDir();

    /**
     * 与 HarnessProperties（即 AgentProperties）双向合并。
     * <p>如果 settings 有数据，以 settings 为准同步到 props；
     * 如果 settings 为空，则从 props 补充到 settings。</p>
     */
    public void mergeFrom() {
        //-----------------------------------------------------
        Props cfg = Solon.cfg();

        // loop: 从 app.yml 的 soloncode.loop.* 回填（仅当 settings.json 未配置时）
        try {
            if (loop.getBudgetWarningPercent() == null)
                loop.setBudgetWarningPercent(cfg.getInt("soloncode.loop.budgetWarningPercent", 70));
            if (loop.getBudgetCriticalPercent() == null)
                loop.setBudgetCriticalPercent(cfg.getInt("soloncode.loop.budgetCriticalPercent", 85));
            if (loop.getDefaultMaxTokens() == null)
                loop.setDefaultMaxTokens(cfg.getLong("soloncode.loop.defaultMaxTokens", 0L));
            if (loop.getDefaultMaxDurationMinutes() == null)
                loop.setDefaultMaxDurationMinutes(cfg.getInt("soloncode.loop.defaultMaxDurationMinutes", 0));
            if (loop.getStagnationThreshold() == null)
                loop.setStagnationThreshold(cfg.getInt("soloncode.loop.stagnationThreshold", 3));
            if (loop.getMaxConsecutiveErrors() == null)
                loop.setMaxConsecutiveErrors(cfg.getInt("soloncode.loop.maxConsecutiveErrors", 3));
            if (loop.getValidatorEnabled() == null)
                loop.setValidatorEnabled(cfg.getBool("soloncode.loop.validatorEnabled", true));
        } catch (Exception ignored) {
            // 非 Solon 环境时保持 null，便捷方法提供默认值
        }

        //-----------------------------------------------------

        // logging: 从 app.yml 的 solon.logging.appender.file.* 回填默认值
        try {
            if (general.getLogLevel() == null) {
                general.setLogLevel(cfg.get("solon.logging.appender.file.level", "INFO"));
            }
            if (general.getLogFileMaxSize() == null) {
                general.setLogFileMaxSize(cfg.get("solon.logging.appender.file.maxFileSize", "10 MB"));
            }
            if (general.getLogMaxHistory() == null) {
                general.setLogMaxHistory(cfg.getInt("solon.logging.appender.file.maxHistory", 7));
            }
        } catch (Exception ignored) {
            // 非 Solon 环境时保持 null
        }

        //-----------------------------------------------------

        if (permission.getTools().size() == 0) {
            permission.getTools().add("**");
        }

        //-----------------------------------------------------


        // 合并完成后统一兜底：如果 defaultModel 未指定，取第一个模型
        if (Assert.isEmpty(this.defaultModel) && this.models.size() > 0) {
            this.defaultModel = this.models.values().iterator().next().getNameOrModel();
        }
    }

    /**
     * 从文件加载配置（启动路径宽松：读盘/解析失败时返回空对象，避免阻断启动）。
     */
    public static AgentSettings loadFromFile() {
        try {
            return loadFromFileStrict();
        } catch (Exception e) {
            LOG.warn("[Settings] Failed to load settings from file: {}", e.getMessage());
            return new AgentSettings();
        }
    }

    /**
     * 从文件严格加载配置。读盘或解析失败时抛出异常，禁止用空配置覆盖运行中实例。
     * <p>语义：先 global，再 local 覆盖；文件不存在视为空配置（合法）。</p>
     */
    public static AgentSettings loadFromFileStrict() throws Exception {
        return loadSettingsStrict(null);
    }

    /**
     * 严格加载配置（可指定 local 层目录）。
     *
     * @param localDir local 层目录；null 时用启动目录（CLI 语义）。
     *                 非默认工作区实例热重载时必须传 workspaceDir，与 saveToFile 写回路径对称，
     *                 否则读A写B错位（启动目录内容被当工作区配置载入并写进工作区文件）。
     */
    private static AgentSettings loadSettingsStrict(String localDir) throws Exception {
        Path globalFile = Paths.get(AgentFlags.getUserHome(), ".soloncode", "settings.json").toAbsolutePath();
        Path localFile = Paths.get(localDir != null ? localDir : AgentFlags.getUserDir(), ".soloncode", "settings.json").toAbsolutePath();
        boolean isLocalAsGlobal = localFile.toString().equals(globalFile.toString());

        AgentSettings agentSettings = new AgentSettings();

        if (Files.exists(globalFile)) {
            bindSettingsFile(globalFile, agentSettings);
        }

        if (isLocalAsGlobal == false && Files.exists(localFile)) {
            bindSettingsFile(localFile, agentSettings);
        }

        return agentSettings;
    }

    /**
     * 按指定工作区目录加载配置（多工作区场景）。
     * <p>语义：先 global（~/.soloncode/settings.json），再以 workspaceDir/.soloncode/settings.json 覆盖。</p>
     */
    public static AgentSettings loadForWorkspace(String workspaceDir) {
        try {
            Path globalFile = Paths.get(AgentFlags.getUserHome(), ".soloncode", "settings.json").toAbsolutePath();
            Path localFile = Paths.get(workspaceDir, ".soloncode", "settings.json").toAbsolutePath();
            boolean isLocalAsGlobal = localFile.toString().equals(globalFile.toString());

            AgentSettings agentSettings = new AgentSettings();
            // 记录本实例所属工作区目录，供 saveToFile 对称写回（而非启动目录）。
            agentSettings.workspaceDir = workspaceDir;

            if (Files.exists(globalFile)) {
                bindSettingsFile(globalFile, agentSettings);
            }

            // 多工作区隔离：全局配置中 scope=user 的 FILES 类挂载点属于“数据目录”，
            // 不应泄入其他物理工作区（否则文件树会显示默认项目目录）；
            // SKILLS/AGENTS 类属于能力注入，保留继承。工作区自己的挂载由下方 local 文件提供。
            if (isLocalAsGlobal == false) {
                agentSettings.getMountPools().values().removeIf(mount ->
                        mount.getType() == org.noear.solon.ai.talents.mount.MountType.FILES
                                && AgentFlags.SCOPE_LOCAL.equals(mount.getScope()) == false);
            }

            if (isLocalAsGlobal == false && Files.exists(localFile)) {
                bindSettingsFile(localFile, agentSettings);
            }

            agentSettings.mergeFrom();
            return agentSettings;
        } catch (Exception e) {
            LOG.warn("[Settings] Failed to load settings for workspace {}: {}", workspaceDir, e.getMessage());
            return new AgentSettings();
        }
    }

    private static void bindSettingsFile(Path file, AgentSettings agentSettings) throws Exception {
        String json = new String(Files.readAllBytes(file), "UTF-8");
        ONode oNode = ONode.ofJson(json);

        ONode oModels = oNode.get("models");
        if (oModels.isArray()) { //旧格式，转成新格式
            ONode map = new ONode().asObject();
            for (ONode item : oModels.getArrayUnsafe()) {
                map.set(item.get("name").getString(), item);
            }
            oNode.set("models", map);
        }

        oNode.bindTo(agentSettings);
    }

    /**
     * 从磁盘重载到当前实例（in-place）。
     * <p>语义与 {@link #loadFromFileStrict()} 一致：先 global，再 local 覆盖。
     * 仅在读盘+解析成功后才修改当前实例；不会写回磁盘。
     * 读盘/解析失败时抛出异常，保留内存中的现有配置。</p>
     *
     * @return true 表示内容已变化并已写入当前实例；false 表示无变化
     */
    public synchronized boolean reloadInPlace() {
        AgentSettings disk;
        try {
            // 读路径与写路径对称：工作区实例从本工作区目录读 local 层
            disk = loadSettingsStrict(this.workspaceDir);
            disk.workspaceDir = this.workspaceDir;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load settings from disk: " + e.getMessage(), e);
        }
        // 先对磁盘快照补默认值，再比指纹：避免“磁盘缺字段(null) vs 内存已 merge 默认”被误判为变更。
        disk.fillRuntimeDefaults();
        if (contentFingerprint(this).equals(contentFingerprint(disk))) {
            return false;
        }
        copyFrom(disk);
        return true;
    }

    /**
     * 将 general/loop 中仍为 null 的字段回落到运行时默认值（不覆盖已有非 null）。
     * <p>优先走 {@link #mergeFrom()}，与启动时 Configurator 行为一致；
     * 非 Solon 环境（如单测）时再补一层硬编码默认，避免 loop/log 永久为 null。</p>
     */
    public synchronized void fillRuntimeDefaults() {
        mergeFrom();
        // mergeFrom 内 loop/log 依赖 Solon.cfg()；非 Solon 环境时补硬编码默认
        if (loop.getBudgetWarningPercent() == null) {
            loop.setBudgetWarningPercent(70);
        }
        if (loop.getBudgetCriticalPercent() == null) {
            loop.setBudgetCriticalPercent(85);
        }
        if (loop.getDefaultMaxTokens() == null) {
            loop.setDefaultMaxTokens(0L);
        }
        if (loop.getDefaultMaxDurationMinutes() == null) {
            loop.setDefaultMaxDurationMinutes(0);
        }
        if (loop.getStagnationThreshold() == null) {
            loop.setStagnationThreshold(3);
        }
        if (loop.getMaxConsecutiveErrors() == null) {
            loop.setMaxConsecutiveErrors(3);
        }
        if (loop.getValidatorEnabled() == null) {
            loop.setValidatorEnabled(true);
        }
        if (general.getLogLevel() == null) {
            general.setLogLevel("INFO");
        }
        if (general.getLogFileMaxSize() == null) {
            general.setLogFileMaxSize("10MB");
        }
        if (general.getLogMaxHistory() == null) {
            general.setLogMaxHistory(7);
        }
    }

    /**
     * 将另一份 settings 内容合并进当前实例（in-place，保持 final 字段引用不变）。
     * <p>对 general/loop 做字段级显式赋值，确保磁盘上的 null 能清空内存旧值
     * （避免 ONode.bindTo 跳过 null 字段）。</p>
     */
    public synchronized void copyFrom(AgentSettings other) {
        if (other == null || other == this) {
            return;
        }

        copyGeneral(this.general, other.general);
        copyPermission(this.permission, other.permission);
        copyLoop(this.loop, other.loop);

        this.defaultModel = other.defaultModel;

        replaceMap(this.models, other.models);
        replaceMap(this.mountPools, other.mountPools);
        replaceMap(this.mcpServers, other.mcpServers);
        replaceMap(this.apiServers, other.apiServers);
        replaceMap(this.lspServers, other.lspServers);
        replaceMap(this.providers, other.providers);
        copyUserAuth(this.userAuth, other.userAuth);
    }

    private static void copyGeneral(GeneralGroupDo target, GeneralGroupDo source) {
        if (source == null) {
            source = new GeneralGroupDo();
        }
        target.setSessionWindowSize(source.getSessionWindowSize());
        target.setCompressionThresholdMessages(source.getCompressionThresholdMessages());
        target.setCompressionThresholdPercent(source.getCompressionThresholdPercent());
        target.setSandboxMode(source.isSandboxMode());
        target.setSandboxAllowUserHome(source.isSandboxAllowUserHome());
        target.setSandboxSystemRestrict(source.isSandboxSystemRestrict());
        target.setApiRetries(source.getApiRetries());
        target.setMcpRetries(source.getMcpRetries());
        target.setModelRetries(source.getModelRetries());
        target.setBashAsyncEnabled(source.isBashAsyncEnabled());
        target.setMemoryEnabled(source.isMemoryEnabled());
        target.setMemoryRelevanceCount(source.getMemoryRelevanceCount());
        target.setMemoryPriorityCount(source.getMemoryPriorityCount());
        target.setMemorySummaryLength(source.getMemorySummaryLength());
        target.setMcpEnabled(source.isMcpEnabled());
        target.setOpenApiEnabled(source.isOpenApiEnabled());
        target.setLspEnabled(source.isLspEnabled());
        target.setUserAgent(source.getUserAgent());
        target.setMaxTurns(source.getMaxTurns());
        target.setAutoRethink(source.isAutoRethink());
        target.setHitlEnabled(source.isHitlEnabled());
        target.setSubagentEnabled(source.isSubagentEnabled());
        target.setManagerEnabled(source.isManagerEnabled());
        target.setCliThinkPrinted(source.isCliThinkPrinted());
        target.setCliPrintSimplified(source.isCliPrintSimplified());
        target.setGoalsEnabled(source.isGoalsEnabled());
        target.setLoopsEnabled(source.isLoopsEnabled());
        target.setActiveSkin(source.getActiveSkin());
        target.setDefaultThinkingMode(source.getDefaultThinkingMode());
        target.setDefaultReasoningEffort(source.getDefaultReasoningEffort());
        target.setWebAuthUser(source.getWebAuthUser());
        target.setWebAuthPass(source.getWebAuthPass());
        target.setLogLevel(source.getLogLevel());
        target.setLogFileMaxSize(source.getLogFileMaxSize());
        target.setLogMaxHistory(source.getLogMaxHistory());
    }

    private static void copyPermission(PermissionGroupDo target, PermissionGroupDo source) {
        target.getTools().clear();
        target.getDisallowedTools().clear();
        target.getDisallowedSkills().clear();
        if (source != null) {
            if (source.getTools() != null) {
                target.getTools().addAll(source.getTools());
            }
            if (source.getDisallowedTools() != null) {
                target.getDisallowedTools().addAll(source.getDisallowedTools());
            }
            if (source.getDisallowedSkills() != null) {
                target.getDisallowedSkills().addAll(source.getDisallowedSkills());
            }
        }
    }

    private static void copyLoop(LoopGroupDo target, LoopGroupDo source) {
        if (source == null) {
            source = new LoopGroupDo();
        }
        target.setBudgetWarningPercent(source.getBudgetWarningPercent());
        target.setBudgetCriticalPercent(source.getBudgetCriticalPercent());
        target.setDefaultMaxTokens(source.getDefaultMaxTokens());
        target.setDefaultMaxDurationMinutes(source.getDefaultMaxDurationMinutes());
        target.setStagnationThreshold(source.getStagnationThreshold());
        target.setMaxConsecutiveErrors(source.getMaxConsecutiveErrors());
        target.setValidatorEnabled(source.getValidatorEnabled());
    }

    /**
     * 生成配置内容指纹（用于 reload 时判断是否有变化）。
     */
    static String contentFingerprint(AgentSettings s) {
        if (s == null) {
            return "";
        }
        ONode node = new ONode();
        node.set("general", ONode.ofBean(s.general));
        node.set("permission", ONode.ofBean(s.permission));
        node.set("loop", ONode.ofBean(s.loop));
        node.set("defaultModel", s.defaultModel);
        node.set("models", ONode.ofBean(s.models));
        node.set("mountPools", ONode.ofBean(s.mountPools));
        node.set("mcpServers", ONode.ofBean(s.mcpServers));
        node.set("apiServers", ONode.ofBean(s.apiServers));
        node.set("lspServers", ONode.ofBean(s.lspServers));
        node.set("providers", ONode.ofBean(s.providers));
        node.set("userAuth", ONode.ofBean(s.userAuth));
        return node.toJson();
    }

    private static void copyUserAuth(UserAuthConfig target, UserAuthConfig source) {
        if (source == null) {
            source = new UserAuthConfig();
        }
        target.setEnabled(source.isEnabled());
        target.setMode(source.getMode());
        target.setDbUrl(source.getDbUrl());
        target.setDbUser(source.getDbUser());
        target.setDbPassword(source.getDbPassword());
        target.setDbDriverClass(source.getDbDriverClass());
        target.setLdapUrl(source.getLdapUrl());
        target.setLdapAdminDn(source.getLdapAdminDn());
        target.setLdapAdminPassword(source.getLdapAdminPassword());
        target.setLdapBaseDn(source.getLdapBaseDn());
        target.setLdapUserFilter(source.getLdapUserFilter());
        target.setLdapSsl(source.isLdapSsl());
        target.setSessionTimeoutMinutes(source.getSessionTimeoutMinutes());
        target.setSessionTokenLength(source.getSessionTokenLength());
    }
    
    private static <K, V> void replaceMap(Map<K, V> target, Map<K, V> source) {
        target.clear();
        if (source != null && source.size() > 0) {
            target.putAll(source);
        }
    }

    /**
     * 保存配置到文件
     */
    public synchronized void saveToFile() {
        try {
            // 多工作区隔离：local 目录取本实例所属工作区（workspaceDir），而非启动目录；
            // 确保非默认工作区的 scope=workspace 配置能写回其所属目录（与 loadForWorkspace 对称）。
            String localDir = (workspaceDir != null && workspaceDir.length() > 0) ? workspaceDir : AgentFlags.getUserDir();

            Path globalFileOld = Paths.get(AgentFlags.getUserHome(), ".soloncode", "config.yml").toAbsolutePath();
            Path localFileOld = Paths.get(localDir, ".soloncode", "config.yml").toAbsolutePath();

            Path globalFile = Paths.get(AgentFlags.getUserHome(), ".soloncode", "settings.json").toAbsolutePath();
            Path localFile = Paths.get(localDir, ".soloncode", "settings.json").toAbsolutePath();
            boolean isLocalAsGlobal = localFile.toString().equals(globalFile.toString());

            // 原子写入：先写临时文件再原子移动，防止写入过程中崩溃导致文件损坏
            Files.createDirectories(globalFile.getParent());
            Path globalTmp = globalFile.resolveSibling(globalFile.getFileName() + ".tmp");
            Files.write(globalTmp, getGlobalJson(isLocalAsGlobal).getBytes("UTF-8"));
            Files.move(globalTmp, globalFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.deleteIfExists(globalFileOld); //有新配置后，去掉旧配置


            if (isLocalAsGlobal == false) {
                //如果本地文件，不同于全局文件
                Files.createDirectories(localFile.getParent());
                Path localTmp = localFile.resolveSibling(localFile.getFileName() + ".tmp");
                Files.write(localTmp, getLocalJson().getBytes("UTF-8"));
                Files.move(localTmp, localFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                Files.deleteIfExists(localFileOld); //有新配置后，去掉旧配置
            }
        } catch (Exception e) {
            LOG.warn("[Settings] Failed to save settings to file: {}", e.getMessage());
        }
    }


    public String getGlobalJson(boolean isLocalAsGlobal) {
        ONode oNode = new ONode(Options.of(Feature.Write_PrettyFormat));
        oNode.set("$schema", "https://solon.noear.org/soloncode/settings.schema.json");

        oNode.getOrNew("general").fill(general);
        oNode.getOrNew("permission").fill(permission);
        oNode.getOrNew("loop").fill(loop);

        oNode.set("defaultModel", this.defaultModel);

        oNode.getOrNew("models").asObject().then(map -> {
            for (Map.Entry<String, ModelDo> entry : models.entrySet()) {
                if (isLocalAsGlobal == false && AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope())) {
                    continue;
                }

                map.getOrNew(entry.getValue().getNameOrModel()).then(item -> {
                    item.fill(entry.getValue());
                    item.remove("userAgent");

                    if (entry.getValue().getTimeout() != null) {
                        item.set("timeout", entry.getValue().getTimeout().getSeconds() + "s");
                    }
                });
            }
        });

        oNode.getOrNew("mcpServers").asObject().then(map -> {
            for (Map.Entry<String, McpServerDo> entry : mcpServers.entrySet()) {
                if (isLocalAsGlobal == false && AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope())) {
                    continue;
                }

                map.getOrNew(entry.getKey()).then(item -> {
                    item.fill(entry.getValue());

                    if (entry.getValue().getTimeout() != null) {
                        item.set("timeout", entry.getValue().getTimeout().getSeconds() + "s");
                    }
                });
            }
        });

        oNode.getOrNew("apiServers").asObject().then(map -> {
            for (Map.Entry<String, ApiSourceDo> entry : apiServers.entrySet()) {
                if (isLocalAsGlobal == false && AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope())) {
                    continue;
                }

                map.getOrNew(entry.getKey()).then(item -> {
                    item.fill(entry.getValue());

                    if (entry.getValue().getTimeout() != null) {
                        item.set("timeout", entry.getValue().getTimeout().getSeconds() + "s");
                    }
                });
            }
        });

        oNode.getOrNew("mountPools").asObject().then(map -> {
            for (Map.Entry<String, MountDo> entry : mountPools.entrySet()) {
                if (isLocalAsGlobal == false && AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope())) {
                    continue;
                }

                map.getOrNew(entry.getKey()).fill(entry.getValue());
            }
        });

        oNode.getOrNew("lspServers").asObject().then(map -> {
            for (Map.Entry<String, LspServerDo> entry : lspServers.entrySet()) {
                if (isLocalAsGlobal == false && AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope())) {
                    continue;
                }

                map.getOrNew(entry.getKey()).fill(entry.getValue());
            }
        });

        oNode.getOrNew("providers").asObject().then(map -> {
            for (Map.Entry<String, ProviderDo> entry : providers.entrySet()) {
                if (isLocalAsGlobal == false && AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope())) {
                    continue;
                }

                map.getOrNew(entry.getKey()).fill(entry.getValue());
            }
        });

        return oNode.toJson();
    }

    public String getLocalJson() {
        ONode oNode = new ONode(Options.of(Feature.Write_PrettyFormat));
        oNode.set("$schema", "https://solon.noear.org/soloncode/settings.schema.json");

        oNode.getOrNew("models").asObject().then(map -> {
            for (Map.Entry<String, ModelDo> entry : models.entrySet()) {
                if (AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope()) == false) {
                    continue;
                }

                map.getOrNew(entry.getValue().getNameOrModel()).then(item -> {
                    item.fill(entry.getValue());
                    item.remove("userAgent");

                    if (entry.getValue().getTimeout() != null) {
                        item.set("timeout", entry.getValue().getTimeout().getSeconds() + "s");
                    }
                });
            }
        });

        oNode.getOrNew("mcpServers").asObject().then(map -> {
            for (Map.Entry<String, McpServerDo> entry : mcpServers.entrySet()) {
                if (AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope()) == false) {
                    continue;
                }

                map.getOrNew(entry.getKey()).then(item -> {
                    item.fill(entry.getValue());

                    if (entry.getValue().getTimeout() != null) {
                        item.set("timeout", entry.getValue().getTimeout().getSeconds() + "s");
                    }
                });
            }
        });

        oNode.getOrNew("apiServers").asObject().then(map -> {
            for (Map.Entry<String, ApiSourceDo> entry : apiServers.entrySet()) {
                if (AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope()) == false) {
                    continue;
                }

                map.getOrNew(entry.getKey()).then(item -> {
                    item.fill(entry.getValue());

                    if (entry.getValue().getTimeout() != null) {
                        item.set("timeout", entry.getValue().getTimeout().getSeconds() + "s");
                    }
                });
            }
        });

        oNode.getOrNew("mountPools").asObject().then(map -> {
            for (Map.Entry<String, MountDo> entry : mountPools.entrySet()) {
                if (AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope()) == false) {
                    continue;
                }

                map.getOrNew(entry.getKey()).fill(entry.getValue());
            }
        });

        oNode.getOrNew("lspServers").asObject().then(map -> {
            for (Map.Entry<String, LspServerDo> entry : lspServers.entrySet()) {
                if (AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope()) == false) {
                    continue;
                }

                map.getOrNew(entry.getKey()).fill(entry.getValue());
            }
        });

        oNode.getOrNew("providers").asObject().then(map -> {
            for (Map.Entry<String, ProviderDo> entry : providers.entrySet()) {
                if (AgentFlags.SCOPE_LOCAL.equals(entry.getValue().getScope()) == false) {
                    continue;
                }

                map.getOrNew(entry.getKey()).fill(entry.getValue());
            }
        });

        return oNode.toJson();
    }
}