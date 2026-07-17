package org.noear.solon.codecli.portal.web.service;

import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkinService 单元测试：zip 安装、列表、卸载、路径安全、CSS url 改写。
 */
public class SkinServiceTest {

    private SkinService service;
    private Path tempHome;
    private String originalUserHome;

    @BeforeEach
    void setUp() throws Exception {
        service = new SkinService();
        tempHome = Files.createTempDirectory("soloncode-skin-home-");
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
        if (tempHome != null && Files.exists(tempHome)) {
            Files.walk(tempHome)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    @Test
    @DisplayName("安装扁平 zip 皮肤并出现在列表中")
    void installFlatZip() throws Exception {
        byte[] zip = buildZip(false, "aurora",
                "{\n  \"name\": \"aurora\",\n  \"displayName\": \"极光\",\n  \"version\": \"1.0.0\"\n}",
                "[data-skin=\"aurora\"][data-theme=\"light\"] {\n  --accent: #7c5cff;\n  --bg-main-image: url(\"./assets/bg.webp\");\n}\n");

        String name = service.installZip(new ByteArrayInputStream(zip), "aurora.zip");
        assertEquals("aurora", name);
        assertTrue(service.isInstalled("aurora"));

        List<Map<String, Object>> list = service.listInstalled();
        assertEquals(1, list.size());
        assertEquals("aurora", list.get(0).get("name"));
        assertEquals("极光", list.get(0).get("displayName"));
        assertEquals("local", list.get(0).get("source"));
    }

    @Test
    @DisplayName("安装单层目录包装 zip")
    void installWrappedZip() throws Exception {
        byte[] zip = buildZip(true, "mist",
                "{\n  \"name\": \"mist\",\n  \"displayName\": \"薄雾\"\n}",
                "[data-skin=\"mist\"] { --accent: #88a; }\n");

        String name = service.installZip(new ByteArrayInputStream(zip), "mist.zip");
        assertEquals("mist", name);
        assertTrue(Files.isRegularFile(service.skinsRoot().resolve("mist").resolve("skin.css")));
    }

    @Test
    @DisplayName("不能覆盖预置皮肤名")
    void rejectBuiltinName() {
        byte[] zip = buildZip(false, "eyecare",
                "{\n  \"name\": \"eyecare\"\n}",
                "[data-skin=\"eyecare\"] { --accent: #000; }\n");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.installZip(new ByteArrayInputStream(zip), "eyecare.zip"));
        assertTrue(ex.getMessage().contains("预置"));
    }

    @Test
    @DisplayName("CSS 相对 url 改写为代理地址")
    void rewriteCssUrls() throws Exception {
        byte[] zip = buildZip(false, "lake",
                "{\n  \"name\": \"lake\"\n}",
                "[data-skin=\"lake\"] {\n  --bg-main-image: url(\"./assets/a.webp\");\n  --x: url(https://example.com/x.png);\n}\n");
        service.installZip(new ByteArrayInputStream(zip), "lake.zip");

        // 放入 assets 以便 resolve 校验（改写本身不要求文件存在）
        Path assets = service.skinsRoot().resolve("lake").resolve("assets");
        Files.createDirectories(assets);
        Files.write(assets.resolve("a.webp"), new byte[]{1, 2, 3});

        String css = service.loadCssWithRewrittenUrls("lake");
        assertNotNull(css);
        assertTrue(css.contains("/web/settings/skins/file?name=lake&file=assets/a.webp"));
        assertTrue(css.contains("https://example.com/x.png"));
    }

    @Test
    @DisplayName("路径穿越文件读取应被拒绝")
    void rejectPathTraversal() throws Exception {
        byte[] zip = buildZip(false, "safe",
                "{\n  \"name\": \"safe\"\n}",
                "[data-skin=\"safe\"] { --accent: #111; }\n");
        service.installZip(new ByteArrayInputStream(zip), "safe.zip");

        assertNull(service.resolveSkinFile("safe", "../settings.json"));
        assertNull(service.resolveSkinFile("safe", "assets/../../x.png"));
        assertNull(service.resolveSkinFile("safe", "evil.js"));
    }

    @Test
    @DisplayName("卸载本地皮肤")
    void uninstallLocal() throws Exception {
        byte[] zip = buildZip(false, "tmpx",
                "{\n  \"name\": \"tmpx\"\n}",
                "[data-skin=\"tmpx\"] { --accent: #222; }\n");
        service.installZip(new ByteArrayInputStream(zip), "tmpx.zip");
        assertTrue(service.isInstalled("tmpx"));
        service.uninstall("tmpx");
        assertFalse(service.isInstalled("tmpx"));
    }

    @Test
    @DisplayName("预置皮肤不可卸载")
    void cannotUninstallBuiltin() {
        assertThrows(IllegalArgumentException.class, () -> service.uninstall("eyecare"));
    }
    
    @Test
    @DisplayName("导出本地皮肤 zip 并可再次安装")
    void exportLocalZip() throws Exception {
        byte[] zip = buildZip(false, "shareme",
                "{\n  \"name\": \"shareme\",\n  \"displayName\": \"分享\"\n}",
                "[data-skin=\"shareme\"] { --accent: #333; }\n");
        service.installZip(new ByteArrayInputStream(zip), "shareme.zip");
    
        // 补一个 assets 资源，确认导出包含子目录文件
        Path assets = service.skinsRoot().resolve("shareme").resolve("assets");
        Files.createDirectories(assets);
        Files.write(assets.resolve("bg.webp"), new byte[]{9, 8, 7});
    
        byte[] exported = service.exportZip("shareme");
        assertNotNull(exported);
        assertTrue(exported.length > 20);
    
        // 卸载后用导出包重装
        service.uninstall("shareme");
        assertFalse(service.isInstalled("shareme"));
    
        String name = service.installZip(new ByteArrayInputStream(exported), "shareme.zip");
        assertEquals("shareme", name);
        assertTrue(service.isInstalled("shareme"));
        assertTrue(Files.isRegularFile(service.skinsRoot().resolve("shareme").resolve("assets").resolve("bg.webp")));
    }
    
    @Test
    @DisplayName("预置皮肤不可导出")
    void cannotExportBuiltin() {
        assertThrows(IllegalArgumentException.class, () -> service.exportZip("default"));
    }

    @Test
    @DisplayName("从本地 zip 文件路径安装（一键安装链路）")
    void installFromZipFilePath() throws Exception {
        byte[] zip = buildZip(false, "quick",
                "{\n  \"name\": \"quick\",\n  \"displayName\": \"快装\"\n}",
                "[data-skin=\"quick\"] { --accent: #444; }\n");
        Path zipPath = tempHome.resolve("quick.zip");
        Files.write(zipPath, zip);

        String name = service.installZipFile(zipPath);
        assertEquals("quick", name);
        assertTrue(service.isInstalled("quick"));
        assertTrue(Files.isRegularFile(service.skinsRoot().resolve("quick").resolve("skin.css")));
    }

    @Test
    @DisplayName("installZipFile 对不存在路径应失败")
    void installZipFileMissing() {
        Path missing = tempHome.resolve("no-such.zip");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.installZipFile(missing));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    private static byte[] buildZip(boolean wrapInDir, String dirName, String skinJson, String skinCss) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ZipOutputStream zos = new ZipOutputStream(bos);
            String prefix = wrapInDir ? (dirName + "/") : "";
            put(zos, prefix + "skin.json", skinJson.getBytes(StandardCharsets.UTF_8));
            put(zos, prefix + "skin.css", skinCss.getBytes(StandardCharsets.UTF_8));
            zos.close();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void put(ZipOutputStream zos, String name, byte[] data) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }
}
