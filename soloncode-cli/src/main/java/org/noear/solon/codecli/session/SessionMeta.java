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
package org.noear.solon.codecli.session;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 会话元数据，对应 session 目录下的 {@code meta.json}。
 *
 * <p>统一管理 label / pinned / createdAt 等扩展属性，避免散落的 label.txt、pin.txt。
 * 旧会话仅有 label.txt 时，{@link #load} 会一次性迁移为 meta.json 并删除 label.txt；
 * pin.txt 不做兼容。</p>
 *
 * @author noear
 */
public class SessionMeta {
    private static final Logger LOG = LoggerFactory.getLogger(SessionMeta.class);

    public static final String FILE_NAME = "_meta.json";
    private static final String LEGACY_LABEL = "label.txt";

    private String label;
    private boolean pinned;
    /** 会话创建时间（epoch millis）；0 表示未知，读时会尽量回填。 */
    private long createdAt;
    /** 会话所有者用户 ID（用户认证启用时设置，用于会话隔离）。 */
    private String ownerUserId;

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(String ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    /**
     * 是否无业务展示字段（无自定义标题、未置顶）。
     * 不含 createdAt：load 后目录总会回填创建时间，createdAt 不能作为“业务空”判断。
     */
    public boolean isEmpty() {
        return Assert.isEmpty(label) && !pinned;
    }

    public static SessionMeta load(File sessionDir) {
        if (sessionDir == null) {
            return new SessionMeta();
        }
        return load(sessionDir.toPath());
    }

    /**
     * 读取会话 meta。文件不存在时返回空对象；发现旧 label.txt 时自动迁移。
     * 读失败时降级为空对象，避免列表接口因坏 meta 整页失败。
     */
    public static SessionMeta load(Path sessionDir) {
        SessionMeta meta = new SessionMeta();
        if (sessionDir == null || !Files.isDirectory(sessionDir)) {
            return meta;
        }

        Path metaFile = sessionDir.resolve(FILE_NAME);
        Path legacyLabel = sessionDir.resolve(LEGACY_LABEL);

        try {
            if (Files.isRegularFile(metaFile)) {
                String json = new String(Files.readAllBytes(metaFile), StandardCharsets.UTF_8);
                if (Assert.isNotEmpty(json)) {
                    ONode root = ONode.ofJson(json);
                    if (root != null && root.isObject()) {
                        if (root.hasKey("label")) {
                            meta.label = root.get("label").getString();
                        }
                        if (root.hasKey("pinned")) {
                            meta.pinned = root.get("pinned").getBoolean(false);
                        }
                        if (root.hasKey("createdAt")) {
                            meta.createdAt = root.get("createdAt").getLong(0L);
                        }
                        if (root.hasKey("ownerUserId")) {
                            meta.ownerUserId = root.get("ownerUserId").getString();
                        }
                    }
                }
                // 已有 meta.json 时，清理残留旧文件，不再读 label.txt
                cleanupLegacyFiles(sessionDir);
                // 若创建时间缺失则回填并重写
                if (meta.createdAt <= 0L) {
                    meta.createdAt = resolveCreatedAtFallback(sessionDir);
                    if (meta.createdAt > 0L) {
                        try {
                            meta.save(sessionDir);
                        } catch (Exception e) {
                            LOG.warn("[SessionMeta] Failed to backfill createdAt for {}: {}", sessionDir, e.getMessage());
                        }
                    }
                }
                return meta;
            }

            // 兼容旧 label.txt：读后写出 meta.json 并删除旧文件
            if (Files.isRegularFile(legacyLabel)) {
                String text = new String(Files.readAllBytes(legacyLabel), StandardCharsets.UTF_8);
                if (text != null) {
                    // 仅取首行，与旧逻辑一致
                    int nl = text.indexOf('\n');
                    meta.label = (nl >= 0 ? text.substring(0, nl) : text).trim();
                    if (meta.label.isEmpty()) {
                        meta.label = null;
                    }
                }
                meta.createdAt = resolveCreatedAtFallback(sessionDir);
                try {
                    meta.save(sessionDir);
                    Files.deleteIfExists(legacyLabel);
                } catch (Exception e) {
                    LOG.warn("[SessionMeta] Failed to migrate label.txt for {}: {}", sessionDir, e.getMessage());
                }
                return meta;
            }

            // 全新空 meta：尽量补 createdAt（目录创建时间），但不强制落盘
            meta.createdAt = resolveCreatedAtFallback(sessionDir);
            return meta;
        } catch (Exception e) {
            LOG.warn("[SessionMeta] Failed to load meta from {}: {}", sessionDir, e.getMessage());
            if (meta.createdAt <= 0L) {
                meta.createdAt = resolveCreatedAtFallback(sessionDir);
            }
            return meta;
        }
    }

    public void save(File sessionDir) throws IOException {
        if (sessionDir == null) {
            throw new IOException("sessionDir is null");
        }
        save(sessionDir.toPath());
    }

    /**
     * 原子写入 meta.json（tmp + rename）。
     */
    public void save(Path sessionDir) throws IOException {
        if (sessionDir == null) {
            throw new IOException("sessionDir is null");
        }
        if (createdAt <= 0L) {
            createdAt = System.currentTimeMillis();
        }

        Files.createDirectories(sessionDir);

        ONode root = new ONode(Options.of(Feature.Write_PrettyFormat));
        root.set("label", label == null ? "" : label);
        root.set("pinned", pinned);
        root.set("createdAt", createdAt);
        root.set("ownerUserId", ownerUserId == null ? "" : ownerUserId);
        String json = root.toJson();

        Path metaFile = sessionDir.resolve(FILE_NAME);
        Path tempFile = sessionDir.resolve(FILE_NAME + ".tmp");
        try {
            Files.write(tempFile, json.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tempFile, metaFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tempFile, metaFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    /**
     * fork：把源会话 meta 复制到目标目录（源侧会顺带完成 label 迁移）。
     * 始终写出目标 meta.json：保留 label/pinned，刷新 createdAt，保证 fork 后按创建时间排序稳定。
     */
    public static void copy(File sourceDir, File targetDir) throws IOException {
        if (sourceDir == null || targetDir == null) {
            return;
        }
        copy(sourceDir.toPath(), targetDir.toPath());
    }

    public static void copy(Path sourceDir, Path targetDir) throws IOException {
        if (sourceDir == null || targetDir == null) {
            return;
        }
        SessionMeta meta = load(sourceDir);
        // fork 后视为新会话：保留 label/pinned（可为空），刷新创建时间
        SessionMeta target = new SessionMeta();
        target.setLabel(meta.getLabel());
        target.setPinned(meta.isPinned());
        target.setOwnerUserId(meta.getOwnerUserId());
        target.setCreatedAt(System.currentTimeMillis());
        target.save(targetDir);
    }

    public static void updateLabel(File sessionDir, String label) throws IOException {
        if (sessionDir == null) {
            throw new IOException("sessionDir is null");
        }
        updateLabel(sessionDir.toPath(), label);
    }

    public static void updateLabel(Path sessionDir, String label) throws IOException {
        SessionMeta meta = load(sessionDir);
        meta.setLabel(label);
        meta.save(sessionDir);
    }

    public static void updatePinned(File sessionDir, boolean pinned) throws IOException {
        if (sessionDir == null) {
            throw new IOException("sessionDir is null");
        }
        updatePinned(sessionDir.toPath(), pinned);
    }

    public static void updatePinned(Path sessionDir, boolean pinned) throws IOException {
        SessionMeta meta = load(sessionDir);
        meta.setPinned(pinned);
        meta.save(sessionDir);
    }

    /**
     * 清理 meta.json 并存时的残留旧文件（label.txt / pin.txt）。
     */
    private static void cleanupLegacyFiles(Path sessionDir) {
        try {
            Files.deleteIfExists(sessionDir.resolve(LEGACY_LABEL));
            Files.deleteIfExists(sessionDir.resolve("pin.txt"));
        } catch (Exception e) {
            LOG.warn("[SessionMeta] Failed to cleanup legacy files in {}: {}", sessionDir, e.getMessage());
        }
    }
    
    /**
     * 创建时间回退：目录创建时间 → lastModified → 当前时间。
     */
    private static long resolveCreatedAtFallback(Path sessionDir) {
        try {
            if (sessionDir != null && Files.exists(sessionDir)) {
                try {
                    Object attr = Files.getAttribute(sessionDir, "creationTime");
                    if (attr instanceof java.nio.file.attribute.FileTime) {
                        long millis = ((java.nio.file.attribute.FileTime) attr).toMillis();
                        if (millis > 0L) {
                            return millis;
                        }
                    }
                } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
                    // 某些 FS 不支持 creationTime
                }
                long modified = Files.getLastModifiedTime(sessionDir).toMillis();
                if (modified > 0L) {
                    return modified;
                }
            }
        } catch (Exception ignored) {
        }
        return System.currentTimeMillis();
    }
}
