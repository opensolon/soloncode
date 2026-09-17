package org.noear.solon.codecli.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工作区存储生命周期阶段一（识别与观测）的行为校验：
 * v1 原地采用、v2 新命名、路径别名兼容、_meta.json 合并协议。
 *
 * <p>全部用例把 user.home 指向临时目录，避免污染真实主目录。</p>
 *
 * @author noear
 */
public class WorkspaceStorageLifecycleTest {
    @TempDir
    Path tempDir;

    /**
     * 在临时 user.home 下执行动作
     */
    private void withTempHome(HomeAction action) throws Exception {
        String oldHome = System.getProperty("user.home");
        Path home = Files.createTempDirectory("sc-home-");
        System.setProperty("user.home", home.toString());
        try {
            action.run(home);
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    private interface HomeAction {
        void run(Path home) throws Exception;
    }

    /**
     * 验收 1（新路径）：全新路径生成 v2 目录名 ws-md5-名字，目录不存在时不预先创建
     */
    @Test
    public void case1_newPath_usesV2LayoutName() throws Exception {
        withTempHome(home -> {
            Path ws = Files.createTempDirectory("sc-ws-");
            String key = WorkspaceDataUtil.workspaceKey(ws.toString());

            assertTrue(key.startsWith("ws-"), "新路径应生成 v2 目录名（ws- 前缀）");
            assertTrue(key.endsWith("-" + ws.getFileName().toString()), "目录名应带可读名后缀");
            assertEquals(2, WorkspaceDataUtil.layoutVersion(ws.toString()), "新路径布局版本应为 2");
            // 不预创建目录：只有真正使用（markWorkspace/meta 写入）时才落盘
            assertFalse(WorkspaceDataUtil.dataDirByKey(key).exists(), "workspaceKey 计算不应有创建目录副作用");
        });
    }

    /**
     * 验收 2（存量采用）：已存在的 v1 目录（md5-名字）原地采用，不改名、不新建 v2
     */
    @Test
    public void case2_existingV1Dir_isAdoptedInPlace() throws Exception {
        withTempHome(home -> {
            Path ws = Files.createTempDirectory("sc-ws-");
            String v1Name = legacyV1Key(ws);

            // 模拟旧版本创建的 v1 目录（含会话数据）
            Path v1Dir = home.resolve(".soloncode/workspaces").resolve(v1Name);
            Files.createDirectories(v1Dir.resolve("sessions/s1"));

            String key = WorkspaceDataUtil.workspaceKey(ws.toString());
            assertEquals(v1Name, key, "已存在 v1 目录必须原地采用，不得生成 ws- 新目录");
            assertEquals(1, WorkspaceDataUtil.layoutVersion(ws.toString()), "布局版本应为 1");
            assertTrue(Files.exists(v1Dir.resolve("sessions/s1")), "v1 目录内容保持不动");
        });
    }

    /**
     * 验收 3（别名路径兼容）：历史版本按原始路径（未解析符号链接）计算的 v1 目录，
     * 归一化后路径（如 /tmp → /private/tmp）打开时仍能命中原目录，会话不"失踪"
     */
    @Test
    public void case3_aliasPath_stillHitsLegacyV1Dir() throws Exception {
        withTempHome(home -> {
            // real: <tmp>/real-ws；alias: <tmp>/alias-ws -> real-ws（符号链接）
            Path real = Files.createTempDirectory("sc-real-");
            Path alias = real.getParent().resolve("sc-alias-" + System.nanoTime());
            try {
                Files.createSymbolicLink(alias, real);
            } catch (Throwable e) {
                // 无符号链接权限的环境跳过本用例
                return;
            }

            // 旧版本按"用户输入的别名路径"计算的 v1 目录（不含 toRealPath）
            String legacyKey = plainKey(alias.toString());
            Path v1Dir = home.resolve(".soloncode/workspaces").resolve(legacyKey);
            Files.createDirectories(v1Dir.resolve("sessions/s1"));

            // 新版本按别名路径打开（内部会 toRealPath 到 real）
            String key = WorkspaceDataUtil.workspaceKey(alias.toString());
            assertEquals(legacyKey, key, "别名路径必须命中旧目录，而不是另建 v2 目录");
        });
    }

    /**
     * 验收 4（幂等）：同一路径重复计算 key 稳定；连续两次打开不产生第二个数据目录
     */
    @Test
    public void case4_keyIsStable_acrossCalls() throws Exception {
        withTempHome(home -> {
            Path ws = Files.createTempDirectory("sc-ws-");
            String k1 = WorkspaceDataUtil.workspaceKey(ws.toString());
            String k2 = WorkspaceDataUtil.workspaceKey(ws.toString());
            assertEquals(k1, k2, "同一路径 key 必须稳定");

            // 模拟打开（写元数据）后再计算，仍指向同一目录
            WorkspaceDataUtil.markWorkspace(ws.toString());
            assertEquals(k1, WorkspaceDataUtil.workspaceKey(ws.toString()), "打开后 key 不变");
        });
    }

    /**
     * 验收 5（_meta.json 合并协议）：补写不覆盖已有 workspaceId/createdAt；
     * 未知字段保留；retention 只升不降；storageKey=basename 校验
     */
    @Test
    public void case5_metaMergeProtocol() throws Exception {
        withTempHome(home -> {
            Path ws = Files.createTempDirectory("sc-ws-");
            Path dataDir = WorkspaceDataUtil.dataDir(ws.toString()).toPath();
            Files.createDirectories(dataDir);

            // 预置旧元数据：含未知字段（模拟未来版本写入）
            String json = "{\n" +
                    "  \"schemaVersion\": 1,\n" +
                    "  \"workspaceId\": \"old-random-id-42\",\n" +
                    "  \"storageKey\": \"" + dataDir.getFileName() + "\",\n" +
                    "  \"path\": \"" + ws + "\",\n" +
                    "  \"retention\": \"EPHEMERAL\",\n" +
                    "  \"createdAt\": 100,\n" +
                    "  \"futureField\": \"keep-me\"\n" +
                    "}";
            Files.write(dataDir.resolve(WorkspaceDataMeta.FILE_NAME), json.getBytes(StandardCharsets.UTF_8));

            // 正常打开：写 PERSISTENT
            WorkspaceDataMeta meta = WorkspaceDataMeta.load(dataDir);
            meta.setRetention(WorkspaceDataMeta.Retention.PERSISTENT);
            meta.setWorkspaceId("ws-should-not-override");
            meta.save(dataDir);

            WorkspaceDataMeta reloaded = WorkspaceDataMeta.load(dataDir);
            assertEquals("old-random-id-42", reloaded.getWorkspaceId(), "已有 workspaceId 不得被覆盖（I2：ID 不重算）");
            assertEquals(100L, reloaded.getCreatedAt(), "已有 createdAt 不得被覆盖");
            assertEquals(WorkspaceDataMeta.Retention.PERSISTENT, reloaded.getRetention(), "EPHEMERAL 提升为 PERSISTENT 生效");

            String after = new String(Files.readAllBytes(dataDir.resolve(WorkspaceDataMeta.FILE_NAME)), StandardCharsets.UTF_8);
            String compact = after.replaceAll("\\s", "");
            assertTrue(compact.contains("keep-me"), "未知字段必须原样保留（前向兼容）");
            assertTrue(compact.contains("\"retention\":\"PERSISTENT\""), "retention 应写为 PERSISTENT");
            assertFalse(compact.contains("\"retention\":\"EPHEMERAL\""), "EPHEMERAL 不得残留");
        });
    }

    /**
     * 验收 6（storageKey 异常防护）：目录名与 storageKey 不一致时 retention 落 UNKNOWN（保护态）
     */
    @Test
    public void case6_storageKeyMismatch_fallsToUnknown() throws Exception {
        withTempHome(home -> {
            Path dataDir = home.resolve(".soloncode/workspaces/fake-abc-name");
            Files.createDirectories(dataDir);
            String json = "{\"schemaVersion\":1,\"storageKey\":\"not-the-basename\",\"retention\":\"PERSISTENT\",\"path\":\"/tmp/x\"}";
            Files.write(dataDir.resolve(WorkspaceDataMeta.FILE_NAME), json.getBytes(StandardCharsets.UTF_8));

            WorkspaceDataMeta meta = WorkspaceDataMeta.load(dataDir);
            assertEquals(WorkspaceDataMeta.Retention.UNKNOWN, meta.getRetention(), "storageKey 不一致应落保护态");
            assertFalse(meta.isTrusted(), "身份不可信");
        });
    }

    /**
     * 验收 7（损坏防护）：_meta.json 损坏、非对象、缺失都落 UNKNOWN，不抛异常
     */
    @Test
    public void case7_brokenMeta_fallsToUnknown() throws Exception {
        withTempHome(home -> {
            Path dir = home.resolve("d1");
            Files.createDirectories(dir);
            Files.write(dir.resolve(WorkspaceDataMeta.FILE_NAME), "{broken".getBytes(StandardCharsets.UTF_8));
            assertEquals(WorkspaceDataMeta.Retention.UNKNOWN, WorkspaceDataMeta.load(dir).getRetention());

            Files.write(dir.resolve(WorkspaceDataMeta.FILE_NAME), "[]".getBytes(StandardCharsets.UTF_8));
            assertEquals(WorkspaceDataMeta.Retention.UNKNOWN, WorkspaceDataMeta.load(dir).getRetention());

            Path empty = home.resolve("d2");
            Files.createDirectories(empty);
            assertEquals(WorkspaceDataMeta.Retention.UNKNOWN, WorkspaceDataMeta.load(empty).getRetention());
        });
    }

    /**
     * 验收 8（未来 schema 只读）：schemaVersion 超前时禁止改写
     */
    @Test
    public void case8_futureSchema_isReadonly() throws Exception {
        withTempHome(home -> {
            Path dir = home.resolve("d3");
            Files.createDirectories(dir);
            String json = "{\"schemaVersion\":99,\"retention\":\"PERSISTENT\",\"storageKey\":\"d3\"}";
            Files.write(dir.resolve(WorkspaceDataMeta.FILE_NAME), json.getBytes(StandardCharsets.UTF_8));

            WorkspaceDataMeta meta = WorkspaceDataMeta.load(dir);
            assertEquals(WorkspaceDataMeta.Retention.PERSISTENT, meta.getRetention(), "未来 schema 可读最小字段");
            assertFalse(meta.isWritable(), "未来 schema 必须只读");
            meta.save(dir); // 无操作
            String after = new String(Files.readAllBytes(dir.resolve(WorkspaceDataMeta.FILE_NAME)), StandardCharsets.UTF_8);
            assertEquals(json, after, "未来 schema 不得被改写");
        });
    }

    /**
     * 验收 9（启动报告）：v1/v2/缺元数据目录被正确分类计数，不产生任何删除或改名
     */
    @Test
    public void case9_startupReport_classifiesOnly() throws Exception {
        withTempHome(home -> {
            Path root = home.resolve(".soloncode/workspaces");
            Path ws = Files.createTempDirectory("sc-ws-");

            // v1 目录（带 _workspace 旧标记，无 _meta.json）
            Path v1Dir = root.resolve(legacyV1Key(ws));
            Files.createDirectories(v1Dir);
            Files.write(v1Dir.resolve(WorkspaceDataUtil.FILE_WORKSPACE_MARK), ws.toString().getBytes(StandardCharsets.UTF_8));
            // v2 目录（有 _meta.json，PERSISTENT）：同 md5 同名 → 触发 DUAL_LAYOUT
            Path v2Dir = root.resolve("ws-" + legacyV1Key(ws));
            Files.createDirectories(v2Dir);
            Files.write(v2Dir.resolve(WorkspaceDataMeta.FILE_NAME),
                    ("{\"schemaVersion\":1,\"storageKey\":\"" + v2Dir.getFileName() + "\",\"retention\":\"PERSISTENT\"}").getBytes(StandardCharsets.UTF_8));
            // 另一个 v1 目录（EPHEMERAL，无标记文件）
            Path otherV1 = root.resolve("22222222222222222222222222222222-other");
            Files.createDirectories(otherV1);
            Files.write(otherV1.resolve(WorkspaceDataMeta.FILE_NAME),
                    ("{\"schemaVersion\":1,\"storageKey\":\"" + otherV1.getFileName() + "\",\"retention\":\"EPHEMERAL\"}").getBytes(StandardCharsets.UTF_8));
            // 无法识别的目录名
            Files.createDirectories(root.resolve("not-a-workspace-dir"));

            WorkspaceStorageReport.Counts c = WorkspaceStorageReport.scan(root.toFile().listFiles());

            assertEquals(2, c.v1, "两个 v1 目录（含 other）");
            assertEquals(1, c.v2, "一个 v2 目录");
            assertEquals(1, c.legacyMarkOnly, "一个仅旧标记无元数据目录");
            assertEquals(1, c.unknown, "一个无法识别目录");
            assertEquals(1, c.dualLayout, "一对双布局目录");
            assertEquals(1, c.persistent, "一个 PERSISTENT");
            assertEquals(1, c.ephemeral, "一个 EPHEMERAL");

            assertTrue(Files.exists(v1Dir), "报告不得删除 v1 目录");
            assertTrue(Files.exists(v2Dir), "报告不得删除 v2 目录");
        });
    }

    /**
     * 验收 10（retention 时序）：创建起点 EPHEMERAL → 建成后提升 PERSISTENT；
     * 未提升（初始化中断）的目录保持 EPHEMERAL 可被识别；重复打开不降级
     */
    @Test
    public void case10_retentionLifecycle_ephemeralToPersistent() throws Exception {
        withTempHome(home -> {
            Path ws = Files.createTempDirectory("sc-ws-");
            Path dataDir = WorkspaceDataUtil.dataDir(ws.toString()).toPath();

            // 阶段一：创建起点写入（模拟 writeWorkspaceMeta 的语义）
            WorkspaceDataMeta creating = WorkspaceDataMeta.empty();
            creating.setWorkspaceId("ws-test-id");
            creating.setPath(ws.toString());
            creating.setRetention(WorkspaceDataMeta.Retention.EPHEMERAL);
            creating.save(dataDir);

            assertEquals(WorkspaceDataMeta.Retention.EPHEMERAL,
                    WorkspaceDataMeta.load(dataDir).getRetention(), "创建中目录应为 EPHEMERAL");

            // 初始化中断：不再写入 → 磁盘保持 EPHEMERAL（清理候选）
            assertEquals(WorkspaceDataMeta.Retention.EPHEMERAL,
                    WorkspaceDataMeta.load(dataDir).getRetention(), "中断残留保持 EPHEMERAL");

            // 建成后：提升 PERSISTENT（同 promoteWorkspaceMeta 语义）
            WorkspaceDataMeta promoted = WorkspaceDataMeta.load(dataDir);
            promoted.setRetention(WorkspaceDataMeta.Retention.PERSISTENT);
            promoted.setLastAccessedAt(System.currentTimeMillis());
            promoted.save(dataDir);
            assertEquals(WorkspaceDataMeta.Retention.PERSISTENT,
                    WorkspaceDataMeta.load(dataDir).getRetention(), "建成后应为 PERSISTENT");

            // 重复打开：再次写 EPHEMERAL 也不得降级（只升不降）
            WorkspaceDataMeta reopen = WorkspaceDataMeta.load(dataDir);
            reopen.setRetention(WorkspaceDataMeta.Retention.EPHEMERAL);
            reopen.save(dataDir);
            assertEquals(WorkspaceDataMeta.Retention.PERSISTENT,
                    WorkspaceDataMeta.load(dataDir).getRetention(), "PERSISTENT 不得降级（I5）");
        });
    }

    /**
     * 验收 11（启动归一化-删临时）：工作区位于临时根下的数据目录被删除；
     * 无法反解工作区路径、或不在临时根下的目录不动（防误删）
     */
    @Test
    public void case11_startupMigration_deletesTempOnly() throws Exception {
        withTempHome(home -> {
            Path root = home.resolve(".soloncode/workspaces");
            Path tmpWs = Files.createTempDirectory("sc-tmpws-"); // 位于 java.io.tmpdir 下

            // 临时工作区数据目录（v1 名，带 _workspace 标记指向 tmpWs）
            String tmpV1 = legacyV1Key(tmpWs);
            Path tmpDir = root.resolve(tmpV1);
            Files.createDirectories(tmpDir.resolve("sessions/s1"));
            Files.write(tmpDir.resolve(WorkspaceDataUtil.FILE_WORKSPACE_MARK), tmpWs.toString().getBytes(StandardCharsets.UTF_8));

            // 另一个临时工作区目录（仅 _meta.json.path 无 _workspace 标记，验证两路反解）
            Path tmpWs2 = Files.createTempDirectory("sc-tmpws2-");
            Path tmpDir2 = root.resolve(legacyV1Key(tmpWs2));
            Files.createDirectories(tmpDir2);
            Files.write(tmpDir2.resolve(WorkspaceDataMeta.FILE_NAME),
                    ("{\"schemaVersion\":1,\"storageKey\":\"" + tmpDir2.getFileName() + "\",\"retention\":\"PERSISTENT\",\"path\":\"" + tmpWs2 + "\"}").getBytes(StandardCharsets.UTF_8));

            // 正常工作区数据目录（同样 v1 名，标记指向普通目录）。
            // 注意：不能用 createTempDirectory（位于临时根下会被判定为临时），改用 target/ 下目录
            Path normalWs = Paths.get("target").toAbsolutePath().resolve("sc-normal-" + System.nanoTime());
            Files.createDirectories(normalWs);
            Path normalDir = root.resolve(legacyV1Key(normalWs));
            Files.createDirectories(normalDir.resolve("sessions/s1"));
            Files.write(normalDir.resolve(WorkspaceDataUtil.FILE_WORKSPACE_MARK), normalWs.toString().getBytes(StandardCharsets.UTF_8));

            // 无法反解路径的目录（无标记无元数据）：保守不动
            Path orphanDir = root.resolve("33333333333333333333333333333333-orphan");
            Files.createDirectories(orphanDir);

            WorkspaceStartupMigrator.Counts c = WorkspaceStartupMigrator.migrate(root.toFile());

            assertEquals(2, c.tempDeleted, "两个临时工作区目录（标记与元数据两路反解）被删");
            assertFalse(Files.exists(tmpDir), "临时目录应被删除");
            assertFalse(Files.exists(tmpDir2), "仅凭 _meta.json.path 判定的临时目录也应被删");
            // 非临时 v1 目录不是被误删，而是被改名为 ws- 新名（会话内容随目录保留）
            Path normalV2 = root.resolve("ws-" + legacyV1Key(normalWs));
            assertTrue(Files.exists(normalV2.resolve("sessions/s1")), "非临时目录改名为 ws- 后内容保留");
            // 无法反解路径的目录不会被删除（防误删），仅按 v1 名特征改名
            Path orphanV2 = root.resolve("ws-33333333333333333333333333333333-orphan");
            assertTrue(Files.exists(orphanV2), "无法判定的目录仅改名不删除（防误删）");
        });
    }

    /**
     * 验收 12（启动归一化-改名）：v1 目录改名为 ws- 前缀，内容原样保留，
     * _meta.json storageKey 同步修复；改名后 workspaceKey 命中新名（会话不失踪）
     */
    @Test
    public void case12_startupMigration_renamesV1ToV2() throws Exception {
        withTempHome(home -> {
            Path root = home.resolve(".soloncode/workspaces");
            // 注意：不能用 createTempDirectory（位于临时根下会被删除而非改名），改用 target/ 下目录
            Path ws = Paths.get("target").toAbsolutePath().resolve("sc-rename-" + System.nanoTime());
            Files.createDirectories(ws);

            Path v1Dir = root.resolve(legacyV1Key(ws));
            Files.createDirectories(v1Dir.resolve("sessions/s1"));
            Files.write(v1Dir.resolve(WorkspaceDataUtil.FILE_WORKSPACE_MARK), ws.toString().getBytes(StandardCharsets.UTF_8));
            Files.write(v1Dir.resolve(WorkspaceDataMeta.FILE_NAME),
                    ("{\"schemaVersion\":1,\"storageKey\":\"" + v1Dir.getFileName() + "\",\"retention\":\"PERSISTENT\",\"path\":\"" + ws + "\"}").getBytes(StandardCharsets.UTF_8));

            WorkspaceStartupMigrator.Counts c = WorkspaceStartupMigrator.migrate(root.toFile());

            Path v2Dir = root.resolve("ws-" + legacyV1Key(ws));
            assertEquals(1, c.renamed, "v1 目录应被改名");
            assertFalse(Files.exists(v1Dir), "旧名目录不存在");
            assertTrue(Files.exists(v2Dir.resolve("sessions/s1")), "会话内容原样保留");

            // 改名后 meta 身份恢复可信（storageKey 已同步）
            WorkspaceDataMeta meta = WorkspaceDataMeta.load(v2Dir);
            assertTrue(meta.isTrusted(), "改名后 storageKey 应同步为新 basename");
            assertEquals(WorkspaceDataMeta.Retention.PERSISTENT, meta.getRetention(), "retention 不丢");

            // 改名后 workspaceKey 探测命中新名（v1 已不存在，自然落到 ws-）
            assertEquals(v2Dir.getFileName().toString(), WorkspaceDataUtil.workspaceKey(ws.toString()), "改名后 key 必须指向新目录");
        });
    }

    /**
     * 验收 13（冲突跳过）：目标 ws- 目录已存在（双布局）时不改名不覆盖；
     * 未来 schema（只读）目录不改名
     */
    @Test
    public void case13_startupMigration_conflictAndFutureSchemaSkipped() throws Exception {
        withTempHome(home -> {
            Path root = home.resolve(".soloncode/workspaces");
            Path ws = Paths.get("target").toAbsolutePath().resolve("sc-conflict-" + System.nanoTime());
            Files.createDirectories(ws);

            // 双布局冲突：v1 与同名 ws- 并存
            Path v1Dir = root.resolve(legacyV1Key(ws));
            Files.createDirectories(v1Dir);
            Path v2Dir = root.resolve("ws-" + legacyV1Key(ws));
            Files.createDirectories(v2Dir);

            // 未来 schema 目录
            Path futureDir = root.resolve("44444444444444444444444444444444-future");
            Files.createDirectories(futureDir);
            Files.write(futureDir.resolve(WorkspaceDataMeta.FILE_NAME),
                    "{\"schemaVersion\":99,\"retention\":\"PERSISTENT\"}".getBytes(StandardCharsets.UTF_8));

            WorkspaceStartupMigrator.Counts c = WorkspaceStartupMigrator.migrate(root.toFile());

            assertTrue(Files.exists(v1Dir), "冲突时不得改名（v1 保留，运行期原地采用兜底）");
            assertTrue(Files.exists(v2Dir), "已有 ws- 目录不得被覆盖");
            assertTrue(Files.exists(futureDir), "未来 schema 目录不改名");
            assertEquals(2, c.skipped, "两个目录被跳过");
            assertEquals(0, c.renamed, "无改名发生");
            assertEquals(0, c.tempDeleted, "无删除发生");
        });
    }

    /**
     * 验收 14（别名路径改名后仍命中）：别名路径的旧 v1 目录被启动迁移器改名后，
     * 按别名路径打开时命中改名后的 ws- 目录（补探测逻辑生效）
     */
    @Test
    public void case14_aliasPath_afterRenameStillHits() throws Exception {
        withTempHome(home -> {
            Path real = Files.createTempDirectory("sc-real-");
            Path alias = real.getParent().resolve("sc-alias-" + System.nanoTime());
            try {
                Files.createSymbolicLink(alias, real);
            } catch (Throwable e) {
                return; // 无符号链接权限的环境跳过
            }

            // 旧版本按别名路径计算的 v1 目录，已被启动迁移器改名
            String legacyKey = plainKey(alias.toString());
            Path renamedDir = home.resolve(".soloncode/workspaces").resolve("ws-" + legacyKey);
            Files.createDirectories(renamedDir.resolve("sessions/s1"));

            assertEquals("ws-" + legacyKey, WorkspaceDataUtil.workspaceKey(alias.toString()),
                    "别名路径改名后必须命中改名目录，会话不失踪");
        });
    }

    /**
     * 验收 15（临时删除对 v2 同样生效）：v2 目录位于临时根下同样被删；
     * 非临时 v2 目录不动；未来 schema + 临时路径不删（只读保护优先于删除）
     */
    @Test
    public void case15_startupMigration_tempDeleteCoversV2() throws Exception {
        withTempHome(home -> {
            Path root = home.resolve(".soloncode/workspaces");
            Path tmpWs = Files.createTempDirectory("sc-tmpws3-"); // 位于 java.io.tmpdir 下

            // v2 名的临时工作区数据目录（带 _workspace 标记）
            String tmpV2 = "ws-" + legacyV1Key(tmpWs);
            Path tmpDir = root.resolve(tmpV2);
            Files.createDirectories(tmpDir.resolve("sessions/s1"));
            Files.write(tmpDir.resolve(WorkspaceDataUtil.FILE_WORKSPACE_MARK), tmpWs.toString().getBytes(StandardCharsets.UTF_8));

            // 非临时 v2 目录（target/ 下）：不动
            Path normalWs = Paths.get("target").toAbsolutePath().resolve("sc-v2normal-" + System.nanoTime());
            Files.createDirectories(normalWs);
            Path normalV2 = root.resolve("ws-" + legacyV1Key(normalWs));
            Files.createDirectories(normalV2.resolve("sessions/s1"));
            Files.write(normalV2.resolve(WorkspaceDataUtil.FILE_WORKSPACE_MARK), normalWs.toString().getBytes(StandardCharsets.UTF_8));

            // 未来 schema + 临时路径：只读保护优先，不删也不改
            Path tmpWsF = Files.createTempDirectory("sc-tmpwsf-");
            Path futureDir = root.resolve("ws-" + legacyV1Key(tmpWsF));
            Files.createDirectories(futureDir);
            Files.write(futureDir.resolve(WorkspaceDataUtil.FILE_WORKSPACE_MARK), tmpWsF.toString().getBytes(StandardCharsets.UTF_8));
            Files.write(futureDir.resolve(WorkspaceDataMeta.FILE_NAME),
                    "{\"schemaVersion\":99,\"retention\":\"PERSISTENT\"}".getBytes(StandardCharsets.UTF_8));

            WorkspaceStartupMigrator.Counts c = WorkspaceStartupMigrator.migrate(root.toFile());

            assertEquals(1, c.tempDeleted, "仅临时 v2 目录被删");
            assertFalse(Files.exists(tmpDir), "临时 v2 目录应被删除（持续行为，非仅清 v1 欠账）");
            assertTrue(Files.exists(normalV2.resolve("sessions/s1")), "非临时 v2 目录不动");
            assertTrue(Files.exists(futureDir), "未来 schema 目录即使位于临时根下也不删（只读保护优先）");
            assertEquals(1, c.skipped, "未来 schema 目录被跳过");
        });
    }

    /**
     * 按旧版算法计算 v1 目录名（不复用被测代码，独立实现以验证行为而非镜像实现）
     */
    private String legacyV1Key(Path ws) {
        return plainKey(ws.toString());
    }

    private String plainKey(String pathStr) {
        String s = pathStr;
        if (File.separatorChar == '\\') {
            s = s.toLowerCase();
        }
        return md5Hex(s) + "-" + lastSegment(s);
    }

    private String lastSegment(String s) {
        String t = s.replace("\\", "/");
        int idx = t.lastIndexOf('/');
        return idx < 0 ? t : t.substring(idx + 1);
    }

    /**
     * 独立 md5 十六进制实现（避免依赖被测类的 Utils.md5 造成镜像验证）
     */
    private String md5Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
