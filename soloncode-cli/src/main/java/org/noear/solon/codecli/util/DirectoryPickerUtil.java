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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 系统级“选择目录”工具：在宿主机桌面弹出系统原生目录选择框，返回用户选中的绝对路径。
 * <p>
 * 优先使用操作系统提供的选择器：macOS 使用 Finder（osascript choose folder），Windows 仅使用 Shell 的
 * {@code IFileOpenDialog + FOS_PICKFOLDERS}（Win10/Win11 资源管理器同款现代目录对话框），Linux 依次使用
 * Zenity、KDialog。Windows 原生选择器失败时直接报错，不回退到 Swing，避免出现非系统样式组件；其它
 * 平台在原生能力不可用或执行失败时，回退到 {@link DirectoryPickerSubprocess}。
 * <p>
 * <b>Windows 弹窗为何不会沉到浏览器后面：</b>脚本在创建任何辅助窗口前捕获当前前台根窗口（通常就是
 * 发起点击的浏览器），并把它作为 {@code IModalWindow.Show} 的真实 owner；同时通过
 * {@code IFileDialogEvents + IOleWindow} 获取真实对话框 HWND，在对话框存活期间持续维持 topmost z-order。
 * <p>
 * <b>为何 Swing 兜底要用子进程：</b>Solon 框架类初始化时会把 {@code java.awt.headless}
 * 默认置为 true，CLI 宿主 JVM 内 AWT 因此永远是 headless、无法直接弹框；且该状态在 Toolkit
 * 首次加载后不可逆。故在子 JVM 中以 {@code -Djava.awt.headless=false} 运行 Swing 兜底选择器。
 * 子进程结果按协议写 stdout 后退出：
 * <ul>
 *     <li>{@code PICK <abs-path>}：用户选中目录</li>
 *     <li>{@code PICK_NONE}：用户取消</li>
 * </ul>
 * <p>
 * <b>类路径定位：</b>只用于 Swing 兜底。优先系统属性 {@code soloncode.pick.jar}（显式指定）；
 * 开发模式（classes 目录）直接用该目录；fat jar 模式下 classpath 不含 BOOT-INF，
 * 故将子进程类从自身 jar 抽取到临时目录后再启动。
 *
 * @author noear
 * @since 3.9.1
 */
public class DirectoryPickerUtil {
    /**
     * 子进程最长存活（用户慢慢选也够；超时强制销毁）
     */
    public static final long DEFAULT_TIMEOUT_MS = 300_000L;

    /**
     * 子进程主类的全限定名（与 {@link DirectoryPickerSubprocess} 保持一致）
     */
    static final String SUBPROCESS_CLASS = "org.noear.solon.codecli.util.DirectoryPickerSubprocess";

    /**
     * 子进程类在 jar 内的相对路径（fat jar 中带 BOOT-INF/classes 前缀，故用 endsWith 匹配）
     */
    static final String SUBPROCESS_ENTRY = "org/noear/solon/codecli/util/DirectoryPickerSubprocess.class";

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(DirectoryPickerUtil.class);

    private static void warn(String msg) {
        try {
            LOG.warn("[DirectoryPicker] " + msg);
        } catch (Throwable ignored) {
            // 日志系统不可用时静默
        }
    }

    private DirectoryPickerUtil() {
    }

    /**
     * 弹出目录选择框（默认超时，起始目录 user.home）
     *
     * @param title 对话框标题
     * @return 选中的目录绝对路径；用户取消或超时返回 null
     * @throws IOException 子进程启动或通信失败
     */
    public static String pick(String title) throws IOException {
        return pick(title, DEFAULT_TIMEOUT_MS, null);
    }

