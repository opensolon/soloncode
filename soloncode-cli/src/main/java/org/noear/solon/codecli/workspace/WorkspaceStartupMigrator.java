package org.noear.solon.codecli.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工作区存储目录启动归一化：
 * <ol>
 *     <li>清理临时测试工作区：工作区路径位于系统已知临时目录（如 /tmp、/private/tmp）下的
 *         数据目录（v1/v2 一视同仁），属运行时临时测试产物，直接删除；</li>
 *     <li>旧布局（v1）目录改名为新风格：md5-名字 → ws-md5-名字，目录内容原样保留，
 *         改名成功后同步修正 _meta.json 的 storageKey。</li>
 * </ol>
 *
 * <p>执行时机：必须在 {@code App.main} 设置 {@code LogDirUtil.WS_KEY} 之前（Solon 启动前），
 * 否则日志 appender 会按旧 v1 key 重建出已被改名的空目录。</p>
 *
 * <p>防护与兜底：</p>
 * <ul>
 *     <li>目标名已存在（双布局冲突，如旧版本回滚后混跑产生）→ 跳过不改名不覆盖；</li>
 *     <li>未来 schema（只读元数据）→ 跳过改名，保持原样；</li>
 *     <li>改名失败（如目录被其它进程占用）→ 跳过，运行期 v1 探测兜底原地采用；</li>
 *     <li>无法判定工作区路径（无 _workspace 也无 _meta.json.path）→ 保守不删除；</li>
 *     <li>任何异常只记录日志，绝不阻断启动。</li>
 * </ul>
 *
 * <p>回滚语义：本迁移执行过一次后，目录名全部为 ws- 风格；回滚到旧版本时旧版本按路径
 * 新建自己的 v1 目录（会话数据仍在新目录中，不丢失但旧版本不可见）——须在发布说明中披露。</p>
 *
 * @author noear
 * @since 3.9.2
 */
public class WorkspaceStartupMigrator {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceStartupMigrator.class);

    /**
     * 启动归一化入口（失败静默，不影响启动）
     */
    public static void migrateIfNeeded() {
        Counts c = migrate(WorkspaceDataUtil.dataRootDir());
        if (c.renamed + c.tempDeleted > 0) {
            log.debug("[Storage] startup normalized: renamed={}, tempDeleted={}, skipped={}",
                    c.renamed, c.tempDeleted, c.skipped);
        }
    }

    /**
     * 迁移结果计数（包内可见，供测试断言）
     */
    static class Counts {
        int renamed;
        int tempDeleted;
        int skipped;
    }

    /**
     * 扫描数据根目录并执行归一化
     */
    static Counts migrate(File root) {
        Counts c = new Counts();
        if (root == null || root.isDirectory() == false) {
            return c;
        }
        File[] children = root.listFiles();
        if (children == null) {
            return c;
        }

        for (File child : children) {
            if (child.isDirectory() == false) {
                continue;
            }
            String name = child.getName();
            // 保留区/治理文件（.locks 等）不参与
            if (name.startsWith(".")) {
                continue;
            }
            // 临时判定对 v1/v2 一视同仁（用户决策：/tmp 下工作区启动即删，是持续行为而非一次性清欠账）
            if (WorkspaceDataUtil.isLegacyV1Name(name) == false && isV2Name(name) == false) {
                continue;
            }
            // 未来 schema（只读元数据）不参与任何变更（含删除），保持原样由人工处理
            if (isFutureSchema(child.toPath())) {
                c.skipped++;
                continue;
            }

            try {
                if (deleteIfTemporary(child.toPath(), c)) {
                    continue;
                }
                if (WorkspaceDataUtil.isLegacyV1Name(name)) {
                    renameToV2(child.toPath(), c);
                }
            } catch (Throwable e) {
                // 单个目录失败不阻断其它目录与启动
                c.skipped++;
                log.warn("[Storage] normalize dir failed: {}", name, e);
            }
        }
        return c;
    }

    /**
     * v2 布局目录名：ws-md5-名字（供临时判定识别，不改名）
     */
    private static boolean isV2Name(String dirName) {
        return dirName.startsWith("ws-");
    }

    /**
     * 未来 schema（_meta.json.schemaVersion 超出当前认知）只读保护：不删除、不改名
     */
    private static boolean isFutureSchema(Path dataDir) {
        if (Files.exists(dataDir.resolve(WorkspaceDataMeta.FILE_NAME)) == false) {
            return false;
        }
        WorkspaceDataMeta meta = WorkspaceDataMeta.load(dataDir);
        return meta.isWritable() == false;
    }

    /**
     * 临时工作区判定并删除：工作区路径（_workspace 标记或 _meta.json.path）位于已知临时根之下才删。
     *
     * @return {@code true} 表示已判定为临时并尝试删除（无论成败本次处理结束）
     */
    private static boolean deleteIfTemporary(Path dataDir, Counts c) {
        Path workspacePath = WorkspaceDataUtil.resolveWorkspacePath(dataDir);
        if (workspacePath == null || WorkspaceDataUtil.isUnderKnownTempRoot(workspacePath) == false) {
            return false;
        }
        if (WorkspaceDataUtil.deleteRecursively(dataDir)) {
            c.tempDeleted++;
            log.info("[Storage] deleted temp workspace data: {} -> {}", dataDir.getFileName(), workspacePath);
        } else {
            c.skipped++;
            log.warn("[Storage] delete temp workspace data incomplete: {}", dataDir);
        }
        return true;
    }

    /**
     * v1 → v2 改名（同目录 rename，原子）。冲突/占用/未来 schema 时跳过。
     */
    private static void renameToV2(Path dataDir, Counts c) throws IOException {
        Path target = dataDir.resolveSibling("ws-" + dataDir.getFileName().toString());
        if (Files.exists(target)) {
            // 双布局冲突（旧版本回滚混跑等）：不覆盖，运行期 v1 探测原地采用兜底
            c.skipped++;
            return;
        }
        Files.move(dataDir, target);
        repairMetaAfterRename(target);
        c.renamed++;
        log.info("[Storage] renamed workspace dir: {} -> {}", dataDir.getFileName(), target.getFileName());
    }

    /**
     * 改名后修复 _meta.json：storageKey 同步为新目录名（I2），恢复身份可信。
     * 失败仅告警——目录处于 UNKNOWN 保护态，数据无损，下次按 ws- 名字正常探测。
     */
    private static void repairMetaAfterRename(Path renamedDir) {
        try {
            if (Files.exists(renamedDir.resolve(WorkspaceDataMeta.FILE_NAME)) == false) {
                return;
            }
            // load 时 storageKey=旧名 != 新 basename，会落 untrusted/UNKNOWN 保护态，属预期中间态
            WorkspaceDataMeta meta = WorkspaceDataMeta.load(renamedDir);
            if (meta.isWritable() == false) {
                return;
            }
            meta.setStorageKey(renamedDir.getFileName().toString());
            meta.setUpdatedAt(System.currentTimeMillis());
            // save 内部把 storageKey 强制同步为所在目录 basename，重写后恢复 trusted
            meta.save(renamedDir);
        } catch (Throwable e) {
            log.warn("[Storage] repair meta after rename failed: {}", renamedDir, e);
        }
    }
}
