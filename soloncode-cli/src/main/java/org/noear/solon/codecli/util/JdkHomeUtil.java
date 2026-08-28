package org.noear.solon.codecli.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JDK 安装位置探测。
 *
 * <p>用途：某些工具进程（如 jdtls 要求 JDK 21+）对 JDK 版本有下限要求，
 * 而 soloncode 自身可能跑在 JDK 8 上；子进程默认继承父进程的 JAVA_HOME，
 * 会直接因版本不满足而启动失败。这里负责在本机已安装的 JDK 中挑一个满足下限的。
 *
 * <p>实现上只做目录扫描 + 读取 {@code release} 文件，不 fork 任何进程，结果进程级缓存。
 *
 * @author noear
 */
public final class JdkHomeUtil {
    static final Logger LOG = LoggerFactory.getLogger(JdkHomeUtil.class);

    /**
     * 缓存未命中时的占位（ConcurrentHashMap 不允许 null 值）
     */
    private static final String NONE = "";

    private static final Map<Integer, String> CACHE = new ConcurrentHashMap<>();

    private JdkHomeUtil() {
    }

    /**
     * 查找一个主版本号不低于 minMajor 的 JDK 安装目录。
     *
     * <p>优先返回当前 JAVA_HOME（若已满足），其次返回本机已安装的最高版本。
     *
     * @param minMajor 最低主版本号（如 21）
     * @return JDK 安装目录；找不到返回 null
     */
    public static String findJavaHomeAtLeast(int minMajor) {
        String cached = CACHE.computeIfAbsent(minMajor, k -> {
            String found = doFind(k);
            return found == null ? NONE : found;
        });

        return NONE.equals(cached) ? null : cached;
    }

    /**
     * 当前 JAVA_HOME 是否已满足版本下限
     */
    public static boolean currentJavaHomeSatisfies(int minMajor) {
        String home = System.getenv("JAVA_HOME");
        return home != null && !home.isEmpty() && majorOf(Paths.get(home)) >= minMajor;
    }

    private static String doFind(int minMajor) {
        // 1. 当前 JAVA_HOME 已满足：直接沿用，避免无谓切换
        String envHome = System.getenv("JAVA_HOME");
        if (envHome != null && !envHome.isEmpty()) {
            Path p = Paths.get(envHome);
            if (majorOf(p) >= minMajor) {
                return normalize(p);
            }
        }

        // 2. 当前进程自身的 JDK
        String selfHome = System.getProperty("java.home");
        if (selfHome != null && !selfHome.isEmpty()) {
            Path p = Paths.get(selfHome);
            if (majorOf(p) >= minMajor) {
                return normalize(p);
            }
        }

        // 3. 扫描常见安装位置，取满足下限的最高版本
        String best = null;
        int bestMajor = -1;

        for (Path candidate : candidates()) {
            int major = majorOf(candidate);
            if (major >= minMajor && major > bestMajor) {
                bestMajor = major;
                best = normalize(candidate);
            }
        }

        if (best != null) {
            LOG.debug("[JDK] resolved java home for major>={}: {} (major={})", minMajor, best, bestMajor);
        }

        return best;
    }

    /**
     * 枚举候选 JDK 目录（含 macOS 的 Contents/Home 展开）
     */
    private static List<Path> candidates() {
        List<Path> result = new ArrayList<>();
        String userHome = System.getProperty("user.home", "");

        List<String> roots = new ArrayList<>();
        roots.add("/Library/Java/JavaVirtualMachines");
        roots.add("/usr/lib/jvm");
        roots.add("/opt/java");
        if (!userHome.isEmpty()) {
            roots.add(userHome + "/Library/Java/JavaVirtualMachines");
            roots.add(userHome + "/.sdkman/candidates/java");
            roots.add(userHome + "/.jdks");
        }
        roots.add("C:\\Program Files\\Java");
        roots.add("C:\\Program Files\\Eclipse Adoptium");

        for (String root : roots) {
            File dir = new File(root);
            File[] children = dir.listFiles();
            if (children == null) {
                continue;
            }

            for (File child : children) {
                if (child.isDirectory() == false) {
                    continue;
                }

                // macOS 形如 jdk-21.jdk/Contents/Home
                File macHome = new File(child, "Contents/Home");
                result.add((macHome.isDirectory() ? macHome : child).toPath());
            }
        }

        return result;
    }

    /**
     * 读取 JDK 主版本号；不是有效 JDK 目录时返回 -1
     */
    static int majorOf(Path javaHome) {
        if (javaHome == null || Files.isDirectory(javaHome) == false) {
            return -1;
        }

        // 必须真的能执行 java，否则只是个空壳目录
        if (Files.isRegularFile(javaHome.resolve("bin/java")) == false
                && Files.isRegularFile(javaHome.resolve("bin/java.exe")) == false) {
            return -1;
        }

        Path release = javaHome.resolve("release");
        if (Files.isRegularFile(release)) {
            try {
                for (String line : Files.readAllLines(release, StandardCharsets.UTF_8)) {
                    if (line.startsWith("JAVA_VERSION=")) {
                        String raw = line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                        return parseMajor(raw);
                    }
                }
            } catch (Exception e) {
                // 读不到就退回目录名推断
                LOG.trace("[JDK] read release failed: {}", release, e);
            }
        }

        // 兜底：从路径名里找版本（如 jdk-21.jdk、openjdk-23、java-17-openjdk）
        return parseMajorFromName(javaHome.toString());
    }

    /**
     * 解析版本串主版本号：{@code "1.8.0_181" -> 8}、{@code "21.0.1" -> 21}
     */
    static int parseMajor(String version) {
        if (version == null || version.isEmpty()) {
            return -1;
        }

        String[] parts = version.split("[._\\-+]");
        try {
            if ("1".equals(parts[0]) && parts.length > 1) {
                return Integer.parseInt(parts[1]);
            }
            return Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static int parseMajorFromName(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:jdk|java|openjdk|graalvm[a-z\\-]*)-?(\\d+)")
                .matcher(name.toLowerCase());

        int last = -1;
        while (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                // 1.8 风格目录名（jdk1.8.0_181）会匹配到 1，需要二次修正
                if (v == 1) {
                    continue;
                }
                last = v;
            } catch (NumberFormatException ignored) {
            }
        }

        return last;
    }

    /**
     * 仅供测试：清空缓存
     */
    static void clearCache() {
        CACHE.clear();
    }

    private static String normalize(Path path) {
        try {
            return path.toRealPath().toString();
        } catch (Exception e) {
            return path.toAbsolutePath().toString();
        }
    }
}
