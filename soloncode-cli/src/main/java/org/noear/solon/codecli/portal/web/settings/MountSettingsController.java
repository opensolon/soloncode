package org.noear.solon.codecli.portal.web.settings;

import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.talents.mount.catalog.AgentDescriptor;
import org.noear.solon.ai.talents.mount.catalog.SkillDescriptor;
import org.noear.solon.ai.talents.mount.source.FileMountSource;
import org.noear.solon.ai.talents.mount.source.MountCapabilities;
import org.noear.solon.ai.talents.mount.source.MountSource;
import org.noear.solon.ai.talents.mount.Mount;
import org.noear.solon.ai.talents.mount.MountEntry;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Post;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.entity.MountDo;
import org.noear.solon.codecli.util.MountPathUtil;
import org.noear.solon.codecli.portal.FileWatchService;
import org.noear.solon.codecli.util.OsOpenUtil;
import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/**
 *
 * @author noear 2026/7/23 created
 *
 */
public class MountSettingsController extends BaseSettingsController {
    private static final int FILES_PREVIEW_LIMIT = 50;

    /**
     * 日志记录器
     */
    private static final Logger LOG = LoggerFactory.getLogger(MountSettingsController.class);

    /**
     * 构造函数：支持自定义所有依赖。
     */
    public MountSettingsController(WorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    // ==================== 设置：挂载池管理 ====================

    /**
     * 获取所有挂载池列表（含系统池标记）
     */
    @Get
    @Mapping("/web/settings/mounts")
    public Result mountsList(Context ctx) {
        List<Map<String, Object>> list = new ArrayList<>();

        // 同一目录可能同时保留旧版自定义别名和新版系统别名。
        // 安装目标只保留一个，并优先使用能明确表达 user/workspace 作用域的系统挂载。
        // 引擎内置挂载（visible=false，如 @harness-agents）不属于用户挂载池，不展示。
        Map<String, Mount> uniqueMounts = new LinkedHashMap<>();
        for (Mount entry : engine().getMounts()) {
            if (entry.isVisible() == false) {
                continue;
            }
            String storageKey = mountStorageKey(entry);
            Mount current = uniqueMounts.get(storageKey);
            if (current == null || (!current.isPrimary() && entry.isPrimary())) {
                uniqueMounts.put(storageKey, entry);
            }
        }

        for (Mount entry : uniqueMounts.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("alias", entry.getAlias());
            item.put("type", entry.getType());
            Path entryRoot = fileMountRoot(entry);
            MountDo configured = settings().getMountPools().get(entry.getAlias());
            MountSource source = entry.getSource();
            MountCapabilities capabilities = source.capabilities();
            String displayLocation = configured != null && configured.getPath() != null
                    ? configured.getPath() : (entryRoot != null ? entryRoot.toString() : source.getScheme());
            item.put("path", displayLocation); // 兼容旧客户端；新客户端使用 displayLocation
            item.put("displayLocation", displayLocation);
            item.put("scheme", source.getScheme());
            item.put("sourceDisplayName", source.getScheme());
            item.put("enabled", entry.isEnabled());
            item.put("system", entry.isPrimary());
            item.put("writeable", entry.isWriteable() && capabilities.isWritable());
            item.put("realPath", entryRoot != null ? entryRoot.toString() : ""); // 兼容旧客户端
            item.put("description", entry.getDescription());
            item.put("capabilities", capabilitiesToMap(capabilities));
            item.put("actions", mountActions(entry, capabilities));


            MountDo mountDo = settings().getMountPools().get(entry.getAlias());
            if (mountDo == null) {
                item.put("scope", entry.getAlias().startsWith("@workspace-")
                        ? AgentFlags.SCOPE_LOCAL
                        : AgentFlags.SCOPE_USER);
            } else {
                item.put("scope", mountDo.getScope());
            }

            list.add(item);
        }

        sortByName(list, "alias");

        return Result.succeed(list);
    }

    private Map<String, Boolean> mountActions(Mount mount, MountCapabilities capabilities) {
        Map<String, Boolean> actions = new LinkedHashMap<>();
        actions.put("browse", mount.getType() == MountType.SKILLS || mount.getType() == MountType.AGENTS);
        actions.put("openLocal", capabilities.isLocalPathAccessible() && fileMountRoot(mount) != null);
        actions.put("deleteContent", mount.getType() == MountType.SKILLS
                && capabilities.isDeletable() && mount.getSource() instanceof FileMountSource);
        actions.put("removeMount", !mount.isPrimary());
        return actions;
    }

    private Map<String, Boolean> capabilitiesToMap(MountCapabilities capabilities) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        result.put("readable", capabilities.isReadable());
        result.put("writable", capabilities.isWritable());
        result.put("searchable", capabilities.isSearchable());
        result.put("editable", capabilities.isEditable());
        result.put("deletable", capabilities.isDeletable());
        result.put("movable", capabilities.isMovable());
        result.put("watchable", capabilities.isWatchable());
        result.put("shellAccessible", capabilities.isShellAccessible());
        result.put("localPathAccessible", capabilities.isLocalPathAccessible());
        result.put("materializable", capabilities.isMaterializable());
        return result;
    }

