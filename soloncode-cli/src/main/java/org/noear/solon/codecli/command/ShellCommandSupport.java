/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.command;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.JavaUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 系统命令上下文注入支持。
 */
public class ShellCommandSupport {
    private static final int MAX_OUTPUT_CHARS = 20_000;
    private static final long TIMEOUT_SECONDS = 60;

    public static boolean isShellCommand(String input) {
        return input != null && input.startsWith("!");
    }

    public static Result executeAndInject(AgentSession session, String workspace, String input) throws Exception {
        String command = input.substring(1).trim();
        if (command.length() == 0) {
            throw new IllegalArgumentException("系统命令不能为空");
        }

        Result result = execute(workspace, command);
        session.addMessage(ChatMessage.ofUser(result.toContextText()));
        return result;
    }

    private static Result execute(String workspace, String command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(buildShellArgs(resolveShell(), command));

        if (workspace != null && workspace.length() > 0) {
            builder.directory(new File(workspace));
        }
        if (JavaUtil.IS_WINDOWS) {
            enrichWindowsEnvironment(builder.environment());
        }
        builder.redirectErrorStream(true);

        long startMs = System.currentTimeMillis();
        Process process = builder.start();
        StreamCollector collector = new StreamCollector(process.getInputStream());
        Thread collectorThread = new Thread(collector, "shell-command-output-collector");
        collectorThread.setDaemon(true);
        collectorThread.start();

        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }

        collectorThread.join(TimeUnit.SECONDS.toMillis(2));

