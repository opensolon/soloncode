package org.noear.solon.codecli;

import org.noear.solon.codecli.core.AgentProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Solon Code 配置加载器
 * <p>
 * 三级配置优先级: 当前目录 .soloncode/config.yml > 全局 ~/.soloncode/config.yml > jar 内置 config.yml
 *
 * @author noear
 * @since 2.0
 */
public class ConfigLoader {

    private static final String USER_HOME = AgentProperties.getUserHome();

    /**
     * 加载最优配置文件路径
     * <p>
     * 优先级: 当前目录 > 全局 HOME > null(使用 jar 内置)
     *
     * @return 配置文件路径，如果没有外部配置则返回 null
     */
    public static Path loadConfig() {
        // 1. 当前目录的配置（项目级）
        Path currentDirConfig = getWorkspaceConfigPath();
        if (Files.exists(currentDirConfig)) {
            return currentDirConfig;
        }

        // 2. 全局配置
        Path globalConfig = getGlobalConfigPath();
        if (Files.exists(globalConfig)) {
            return globalConfig;
        }

        // 3. 没有外部配置，返回 null（Solon 会使用 classpath 内置的 config.yml）
        return null;
    }

    /**
     * 获取全局配置路径
     *
     * @return ~/.soloncode/config.yml
     */
    public static Path getGlobalConfigPath() {
        return Paths.get(USER_HOME, ".soloncode", "config.yml");
    }

    /**
     * 获取当前工作区配置路径
     */
    public static Path getWorkspaceConfigPath() {
        return Paths.get(AgentProperties.getUserDir(), ".soloncode", "config.yml");
    }

    /**
     * 获取全局配置目录
     *
     * @return ~/.soloncode/
     */
    public static Path getGlobalConfigDir() {
        return Paths.get(USER_HOME, ".soloncode");
    }

    /**
     * 确保全局配置目录存在
     */
    public static void ensureGlobalConfigDir() {
        Path dir = getGlobalConfigDir();
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (Exception e) {
                throw new RuntimeException("无法创建全局配置目录: " + dir, e);
            }
        }
    }

}
