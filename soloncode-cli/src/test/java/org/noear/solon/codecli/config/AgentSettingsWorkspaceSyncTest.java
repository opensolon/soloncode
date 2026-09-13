package org.noear.solon.codecli.config;

import org.junit.jupiter.api.*;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.codecli.config.entity.ModelDo;
import org.noear.solon.codecli.config.entity.MountDo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多工作区「公用（全局）配置」同步契约测试。
 *
 * <p>Web 设置页把带 scope 的资产（模型 / 供应商 / MCP / OpenApi / LSP / 挂载等）保存进
 * global settings.json 后，其它已加载工作区不会收到通知，只能靠
 * {@link AgentSettings#reloadInPlace()} 重新读盘拉齐。本测试锁定这条底层契约：</p>
 * <ul>
 *   <li>scope=user 的条目能被其它工作区读到（全局生效）；</li>
 *   <li>各工作区自己的 scope=workspace 覆盖不被串改（隔离不破）；</li>
 *   <li>全局 FILES 类挂载不泄入其它工作区（与 loadForWorkspace 的隔离规则一致）。</li>
 * </ul>
 */
public class AgentSettingsWorkspaceSyncTest {

    private Path tempHome;
    private Path workspaceA;
    private Path workspaceB;
    private Path workspaceC;
    private String originalUserHome;
    private String originalUserDir;

    @BeforeEach
    void setUp() throws Exception {
        tempHome = Files.createTempDirectory("soloncode-sync-home-");
        workspaceA = Files.createTempDirectory("soloncode-sync-a-");
        workspaceB = Files.createTempDirectory("soloncode-sync-b-");
        workspaceC = Files.createTempDirectory("soloncode-sync-c-");
        originalUserHome = System.getProperty("user.home");
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.home", tempHome.toString());
        System.setProperty("user.dir", workspaceA.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
        if (originalUserDir != null) {
            System.setProperty("user.dir", originalUserDir);
        }
        deleteRecursively(tempHome);
        deleteRecursively(workspaceA);
        deleteRecursively(workspaceB);
        deleteRecursively(workspaceC);
    }

    private static ModelDo model(String name, String scope) {
        ModelDo m = new ModelDo();
        m.setName(name);
        m.setModel(name + "-model");
        m.setApiUrl("http://localhost/" + name);
        m.setScope(scope);
        return m;
    }

    @Test
    @DisplayName("全局模型保存后，其它工作区 reloadInPlace 即可拉到")
    void globalModelSyncsToOtherWorkspace() {
        AgentSettings b = AgentSettings.loadForWorkspace(workspaceB.toString());
        assertTrue(b.getModels().isEmpty());

        AgentSettings a = AgentSettings.loadForWorkspace(workspaceA.toString());
        a.getModels().put("m-global", model("m-global", AgentFlags.SCOPE_USER));
        a.setDefaultModel("m-global");
        a.saveToFile();

        assertTrue(b.reloadInPlace());
        assertTrue(b.getModels().containsKey("m-global"));
        assertEquals("m-global", b.getDefaultModel());

        // 内容已一致：再次重载不应误报变更
        assertFalse(b.reloadInPlace());
    }

    @Test
    @DisplayName("全局变更不破坏其它工作区自身的 scope=workspace 覆盖")
    void globalChangeKeepsOtherWorkspaceLocalOverride() {
        AgentSettings b = AgentSettings.loadForWorkspace(workspaceB.toString());
        b.getModels().put("m-local", model("m-local", AgentFlags.SCOPE_LOCAL));
        b.saveToFile();

        AgentSettings a = AgentSettings.loadForWorkspace(workspaceA.toString());
        a.getModels().put("m-global", model("m-global", AgentFlags.SCOPE_USER));
        a.saveToFile();

        assertTrue(b.reloadInPlace());
        assertTrue(b.getModels().containsKey("m-local"), "本工作区的 workspace 级覆盖必须保留");
        assertTrue(b.getModels().containsKey("m-global"), "全局条目必须对本工作区可见");

        // 反向隔离：A 视角不应看到 B 的 workspace 级模型
        AgentSettings aReloaded = AgentSettings.loadForWorkspace(workspaceA.toString());
        assertTrue(aReloaded.getModels().containsKey("m-global"));
        assertFalse(aReloaded.getModels().containsKey("m-local"));
    }

    @Test
    @DisplayName("全局 FILES 挂载不泄入其它工作区，SKILLS 挂载保持继承")
    void workspaceViewFiltersGlobalFilesMount() {
        AgentSettings a = AgentSettings.loadForWorkspace(workspaceA.toString());
        a.getMountPools().put("@data",
                new MountDo(AgentFlags.SCOPE_USER, "数据目录", MountType.FILES, "./data", false, true, false));
        a.getMountPools().put("@shared-skills",
                new MountDo(AgentFlags.SCOPE_USER, "共享技能", MountType.SKILLS, "~/skills", false, true, false));
        a.saveToFile();

        // 其它物理工作区：FILES 数据目录被隔离，能力类挂载保留
        AgentSettings c = AgentSettings.loadForWorkspace(workspaceC.toString());
        assertFalse(c.getMountPools().containsKey("@data"));
        assertTrue(c.getMountPools().containsKey("@shared-skills"));

        // 默认工作区视角（workspaceDir 即用户目录）不做隔离
        AgentSettings homeView = AgentSettings.loadForWorkspace(tempHome.toString());
        assertTrue(homeView.getMountPools().containsKey("@data"));
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
    }
}