    /**
     * 弹出目录选择框，阻塞直到用户选择/取消或超时
     *
     * @param title     对话框标题（null 视为空）
     * @param timeoutMs 最长等待毫秒数；超时销毁子进程并返回 null
     * @param startDir  起始目录（null 或不存在时由子进程回落 user.home）
     * @return 选中的目录绝对路径；用户取消或超时返回 null
     * @throws IOException 子进程启动或通信失败
     */
    public static String pick(String title, long timeoutMs, File startDir) throws IOException {
        String osName = System.getProperty("os.name", "");
        try {
            NativePickResult nativeResult = pickNative(osName, title, timeoutMs, startDir);
            if (nativeResult.supported) {
                return nativeResult.path;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            if (isWindows(osName.toLowerCase(Locale.ROOT))) {
                throw e;
            }
            warn("native picker failed, falling back to Swing: " + e);
        }

        return pickWithSwing(title, timeoutMs, startDir);
    }

    /**
     * 尝试系统原生选择器。返回 supported=false 时由调用方回退 Swing。
     */
    static NativePickResult pickNative(String osName, String title, long timeoutMs, File startDir)
            throws IOException, InterruptedException {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        File effectiveStartDir = effectiveStartDir(startDir);
        if (isMac(os)) {
            List<String> cmd = macCommand(title, effectiveStartDir);
            CommandResult result = runCommand(cmd, timeoutMs);
            if (result.timedOut || isMacCancel(result)) {
                return NativePickResult.handled(null);
            }
            if (result.exitCode != 0) {
                throw commandFailure("macOS", result);
            }
            return NativePickResult.handled(outputPath(result.output));
        }

        if (isWindows(os)) {
            List<String> cmd = windowsCommand(title, effectiveStartDir);
            CommandResult result = runCommand(cmd, timeoutMs);
            if (result.timedOut) {
                return NativePickResult.handled(null);
            }
            if (result.exitCode != 0) {
                throw commandFailure("Windows", result);
            }
            return NativePickResult.handled(protocolPath(result.output));
        }

        if (os.contains("linux")) {
            List<String> zenity = new ArrayList<String>();
            zenity.add("zenity");
            zenity.add("--file-selection");
            zenity.add("--directory");
            zenity.add("--title=" + safeTitle(title));
            zenity.add("--filename=" + effectiveStartDir.getAbsolutePath() + File.separator);
            try {
                CommandResult result = runCommand(zenity, timeoutMs);
                if (result.timedOut || result.exitCode == 1) {
                    return NativePickResult.handled(null);
                }
                if (result.exitCode != 0) {
                    throw commandFailure("Zenity", result);
                }
                return NativePickResult.handled(outputPath(result.output));
            } catch (IOException e) {
                if (!isCommandMissing(e)) {
                    throw e;
                }
            }

            List<String> kdialog = new ArrayList<String>();
            kdialog.add("kdialog");
            kdialog.add("--getexistingdirectory");
            kdialog.add(effectiveStartDir.getAbsolutePath());
            kdialog.add("--title");
            kdialog.add(safeTitle(title));
            try {
                CommandResult result = runCommand(kdialog, timeoutMs);
                if (result.timedOut || result.exitCode == 1) {
                    return NativePickResult.handled(null);
                }
                if (result.exitCode != 0) {
                    throw commandFailure("KDialog", result);
                }
                return NativePickResult.handled(outputPath(result.output));
            } catch (IOException e) {
                if (isCommandMissing(e)) {
                    return NativePickResult.unsupported();
                }
                throw e;
            }
        }

        return NativePickResult.unsupported();
    }

    private static String pickWithSwing(String title, long timeoutMs, File startDir) throws IOException {
        String cp = resolveClasspath();
        if (cp == null) {
            throw new IOException("Cannot locate directory-picker classpath (try -Dsoloncode.pick.jar=<path>)");
        }

        List<String> cmd = new ArrayList<String>();
        cmd.add(javaBin());
        cmd.add("-Djava.awt.headless=false");
        cmd.add("-cp");
        cmd.add(cp);
        cmd.add(SUBPROCESS_CLASS);
        cmd.add(title == null ? "" : title);
        cmd.add(startDir != null && startDir.isDirectory() ? startDir.getAbsolutePath() : "");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        final Process p = pb.start();

        final StringBuilder out = new StringBuilder();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (out) {
                            out.append(line).append('\n');
                        }
                    }
                } catch (IOException ignored) {
                    // 进程被销毁时流关闭，正常
                }
            }
        }, "soloncode-pick-reader");
        reader.setDaemon(true);
        reader.start();

        try {
            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                // 超时：用户可能走开了，杀掉子进程（对话框随之消失），本次视为取消
                p.destroyForcibly();
                return null;
            }
            reader.join(3000);

            String text;
            synchronized (out) {
                text = out.toString();
            }
            for (String line : text.split("\n")) {
                if (line.startsWith("PICK ")) {
                    String path = line.substring("PICK ".length());
                    if (path.isEmpty()) {
                        throw new IOException("Directory picker subprocess returned an empty path");
                    }
                    return path;
                }
                if (line.equals("PICK_NONE")) {
                    return null;
                }
            }
            // 未输出协议行即退出：视为失败，透传子进程输出便于诊断
            throw new IOException("Directory picker subprocess failed (exit=" + p.exitValue() + "): " + text.trim());
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static List<String> macCommand(String title, File startDir) {
        List<String> cmd = new ArrayList<String>();
        cmd.add("osascript");
        cmd.add("-e");
        cmd.add("set selectedFolder to choose folder with prompt \"" + escapeAppleScript(title)
                + "\" default location (POSIX file \"" + escapeAppleScript(startDir.getAbsolutePath()) + "\")");
        cmd.add("-e");
        cmd.add("POSIX path of selectedFolder");
        return cmd;
    }

    /**
     * Windows 目录选择器脚本（包内资源）。
     * <p>
     * 使用临时 ps1 文件而不是 {@code -EncodedCommand}，避免脚本增长后撞上 Windows 32767 字符的
     * CreateProcess 命令行上限，也让测试可以真实执行 PowerShell/C# 编译探针。
     */
    static final String WINDOWS_PICKER_SCRIPT = loadWindowsPickerScript();
    private static volatile File windowsPickerScriptFile;

    private static String loadWindowsPickerScript() {
        InputStream in = DirectoryPickerUtil.class.getResourceAsStream("win-folder-picker.ps1");
        if (in == null) {
            warn("missing resource win-folder-picker.ps1; modern Windows picker is unavailable");
            return null;
        }

        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int count;
            while ((count = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, count);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            warn("read win-folder-picker.ps1 failed; modern Windows picker is unavailable: " + e);
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // 读取资源失败已在上面处理
            }
        }
    }

    /**
     * Windows 选择器命令。参数由 {@link ProcessBuilder} 直接传递，不经过 shell 字符串拼接。
     *
     * @param title    对话框标题
     * @param startDir 起始目录（null 表示交给系统默认位置）
     */
    static List<String> windowsCommand(String title, File startDir) throws IOException {
        List<String> cmd = windowsPowerShellCommand();
        cmd.add("-SoloncodeTitle");
        cmd.add(safeTitle(title));
        cmd.add("-SoloncodeStartDir");
        cmd.add(startDir == null ? "" : startDir.getAbsolutePath());
        return cmd;
    }

    static List<String> windowsProbeCommand() throws IOException {
        List<String> cmd = windowsPowerShellCommand();
        cmd.add("-SoloncodeProbe");
        return cmd;
    }

    static List<String> windowsSmokeCommand() throws IOException {
        List<String> cmd = windowsPowerShellCommand();
        cmd.add("-SoloncodeTitle");
        cmd.add("SolonCode directory picker smoke test");
        cmd.add("-SoloncodeSmoke");
        return cmd;
    }

    static String probeWindowsPicker(long timeoutMs) throws IOException, InterruptedException {
        CommandResult result = runCommand(windowsProbeCommand(), timeoutMs);
        if (result.timedOut) {
            throw new IOException("Windows directory picker probe timed out");
        }
        if (result.exitCode != 0 || !result.output.contains("PICK_PROBE_OK")) {
            throw commandFailure("Windows probe", result);
        }
        return result.output;
    }

    static String smokeWindowsPicker(long timeoutMs) throws IOException, InterruptedException {
        CommandResult result = runCommand(windowsSmokeCommand(), timeoutMs);
        if (result.timedOut) {
            throw new IOException("Windows directory picker smoke test timed out");
        }
        if (result.exitCode != 0 || !result.output.contains("PICK_SMOKE_OK")) {
            throw commandFailure("Windows smoke test", result);
        }
        return result.output;
    }

    private static List<String> windowsPowerShellCommand() throws IOException {
        File script = windowsPickerScriptFile();
        List<String> cmd = new ArrayList<String>();
        cmd.add("powershell.exe");
        cmd.add("-NoProfile");
        cmd.add("-NonInteractive");
        cmd.add("-ExecutionPolicy");
        cmd.add("Bypass");
        cmd.add("-STA");
        cmd.add("-File");
        cmd.add(script.getAbsolutePath());
        return cmd;
    }

    private static File windowsPickerScriptFile() throws IOException {
        if (WINDOWS_PICKER_SCRIPT == null || WINDOWS_PICKER_SCRIPT.isEmpty()) {
            throw new IOException("Modern Windows directory picker resource is unavailable");
        }
        File current = windowsPickerScriptFile;
        if (current != null && current.isFile()) {
            return current;
        }
        synchronized (DirectoryPickerUtil.class) {
            current = windowsPickerScriptFile;
            if (current == null || !current.isFile()) {
                Path path = Files.createTempFile("soloncode-win-folder-picker-", ".ps1");
                Files.write(path, WINDOWS_PICKER_SCRIPT.getBytes(StandardCharsets.UTF_8));
                current = path.toFile();
                current.deleteOnExit();
                windowsPickerScriptFile = current;
            }
            return current;
        }
    }

    private static File effectiveStartDir(File startDir) {
        if (startDir != null && startDir.isDirectory()) {
            return startDir;
        }
        File home = new File(System.getProperty("user.home", "."));
        return home.isDirectory() ? home : new File(".");
    }

    private static boolean isMac(String os) {
        return os.contains("mac") || os.contains("darwin");
    }

    private static boolean isWindows(String os) {
        return os.startsWith("windows");
    }

    private static String safeTitle(String title) {
        return title == null || title.isEmpty() ? "Select Workspace Directory" : title;
    }

    private static String escapeAppleScript(String value) {
        return safeTitle(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean isMacCancel(CommandResult result) {
        return result.exitCode == 1
                && (result.output.toLowerCase(Locale.ROOT).contains("user canceled") || result.output.contains("-128"));
    }

    private static boolean isCommandMissing(IOException e) {
        String message = e.getMessage();
        return message != null && (message.contains("error=2") || message.contains("No such file"));
    }

    private static IOException commandFailure(String picker, CommandResult result) {
        return new IOException(picker + " directory picker failed (exit=" + result.exitCode + "): "
                + stripTrailingLineBreaks(result.output));
    }

    static String outputPath(String output) {
        String path = stripTrailingLineBreaks(output);
        return path.isEmpty() ? null : path;
    }

    static String protocolPath(String output) throws IOException {
        for (String line : output.split("\\r?\\n")) {
            if (line.startsWith("PICK ")) {
                String path = line.substring("PICK ".length());
                if (path.isEmpty()) {
                    throw new IOException("Native directory picker returned an empty path");
                }
                return path;
            }
            if (line.equals("PICK_NONE")) {
                return null;
            }
        }
        throw new IOException("Native directory picker returned no protocol result: "
                + stripTrailingLineBreaks(output));
    }

    private static String stripTrailingLineBreaks(String value) {
        int end = value.length();
        while (end > 0) {
            char c = value.charAt(end - 1);
            if (c != '\r' && c != '\n') {
                break;
            }
            end--;
        }
        return value.substring(0, end);
    }

    private static CommandResult runCommand(List<String> command, long timeoutMs)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        final Process process = pb.start();
        final StringBuilder output = new StringBuilder();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                        }
                    }
                } catch (IOException ignored) {
                    // 超时销毁进程时流关闭，正常
                }
            }
        }, "soloncode-native-pick-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }
        if (!finished) {
            process.destroyForcibly();
            reader.join(3000L);
            return new CommandResult(-1, snapshot(output), true);
        }
        reader.join(3000L);
        return new CommandResult(process.exitValue(), snapshot(output), false);
    }

    private static String snapshot(StringBuilder output) {
        synchronized (output) {
            return output.toString();
        }
    }

    static final class NativePickResult {
        final boolean supported;
        final String path;

        private NativePickResult(boolean supported, String path) {
            this.supported = supported;
            this.path = path;
        }

        static NativePickResult handled(String path) {
            return new NativePickResult(true, path);
        }

        static NativePickResult unsupported() {
            return new NativePickResult(false, null);
        }
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;
        final boolean timedOut;

        private CommandResult(int exitCode, String output, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = output;
            this.timedOut = timedOut;
        }
    }

    /**
     * 判断当前宿主是否具备可交互桌面。
     * <p>
     * 不能使用 {@code GraphicsEnvironment.isHeadless()}：Solon 会将宿主 JVM 标为 headless，
     * 而真正的选择器运行在系统进程或独立 Java 子进程中。这里采用保守策略：
     * Linux/Unix 必须有 DISPLAY 或 WAYLAND_DISPLAY；SSH 会话与 Windows Service 会话直接禁用。
     * 可用 {@code -Dsoloncode.directoryPicker.enabled=true|false} 显式覆盖自动判断。
     */
    public static boolean isAvailable() {
        return isAvailable(System.getProperty("os.name", ""), System.getenv(),
                System.getProperty("soloncode.directoryPicker.enabled"));
    }

    /**
     * 可测试的桌面能力判断实现。
     */
    static boolean isAvailable(String osName, java.util.Map<String, String> env, String enabledOverride) {
        if (enabledOverride != null && !enabledOverride.trim().isEmpty()) {
            return Boolean.parseBoolean(enabledOverride.trim());
        }

        if (env == null) {
            return false;
        }

        // Web 可能经 SSH 端口转发访问；此时弹窗会出现在服务器端，必须禁用。
        if (hasEnv(env, "SSH_CONNECTION") || hasEnv(env, "SSH_CLIENT") || hasEnv(env, "SSH_TTY")) {
            return false;
        }

        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (isWindows(os)) {
            // Windows 服务运行在隔离桌面，不能向登录用户显示交互窗口。
            String sessionName = env.get("SESSIONNAME");
            return sessionName == null || !"services".equalsIgnoreCase(sessionName.trim());
        }
        if (isMac(os)) {
            // macOS 没有 DISPLAY；非 SSH 的用户会话交给 osascript 做最终确认。
            return true;
        }

        // Linux/其它 Unix 的本地图形会话至少应暴露一种显示协议。
        return hasEnv(env, "DISPLAY") || hasEnv(env, "WAYLAND_DISPLAY");
    }

    private static boolean hasEnv(java.util.Map<String, String> env, String name) {
        String value = env.get(name);
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 当前 JVM 的 java 可执行文件绝对路径
     */
    private static String javaBin() throws IOException {
        File javaHome = new File(System.getProperty("java.home"));
        String name = isWindows(System.getProperty("os.name", "").toLowerCase(Locale.ROOT)) ? "java.exe" : "java";
        File bin = new File(javaHome, "bin" + File.separator + name);
        if (bin.isFile()) {
            return bin.getAbsolutePath();
        }
        throw new IOException("Cannot locate java executable under " + javaHome.getAbsolutePath());
    }

    /**
     * 解析子进程类路径：
     * <ol>
     *     <li>系统属性 {@code soloncode.pick.jar} 指定的文件/jar</li>
     *     <li>当前类 code source 为目录（开发模式）→ 该目录即 classpath 根</li>
     *     <li>当前类 code source 为 fat jar → 从中抽出子进程类到临时目录</li>
     * </ol>
     *
     * @return 可作 {@code -cp} 的路径；定位失败返回 null
     */
    static String resolveClasspath() {
        String explicit = System.getProperty("soloncode.pick.jar");
        if (explicit != null) {
            File f = new File(explicit);
            if (f.exists()) {
                return f.getAbsolutePath();
            }
        }

        File codeSource;
        try {
            codeSource = new File(DirectoryPickerUtil.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (Throwable e) {
            codeSource = null;
        }
        // fat-jar 自定义 ClassLoader 下 codeSource 可能拿不到：改用类资源 URL 解析（jar:file:/..!/path）
        if (codeSource == null || !codeSource.exists()) {
            codeSource = locateSelfJarByResource();
        }
        if (codeSource == null || !codeSource.exists()) {
            warn("resolveClasspath: codeSource and resource-locate both failed");
            return null;
        }
        if (codeSource.isDirectory()) {
            // 开发模式：classes 目录本身即 classpath 根
            return codeSource.getAbsolutePath();
        }

        // fat jar 模式：jar 不能直接作 classpath（类在 BOOT-INF/classes 下），抽取到临时目录
        ZipFile zip = null;
        try {
            zip = new ZipFile(codeSource);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(SUBPROCESS_ENTRY)) {
                    continue;
                }
                Path tmp = Files.createTempDirectory("soloncode-pick");
                tmp.toFile().deleteOnExit();
                Path target = tmp.resolve(SUBPROCESS_ENTRY);
                Files.createDirectories(target.getParent());
                InputStream in = zip.getInputStream(entry);
                try {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    in.close();
                }
                return tmp.toAbsolutePath().toString();
            }
            warn("resolveClasspath: no entry ends with " + SUBPROCESS_ENTRY + " in " + codeSource.getAbsolutePath());
            return null;
        } catch (Exception e) {
            warn("resolveClasspath: scan jar failed: " + e);
            return null;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 通过类资源 URL 反解自身 jar 路径：{@code jar:file:/path/to.jar!/BOOT-INF/classes/…}
     * <p>
     * fat jar 中类资源的真实条目路径带 {@code BOOT-INF/classes/} 前缀（2026-09-05 线上报障：
     * 只查根路径导致定位失败、对话框从未弹出），两个位置都尝试。
     */
    private static File locateSelfJarByResource() {
        for (String prefix : new String[]{"", "BOOT-INF/classes/"}) {
            try {
                java.net.URL url = DirectoryPickerUtil.class.getResource(
                        "/" + prefix + SUBPROCESS_ENTRY);
                if (url == null || !"jar".equals(url.getProtocol())) {
                    continue;
                }
                String spec = url.toString();
                int bang = spec.indexOf("!/");
                if (bang <= 0) {
                    continue;
                }
                // jar:file:/path → 去掉 jar:file: 前缀后还原为文件路径（兼容空格等已编码字符）
                java.net.URL fileUrl = new java.net.URL(spec.substring(4, bang));
                File jar = new File(fileUrl.toURI());
                if (jar.isFile()) {
                    return jar;
                }
            } catch (Throwable ignored) {
                // 换下一个前缀继续尝试
            }
        }
        return null;
    }
}
