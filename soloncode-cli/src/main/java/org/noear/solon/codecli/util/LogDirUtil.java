/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.util;

import org.noear.solon.Utils;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.codecli.workspace.WorkspaceDataUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;

/**
 * 日志目录工具：统一计算工作区日志目录（~/.soloncode/workspaces/&lt;工作区标识&gt;/logs/），并清理旧版遗留日志。
 * <p>
 * 目录标识与数据目录布局见 {@link WorkspaceDataUtil}；打开目录请用 OsOpenUtil.openDirectory(File)。
 *
 * @author noear
 * @since 3.9.1
 */
public class LogDirUtil {
    private static final Logger log = LoggerFactory.getLogger(LogDirUtil.class);

    /**
     * 旧版日志目录名（两种旧位置共用）：
     * <ul>
     *     <li>~/.soloncode/logs/&lt;标识&gt;/ —— 上一版的全局位置（按标识分片）</li>
     *     <li>&lt;工作区&gt;/.soloncode/logs/ —— 更早版本写在工作区内，会污染 IDE 全文搜索</li>
     * </ul>
     */
    private static final String LEGACY_LOG_DIR = ".soloncode/logs";

    /**
     * 日志文件后缀（与 app.yml 中 solon.logging.appender.file.extension 一致）
     */
    private static final String LOG_EXTENSION = ".log";

    /**
     * 日志目录标识的系统属性名（app.yml 中 file.name 引用 ${soloncode.wskey}）
     */
    public static final String WS_LABEL_PROP = "soloncode.wslabel";

    /**
     * MDC 中当前工作区日志标识的 key（由 WorkspaceFilter / WebGate 打标，WorkspaceLogRouter 消费）
     */
    public static final String WS_KEY = "soloncode.wskey";

    /**
     * 计算进程启动目录的日志目录标识（供启动阶段写入 {@link #WS_KEY}）
     *
     * @see WorkspaceDataUtil#workspaceKey(String)
     */
    public static String workspaceKey() {
        return workspaceKey(AgentFlags.getUserDir());
    }

    /**
     * 计算指定工作区的日志目录标识（等价于工作区数据目录标识）
     *
     * @param workspacePath 工作区目录（为空时回退到进程启动目录）
     */
    public static String workspaceKey(String workspacePath) {
        return WorkspaceDataUtil.workspaceKey(workspacePath);
    }

    /**
     * 获取指定工作区专属的日志目录（~/.soloncode/workspaces/&lt;工作区标识&gt;/logs/）。
     * <p>启用 WorkspaceLogRouter 后，每个工作区的日志分流到各自目录，本方法返回即真实写入位置。</p>
     */
    public static File logDir(String workspacePath) {
        return logDirByKey(workspaceKey(workspacePath));
    }

    /**
     * 获取指定标识的日志目录（~/.soloncode/workspaces/&lt;工作区标识&gt;/logs/）
     */
    public static File logDirByKey(String logKey) {
        return new File(WorkspaceDataUtil.dataDirByKey(logKey), WorkspaceDataUtil.DIR_LOGS);
    }

    /**
     * 清理旧版本遗留的日志（日志属于可丢弃数据，不做迁移）。
     * <p>
     * 涉及两处旧位置：
     * <ul>
     *     <li>&lt;工作区&gt;/.soloncode/logs/*.log —— 更早的版本写在工作区内，会污染 IDE 全文搜索</li>
     *     <li>~/.soloncode/logs/&lt;工作区标识&gt;/ —— 上一版的全局位置，现已按工作区聚合到 ~/.soloncode/workspaces/</li>
     * </ul>
     * 清理策略（保守）：只删日志文件本身，不递归无关内容；任何失败（文件被占用、无权限等）静默跳过，绝不影响工作区创建。
     *
     * @param workspacePath 工作区目录
     */
    public static void cleanLegacyLogs(String workspacePath) {
        if (Utils.isEmpty(workspacePath)) {
            return;
        }

        cleanLegacyWorkspaceLogs(workspacePath);
        cleanLegacyGlobalLogs(workspacePath);
    }

    /**
     * 清理 &lt;工作区&gt;/.soloncode/logs/ 下的直接子级 .log 文件（子目录一概不动）
     */
    private static void cleanLegacyWorkspaceLogs(String workspacePath) {
        try {
            File legacyDir = Paths.get(workspacePath, LEGACY_LOG_DIR).toFile();

            if (legacyDir.isDirectory() == false) {
                return;
            }

            int deleted = deleteLogFiles(legacyDir);
            if (deleted > 0) {
                log.debug("Cleaned {} legacy log file(s) under: {}", deleted, legacyDir.getAbsolutePath());
            }
        } catch (Throwable e) {
            //清理属于 best-effort，任何异常都不应影响工作区创建
            log.debug("Clean legacy workspace logs failed: {}", workspacePath, e);
        }
    }

    /**
     * 清理 ~/.soloncode/logs/&lt;工作区标识&gt;/ （只处理当前工作区自己那一份，不碰其它工作区）
     */
    private static void cleanLegacyGlobalLogs(String workspacePath) {
        try {
            File legacyRoot = Paths.get(AgentFlags.getUserHome(), LEGACY_LOG_DIR).toFile();
            if (legacyRoot.isDirectory() == false) {
                return;
            }

            File legacyDir = new File(legacyRoot, workspaceKey(workspacePath));
            if (legacyDir.isDirectory() == false) {
                return;
            }

            int deleted = deleteLogFiles(legacyDir);
            if (deleted > 0) {
                log.debug("Cleaned {} legacy log file(s) under: {}", deleted, legacyDir.getAbsolutePath());
            }

            //旧根目录已空则一并移除（其它工作区尚未启动过时仍会保留其目录）
            String[] remains = legacyRoot.list();
            if (remains != null && remains.length == 0) {
                legacyRoot.delete();
            }
        } catch (Throwable e) {
            log.debug("Clean legacy global logs failed: {}", workspacePath, e);
        }
    }

    /**
     * 删除目录下直接子级的日志文件；目录清空后一并移除该目录自身
     *
     * @return 实际删除的文件数
     */
    private static int deleteLogFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }

        int deleted = 0;
        int remained = 0;
        for (File file : files) {
            //只处理直接子级的普通文件，子目录（孙级）一概不动
            if (file.isFile() && isLogFile(file.getName())) {
                if (file.delete()) {
                    deleted++;
                } else {
                    //可能被旧实例占用（Windows 常见），保留即可
                    remained++;
                }
            } else {
                remained++;
            }
        }

        //目录已空则移除，避免残留空目录
        if (remained == 0) {
            dir.delete();
        }

        return deleted;
    }

    /**
     * 是否日志文件（含滚动归档产物，如 soloncode_2026-08-24_0.log.gz）
     */
    private static boolean isLogFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(LOG_EXTENSION)
                || lower.endsWith(LOG_EXTENSION + ".gz")
                || lower.endsWith(LOG_EXTENSION + ".zip");
    }
}
