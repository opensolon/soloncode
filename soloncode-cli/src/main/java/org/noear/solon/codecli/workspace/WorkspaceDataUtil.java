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
package org.noear.solon.codecli.workspace;

import org.noear.solon.Utils;
import org.noear.solon.codecli.config.AgentFlags;
import org.noear.solon.core.util.JavaUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作区本地数据目录工具。
 * <p>
 * 按工作区分片聚合运行时数据（不随项目走、也不应污染项目目录的那部分）：
 * <pre>
 * ~/.soloncode/workspaces/&lt;工作区标识&gt;/
 *     _workspace     工作区绝对路径标记（供反查，工作区改名/移动后人工修复用）
 *     logs/          日志
 *     sessions/      会话（消息 ndjson、快照、TODO.md、loop-tasks.json）
 * </pre>
 * 用户级资产（settings.json、agents/、skills/、commands/、memory/ 等）仍在 ~/.soloncode/ 一级，不受此处影响。
 *
 * @author noear
 * @since 3.9.1
 */
public class WorkspaceDataUtil {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceDataUtil.class);

    /**
     * 用户主目录下的工作区数据根目录名
     */
    private static final String DATA_ROOT = ".soloncode/workspaces";

    /**
     * 日志子目录名（与 app.yml 中 solon.logging.appender.file.name 的层级一致）
     */
    public static final String DIR_LOGS = "logs";

    /**
     * 会话子目录名
     */
    public static final String DIR_SESSIONS = "sessions";

    /**
     * 工作区路径标记文件名（下划线前缀，与会话 id 目录区分）
     */
    public static final String FILE_WORKSPACE_MARK = "_workspace";

    /**
     * 计算指定工作区的数据目录标识：md5(工作区目录) + "-" + 可读目录名。
     *
     * <p>命名规则（见 docs/workspace-storage-lifecycle-plan.md 第三章）：</p>
     * <ul>
     *     <li>新建目录用 v2 布局：ws-md5-目录名（仅新路径使用）；</li>
     *     <li>已存在 v1 目录（md5-目录名，含历史别名路径如 /tmp 与 /private/tmp 产生的）则原地采用，不改名；</li>
     *     <li>两个布局都存在时优先 v1（存量优先），调用方可通过 layoutVersion() 探测 DUAL_LAYOUT。</li>
     * </ul>
     *
     * @param workspacePath 工作区目录（为空时回退到进程启动目录）
     */
    public static String workspaceKey(String workspacePath) {
        String pathStr = Utils.isEmpty(workspacePath) ? AgentFlags.getUserDir() : workspacePath;
        Path dir = normalizeWorkspacePath(Paths.get(pathStr));

        //存量探测：v1 目录已存在则原地采用，否则新建 v2。
        //同时探测归一化路径与原始路径两个 key：历史版本按原始路径（不解析符号链接）
        //计算目录名，/tmp 与 /private/tmp 这类别名路径在归一化后会算出不同 key，
        //不补查原始路径就会把旧目录进而不见，白白另建 v2 目录造成会话“失踪”。
        String v1 = v1Key(dir);
        if (existingLayoutDir(v1) != null) {
            return v1;
        }
        Path raw = Paths.get(pathStr).toAbsolutePath().normalize();
        if (raw.equals(dir) == false) {
            String legacyV1 = v1Key(raw);
            if (existingLayoutDir(legacyV1) != null) {
                return legacyV1;
            }
            //启动迁移器已把别名路径旧目录改名为 ws- 前缀：补探测改名后形态，否则会话"失踪"
            if (existingLayoutDir("ws-" + legacyV1) != null) {
                return "ws-" + legacyV1;
            }
        }
        return v2Key(dir);
    }

    /**
     * 归一化路径（用于 IO）：在 normalize 基础上尽量解析符号链接（toRealPath），
     * 避免 /tmp vs /private/tmp、目录软链产生双身份。目录不存在或无法解析时退回 normalize 结果。
     */
    public static Path normalizeWorkspacePath(Path path) {
        Path p = path.toAbsolutePath().normalize();
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p;
        }
    }

    /**
     * v2 布局目录名：ws-md5-目录名（仅对不存在 v1 目录的路径使用）
     */
    private static String v2Key(Path dir) {
        return "ws-" + v1Key(dir);
    }

    /**
     * v1 布局目录名（即旧版算法）：md5-目录名
     */
    private static String v1Key(Path dir) {
        //Windows 文件系统大小写不敏感：统一小写后再哈希，避免 "D:\\Work\\MyApp" 与 "D:\\work\\myapp" 生成两个目录
        String hashSource = JavaUtil.IS_WINDOWS ? dir.toString().toLowerCase() : dir.toString();
        return Utils.md5(hashSource) + "-" + readableDirName(dir);
    }

    /**
     * 判定是否旧布局（v1）目录名：md5-名字（无 ws- 前缀）。
     * 供启动迁移器识别改名候选。
     */
    public static boolean isLegacyV1Name(String dirName) {
        return dirName.matches("^[0-9a-fA-F]{32}-.+$") && dirName.startsWith("ws-") == false;
    }

    /**
     * 反解数据目录对应的工作区路径：优先 _workspace 标记，其次 _meta.json 的 path 字段。
     *
     * <p>两者皆无（如初始化中断残留）返回 null，调用方需保守处理（不删除）。</p>
     */
    public static Path resolveWorkspacePath(Path dataDir) {
        try {
            Path mark = dataDir.resolve(FILE_WORKSPACE_MARK);
            if (Files.exists(mark)) {
                String content = new String(Files.readAllBytes(mark), StandardCharsets.UTF_8).trim();
                if (content.isEmpty() == false) {
                    return Paths.get(content);
                }
            }
            WorkspaceDataMeta meta = WorkspaceDataMeta.load(dataDir);
            if (meta.getPath() != null && meta.getPath().isEmpty() == false) {
                return Paths.get(meta.getPath());
            }
        } catch (Throwable ignore) {
            // 反解失败按无法判定处理
        }
        return null;
    }

    /**
     * 路径是否位于已知临时根之下（如 /tmp、/private/tmp、/var/tmp、TMPDIR）。
     * 用于识别运行时临时测试产生的工作区，删除收益高、误删风险低。
     */
    public static boolean isUnderKnownTempRoot(Path path) {
        List<String> roots = new ArrayList<>();
        String tmpdir = System.getProperty("java.io.tmpdir");
        if (tmpdir != null && tmpdir.isEmpty() == false) {
            roots.add(tmpdir);
        }
        String envTmp = System.getenv("TMPDIR");
        if (envTmp != null && envTmp.isEmpty() == false) {
            roots.add(envTmp);
        }
        String envTmp2 = System.getenv("TMP");
        if (envTmp2 != null && envTmp2.isEmpty() == false) {
            roots.add(envTmp2);
        }
        String envTemp = System.getenv("TEMP");
        if (envTemp != null && envTemp.isEmpty() == false) {
            roots.add(envTemp);
        }
        roots.add("/tmp");
        roots.add("/private/tmp");
        roots.add("/var/tmp");

        Path target = path.toAbsolutePath().normalize();
        for (String root : roots) {
            Path rp = Paths.get(root).toAbsolutePath().normalize();
            if (target.startsWith(rp)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 探测指定 key 的 v1 数据目录是否已存在（存在即原地采用）
     */
    private static File existingLayoutDir(String v1Key) {
        File dir = dataDirByKey(v1Key);
        if (dir.isDirectory()) {
            return dir;
        }
        return null;
    }

    /**
     * 生成可读的目录名片段。
     * 根目录（如 "C:\" 或 "/"）时 getFileName() 为 null，退化为清洗后的完整路径（如 "C_"）。
     * 供目录命名与元数据记录共用。
     */
    public static String readableDirName(Path dir) {
        String name;
        Path fileName = dir.getFileName();
        if (fileName != null) {
            name = fileName.toString();
        } else {
            //根目录：去掉末尾分隔符，清洗非法字符后作为名称（如 "C:" -> "C_"，"/" -> "root"）
            name = dir.toString().replace("\\", "/");
            if (name.endsWith("/")) {
                name = name.substring(0, name.length() - 1);
            }
        }

        name = name.replaceAll("[:/*?\"<>|\\s]", "_");
        if (name.isEmpty()) {
            name = "root";
        }
        //目录名长度兜底（各文件系统名称上限多在 255，这里留足余量）
        if (name.length() > 60) {
            name = name.substring(0, 60);
        }
        return name;
    }

    /**
     * 工作区数据根目录（~/.soloncode/workspaces/）
     */
    public static File dataRootDir() {
        return Paths.get(AgentFlags.getUserHome(), DATA_ROOT).toFile();
    }

    /**
     * 指定标识的工作区数据目录（~/.soloncode/workspaces/&lt;工作区标识&gt;/）
     */
    public static File dataDirByKey(String workspaceKey) {
        return new File(dataRootDir(), workspaceKey);
    }

    /**
     * 指定工作区的数据目录（~/.soloncode/workspaces/&lt;工作区标识&gt;/）
     */
    public static File dataDir(String workspacePath) {
        return dataDirByKey(workspaceKey(workspacePath));
    }

    /**
     * 当前实际布局版本：1=旧无前缀（原地采用），2=新 ws- 前缀。
     * 由实际目录名判定（I2/I3）；目录不存在时返回将要新建的版本（2）。
     */
    public static int layoutVersion(String workspacePath) {
        String key = workspaceKey(workspacePath);
        if (key.startsWith("ws-")) {
            return 2;
        }
        return 1;
    }

    /**
     * 指定工作区的会话目录（~/.soloncode/workspaces/&lt;工作区标识&gt;/sessions/）。
     * <p>返回已 normalize 的绝对路径，调用方的 startsWith 防穿越校验依赖这一点。</p>
     */
    public static Path sessionsPath(String workspacePath) {
        return dataDir(workspacePath).toPath().resolve(DIR_SESSIONS).normalize();
    }

    /**
     * @see #sessionsPath(String)
     */
    public static File sessionsDir(String workspacePath) {
        return sessionsPath(workspacePath).toFile();
    }

    /**
     * 写入工作区路径标记文件。
     * <p>工作区标识由绝对路径哈希而来，工作区被改名或移动后标识会变（历史会话看起来"消失"）；
     * 该标记用于反查某个数据目录原属哪个工作区，便于人工修复。</p>
     */
    public static void markWorkspace(String workspacePath) {
        if (Utils.isEmpty(workspacePath)) {
            return;
        }

        try {
            File dataDir = dataDir(workspacePath);
            if (dataDir.isDirectory() == false && dataDir.mkdirs() == false) {
                return;
            }

            Path mark = dataDir.toPath().resolve(FILE_WORKSPACE_MARK);
            String content = Paths.get(workspacePath).toAbsolutePath().normalize().toString();

            //已存在且内容一致则不重复写（减少无意义 IO）
            if (Files.exists(mark)) {
                String old = new String(Files.readAllBytes(mark), StandardCharsets.UTF_8).trim();
                if (content.equals(old)) {
                    return;
                }
            }

            Files.write(mark, (content + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Throwable e) {
            //标记属于 best-effort，任何异常都不应影响工作区创建
            log.debug("Mark workspace failed: {}", workspacePath, e);
        }
    }

    /**
     * 迁移旧版本遗留在工作区目录下的会话数据（&lt;工作区&gt;/.soloncode/sessions/ → ~/.soloncode/workspaces/&lt;标识&gt;/sessions/）。
     * <p>
     * 会话是有价值数据（对话原文、快照、TODO、loop 任务），与日志不同，只能搬迁、不能删除。
     * 迁移策略（保守）：
     * <ul>
     *     <li>逐个会话目录搬迁；目标已存在同名会话则跳过该项（保留新位置为准，不覆盖）</li>
     *     <li>先尝试原子 move，跨文件系统失败时退化为递归复制 + 删除源</li>
     *     <li>源目录全部搬完则移除空目录；任何失败静默跳过，绝不影响工作区创建</li>
     * </ul>
     *
     * @param workspacePath 工作区目录
     * @return 实际迁移的会话数
     */
    public static int migrateLegacySessions(String workspacePath) {
        if (Utils.isEmpty(workspacePath)) {
            return 0;
        }

        try {
            File legacyDir = Paths.get(workspacePath, AgentFlags.getHarnessSessions()).toFile();
            if (legacyDir.isDirectory() == false) {
                return 0;
            }

            File targetDir = sessionsDir(workspacePath);

            //工作区恰好指向新数据目录时（极端情况）直接跳过，避免自搬自
            if (isSameDir(legacyDir, targetDir)) {
                return 0;
            }

            File[] items = legacyDir.listFiles();
            if (items == null || items.length == 0) {
                legacyDir.delete();
                return 0;
            }

            if (targetDir.isDirectory() == false && targetDir.mkdirs() == false) {
                return 0;
            }

            int moved = 0;
            int remained = 0;
            for (File item : items) {
                File dest = new File(targetDir, item.getName());
                if (dest.exists()) {
                    //新位置已有同名会话：以新位置为准，源保留待人工处理
                    remained++;
                    continue;
                }

                if (moveQuietly(item, dest)) {
                    moved++;
                } else {
                    remained++;
                }
            }

            //源目录已空则移除，避免残留空目录继续出现在项目树里
            if (remained == 0) {
                legacyDir.delete();
            }

            if (moved > 0) {
                log.info("Migrated {} legacy session(s) to: {}", moved, targetDir.getAbsolutePath());
            }
            if (remained > 0) {
                log.warn("{} legacy session item(s) remained at: {}", remained, legacyDir.getAbsolutePath());
            }

            return moved;
        } catch (Throwable e) {
            //迁移属于 best-effort，任何异常都不应影响工作区创建
            log.warn("Migrate legacy sessions failed: {}", workspacePath, e);
            return 0;
        }
    }

    private static boolean moveQuietly(File src, File dest) {
        try {
            Files.move(src.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Throwable e) {
            //跨文件系统等场景不支持原子 move，退化为复制 + 删除
        }

        try {
            Files.move(src.toPath(), dest.toPath());
            return true;
        } catch (Throwable e) {
            //继续退化
        }

        try {
            copyRecursively(src.toPath(), dest.toPath());
            deleteRecursively(src.toPath());
            return true;
        } catch (Throwable e) {
            log.debug("Move session item failed: {}", src.getAbsolutePath(), e);
            return false;
        }
    }

    private static void copyRecursively(Path src, Path dest) throws IOException {
        if (Files.isDirectory(src)) {
            if (Files.notExists(dest)) {
                Files.createDirectories(dest);
            }

            File[] children = src.toFile().listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursively(child.toPath(), dest.resolve(child.getName()));
                }
            }
        } else {
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 递归删除并返回真实成败（供启动迁移器计数与告警；异常上抛由调用方处理）
     */
    static boolean deleteRecursively(Path path) {
        deleteRecursively0(path);
        return Files.notExists(path);
    }

    private static void deleteRecursively0(Path path) {
        if (Files.isDirectory(path)) {
            File[] children = path.toFile().listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child.toPath());
                }
            }
        }

        //noinspection ResultOfMethodCallIgnored
        path.toFile().delete();
    }

    static boolean isSameDir(File a, File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (Exception e) {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        }
    }
}
