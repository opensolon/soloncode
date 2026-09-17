package org.noear.solon.codecli.portal;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 文件面板的统一忽略规则 —— 「树里能看见的目录，必须也在监听范围内」。
 *
 * <p>此前展示侧（FileService）与监听侧（FileWatchService）各自维护一份排除表，
 * 两份不一致会造成确定性的「树里看得见、但改动永远不推送」：</p>
 * <ul>
 *   <li>{@code venv}/{@code vendor}：展示侧不排除，监听侧排除</li>
 *   <li>{@code .uploads}：展示侧白名单放行，监听侧按「点号开头」一律跳过</li>
 * </ul>
 *
 * <p>因此两侧统一收口到本类：排除表取并集（依赖目录一律不展示也不监听），
 * 隐藏目录白名单两侧同时放行。</p>
 *
 * <p>取并集意味着 {@code venv}/{@code vendor} 与 {@code node_modules} 同等对待（不展示）。
 * 若项目确实需要浏览这类目录，用系统属性放行即可（放行后既展示也监听，不破坏不变式）：</p>
 * <pre>
 *   -Dsoloncode.filer.excludeDirs.allow=venv,vendor
 *   -Dsoloncode.filer.excludeDirs.extra=dist,coverage
 * </pre>
 *
 * @author noear 2026-9-4
 * @see FileWatchService
 */
public final class FilerIgnoreRules {

    /** 系统属性：从默认排除表中放行的目录名（逗号分隔），放行后既展示也监听 */
    public static final String PROP_ALLOW = "soloncode.filer.excludeDirs.allow";

    /** 系统属性：追加排除的目录名（逗号分隔），两侧同时生效 */
    public static final String PROP_EXTRA = "soloncode.filer.excludeDirs.extra";

    /** 既不展示、也不监听的目录名（构建产物、依赖目录、IDE 与工具元数据） */
    private static final String[] DEFAULT_EXCLUDED_DIRS = {
            // 项目元数据 & IDE
            ".soloncode", ".claude", ".opencode",
            ".idea", ".vscode", ".settings",
            // 版本控制 & 构建工具
            ".git", ".gradle", ".mvn",
            // 运行时缓存
            ".pytest_cache", "__pycache__",
            ".DS_Store",
            // 依赖目录
            "node_modules", "venv", "vendor",
            // 构建输出
            "target", "build"
    };

    /** 隐藏目录白名单：以点号开头，但需要展示且需要监听 */
    private static final String[] DEFAULT_VISIBLE_HIDDEN_DIRS = {
            ".uploads"
    };

    private static final Set<String> EXCLUDED_DIRS;
    private static final Set<String> VISIBLE_HIDDEN_DIRS;

    static {
        Set<String> allow = parseNames(System.getProperty(PROP_ALLOW));
        Set<String> extra = parseNames(System.getProperty(PROP_EXTRA));

        EXCLUDED_DIRS = resolveExcluded(allow, extra);
        VISIBLE_HIDDEN_DIRS = resolveVisibleHidden(allow, EXCLUDED_DIRS);
    }

    /** 排除表 = 默认表 - allow + extra（包可见，供单测直接验证配置语义） */
    static Set<String> resolveExcluded(Set<String> allow, Set<String> extra) {
        Set<String> excluded = new HashSet<>(Arrays.asList(DEFAULT_EXCLUDED_DIRS));
        excluded.removeAll(allow);
        excluded.addAll(extra);
        return Collections.unmodifiableSet(excluded);
    }

    /**
     * 隐藏白名单 = 默认白名单 + allow 中点号开头的项 - 排除表
     *
     * <p>放行以点号开头的目录（如 {@code .venv}）时，仅从排除表移除不够 ——
     * 还需进隐藏白名单，否则仍会被「点号开头一律忽略」规则拦下。</p>
     */
    static Set<String> resolveVisibleHidden(Set<String> allow, Set<String> excluded) {
        Set<String> visibleHidden = new HashSet<>(Arrays.asList(DEFAULT_VISIBLE_HIDDEN_DIRS));
        for (String name : allow) {
            if (name.startsWith(".")) visibleHidden.add(name);
        }
        visibleHidden.removeAll(excluded);
        return Collections.unmodifiableSet(visibleHidden);
    }

    static Set<String> parseNames(String csv) {
        if (csv == null || csv.trim().isEmpty()) return Collections.emptySet();

        Set<String> out = new HashSet<>();
        for (String item : csv.split(",")) {
            String name = item.trim();
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }

    private FilerIgnoreRules() {
    }

    /**
     * 名称是否忽略（不区分节点类型，用于监听侧：删除事件拿不到类型）
     *
     * @param name 单个路径段名称
     */
    public static boolean isIgnoredName(String name) {
        return isIgnoredName(name, true);
    }

    /**
     * 名称是否忽略
     *
     * @param name      单个路径段名称
     * @param directory 是否目录节点（隐藏白名单仅对目录生效）
     */
    public static boolean isIgnoredName(String name, boolean directory) {
        if (name == null || name.isEmpty()) return false;
        if (EXCLUDED_DIRS.contains(name)) return true;
        if (directory && VISIBLE_HIDDEN_DIRS.contains(name)) return false;
        return name.startsWith(".");
    }

    /**
     * 相对路径是否忽略：任一路径段命中忽略规则即忽略
     *
     * <p>中间段必然是目录，末段类型未知（删除后无法 stat），按目录规则判断，
     * 使 {@code .uploads} 这类白名单目录自身的创建/删除事件也能通过。</p>
     *
     * @param relative 相对于根的路径
     */
    public static boolean isIgnoredPath(Path relative) {
        if (relative == null) return false;
        for (Path segment : relative) {
            if (isIgnoredName(segment.toString())) return true;
        }
        return false;
    }
}
