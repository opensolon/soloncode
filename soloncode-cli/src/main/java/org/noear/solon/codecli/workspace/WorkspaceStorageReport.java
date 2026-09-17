package org.noear.solon.codecli.workspace;

import org.noear.solon.codecli.config.AgentFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工作区存储目录启动报告：扫描 ~/.soloncode/workspaces/ 直接子目录并按类别统计。
 *
 * <p>阶段一（识别与观测）只做分类统计，不做任何迁移、补写或删除（见
 * docs/workspace-storage-lifecycle-plan.md 第十四章）。目的：让 v1/v2 布局分布、
 * 缺元数据目录、双布局共存（DUAL_LAYOUT）在升级后可见，为后续阶段的灰度决策提供数据。</p>
 *
 * @author noear
 * @since 3.9.2
 */
public class WorkspaceStorageReport {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceStorageReport.class);

    /**
     * v2 目录名：ws-<32位小写十六进制>-<可读名>
     */
    private static final String V2_PATTERN = "^ws-[0-9a-f]{32}-.+$";
    /**
     * v1 目录名：<32位十六进制>-<可读名>
     */
    private static final String V1_PATTERN = "^[0-9a-fA-F]{32}-.+$";

    public static void reportIfNeeded() {
        try {
            File root = WorkspaceDataUtil.dataRootDir();
            if (root.isDirectory() == false) {
                return;
            }

            File[] children = root.listFiles();
            if (children == null || children.length == 0) {
                return;
            }

            Counts c = scan(children);
            log.info("[Storage] workspace dirs: v1={}, v2={}, no-meta={}, unrecognized={}, dualLayout={}, retention(P/E/U={}/{}/{})",
                    c.v1, c.v2, c.legacyMarkOnly, c.unknown, c.dualLayout, c.persistent, c.ephemeral, c.unknownRetention);
        } catch (Throwable e) {
            // 报告属于观测行为，任何失败都不影响启动
            log.debug("[Storage] report failed: {}", e.getMessage());
        }
    }

    /**
     * 分类统计结果（包内可见，供测试断言）
     */
    static class Counts {
        int v1, v2, legacyMarkOnly, unknown;
        int dualLayout;
        int persistent, ephemeral, unknownRetention;
    }

    /**
     * 扫描并分类于根目录下的直接子目录（只读，无任何写动作）
     */
    static Counts scan(File[] children) {
        Counts c = new Counts();
        for (File child : children) {
            if (child.isDirectory() == false) {
                continue;
            }
            String name = child.getName();
            // 保留区/治理文件不参与统计
            if (name.startsWith(".")) {
                continue;
            }

            boolean isV1 = name.matches(V1_PATTERN);
            boolean isV2 = name.matches(V2_PATTERN);
            if (isV2) {
                c.v2++;
            } else if (isV1) {
                c.v1++;
            } else {
                c.unknown++;
                continue;
            }

            // 双布局探测：同一 md5 前缀的 v1 与 v2 目录同时存在（不合并不删除，仅报告）。
            // 须在 _meta.json 缺失 continue 之前：旧 v1 目录（无元数据）+ 新建 v2 是最典型场景
            if (isV1 && hasSiblingV2(child)) {
                c.dualLayout++;
            }

            WorkspaceDataMeta meta = WorkspaceDataMeta.load(child.toPath());
            if (Files.exists(child.toPath().resolve(WorkspaceDataMeta.FILE_NAME)) == false) {
                if (Files.exists(child.toPath().resolve(WorkspaceDataUtil.FILE_WORKSPACE_MARK))) {
                    c.legacyMarkOnly++;
                }
                continue;
            }
            switch (meta.getRetention()) {
                case PERSISTENT: c.persistent++; break;
                case EPHEMERAL: c.ephemeral++; break;
                default: c.unknownRetention++; break;
            }
        }
        return c;
    }

    /**
     * v1 目录是否存在同 md5 的 v2 兄弟目录
     */
    private static boolean hasSiblingV2(File v1Dir) {
        String name = v1Dir.getName();
        File[] siblings = v1Dir.getParentFile().listFiles(f ->
                f.isDirectory() && f.getName().equals("ws-" + name));
        return siblings != null && siblings.length > 0;
    }
}