        int exitCode = finished ? process.exitValue() : -1;
        long elapsedMs = System.currentTimeMillis() - startMs;
        return new Result(command, workspace, exitCode, elapsedMs, collector.getOutput(), !finished);
    }

    static ShellInfo resolveShell() {
        if (JavaUtil.IS_WINDOWS) {
            String shell = firstNotEmpty(System.getenv("SOLONCODE_SHELL"), System.getenv("SHELL"));
            if (shell != null) {
                return new ShellInfo(resolveShellType(shell), shell);
            }

            String pwsh = findWindowsCommand("pwsh.exe");
            if (pwsh != null) {
                return new ShellInfo(ShellType.POWERSHELL, pwsh);
            }

            String powershell = findWindowsCommand("powershell.exe");
            if (powershell != null) {
                return new ShellInfo(ShellType.POWERSHELL, powershell);
            }

            String comSpec = System.getenv("ComSpec");
            if (comSpec != null && comSpec.trim().length() > 0) {
                return new ShellInfo(resolveShellType(comSpec), comSpec);
            }

            return new ShellInfo(ShellType.CMD, "cmd");
        }

        String shell = System.getenv("SHELL");
        if (shell != null && shell.trim().length() > 0) {
            return new ShellInfo(resolveShellType(shell), shell);
        }

        if (JavaUtil.IS_MAC && new File("/bin/zsh").exists()) {
            return new ShellInfo(ShellType.ZSH, "/bin/zsh");
        }

        if (new File("/bin/bash").exists()) {
            return new ShellInfo(ShellType.BASH, "/bin/bash");
        }

        return new ShellInfo(ShellType.SH, "/bin/sh");
    }

    static List<String> buildShellArgs(ShellInfo shellInfo, String command) {
        switch (shellInfo.type) {
            case ZSH:
                return Arrays.asList(shellInfo.path, "-lc", buildZshScript(command));
            case BASH:
                return Arrays.asList(shellInfo.path, "-lc", buildBashScript(command));
            case FISH:
                return Arrays.asList(shellInfo.path, "-lc", buildFishScript(command));
            case POWERSHELL:
                return Arrays.asList(shellInfo.path, "-NoLogo", "-Command", buildPowerShellScript(command));
            case CMD:
                return Arrays.asList(shellInfo.path, "/c", buildCmdScript(command));
            case SH:
            default:
                return Arrays.asList(shellInfo.path, "-lc", buildShScript(command));
        }
    }

    private static String buildZshScript(String command) {
        return "if [ -n \"${ZDOTDIR:-}\" ] && [ -r \"$ZDOTDIR/.zshrc\" ]; then\n" +
                "  . \"$ZDOTDIR/.zshrc\"\n" +
                "elif [ -r \"$HOME/.zshrc\" ]; then\n" +
                "  . \"$HOME/.zshrc\"\n" +
                "fi\n" +
                command;
    }

    private static String buildBashScript(String command) {
        return "shopt -s expand_aliases\n" +
                "if [ -r \"$HOME/.bashrc\" ]; then\n" +
                "  . \"$HOME/.bashrc\"\n" +
                "fi\n" +
                command;
    }

    private static String buildShScript(String command) {
        return "if [ -r \"$HOME/.profile\" ]; then\n" +
                "  . \"$HOME/.profile\"\n" +
                "fi\n" +
                command;
    }

    private static String buildFishScript(String command) {
        return "if test -r \"$HOME/.config/fish/config.fish\"\n" +
                "  source \"$HOME/.config/fish/config.fish\"\n" +
                "end\n" +
                command;
    }

    private static String buildPowerShellScript(String command) {
        return "if (Test-Path $PROFILE) { . $PROFILE }; " + command;
    }

    private static String buildCmdScript(String command) {
        return "if exist \"%USERPROFILE%\\cmdrc.cmd\" call \"%USERPROFILE%\\cmdrc.cmd\"\r\n" + command;
    }

    static void mergeWindowsPath(Map<String, String> env, String machinePath, String userPath) {
        String pathKey = env.containsKey("Path") ? "Path" : "PATH";
        String currentPath = env.get(pathKey);
        Set<String> items = new LinkedHashSet<>();

        addPathItems(items, currentPath);
        addPathItems(items, machinePath);
        addPathItems(items, userPath);

        if (items.isEmpty() == false) {
            env.put(pathKey, joinPathItems(items));
        }
    }

    private static void enrichWindowsEnvironment(Map<String, String> env) {
        try {
            mergeWindowsPath(env,
                    readWindowsEnvironmentVariable("Path", "Machine"),
                    readWindowsEnvironmentVariable("Path", "User"));
        } catch (Throwable ignored) {
        }
    }

    private static String readWindowsEnvironmentVariable(String name, String target) throws Exception {
        String shell = firstNotEmpty(findWindowsCommand("pwsh.exe"), findWindowsCommand("powershell.exe"));
        if (shell == null) {
            return null;
        }

        Process process = new ProcessBuilder(shell, "-NoProfile", "-Command",
                "[Environment]::GetEnvironmentVariable('" + name + "','" + target + "')")
                .redirectErrorStream(true)
                .start();
        StreamCollector collector = new StreamCollector(process.getInputStream());
        Thread collectorThread = new Thread(collector, "windows-env-reader");
        collectorThread.setDaemon(true);
        collectorThread.start();

        if (process.waitFor(5, TimeUnit.SECONDS) == false) {
            process.destroyForcibly();
            return null;
        }
        collectorThread.join(TimeUnit.SECONDS.toMillis(1));

        if (process.exitValue() != 0) {
            return null;
        }

        String output = collector.getOutput().trim();
        return output.length() == 0 ? null : output;
    }

    private static void addPathItems(Set<String> items, String path) {
        if (path == null || path.trim().length() == 0) {
            return;
        }

        for (String item : path.split(";")) {
            String normalized = item.trim();
            if (normalized.length() > 0) {
                addPathItem(items, normalized);
            }
        }
    }

    private static void addPathItem(Set<String> items, String item) {
        for (String existing : items) {
            if (existing.equalsIgnoreCase(item)) {
                return;
            }
        }
        items.add(item);
    }

    private static String joinPathItems(Set<String> items) {
        StringBuilder buf = new StringBuilder();
        for (String item : items) {
            if (buf.length() > 0) {
                buf.append(";");
            }
            buf.append(item);
        }
        return buf.toString();
    }

    private static String findWindowsCommand(String command) {
        String path = System.getenv("Path");
        if (path == null || path.length() == 0) {
            path = System.getenv("PATH");
        }
        if (path == null || path.length() == 0) {
            return null;
        }

        for (String dir : path.split(";")) {
            if (dir == null || dir.trim().length() == 0) {
                continue;
            }
            File file = new File(dir.trim(), command);
            if (file.exists()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }

    private static ShellType resolveShellType(String shell) {
        String name = new File(shell).getName().toLowerCase();
        if (name.endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }

        if ("zsh".equals(name)) {
            return ShellType.ZSH;
        }

        if ("bash".equals(name)) {
            return ShellType.BASH;
        }

        if ("fish".equals(name)) {
            return ShellType.FISH;
        }

        if ("pwsh".equals(name) || "powershell".equals(name)) {
            return ShellType.POWERSHELL;
        }

        if ("cmd".equals(name)) {
            return ShellType.CMD;
        }

        if (JavaUtil.IS_WINDOWS) {
            return ShellType.CMD;
        }

        return ShellType.SH;
    }

    private static String firstNotEmpty(String... values) {
        for (String val : values) {
            if (val != null && val.trim().length() > 0) {
                return val;
            }
        }
        return null;
    }

    enum ShellType {
        ZSH, BASH, FISH, SH, POWERSHELL, CMD
    }

    static class ShellInfo {
        private final ShellType type;
        private final String path;

        ShellInfo(ShellType type, String path) {
            this.type = type;
            this.path = path;
        }
    }

    public static class Result {
        private final String command;
        private final String cwd;
        private final int exitCode;
        private final long elapsedMs;
        private final String output;
        private final boolean timeout;

        public Result(String command, String cwd, int exitCode, long elapsedMs, String output, boolean timeout) {
            this.command = command;
            this.cwd = cwd;
            this.exitCode = exitCode;
            this.elapsedMs = elapsedMs;
            this.output = output == null ? "" : output;
            this.timeout = timeout;
        }

        public String toDisplayText() {
            StringBuilder buf = new StringBuilder();
            buf.append("$ ").append(command).append("\n");
            if (output.length() > 0) {
                buf.append(output);
                if (output.endsWith("\n") == false) {
                    buf.append("\n");
                }
            }
            buf.append("[exit: ").append(exitCode);
            if (timeout) {
                buf.append(", timeout");
            }
            buf.append(", ").append(elapsedMs).append("ms]");
            return buf.toString();
        }

        public String toContextText() {
            StringBuilder buf = new StringBuilder();
            buf.append("系统命令执行结果：\n");
            buf.append("命令：").append(command).append("\n");
            if (cwd != null && cwd.length() > 0) {
                buf.append("工作目录：").append(cwd).append("\n");
            }
            buf.append("退出码：").append(exitCode);
            if (timeout) {
                buf.append("（超时）");
            }
            buf.append("\n");
            buf.append("耗时：").append(elapsedMs).append("ms\n");
            buf.append("输出：\n");
            if (output.length() > 0) {
                buf.append(output);
            } else {
                buf.append("(无输出)");
            }
            return buf.toString();
        }
    }

    private static class StreamCollector implements Runnable {
        private final InputStream inputStream;
        private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        private volatile IOException exception;

        private StreamCollector(InputStream inputStream) {
            this.inputStream = inputStream;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[4096];
            try {
                int len;
                while ((len = inputStream.read(buffer)) >= 0) {
                    if (outputStream.size() < MAX_OUTPUT_CHARS * 4) {
                        outputStream.write(buffer, 0, len);
                    }
                }
            } catch (IOException e) {
                exception = e;
            }
        }

        private String getOutput() throws IOException {
            if (exception != null) {
                throw exception;
            }

            String text = new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
            if (text.length() > MAX_OUTPUT_CHARS) {
                return text.substring(0, MAX_OUTPUT_CHARS) + "\n[输出已截断，最多注入 " + MAX_OUTPUT_CHARS + " 字符]";
            }
            return text;
        }
    }
}
