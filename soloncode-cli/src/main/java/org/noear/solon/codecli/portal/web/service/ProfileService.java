/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.portal.web.service;

import org.noear.snack4.ONode;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.config.AgentSettings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 配置备份服务 —— 将 settings.json 分组与 ~/.soloncode 资产目录打包为 zip（导出/导入）。
 *
 * <p>导出包结构（自描述，manifest 为导入端兼容判定锚点）：</p>
 * <pre>
 * manifest.json            # schemaVersion / exportedAt / 各条目 key、scope、来源、条数
 * settings/global.json     # 全局 settings.json 按勾选段过滤后的片段（含脱敏）
 * assets/skills/**         # ~/.soloncode/skills/ 目录快照
 * assets/agents/**
 * assets/commands/**
 * assets/memory/**
 * assets/skins/**
 * </pre>
 *
 * @author noear 2026/9/5
 */
public class ProfileService {
    private static ProfileService instance;

    public static ProfileService getInstance() {
        if (instance == null) {
            instance = new ProfileService();
        }
        return instance;
    }

    private ProfileService() {
    }

    //======================= 常量与条目定义 =======================

    public static final int SCHEMA_VERSION = 1;

    /**
     * 上传备份包大小上限（压缩后），防止超大上传拉爆内存
     */
    public static final long MAX_UPLOAD_BYTES = 64L * 1024 * 1024;

    /**
     * 密钥占位符：导入时保留不覆盖原值
     */
    public static final String MASKED_VALUE = "__MASKED__";

    public static final String KEY_SETTINGS = "settings";
    public static final String KEY_SKILLS = "skills";
    public static final String KEY_AGENTS = "agents";
    public static final String KEY_COMMANDS = "commands";
    public static final String KEY_MEMORY = "memory";
    public static final String KEY_SKINS = "skins";

    /**
     * settings.json 顶层分组（导出/导入均按这些 key 过滤）
     */
    public static final List<String> SETTINGS_GROUPS = Collections.unmodifiableList(Arrays.asList(
            "general", "permission", "loop", "defaultModel",
            "models", "providers", "mountPools", "mcpServers", "apiServers", "lspServers"
    ));

    /**
     * settings 片段中需要脱敏的字段名（导出 includeSecrets=false 时置为占位符）
     */
    private static final Set<String> SECRET_KEYS = new HashSet<>(Arrays.asList(
            "apiKey", "api_key", "webAuthPass", "webAuthUser", "dbPassword", "ldapAdminPassword"
    ));

    private static final long MAX_UNCOMPRESSED_BYTES = 256L * 1024 * 1024; // 256MB（zip 炸弹防护）


    /**
     * 备份条目定义
     */
    public static class Item {
        public final String key;
        public final String nameKey;
        public final String scope;
        public final String sourcePath;
        public final boolean defaultChecked;

        public Item(String key, String nameKey, String scope, String sourcePath, boolean defaultChecked) {
            this.key = key;
            this.nameKey = nameKey;
            this.scope = scope;
            this.sourcePath = sourcePath;
            this.defaultChecked = defaultChecked;
        }
    }

