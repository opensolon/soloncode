package org.noear.solon.codecli.portal.web.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.codecli.config.AgentSettings;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProfileService 单测：勾选过滤 / 密钥脱敏 / ZipSlip 防护 / manifest 结构 / 导入合并。
 *
 * @author noear 2026/9/5
 */
class ProfileServiceTest {
    @TempDir
    Path tempDir;

    private String oldUserHome;

    @BeforeEach
    void setUp() {
        oldUserHome = System.getProperty("user.home");
        // 重定向 user.home 到临时目录，隔离真实 ~/.soloncode
        System.setProperty("user.home", tempDir.resolve("home").toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", oldUserHome);
    }

    private Path home() {
        return Paths.get(System.getProperty("user.home"), ".soloncode");
    }

    private AgentSettings buildSettings() {
        AgentSettings s = new AgentSettings();
        org.noear.solon.codecli.config.entity.ModelDo m1 = new org.noear.solon.codecli.config.entity.ModelDo();
        m1.setApiKey("sk-secret-123");
        m1.setModel("gpt-x");
        s.getModels().put("m1", m1);
        s.setDefaultModel("gpt-x");
        return s;
    }

    @Test
    void testExportOnlySelectedKeys() throws Exception {
        // 准备 skills 与 memory 目录
        Path skills = home().resolve("skills/demo-skill");
        Files.createDirectories(skills);
        Files.write(skills.resolve("SKILL.md"), "hello".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(home().resolve("memory"));
        Files.write(home().resolve("memory/notes.md"), "memory".getBytes(StandardCharsets.UTF_8));

        ProfileService svc = ProfileService.getInstance();
        byte[] zip = svc.exportZip(buildSettings(), Arrays.asList(ProfileService.KEY_SKILLS), false);

        Set<String> entries = zipEntries(zip);
        assertTrue(entries.contains("manifest.json"));
        assertTrue(entries.contains("assets/skills/demo-skill/SKILL.md"));
        // 未勾选 memory：不应包含
        assertFalse(entries.stream().anyMatch(e -> e.startsWith("assets/memory")));
        // 未勾选 settings：不应包含
        assertFalse(entries.contains("settings/global.json"));
    }

    @Test
    void testExportMasksSecretsByDefault() throws Exception {
        ProfileService svc = ProfileService.getInstance();
        byte[] zip = svc.exportZip(buildSettings(), Arrays.asList(ProfileService.KEY_SETTINGS), false);

        String settingsJson = readZipEntry(zip, "settings/global.json");
        String manifestJson = readZipEntry(zip, "manifest.json");

        assertFalse(settingsJson.contains("sk-secret-123"), "默认导出不应含密钥明文");
        assertTrue(settingsJson.contains(ProfileService.MASKED_VALUE), "密钥应被替换为占位符");
        assertTrue(manifestJson.contains("\"masked\":true"));
    }

    @Test
    void testExportIncludesSecretsWhenRequested() throws Exception {
        ProfileService svc = ProfileService.getInstance();
        byte[] zip = svc.exportZip(buildSettings(), Arrays.asList(ProfileService.KEY_SETTINGS), true);
        String settingsJson = readZipEntry(zip, "settings/global.json");
        assertTrue(settingsJson.contains("sk-secret-123"), "显式选择含密钥时应保留明文");
    }

    @Test
    void testManifestStructure() throws Exception {
        ProfileService svc = ProfileService.getInstance();
        byte[] zip = svc.exportZip(buildSettings(),
                Arrays.asList(ProfileService.KEY_SETTINGS, ProfileService.KEY_MEMORY), false);

        ONode manifest = ONode.ofJson(readZipEntry(zip, "manifest.json"));
        assertEquals(ProfileService.SCHEMA_VERSION, manifest.get("schemaVersion").getInt());

        Set<String> keys = new HashSet<>();
        for (ONode item : manifest.get("items").getArray()) {
            keys.add(item.get("key").getString());
        }
        assertEquals(new HashSet<>(Arrays.asList("settings", "memory")), keys);
    }

    @Test
    void testExportRejectsEmptySelection() {
        assertThrows(IllegalArgumentException.class, () ->
                ProfileService.getInstance().exportZip(buildSettings(), Collections.emptyList(), false));
    }

    @Test
    void testImportParseRejectsNonBackupZip() throws Exception {
        // 无 manifest.json 的 zip 应被拒绝
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("random.txt"));
            zos.write("junk".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        assertThrows(IllegalArgumentException.class, () ->
                ProfileService.getInstance().importParse(new ByteArrayInputStream(bos.toByteArray())));
    }

    @Test
    void testImportRejectsManifestWithoutSchemaVersion() throws Exception {
        // manifest 缺 schemaVersion：应明确拒绝而非 NPE（500）
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write("{\"items\":[]}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                ProfileService.getInstance().importParse(new ByteArrayInputStream(bos.toByteArray())));
        assertTrue(ex.getMessage().contains("schemaVersion"));
    }

    @Test
    void testImportCommitAlsoValidatesSchemaVersion() throws Exception {
        // commit 是独立请求（可绕过 parse 直接打后端），也必须校验
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write("{}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        assertThrows(IllegalArgumentException.class, () ->
                ProfileService.getInstance().importCommit(new ByteArrayInputStream(bos.toByteArray()),
                        Arrays.asList(ProfileService.KEY_SETTINGS)));
    }

    @Test
    void testImportRejectsHigherSchemaVersion() throws Exception {
        // 高版本备份包应被拒绝（防止未来结构不兼容静默错乱）
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(("{\"schemaVersion\":" + (ProfileService.SCHEMA_VERSION + 1) + "}")
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                ProfileService.getInstance().importParse(new ByteArrayInputStream(bos.toByteArray())));
        assertTrue(ex.getMessage().contains("过高") || ex.getMessage().contains("upgrade"));
    }

    @Test
    void testImportCommitMergesSettingsAndProtectsMaskedSecrets() throws Exception {
        // 磁盘上已有配置（含真实密钥）
        Files.createDirectories(home());
        Files.write(home().resolve("settings.json"),
                ("{\"defaultModel\":\"gpt-x\",\"models\":{\"m1\":{\"apiKey\":\"real-key-999\",\"model\":\"gpt-x\"}}}")
                        .getBytes(StandardCharsets.UTF_8));

        // 导出一份脱敏备份
        byte[] zip = ProfileService.getInstance().exportZip(buildSettings(),
                Arrays.asList(ProfileService.KEY_SETTINGS), false);

        // 导入（脱敏备份不应覆盖真实密钥）
        Map<String, Object> result = ProfileService.getInstance().importCommit(
                new ByteArrayInputStream(zip), Arrays.asList(ProfileService.KEY_SETTINGS));

        assertNotNull(result.get("applied"));
        String disk = new String(Files.readAllBytes(home().resolve("settings.json")), StandardCharsets.UTF_8);
        assertTrue(disk.contains("real-key-999"), "占位符不应覆盖磁盘现有真实密钥");
        assertFalse(disk.contains(ProfileService.MASKED_VALUE), "磁盘不应残留占位符");
    }

    @Test
    void testImportCommitCopiesAssets() throws Exception {
        Path skills = home().resolve("skills/demo-skill");
        Files.createDirectories(skills);
        Files.write(skills.resolve("SKILL.md"), "hello".getBytes(StandardCharsets.UTF_8));

        byte[] zip = ProfileService.getInstance().exportZip(buildSettings(),
                Arrays.asList(ProfileService.KEY_SKILLS), false);

        // 删除后导入应还原
        Files.delete(skills.resolve("SKILL.md"));
        ProfileService.getInstance().importCommit(new ByteArrayInputStream(zip),
                Arrays.asList(ProfileService.KEY_SKILLS));

        assertEquals("hello", new String(Files.readAllBytes(skills.resolve("SKILL.md")), StandardCharsets.UTF_8));
    }

    @Test
    void testImportRejectsZipSlip() throws Exception {
        // 构造含 ../ 越界条目的恶意 zip：应被跳过且不写入目标外部
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write("{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("assets/../../evil.txt"));
            zos.write("pwned".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        // 不应抛异常（条目被跳过），且 evil.txt 不应出现在临时目录之外
        assertDoesNotThrow(() ->
                ProfileService.getInstance().importParse(new ByteArrayInputStream(bos.toByteArray())));
        assertFalse(Files.exists(tempDir.resolve("evil.txt")));
    }

    // ---------- helpers ----------

    private Set<String> zipEntries(byte[] zip) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                names.add(e.getName());
            }
        }
        return names;
    }

    private String readZipEntry(byte[] zip, String target) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (target.equals(e.getName())) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[1024];
                    int n;
                    while ((n = zis.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                    }
                    return bos.toString("UTF-8");
                }
            }
        }
        fail("zip 中未找到条目: " + target);
        return null;
    }
}