    private String mountStorageKey(Mount mount) {
        Path realPath = fileMountRoot(mount);
        if (realPath == null) {
            return mount.getType() + "|alias:" + mount.getAlias();
        }

        String normalizedPath = realPath.toAbsolutePath().normalize().toString();
        if (File.separatorChar == '\\') {
            normalizedPath = normalizedPath.toLowerCase(Locale.ROOT);
        }
        return mount.getType() + "|path:" + normalizedPath;
    }

    /**
     * 添加挂载池
     */
    @Post
    @Mapping("/web/settings/mounts/add")
    public Result mountsAdd(Context ctx, @Param("description") String description, @Param("alias") String alias, @Param("path") String path, @Param("type") MountType type, @Param("writeable") boolean writeable, @Param("scope") String scope) {
        if (Assert.isEmpty(alias) || Assert.isEmpty(path)) return Result.failure("参数不完整");

        if (alias.startsWith("@") == false) {
            alias = "@" + alias;
        }

        if (engine().hasMount(alias)) return Result.failure("别名已存在");


        if (type == null) {
            type = MountType.SKILLS;
        }

        if (Assert.isEmpty(scope) || (!AgentFlags.SCOPE_LOCAL.equals(scope))) {
            scope = AgentFlags.SCOPE_USER;
        }

        MountDo mountDo = new MountDo(
                scope,
                description,
                type,
                path,
                false, true, writeable);
        settings().getMountPools().put(alias, mountDo);
        saveSettings();
        engine().addMount(Mount.builder()
                .alias(alias)
                .description(description)
                .type(type)
                .source(FileMountSource.of(MountPathUtil.resolve(path, engine().getWorkspace())))
                .writeable(writeable)
                .build());

        // 同步注册文件监听
        Mount newMount = engine().getMount(alias);
        if (newMount != null) {
            registerMountWatch(newMount);
        }

        syncMountPoolsToOtherWorkspaces();
        return Result.succeed("添加成功");
    }

    /**
     * 更新挂载池（只允许修改描述和可写属性）
     */
    @Post
    @Mapping("/web/settings/mounts/update")
    public Result mountsUpdate(Context ctx, @Param("alias") String alias, @Param("description") String description, @Param("writeable") boolean writeable) {
        if (Assert.isEmpty(alias)) return Result.failure("参数不完整");

        if (alias.startsWith("@") == false) {
            alias = "@" + alias;
        }

        if (!engine().hasMount(alias)) return Result.failure("挂载池不存在");

        // 更新配置中的数据
        MountDo mountDo = settings().getMountPools().get(alias);
        if (mountDo != null) {
            mountDo.setDescription(description);
            mountDo.setWriteable(writeable);
        }

        // 更新运行时挂载
        Mount current = engine().getMount(alias);
        if (current != null) {
            engine().removeMount(alias);
            engine().addMount(copyMount(current, description, current.isEnabled(), writeable));
        }

        saveSettings();
        syncMountPoolsToOtherWorkspaces();
        return Result.succeed("更新成功");
    }

    /**
     * 切换挂载池启用/停用
     */
    @Post
    @Mapping("/web/settings/mounts/toggle")
    public Result mountsToggle(@Param("alias") String alias, @Param("enabled") Boolean enabled) {
        if (Assert.isEmpty(alias)) {
            return Result.failure("alias is required");
        }

        Mount mountDir = engine().getMount(alias);
        if (mountDir == null) {
            return Result.failure("挂载池不存在: " + alias);
        } else {
            engine().removeMount(alias);
            engine().addMount(copyMount(mountDir, mountDir.getDescription(), Boolean.TRUE.equals(enabled), mountDir.isWriteable()));
        }

        // 更新配置
        MountDo mountDo = settings().getMountPools().get(alias);
        if (mountDo != null) {
            mountDo.setEnabled(enabled);
        }

        saveSettings();
        syncMountPoolsToOtherWorkspaces();

        // 同步文件监听：启用时注册，停用时移除（判空与取值统一走访问器，避免不对称 NPE）
        if (fileWatchService() != null) {
            if (Boolean.TRUE.equals(enabled)) {
                registerMountWatch(engine().getMount(alias));
            } else {
                fileWatchService().removeRoot(alias);
            }
        }

        LOG.info("[Settings] Mount toggled: {} -> {}", alias, enabled);
        return Result.succeed();
    }

