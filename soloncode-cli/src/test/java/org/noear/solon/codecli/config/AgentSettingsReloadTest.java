package org.noear.solon.codecli.config;

import org.junit.jupiter.api.*;
import org.noear.solon.codecli.config.entity.ModelDo;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentSettings.reloadInPlace / copyFrom 单元测试。
 */
public class AgentSettingsReloadTest {

    private Path tempHome;
    private Path tempDir;
    private String originalUserHome;
    private String originalUserDir;

    @BeforeEach
    void setUp() throws Exception {
        tempHome = Files.createTempDirectory("soloncode-settings-home-");
        tempDir = Files.createTempDirectory("soloncode-settings-dir-");
        originalUserHome = System.getProperty("user.home");
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.home", tempHome.toString());
        System.setProperty("user.dir", tempDir.toString());
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
        deleteRecursively(tempDir);
    }

    @Test
    @DisplayName("reloadInPlace 从全局 settings.json 更新现有实例")
    void reloadInPlace_updatesExistingInstance() throws Exception {
        Path settingsDir = tempHome.resolve(".soloncode");
        Files.createDirectories(settingsDir);
        Files.write(settingsDir.resolve("settings.json"), (
                "{\n" +
                        "  \"defaultModel\": \"m1\",\n" +
                        "  \"general\": { \"sandboxMode\": true, \"logLevel\": \"DEBUG\" },\n" +
                        "  \"models\": {\n" +
                        "    \"m1\": { \"name\": \"m1\", \"model\": \"gpt-x\", \"apiUrl\": \"http://localhost\", \"enabled\": true }\n" +
                        "  }\n" +
                        "}"
        ).getBytes(StandardCharsets.UTF_8));

        AgentSettings settings = new AgentSettings();
        settings.getGeneral().setSandboxMode(false);
        settings.setDefaultModel("old");

        boolean changed = settings.reloadInPlace();
        assertTrue(changed);
        assertEquals("m1", settings.getDefaultModel());
        assertEquals(Boolean.TRUE, settings.getGeneral().getSandboxMode());
        assertEquals("DEBUG", settings.getGeneral().getLogLevel());
        assertTrue(settings.getModels().containsKey("m1"));
        assertEquals("gpt-x", settings.getModels().get("m1").getModel());

        // 二次 reload 无变化
        assertFalse(settings.reloadInPlace());
    }

    @Test
    @DisplayName("reloadInPlace 保持 final 字段对象引用不变")
    void reloadInPlace_keepsFinalGroupIdentity() throws Exception {
        Path settingsDir = tempHome.resolve(".soloncode");
        Files.createDirectories(settingsDir);
        Files.write(settingsDir.resolve("settings.json"), (
                "{ \"general\": { \"maxTurns\": 30 }, \"permission\": { \"disallowedTools\": [\"bash\"] } }"
        ).getBytes(StandardCharsets.UTF_8));

        AgentSettings settings = new AgentSettings();
        Object generalRef = settings.getGeneral();
        Object permissionRef = settings.getPermission();
        Object loopRef = settings.getLoop();

        assertTrue(settings.reloadInPlace());
        assertSame(generalRef, settings.getGeneral());
        assertSame(permissionRef, settings.getPermission());
        assertSame(loopRef, settings.getLoop());
        assertEquals(Integer.valueOf(30), settings.getGeneral().getMaxTurns());
        assertTrue(settings.getPermission().getDisallowedTools().contains("bash"));
    }

    @Test
    @DisplayName("copyFrom 替换 Map 并保留目标 Map 实例")
    void copyFrom_replacesMapsInPlace() {
        AgentSettings target = new AgentSettings();
        AgentSettings source = new AgentSettings();
        ModelDo model = new ModelDo();
        model.setName("n1");
        model.setModel("m-a");
        source.getModels().put("n1", model);
        source.setDefaultModel("n1");

        Object modelsRef = target.getModels();
        target.copyFrom(source);

        assertSame(modelsRef, target.getModels());
        assertEquals(1, target.getModels().size());
        assertEquals("n1", target.getDefaultModel());
    }

    @Test
    @DisplayName("reloadInPlace 读盘/解析失败时不覆盖内存")
    void reloadInPlace_corruptJson_doesNotMutate() throws Exception {
        Path settingsDir = tempHome.resolve(".soloncode");
        Files.createDirectories(settingsDir);
        Files.write(settingsDir.resolve("settings.json"),
                "{ not-valid-json".getBytes(StandardCharsets.UTF_8));

        AgentSettings settings = new AgentSettings();
        settings.setDefaultModel("keep-me");
        settings.getGeneral().setLogLevel("INFO");
        settings.getGeneral().setSandboxMode(true);
        ModelDo model = new ModelDo();
        model.setName("alive");
        model.setModel("alive-model");
        settings.getModels().put("alive", model);

        IllegalStateException ex = assertThrows(IllegalStateException.class, settings::reloadInPlace);
        assertTrue(ex.getMessage().contains("Failed to load settings from disk"));

        // 内存保持不变
        assertEquals("keep-me", settings.getDefaultModel());
        assertEquals("INFO", settings.getGeneral().getLogLevel());
        assertEquals(Boolean.TRUE, settings.getGeneral().getSandboxMode());
        assertTrue(settings.getModels().containsKey("alive"));
        assertEquals(1, settings.getModels().size());
    }

