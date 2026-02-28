package org.noear.solon.ai.codecli.core.tool;

import java.lang.reflect.Field;

public class ShellUtil {
    public static void killTree(Process process) {
        if (process == null || !process.isAlive()) return;

        try {
            long pid = getPid(process);
            if (pid != -1) {
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    Runtime.getRuntime().exec("taskkill /F /T /PID " + pid);
                } else {
                    // Unix 下使用进程组杀法
                    Runtime.getRuntime().exec("pkill -P " + pid);
                    process.destroy();
                }
            }
        } catch (Exception e) {
            process.destroy();
        }
    }

    private static long getPid(Process process) {
        try {
            // 兼容 Unix
            if (process.getClass().getName().equals("java.lang.UNIXProcess")) {
                Field f = process.getClass().getDeclaredField("pid");
                f.setAccessible(true);
                return f.getLong(process);
            }
            // Windows 的 Process 实例反射较为复杂，通常建议直接 destroy
        } catch (Exception ignored) {}
        return -1;
    }
}