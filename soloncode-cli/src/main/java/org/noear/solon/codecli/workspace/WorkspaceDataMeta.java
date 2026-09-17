package org.noear.solon.codecli.workspace;

import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 工作区数据目录元数据（_meta.json）：记录目录归属、布局与生命周期状态。
 *
 * <p>设计要点（见 docs/workspace-storage-lifecycle-plan.md 第四章）：</p>
 * <ul>
 *     <li>storageKey 必须等于所在目录 basename，不一致视为元数据异常（读取结果 retention=UNKNOWN）；</li>
 *     <li>写回采用「读最新 ONode - 字段级合并 - 原子替换」，未知 JSON 字段原样保留，
 *         避免旧版本读取未来 schema 后整体覆盖回写导致字段丢失；</li>
 *     <li>retention 只升不降（EPHEMERAL/UNKNOWN -&gt; PERSISTENT），createdAt/createdSource 首次有效值不可覆盖；</li>
 *     <li>schemaVersion 大于当前支持版本时只读最小字段，禁止任何改写。</li>
 * </ul>
 *
 * @author noear
 * @since 3.9.2
 */
public class WorkspaceDataMeta {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceDataMeta.class);

    /**
     * 元数据文件名（下划线前缀，与会话目录区分）
     */
    public static final String FILE_NAME = "_meta.json";

    /**
     * 当前支持的 schema 版本（只增不改）
     */
    public static final int SCHEMA_VERSION = 1;

    /**
     * 生命周期状态
     */
    public enum Retention {
        /**
         * 用户意图（启动或显式打开），不可自动删除
         */
        PERSISTENT,
        /**
         * 创建中/失败残留/显式内部临时，清理候选
         */
        EPHEMERAL,
        /**
         * 元数据缺失、损坏、schema 未知等保护态，永不清理
         */
        UNKNOWN;

        /**
         * 解析未知字符串时落到保护态（不抛异常，保证治理流程可继续）
         */
        public static Retention parse(String value) {
            if (value == null) {
                return UNKNOWN;
            }
            try {
                return Retention.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }
    }

    private int schemaVersion = SCHEMA_VERSION;
    private int layoutVersion = 2;
    private String workspaceId;
    private String storageKey;
    private String name;
    private String path;
    private Retention retention = Retention.UNKNOWN;
    private String createdSource;
    private String lastOpenedSource;
    private long createdAt;
    private long lastAccessedAt;
    private long updatedAt;
    private String appVersion;

    /**
     * 元数据是否可写：schemaVersion 超前或读取失败时为 false（只读保护）
     */
    private transient boolean readonly = false;

    /**
     * 元数据是否可信任（storageKey 与所在目录 basename 一致）
     */
    private transient boolean trusted = true;

    public static WorkspaceDataMeta empty() {
        return new WorkspaceDataMeta();
    }

    /**
     * 读取指定数据目录的元数据。
     *
     * <p>任何读不出来的情况（文件缺失、损坏、非对象、字段类型异常）都返回 UNKNOWN 态实例，
     * 绝不抛异常阻断调用方；未来 schema 只读。</p>
     */
    public static WorkspaceDataMeta load(Path dataDir) {
        WorkspaceDataMeta meta = new WorkspaceDataMeta();
        if (dataDir == null) {
            return meta;
        }

        Path file = dataDir.resolve(FILE_NAME);
        if (Files.exists(file) == false) {
            return meta; // 缺失 -> UNKNOWN
        }

        try {
            ONode node = ONode.ofJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (node.isObject() == false) {
                return meta; // 损坏 -> UNKNOWN
            }

            meta.schemaVersion = node.get("schemaVersion").getInt();
            if (meta.schemaVersion > SCHEMA_VERSION) {
                // 未来 schema：只读最小字段用于报告，禁止改写（I6）
                meta.retention = Retention.parse(node.get("retention").getString());
                meta.readonly = true;
                return meta;
            }

            meta.workspaceId = node.get("workspaceId").getString();
            meta.storageKey = node.get("storageKey").getString();
            meta.name = node.get("name").getString();
            meta.path = node.get("path").getString();
            meta.retention = Retention.parse(node.get("retention").getString());
            meta.createdSource = node.get("createdSource").getString();
            meta.lastOpenedSource = node.get("lastOpenedSource").getString();
            meta.createdAt = node.get("createdAt").getLong();
            meta.lastAccessedAt = node.get("lastAccessedAt").getLong();
            meta.updatedAt = node.get("updatedAt").getLong();
            meta.appVersion = node.get("appVersion").getString();

            // storageKey 必须等于所在目录 basename（I2），不一致视为元数据异常
            String actualName = dataDir.getFileName() != null ? dataDir.getFileName().toString() : null;
            if (meta.storageKey == null || meta.storageKey.equals(actualName) == false) {
                meta.retention = Retention.UNKNOWN;
                meta.trusted = false;
            }
            return meta;
        } catch (Throwable e) {
            // 损坏 -> UNKNOWN
            WorkspaceDataMeta broken = new WorkspaceDataMeta();
            return broken;
        }
    }

    /**
     * 是否可安全改写本元数据（未来 schema / 异常态禁止覆盖）
     */
    public boolean isWritable() {
        return readonly == false;
    }

    /**
     * 元数据的 storageKey 是否与所在目录一致（身份可信）
     */
    public boolean isTrusted() {
        return trusted;
    }

    /**
     * 按合并协议写入指定数据目录（读最新-合并-原子替换）。
     *
     * <p>合并规则（方案 7.4）：retention 只升不降、createdAt/createdSource 首次有效值保留、
     * 时间戳取 max、workspaceId 已有值不重算、未知字段保留。任何失败仅记录日志不上抛——
     * 元数据是治理辅助数据，不能阻塞工作区创建主链路。</p>
     */
    public void save(Path dataDir) {
        if (dataDir == null || isWritable() == false) {
            return;
        }
        try {
            Files.createDirectories(dataDir);
            Path file = dataDir.resolve(FILE_NAME);

            // 读最新，字段级合并（保留未知字段）
            ONode merged = ONode.ofJson("{}");
            if (Files.exists(file)) {
                try {
                    ONode existing = ONode.ofJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                    if (existing.isObject()) {
                        merged = existing;
                    }
                } catch (Throwable ignore) {
                    // 旧文件损坏：以新内容为准重建（治理数据可重建，不同于用户会话）
                }
            }

            long now = System.currentTimeMillis();
            if (updatedAt <= 0) {
                updatedAt = now;
            }

            merged.set("schemaVersion", schemaVersion);
            merged.set("layoutVersion", layoutVersion);
            setIfAbsent(merged, "workspaceId", workspaceId);
            // storageKey 强制同步为所在目录 basename（I2：storageKey 永远等于当前实际目录名，改名即同步修正）
            merged.set("storageKey", dataDir.getFileName() != null ? dataDir.getFileName().toString() : storageKey);
            setIfAbsent(merged, "name", name);
            setIfAbsent(merged, "path", path);

            // retention 只升不降：EPHEMERAL/UNKNOWN -> PERSISTENT 允许，反向禁止
            Retention existingRetention = Retention.parse(merged.get("retention").getString());
            Retention effective = maxRetention(existingRetention, this.retention);
            merged.set("retention", effective.name());

            setIfAbsent(merged, "createdSource", createdSource);
            merged.set("lastOpenedSource", lastOpenedSource != null ? lastOpenedSource : merged.get("lastOpenedSource").getString());

            setLongIfAbsent(merged, "createdAt", createdAt > 0 ? createdAt : now);
            setLongMax(merged, "lastAccessedAt", lastAccessedAt);
            setLongMax(merged, "updatedAt", updatedAt);

            merged.set("appVersion", appVersion);

            // 唯一 tmp + 原子替换（不与其它进程共享临时文件）
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp." + UUID.randomUUID().toString().substring(0, 8));
            try {
                Files.write(tmp, merged.toJson().getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignore) {
                }
            }
        } catch (Throwable e) {
            log.warn("Save workspace meta failed: {}", dataDir, e);
        }
    }

    /**
     * retention 合并：磁盘值 a，待写入值 b。
     * 唯一的保护是不变式 I5（PERSISTENT 不降级）：任一为 PERSISTENT 即 PERSISTENT；
     * 其余情况以待写入值为准（磁盘无值/UNKNOWN 时正常写入，UNKNOWN 的保护语义在 load 侧）。
     */
    private static Retention maxRetention(Retention a, Retention b) {
        if (a == Retention.PERSISTENT || b == Retention.PERSISTENT) {
            return Retention.PERSISTENT;
        }
        return b == null ? a : b;
    }

    private static void setIfAbsent(ONode node, String key, String value) {
        if (value == null || value.isEmpty()) {
            return; // 新值缺省时保留磁盘已有值
        }
        String existing = node.get(key).getString();
        if (existing == null || existing.isEmpty()) {
            node.set(key, value);
        }
        // 已有值不覆盖（首次有效值不可变）
    }

    private static void setLongIfAbsent(ONode node, String key, long value) {
        long existing = node.get(key).getLong();
        if (existing <= 0) {
            node.set(key, value);
        }
    }

    private static void setLongMax(ONode node, String key, long value) {
        long existing = node.get(key).getLong();
        node.set(key, Math.max(existing, value));
    }

    // ---- getter / setter ----

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public int getLayoutVersion() {
        return layoutVersion;
    }

    public void setLayoutVersion(int layoutVersion) {
        this.layoutVersion = layoutVersion;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Retention getRetention() {
        return retention;
    }

    public void setRetention(Retention retention) {
        this.retention = retention;
    }

    public String getCreatedSource() {
        return createdSource;
    }

    public void setCreatedSource(String createdSource) {
        this.createdSource = createdSource;
    }

    public String getLastOpenedSource() {
        return lastOpenedSource;
    }

    public void setLastOpenedSource(String lastOpenedSource) {
        this.lastOpenedSource = lastOpenedSource;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void setLastAccessedAt(long lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }
}