    @Test
    @DisplayName("copyFrom 能把 general/loop 的 null 字段真正清空")
    void copyFrom_clearsNullableFields() {
        AgentSettings target = new AgentSettings();
        target.getGeneral().setWebAuthUser("user");
        target.getGeneral().setWebAuthPass("pass");
        target.getGeneral().setLogLevel("DEBUG");
        target.getGeneral().setMaxTurns(50);
        target.getGeneral().setActiveSkin("eyecare");
        target.getLoop().setBudgetWarningPercent(80);
        target.getLoop().setValidatorEnabled(false);
        target.setDefaultModel("old");

        AgentSettings source = new AgentSettings();
        // source 全部保持 null / 默认空
        source.setDefaultModel(null);

        target.copyFrom(source);

        assertNull(target.getGeneral().getWebAuthUser());
        assertNull(target.getGeneral().getWebAuthPass());
        assertNull(target.getGeneral().getLogLevel());
        assertNull(target.getGeneral().getMaxTurns());
        assertNull(target.getGeneral().getActiveSkin());
        assertNull(target.getLoop().getBudgetWarningPercent());
        assertNull(target.getLoop().getValidatorEnabled());
        assertNull(target.getDefaultModel());
    }

    @Test
    @DisplayName("reloadInPlace 本地 settings 覆盖全局")
    void reloadInPlace_localOverridesGlobal() throws Exception {
        Path globalDir = tempHome.resolve(".soloncode");
        Path localDir = tempDir.resolve(".soloncode");
        Files.createDirectories(globalDir);
        Files.createDirectories(localDir);

        Files.write(globalDir.resolve("settings.json"), (
                "{\n" +
                        "  \"defaultModel\": \"global-m\",\n" +
                        "  \"general\": { \"logLevel\": \"INFO\", \"maxTurns\": 10 },\n" +
                        "  \"models\": {\n" +
                        "    \"global-m\": { \"name\": \"global-m\", \"model\": \"g\", \"apiUrl\": \"http://g\" },\n" +
                        "    \"shared\": { \"name\": \"shared\", \"model\": \"from-global\", \"apiUrl\": \"http://g\" }\n" +
                        "  }\n" +
                        "}"
        ).getBytes(StandardCharsets.UTF_8));

        Files.write(localDir.resolve("settings.json"), (
                "{\n" +
                        "  \"defaultModel\": \"local-m\",\n" +
                        "  \"general\": { \"logLevel\": \"ERROR\" },\n" +
                        "  \"models\": {\n" +
                        "    \"local-m\": { \"name\": \"local-m\", \"model\": \"l\", \"apiUrl\": \"http://l\" },\n" +
                        "    \"shared\": { \"name\": \"shared\", \"model\": \"from-local\", \"apiUrl\": \"http://l\" }\n" +
                        "  }\n" +
                        "}"
        ).getBytes(StandardCharsets.UTF_8));

        AgentSettings settings = new AgentSettings();
        assertTrue(settings.reloadInPlace());

        assertEquals("local-m", settings.getDefaultModel());
        assertEquals("ERROR", settings.getGeneral().getLogLevel());
        // local 未写 maxTurns 时，bindTo 不会清掉 global 已绑定值（load 路径仍是叠加 bind）
        assertEquals(Integer.valueOf(10), settings.getGeneral().getMaxTurns());
        assertTrue(settings.getModels().containsKey("global-m"));
        assertTrue(settings.getModels().containsKey("local-m"));
        assertEquals("from-local", settings.getModels().get("shared").getModel());
    }

    @Test
    @DisplayName("loadFromFile 失败时仍宽松返回空对象（启动路径）")
    void loadFromFile_corruptJson_returnsEmpty() throws Exception {
        Path settingsDir = tempHome.resolve(".soloncode");
        Files.createDirectories(settingsDir);
        Files.write(settingsDir.resolve("settings.json"),
                "{ broken".getBytes(StandardCharsets.UTF_8));

        AgentSettings loaded = AgentSettings.loadFromFile();
        assertNotNull(loaded);
        assertNull(loaded.getDefaultModel());
        assertTrue(loaded.getModels().isEmpty());
    }
    
    @Test
    @DisplayName("fillRuntimeDefaults 只填充 null，不覆盖已有值")
    void fillRuntimeDefaults_fillsNullOnly() {
        AgentSettings settings = new AgentSettings();
        settings.getGeneral().setSandboxMode(false);
        settings.getGeneral().setMaxTurns(null);
    
        settings.fillRuntimeDefaults();
    
        assertEquals(Boolean.FALSE, settings.getGeneral().getSandboxMode());
        assertNotNull(settings.getGeneral().getMaxTurns());
        assertEquals(Integer.valueOf(20), settings.getGeneral().getMaxTurns());
        assertNotNull(settings.getGeneral().getMemoryEnabled());
        assertNotNull(settings.getLoop().getBudgetWarningPercent());
    }
    
    @Test
    @DisplayName("reloadInPlace 对磁盘缺省字段回落默认，且不因仅缺省字段误报变更")
    void reloadInPlace_fillsDefaults_andStableFingerprint() throws Exception {
        Path settingsDir = tempHome.resolve(".soloncode");
        Files.createDirectories(settingsDir);
        Files.write(settingsDir.resolve("settings.json"), (
                "{ \"defaultModel\": \"m1\", \"models\": { \"m1\": { \"name\": \"m1\", \"model\": \"x\", \"apiUrl\": \"http://x\" } } }"
        ).getBytes(StandardCharsets.UTF_8));
    
        AgentSettings settings = new AgentSettings();
        assertTrue(settings.reloadInPlace());
        assertEquals("m1", settings.getDefaultModel());
        // 磁盘未写 maxTurns → 回落默认
        assertEquals(Integer.valueOf(20), settings.getGeneral().getMaxTurns());
        assertNotNull(settings.getGeneral().getSandboxMode());
    
        // 内存已是“磁盘 + 默认”，再次 reload 应无变化
        assertFalse(settings.reloadInPlace());
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