    /**
     * 移除挂载池
     */
    @Post
    @Mapping("/web/settings/mounts/remove")
    public Result mountsRemove(@Param("alias") String alias) {
        Mount mountDir = engine().getMount(alias);
        if (mountDir == null) {
            return Result.failure("挂载池不存在");
        }

        if (mountDir.isPrimary()) {
            return Result.failure("系统挂载池不可移除");
        }

        settings().getMountPools().remove(alias);
        saveSettings();
        engine().removeMount(alias);
        syncMountPoolsToOtherWorkspaces();

        // 同步移除文件监听（判空与取值统一走访问器）
        if (fileWatchService() != null) {
            fileWatchService().removeRoot(alias);
        }

        return Result.succeed("移除成功");
    }

    /**
     * 获取某挂载池内的内容列表（根据类型分发）
     */
    @Get
    @Mapping("/web/settings/mounts/content")
    public Result mountsContent(@Param("alias") String alias, @Param("type") String type) {
        Mount mount = engine().getMount(alias);
        if (mount == null) {
            return Result.failure("挂载池不存在: " + alias);
        }

        // 类型以服务端挂载定义为准，客户端 type 仅保留作旧版本兼容参数。
        if (mount.getType() == MountType.AGENTS) {
            return loadAgentsContent(alias);
        } else if (mount.getType() == MountType.FILES) {
            return loadFilesContent(mount);
        } else {
            return loadSkillsContent(alias);
        }
    }

