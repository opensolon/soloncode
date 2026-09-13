package org.noear.solon.codecli.portal.web.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.entity.MountDo;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BaseSettingsController#workspaceVisibleMounts} 单元测试。
 *
 * <p>挂载池保存后会广播到其它已加载工作区；广播前必须先取「工作区可见视图」，
 * 规则与 {@code AgentSettings.loadForWorkspace} 的隔离策略保持一致：
 * 非默认工作区不继承全局 scope=user 的 FILES 数据目录挂载，避免文件树泄漏他人项目目录；
 * SKILLS / AGENTS 属于能力注入，保留继承；工作区自己的 scope=workspace 挂载始终可见。</p>
 */
public class MountVisibilityTest {

    private static Map<String, MountDo> sampleMounts() {
        Map<String, MountDo> mounts = new LinkedHashMap<>();
        mounts.put("@global-data",
                new MountDo(AgentFlags.SCOPE_USER, "全局数据目录", MountType.FILES, "./data", false, true, false));
        mounts.put("@local-data",
                new MountDo(AgentFlags.SCOPE_LOCAL, "本工作区数据", MountType.FILES, "./local", false, true, false));
        mounts.put("@shared-skills",
                new MountDo(AgentFlags.SCOPE_USER, "共享技能", MountType.SKILLS, "~/skills", false, true, false));
        return mounts;
    }

    @Test
    @DisplayName("非默认工作区：全局 FILES 被过滤，本地 FILES 与 SKILLS 保留")
    void otherWorkspaceFiltersGlobalFiles() {
        Map<String, MountDo> visible = BaseSettingsController.workspaceVisibleMounts(false, sampleMounts());

        assertFalse(visible.containsKey("@global-data"), "全局 FILES 数据目录不应泄入其它工作区");
        assertTrue(visible.containsKey("@local-data"), "工作区自身的 FILES 挂载必须可见");
        assertTrue(visible.containsKey("@shared-skills"), "能力类挂载必须继承");
    }

    @Test
    @DisplayName("默认工作区：不做隔离，全部可见")
    void defaultWorkspaceKeepsAll() {
        Map<String, MountDo> visible = BaseSettingsController.workspaceVisibleMounts(true, sampleMounts());

        assertEquals(3, visible.size());
        assertTrue(visible.containsKey("@global-data"));
    }

    @Test
    @DisplayName("空集合直接返回，避免无谓拷贝")
    void emptyMapReturnsAsIs() {
        Map<String, MountDo> empty = new LinkedHashMap<>();
        assertSame(empty, BaseSettingsController.workspaceVisibleMounts(false, empty));
    }
}
