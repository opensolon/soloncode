package org.noear.solon.codecli.portal.web.settings;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.talents.mount.AgentMd;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.ai.talents.mount.SkillDir;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Post;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentSettings;
import org.noear.solon.codecli.config.entity.MountDo;
import org.noear.solon.codecli.portal.FileWatchService;
import org.noear.solon.codecli.portal.web.WebGate;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
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
    /**
     * 日志记录器
     */
    private static final Logger LOG = LoggerFactory.getLogger(MountSettingsController.class);

    /**
     * 构造函数：支持自定义所有依赖。
     */
    public MountSettingsController(HarnessEngine engine, AgentSettings settings, FileWatchService fileWatchService, WebGate webGate) {
        super(engine, settings, fileWatchService, webGate);
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
                item.put("scope", AgentFlags.SCOPE_USER);
            } else {
                item.put("scope", mountDo.getScope());
            }

            list.add(item);
        }

        sortByName(list, "alias");

        return Result.succeed(list);
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

        if (engine.hasMount(alias)) return Result.failure("别名已存在");


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
        settings.getMountPools().put(alias, mountDo);
        saveSettings();
        engine.addMount(MountDir.builder()
                .alias(alias)
                .type(type)
                .path(path)
                .writeable(writeable)
                .build());

        // 同步注册文件监听
        MountDir newMount = engine.getMount(alias);
        if (newMount != null) {
            registerMountWatch(newMount);
        }

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
    public Result mountsToggle(@Param("alias") String alias, @Param("enabled") Boolean enabled) {
        if (Assert.isEmpty(alias)) {
            return Result.failure("alias is required");
        }

        MountDir mountDir = engine.getMount(alias);
        if (mountDir == null) {
            return Result.failure("挂载池不存在: " + alias);
        } else {
            mountDir.setEnabled(enabled);
        }

        // 更新配置
        MountDo mountDo = settings.getMountPools().get(alias);
        if (mountDo != null) {
            mountDo.setEnabled(enabled);
        }

        saveSettings();

        // 同步文件监听：启用时注册，停用时移除
        if (fileWatchService != null) {
            if (Boolean.TRUE.equals(enabled)) {
                registerMountWatch(mountDir);
            } else {
                fileWatchService.removeRoot(alias);
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

        // 同步移除文件监听
        if (fileWatchService != null) {
            fileWatchService.removeRoot(alias);
        }

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
    private void registerMountWatch(MountDir mount) {
        if (fileWatchService == null || !mount.isEnabled()) return;

        FileWatchService.WatchRoot root = fileWatchService.addRoot(mount.getAlias(), mount.getRealPath());

        switch (mount.getType()) {
            case FILES:
                root.addHandler(changes -> webGate.broadcastRaw(FileWatchService.buildFrontendJson(changes)));
                break;
            case SKILLS:
                root.addHandler(changes -> engine.getSkillProvider().refreshByGroup(mount.getAlias()));
                break;
            case AGENTS:
                root.addHandler(changes -> engine.getAgentManager().refreshByMountAlias(mount.getAlias()));
                break;
        }
    }
}