    /**
     * 导出/导入清单条目（含统计信息）
     */
    public Map<String, Object> buildManifest(AgentSettings settings) {
        String home = Paths.get(AgentFlags.getUserHome(), AgentFlags.getHarnessHome()).toString();

        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item(KEY_SETTINGS, "profile.item.settings", "user", home + "/settings.json",
                groupCounts(settings), true));
        items.add(item(KEY_SKILLS, "profile.item.skills", "user",
                Paths.get(AgentFlags.getUserHome(), AgentFlags.getHarnessSkills()).toString(),
                dirStats(Paths.get(AgentFlags.getUserHome(), AgentFlags.getHarnessSkills())), true));
        items.add(item(KEY_AGENTS, "profile.item.agents", "user",
                Paths.get(AgentFlags.getUserHome(), AgentFlags.getHarnessAgents()).toString(),
                dirStats(Paths.get(AgentFlags.getUserHome(), AgentFlags.getHarnessAgents())), true));
        items.add(item(KEY_COMMANDS, "profile.item.commands", "user",
                Paths.get(AgentFlags.getUserHome(), AgentFlags.getHarnessCommands()).toString(),
                dirStats(Paths.get(AgentFlags.getUserHome(), AgentFlags.getHarnessCommands())), true));
        items.add(item(KEY_MEMORY, "profile.item.memory", "user",
                Paths.get(AgentFlags.getUserHome(), AgentFlags.getHarnessMemory()).toString(),
                dirStats(Paths.get(AgentFlags.getUserHome(), AgentFlags.getHarnessMemory())), true));
        items.add(item(KEY_SKINS, "profile.item.skins", "user",
                Paths.get(AgentFlags.getUserHome(), AgentFlags.getHarnessSkins()).toString(),
                dirStats(Paths.get(AgentFlags.getUserHome(), AgentFlags.getHarnessSkins())), false));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", SCHEMA_VERSION);
        data.put("items", items);
        return data;
    }

    private Map<String, Object> groupCounts(AgentSettings settings) {
        Map<String, Object> counts = new LinkedHashMap<>();
        if (settings != null) {
            counts.put("models", settings.getModels() != null ? settings.getModels().size() : 0);
            counts.put("mcpServers", settings.getMcpServers() != null ? settings.getMcpServers().size() : 0);
            counts.put("apiServers", settings.getApiServers() != null ? settings.getApiServers().size() : 0);
            counts.put("lspServers", settings.getLspServers() != null ? settings.getLspServers().size() : 0);
            counts.put("providers", settings.getProviders() != null ? settings.getProviders().size() : 0);
            counts.put("mountPools", settings.getMountPools() != null ? settings.getMountPools().size() : 0);
        }
        return counts;
    }

    private Map<String, Object> item(String key, String nameKey, String scope, String sourcePath, Map<String, Object> count, boolean defaultChecked) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("nameKey", nameKey);
        m.put("scope", scope);
        m.put("sourcePath", sourcePath);
        m.put("count", count);
        m.put("defaultChecked", defaultChecked);
        return m;
    }

    private Map<String, Object> dirStats(Path dir) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            m.put("files", 0);
            m.put("sizeBytes", 0L);
            return m;
        }
        final long[] stats = new long[2];
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!Files.isSymbolicLink(file)) {
                        stats[0]++;
                        stats[1] += attrs.size();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 统计失败时保持 0
        }
        m.put("files", (int) stats[0]);
        m.put("sizeBytes", stats[1]);
        return m;
    }

    //======================= 导出 =======================

    /**
     * 导出配置备份 zip 字节。
     *
     * @param settings       当前配置（导出 settings 分组）
     * @param keys           勾选的条目 key 列表（settings/skills/agents/commands/memory/skins）
     * @param includeSecrets true 时保留密钥明文；false 时密钥字段替换为占位符
     */
    public byte[] exportZip(AgentSettings settings, Collection<String> keys, boolean includeSecrets) throws Exception {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("未选择任何导出内容");
        }

        ONode manifest = new ONode().asObject();
        manifest.set("schemaVersion", SCHEMA_VERSION);
        manifest.set("exportedAt", java.time.OffsetDateTime.now().toString());
        manifest.set("includeSecrets", includeSecrets);
        ONode manifestItems = new ONode().asArray();

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            // settings 片段
            if (keys.contains(KEY_SETTINGS)) {
                ONode frag = ONode.ofBean(settings); // 借助 bean 序列化含 final map 字段（getter）
                frag = pickSettingsGroups(frag, SETTINGS_GROUPS);
                if (!includeSecrets) {
                    frag = maskSecrets(frag);
                }
                zos.putNextEntry(new ZipEntry("settings/global.json"));
                zos.write(frag.toJson().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                ONode mi = new ONode().asObject();
                mi.set("key", KEY_SETTINGS);
                mi.set("scope", "user");
                mi.set("source", "~/.soloncode/settings.json");
                mi.set("masked", !includeSecrets);
                manifestItems.add(mi);
            }

            // 资产目录
            addAssetDir(zos, manifestItems, keys, KEY_SKILLS, "skills", AgentFlags.getHarnessSkills());
            addAssetDir(zos, manifestItems, keys, KEY_AGENTS, "agents", AgentFlags.getHarnessAgents());
            addAssetDir(zos, manifestItems, keys, KEY_COMMANDS, "commands", AgentFlags.getHarnessCommands());
            addAssetDir(zos, manifestItems, keys, KEY_MEMORY, "memory", AgentFlags.getHarnessMemory());
            addAssetDir(zos, manifestItems, keys, KEY_SKINS, "skins", AgentFlags.getHarnessSkins());

            manifest.set("items", manifestItems);
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifest.toJson().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    private void addAssetDir(ZipOutputStream zos, ONode manifestItems, Collection<String> keys,
                             String key, String dirName, String harnessSub) throws IOException {
        if (!keys.contains(key)) {
            return;
        }
        Path dir = Paths.get(AgentFlags.getUserHome(), harnessSub).toAbsolutePath().normalize();
        int fileCount = 0;
        if (Files.isDirectory(dir)) {
            final Path srcNorm = dir;
            java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path rel = srcNorm.relativize(file.normalize());
                    String entryName = rel.toString().replace('\\', '/');
                    if (entryName.isEmpty() || entryName.contains("..")) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        zos.putNextEntry(new ZipEntry("assets/" + dirName + "/" + entryName));
                        Files.copy(file, zos);
                        zos.closeEntry();
                        counter.incrementAndGet();
                    } catch (IOException e) {
                        throw e;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            fileCount = counter.get();
        }

        ONode mi = new ONode().asObject();
        mi.set("key", key);
        mi.set("scope", "user");
        mi.set("source", "~/.soloncode/" + dirName + "/");
        mi.set("count", fileCount);
        manifestItems.add(mi);
    }

    /**
     * 仅保留勾选的 settings 顶层分组
     */
    private ONode pickSettingsGroups(ONode frag, List<String> groups) {
        ONode out = new ONode().asObject();
        for (String g : groups) {
            if (frag.hasKey(g) && frag.get(g).isNull() == false) {
                out.set(g, frag.get(g));
            }
        }
        return out;
    }

    /**
     * 递归将密钥字段替换为占位符（返回脱敏后的新节点，不修改原节点）
     */
    private ONode maskSecrets(ONode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ONode out = new ONode().asObject();
            for (Map.Entry<String, ONode> e : node.getObject().entrySet()) {
                if (SECRET_KEYS.contains(e.getKey()) && e.getValue().isString()) {
                    out.set(e.getKey(), MASKED_VALUE);
                } else {
                    out.set(e.getKey(), maskSecrets(e.getValue()));
                }
            }
            return out;
        } else if (node.isArray()) {
            ONode out = new ONode().asArray();
            for (ONode item : node.getArray()) {
                out.add(maskSecrets(item));
            }
            return out;
        }
        return node;
    }

    //======================= 导入解析 =======================

    /**
     * 解析备份 zip，返回预览清单（不落盘）。
     * <p>解压到临时目录，防 Zip Slip 与 zip 炸弹；调用方负责清理（本方法内部 finally 删除）。</p>
     *
     * @param zipStream 上传的 zip 流
     * @return manifest 摘要与各条目统计（files 数、与现有文件的冲突计数）
     */
    public Map<String, Object> importParse(InputStream zipStream) throws Exception {
        if (zipStream == null) {
            throw new IllegalArgumentException("请上传备份 zip 文件");
        }

        Path tempDir = Files.createTempDirectory("soloncode-profile-unpack-");
        try {
            unzipSafely(zipStream, tempDir);

            Path manifestFile = tempDir.resolve("manifest.json");
            if (!Files.isRegularFile(manifestFile)) {
                throw new IllegalArgumentException("无效备份包：缺少 manifest.json");
            }
            ONode manifest = ONode.ofJson(new String(Files.readAllBytes(manifestFile), StandardCharsets.UTF_8));
            int schemaVersion = requireSchemaVersion(manifest);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("schemaVersion", schemaVersion);
            data.put("exportedAt", manifest.get("exportedAt").getString());
            data.put("includeSecrets", manifest.get("includeSecrets").getBoolean());

            // settings 片段预览：分组 + 各组条目计数 + 同名覆盖计数
            List<Map<String, Object>> settingsGroups = new ArrayList<>();
            Path settingsFile = tempDir.resolve("settings/global.json");
            boolean hasSettings = Files.isRegularFile(settingsFile);
            data.put("hasSettings", hasSettings);
            if (hasSettings) {
                ONode frag = ONode.ofJson(new String(Files.readAllBytes(settingsFile), StandardCharsets.UTF_8));
                for (String g : SETTINGS_GROUPS) {
                    if (frag.hasKey(g) && frag.get(g).isNull() == false) {
                        Map<String, Object> gm = new LinkedHashMap<>();
                        gm.put("group", g);
                        ONode gv = frag.get(g);
                        if (gv.isObject()) {
                            gm.put("count", gv.getObject().size());
                        } else if (gv.isArray()) {
                            gm.put("count", gv.getArray().size());
                        } else {
                            gm.put("count", 1);
                        }
                        settingsGroups.add(gm);
                    }
                }
            }
            data.put("settingsGroups", settingsGroups);

            // 资产目录预览
            List<Map<String, Object>> assets = new ArrayList<>();
            for (String[] def : new String[][]{
                    {KEY_SKILLS, "skills", AgentFlags.getHarnessSkills()},
                    {KEY_AGENTS, "agents", AgentFlags.getHarnessAgents()},
                    {KEY_COMMANDS, "commands", AgentFlags.getHarnessCommands()},
                    {KEY_MEMORY, "memory", AgentFlags.getHarnessMemory()},
                    {KEY_SKINS, "skins", AgentFlags.getHarnessSkins()}}) {
                String key = def[0];
                String dirName = def[1];
                Path srcDir = tempDir.resolve("assets").resolve(dirName);
                if (!Files.isDirectory(srcDir)) {
                    continue;
                }
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("key", key);
                int[] counts = new int[3]; // 0:total, 1:new, 2:overwrite
                Path targetRoot = Paths.get(AgentFlags.getUserHome(), def[2]).toAbsolutePath().normalize();
                countFiles(srcDir, targetRoot, counts);
                am.put("files", counts[0]);
                am.put("newFiles", counts[1]);
                am.put("overwriteFiles", counts[2]);
                assets.add(am);
            }
            data.put("assets", assets);

            return data;
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /**
     * 校验 manifest 的 schemaVersion：缺失/非法返回明确错误而非 NPE；过高则拒绝（需升级程序）
     */
    private int requireSchemaVersion(ONode manifest) {
        ONode v = manifest.get("schemaVersion");
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("无效备份包：manifest 缺少 schemaVersion");
        }
        int schemaVersion = v.getInt();
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("无效备份包：schemaVersion 非法（" + schemaVersion + "）");
        }
        if (schemaVersion > SCHEMA_VERSION) {
            throw new IllegalArgumentException("备份包版本过高（schemaVersion=" + schemaVersion + "），当前程序不支持，请升级后重试");
        }
        return schemaVersion;
    }

    private void countFiles(Path srcDir, Path targetRoot, int[] counts) throws IOException {
        final Path srcNorm = srcDir.toAbsolutePath().normalize();
        Files.walkFileTree(srcDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                counts[0]++;
                Path rel = srcNorm.relativize(file.toAbsolutePath().normalize());
                if (Files.isRegularFile(targetRoot.resolve(rel))) {
                    counts[2]++;
                } else {
                    counts[1]++;
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    //======================= 导入提交 =======================

    /**
     * 提交导入：解压 → 备份现有 settings.json → 合并 settings 分组 → 覆盖/新增资产文件。
     *
     * <p>密钥占位符 {@link #MASKED_VALUE} 保留不覆盖原值（空值则跳过）。</p>
     *
     * @param zipStream 备份 zip 流
     * @param keys      勾选导入的条目 key（settings/skills/agents/commands/memory/skins）
     * @return 导入结果统计
     */
    public Map<String, Object> importCommit(InputStream zipStream, Collection<String> keys) throws Exception {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("未选择任何导入内容");
        }

        Path tempDir = Files.createTempDirectory("soloncode-profile-import-");
        try {
            unzipSafely(zipStream, tempDir);
            Path manifestFile = tempDir.resolve("manifest.json");
            if (!Files.isRegularFile(manifestFile)) {
                throw new IllegalArgumentException("无效备份包：缺少 manifest.json");
            }
            requireSchemaVersion(ONode.ofJson(new String(Files.readAllBytes(manifestFile), StandardCharsets.UTF_8)));

            Map<String, Object> data = new LinkedHashMap<>();
            List<String> applied = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            // 1) settings.json：备份 → 合并片段
            if (keys.contains(KEY_SETTINGS)) {
                Path settingsFile = tempDir.resolve("settings/global.json");
                if (Files.isRegularFile(settingsFile)) {
                    applied.add(importSettingsFile(settingsFile, warnings));
                }
            }

            // 2) 资产目录
            importAssetDir(tempDir, keys, KEY_SKILLS, "skills", AgentFlags.getHarnessSkills(), applied);
            importAssetDir(tempDir, keys, KEY_AGENTS, "agents", AgentFlags.getHarnessAgents(), applied);
            importAssetDir(tempDir, keys, KEY_COMMANDS, "commands", AgentFlags.getHarnessCommands(), applied);
            importAssetDir(tempDir, keys, KEY_MEMORY, "memory", AgentFlags.getHarnessMemory(), applied);
            importAssetDir(tempDir, keys, KEY_SKINS, "skins", AgentFlags.getHarnessSkins(), applied);

            data.put("applied", applied);
            data.put("warnings", warnings);
            return data;
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /**
     * 合并备份中的 settings 片段到 ~/.soloncode/settings.json。
     * <p>策略：按分组整体覆盖（merge-by-group）；密钥占位符不覆盖现有非空值。</p>
     *
     * @return 摘要描述（含备份文件路径）
     */
    private String importSettingsFile(Path fragFile, List<String> warnings) throws Exception {
        Path globalFile = Paths.get(AgentFlags.getUserHome(), AgentFlags.getHarnessHome(), "settings.json").toAbsolutePath().normalize();
        ONode frag = ONode.ofJson(new String(Files.readAllBytes(fragFile), StandardCharsets.UTF_8));

        ONode current;
        if (Files.isRegularFile(globalFile)) {
            // 备份现有文件（含时间戳），导入失败可手动还原
            String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            Path backupFile = globalFile.resolveSibling("settings.json.bak-" + ts);
            Files.copy(globalFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            warnings.add("已备份原配置: " + backupFile.getFileName());

            current = ONode.ofJson(new String(Files.readAllBytes(globalFile), StandardCharsets.UTF_8));
        } else {
            current = new ONode().asObject();
            Files.createDirectories(globalFile.getParent());
        }

        int mergedGroups = 0;
        for (String g : SETTINGS_GROUPS) {
                if (!frag.hasKey(g) || frag.get(g).isNull()) {
                continue;
            }
            current.set(g, frag.get(g));
            mergedGroups++; // 组级覆盖（merge-by-group）
        }

        if (mergedGroups == 0) {
            return "settings: 备份中无可导入分组";
        }

        // 占位符保护：组级合并后，在 current 上恢复被 mask 的字段（保留现有值）
        restoreMaskedOnCurrent(globalFile, current);

        Files.write(globalFile, current.toJson().getBytes(StandardCharsets.UTF_8));
        return "settings.json: 合并 " + mergedGroups + " 个分组";
    }

    /**
     * 将导入片段中值为 MASKED_VALUE 的密钥字段恢复为磁盘现有值（若现有值也缺失则原样保留占位符，由用户后续手填）。
     */
    private void restoreMaskedOnCurrent(Path globalFile, ONode current) {
        if (!Files.isRegularFile(globalFile)) {
            return;
        }
        try {
            ONode disk = ONode.ofJson(new String(Files.readAllBytes(globalFile), StandardCharsets.UTF_8));
            restoreMasked(current, disk);
        } catch (Exception ignored) {
            // 磁盘文件损坏时放弃恢复，导入值原样写入
        }
    }

    private void restoreMasked(ONode target, ONode disk) {
        if (target == null || disk == null || target.isObject() == false) {
            return;
        }
        for (String key : new ArrayList<>(target.getObject().keySet())) {
            ONode tv = target.get(key);
            ONode dv = disk.get(key);
            if (SECRET_KEYS.contains(key)) {
                if (MASKED_VALUE.equals(tv.getString())
                        && dv != null && dv.isNull() == false && Assert_notEmpty(dv.getString())) {
                    target.set(key, dv.getString());
                }
            } else if (tv.isObject() && dv != null && dv.isObject()) {
                restoreMasked(tv, dv);
            } else if (tv.isArray() && dv != null && dv.isArray()) {
                List<ONode> tvs = tv.getArrayUnsafe();
                List<ONode> dvs = dv.getArrayUnsafe();
                for (int i = 0; i < tvs.size() && i < dvs.size(); i++) {
                    restoreMasked(tvs.get(i), dvs.get(i));
                }
            }
        }
    }

    private static boolean Assert_notEmpty(String s) {
        return s != null && s.trim().isEmpty() == false;
    }

    private void importAssetDir(Path tempDir, Collection<String> keys, String key, String dirName,
                                String harnessSub, List<String> applied) throws IOException {
        if (!keys.contains(key)) {
            return;
        }
        Path srcDir = tempDir.resolve("assets").resolve(dirName);
        if (!Files.isDirectory(srcDir)) {
            return;
        }
        Path targetRoot = Paths.get(AgentFlags.getUserHome(), harnessSub).toAbsolutePath().normalize();
        Files.createDirectories(targetRoot);

        final Path srcNorm = srcDir.toAbsolutePath().normalize();
        final Path targetNorm = targetRoot;
        final int[] counts = new int[2]; // 0:new, 1:overwrite
        Files.walkFileTree(srcDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = srcNorm.relativize(file.toAbsolutePath().normalize());
                Path dest = targetNorm.resolve(rel).normalize();
                if (!dest.startsWith(targetNorm)) {
                    return FileVisitResult.CONTINUE; // 双保险
                }
                Files.createDirectories(dest.getParent());
                if (Files.exists(dest)) {
                    counts[1]++;
                } else {
                    counts[0]++;
                }
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
        applied.add(dirName + ": 新增 " + counts[0] + " / 覆盖 " + counts[1] + " 个文件");
    }

    //======================= 工具方法 =======================

    /**
     * 安全解压：防 Zip Slip（拒绝越界路径）与 zip 炸弹（总解压量上限）。
     */
    private void unzipSafely(InputStream zipStream, Path targetDir) throws Exception {
        long totalUncompressed = 0;
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (entryName == null || entryName.contains("..")) {
                    continue;
                }
                Path entryPath = targetDir.resolve(entryName).normalize();
                if (!entryPath.startsWith(targetDir.toAbsolutePath().normalize())) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    // 限制单文件并累计总量
                    long written = 0;
                    try (java.io.OutputStream out = Files.newOutputStream(entryPath)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = zis.read(buf)) != -1) {
                            written += n;
                            totalUncompressed += n;
                            if (totalUncompressed > MAX_UNCOMPRESSED_BYTES) {
                                throw new IllegalArgumentException("备份包解压后过大");
                            }
                            out.write(buf, 0, n);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isSymbolicLink(path)) {
            Files.delete(path);
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