    private Result loadFilesContent(Mount mount) {
        try {
            List<MountEntry> entries = mount.getSource().list("");
            int total = entries == null ? 0 : entries.size();
            boolean truncated = total > FILES_PREVIEW_LIMIT;
            List<Map<String, Object>> items = new ArrayList<>();
            if (entries != null) {
                for (int i = 0; i < Math.min(total, FILES_PREVIEW_LIMIT); i++) {
                    MountEntry entry = entries.get(i);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", entry.getName());
                    item.put("path", entry.getPath());
                    item.put("directory", entry.isDirectory());
                    item.put("size", entry.getSize());
                    item.put("lastModified", entry.getLastModified() == null ? null : entry.getLastModified().toString());
                    items.add(item);
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("items", items);
            result.put("limit", FILES_PREVIEW_LIMIT);
            result.put("total", total);
            result.put("truncated", truncated);
            return Result.succeed(result);
        } catch (IOException | RuntimeException e) {
            LOG.warn("[Settings] Failed to list files for mount {}: {}", mount.getAlias(), e.getMessage());
            return Result.failure("读取挂载内容失败: " + e.getMessage());
        }
    }


    private Result loadSkillsContent(String alias) {
        Collection<SkillDescriptor> skillDirList = engine().getSkillsByMount(alias);
        List<Map<String, Object>> skills = new ArrayList<>();

        for (SkillDescriptor subDir : skillDirList) {
            Map<String, Object> skillItem = new LinkedHashMap<>();
            skillItem.put("name", subDir.getName());
            skillItem.put("description", subDir.getDescription());
            skillItem.put("realPath", ""); // 兼容旧客户端；虚拟来源不暴露本地路径
            skillItem.put("version", subDir.getVersion());
            skillItem.put("aliasPath", subDir.getId());
            skillItem.put("enabled", engine().isSkillDisallowed(subDir.getId()) == false);
            Mount mount = engine().getMount(alias);
            MountCapabilities capabilities = mount == null ? null : mount.getSource().capabilities();
            Map<String, Boolean> actions = new LinkedHashMap<>();
            actions.put("delete", mount != null && mount.getType() == MountType.SKILLS
                    && capabilities.isDeletable() && mount.getSource() instanceof FileMountSource);
            actions.put("openLocal", mount != null && capabilities.isLocalPathAccessible()
                    && fileMountRoot(mount) != null);
            skillItem.put("actions", actions);
            skills.add(skillItem);
        }

        return Result.succeed(skills);
    }

    private Result loadAgentsContent(String alias) {
        Collection<AgentDescriptor> agentList = engine().getAgentsByMount(alias);
        List<Map<String, Object>> agents = new ArrayList<>();

        for (AgentDescriptor agent : agentList) {
            Map<String, Object> agentItem = new LinkedHashMap<>();
            agentItem.put("name", agent.getName());
            agentItem.put("description", agent.getDescription());
            Mount mount = engine().getMount(alias);
            boolean openLocal = mount != null && mount.getSource().capabilities().isLocalPathAccessible()
                    && agent.getFilePath() != null;
            agentItem.put("filePath", openLocal ? agent.getFilePath().toString() : "");
            Map<String, Boolean> actions = new LinkedHashMap<>();
            actions.put("openLocal", openLocal);
            agentItem.put("actions", actions);
            agents.add(agentItem);
        }

        return Result.succeed(agents);
    }


    /**
     * 打开挂载池的真实目录
     */
    @Get
    @Mapping("/web/settings/mounts/open")
    public Result mountsOpen(@Param("alias") String alias) {
        if (Assert.isEmpty(alias)) return Result.failure("挂载别名为空");
        Mount mount = engine().getMount(alias);
        if (mount == null) return Result.failure("挂载池不存在: " + alias);
        Path root = fileMountRoot(mount);
        if (!mount.getSource().capabilities().isLocalPathAccessible() || root == null) {
            return Result.failure("该挂载源不支持打开本地目录");
        }
        try {
            OsOpenUtil.openDirectory(new File(root.toString()));
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
        Mount mountDir = engine().getMount(alias);
        if (mountDir == null) return Result.failure("挂载池不存在: " + alias);


        Path mountRoot = fileMountRoot(mountDir);
        if (mountRoot == null || mountDir.getType() != MountType.SKILLS
                || !mountDir.getSource().capabilities().isDeletable()) {
            return Result.failure("该挂载源不支持删除技能");
        }
        Path skillDir = mountRoot.resolve(skillName);
        if (!Files.exists(skillDir)) return Result.failure("技能包不存在: " + skillName);

        // 安全校验：防止路径穿越
        if (!skillDir.normalize().startsWith(mountRoot.normalize())) {
            return Result.failure("非法路径");
        }

        try {
            deleteRecursively(skillDir);
            engine().refreshMount(alias);
            refreshMountInOtherWorkspaces(alias);
            return Result.succeed("删除成功");
        } catch (Exception e) {
            LOG.warn("[Settings] Failed to delete skill: {}", e.getMessage());
            return Result.failure("删除失败: " + e.getMessage());
        }
    }


    /**
     * 递归删除目录
     */
    private void deleteRecursively(Path path) throws Exception {
        // 跳过符号链接，只删除链接本身不跟随
        if (Files.isSymbolicLink(path)) {
            Files.delete(path);
            return;
        }
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path child : stream) deleteRecursively(child);
            }
        }
        Files.deleteIfExists(path);
    }

    /**
     * 注册挂载点的文件监听（根据类型分配不同的处理器）
     */
    private void registerMountWatch(Mount mount) {
        if (mount == null || fileWatchService() == null || !mount.isEnabled()
                || !mount.getSource().capabilities().isWatchable()) return;
        Path rootPath = fileMountRoot(mount);
        if (rootPath == null) return;

        WorkspaceContext wsContext = currentContext();
        FileWatchService.WatchRoot root = fileWatchService().addRoot(mount.getAlias(), rootPath);

        switch (mount.getType()) {
            case FILES:
                root.addHandler(changes -> webGate().broadcastRaw(wsContext, FileWatchService.buildFrontendJson(changes)));
                break;
            case SKILLS:
                root.addHandler(changes -> engine().getSkillCatalog().refreshByMount(mount.getAlias()));
                break;
            case AGENTS:
                root.addHandler(changes -> engine().getAgentCatalog().refreshByMount(mount.getAlias()));
                break;
        }
    }

    private static Path fileMountRoot(Mount mount) {
        if (mount == null || !(mount.getSource() instanceof FileMountSource)) {
            return null;
        }
        return ((FileMountSource) mount.getSource()).getRootPath();
    }

    private static Mount copyMount(Mount mount, String description, boolean enabled, boolean writeable) {
        return Mount.builder()
                .alias(mount.getAlias())
                .description(description)
                .type(mount.getType())
                .primary(mount.isPrimary())
                .visible(mount.isVisible())
                .enabled(enabled)
                .writeable(writeable)
                .source(mount.getSource())
                .build();
    }
}
