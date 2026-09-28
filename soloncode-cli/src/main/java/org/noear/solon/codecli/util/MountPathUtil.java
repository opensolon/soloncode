package org.noear.solon.codecli.util;

import org.noear.solon.codecli.config.AgentFlags;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 将挂载配置中的显示路径解析为 FileMountSource 可用的真实路径。 */
public final class MountPathUtil {
    private MountPathUtil() {
    }

    public static Path resolve(String configuredPath, String workspacePath) {
        String raw = configuredPath == null ? "" : configuredPath.trim();
        String home = AgentFlags.getUserHome();
        String work = workspacePath == null || workspacePath.isEmpty()
                ? System.getProperty("user.dir") : workspacePath;

        if (raw.equals("~")) {
            raw = home;
        } else if (raw.startsWith("~/") || raw.startsWith("~\\")) {
            raw = home + raw.substring(1);
        } else if (raw.equals(".")) {
            raw = work;
        } else if (raw.startsWith("./") || raw.startsWith(".\\")) {
            raw = work + raw.substring(1);
        }
        return Paths.get(raw).toAbsolutePath().normalize();
    }
}
