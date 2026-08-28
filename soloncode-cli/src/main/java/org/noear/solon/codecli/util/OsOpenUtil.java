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

import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.JavaUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;

/**
 * 系统级“打开”工具：用宿主机的默认程序打开目录、文件或链接。
 * <p>
 * 统一策略：优先 java.awt.Desktop（桌面环境可用时体验最好），不可用或失败时回退到系统命令：
 * <ul>
 *     <li>Windows：{@code explorer <path>} / {@code cmd /c start <url>}</li>
 *     <li>macOS：{@code open <path|url>}</li>
 *     <li>Linux 及其它：{@code xdg-open <path|url>}</li>
 * </ul>
 * 无桌面环境（纯 SSH/容器）时两条路径都会失败，调用方需自行提示用户。
 *
 * @author noear
 * @since 3.9.1
 */
public class OsOpenUtil {
    private static final Logger log = LoggerFactory.getLogger(OsOpenUtil.class);

    private OsOpenUtil() {
    }

    /**
     * 打开目录（在系统文件管理器中显示）
     *
     * @param dir 目标目录，必须已存在
     */
    public static void openDirectory(File dir) throws IOException {
        Assert.notNull(dir, "dir cannot be null");

        if (dir.isDirectory() == false) {
            throw new IOException("Directory not found: " + dir.getAbsolutePath());
        }

        openPath(dir);
    }

    /**
     * 打开文件（用系统默认关联程序）
     *
     * @param file 目标文件，必须已存在
     */
    public static void openFile(File file) throws IOException {
        Assert.notNull(file, "file cannot be null");

        if (file.isFile() == false) {
            throw new IOException("File not found: " + file.getAbsolutePath());
        }

        openPath(file);
    }

    /**
     * 打开链接（用系统默认浏览器）
     *
     * @param url 目标链接
     */
    public static void openBrowser(String url) throws IOException {
        Assert.notEmpty(url, "url cannot be empty");

        try {
            if (isDesktopActionSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI.create(url));
                return;
            }
        } catch (Throwable e) {
            //桌面 API 不可用（headless、缺少 DE 支持等），回退到系统命令
            log.debug("Desktop.browse failed, fallback to system command: {}", url, e);
        }

        if (JavaUtil.IS_WINDOWS) {
            //start 是 cmd 内建命令；& 在 cmd 中有特殊含义，需转义
            exec("cmd", "/c", "start", "", url.replace("&", "^&"));
        } else if (JavaUtil.IS_MAC) {
            exec("open", url);
        } else {
            exec("xdg-open", url);
        }
    }

    /**
     * 打开本地路径（目录或文件）
     */
    private static void openPath(File target) throws IOException {
        try {
            if (isDesktopActionSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(target);
                return;
            }
        } catch (Throwable e) {
            //桌面 API 不可用或打开失败，回退到系统命令
            log.debug("Desktop.open failed, fallback to system command: {}", target, e);
        }

        String path = target.getAbsolutePath();

        if (JavaUtil.IS_WINDOWS) {
            exec("explorer", path);
        } else if (JavaUtil.IS_MAC) {
            exec("open", path);
        } else {
            exec("xdg-open", path);
        }
    }

    /**
     * 判断桌面 API 是否可用（headless 环境下为 false）
     */
    private static boolean isDesktopActionSupported(Desktop.Action action) {
        return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(action);
    }

    /**
     * 启动外部进程（不等待结束，避免阻塞调用线程）
     */
    private static void exec(String... cmd) throws IOException {
        //注：Windows 下 explorer 即便成功也可能返回非 0 退出码，故一律不校验退出码
        new ProcessBuilder(cmd).start();
    }
}
