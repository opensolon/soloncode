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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.JavaUtil;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ShellCommandSupportTest {
    @TempDir
    Path tempDir;

    @Test
    public void isShellCommand() {
        assertTrue(ShellCommandSupport.isShellCommand("!pwd"));
        assertTrue(ShellCommandSupport.isShellCommand("!"));
        assertFalse(ShellCommandSupport.isShellCommand("/model"));
        assertFalse(ShellCommandSupport.isShellCommand("pwd"));
        assertFalse(ShellCommandSupport.isShellCommand(null));
    }

    @Test
    public void executeAndInject() throws Exception {
        FileAgentSession session = new FileAgentSession("shell-test", tempDir.resolve("session").toString());
        String input = JavaUtil.IS_WINDOWS ? "!cd" : "!pwd";

        ShellCommandSupport.Result result = ShellCommandSupport.executeAndInject(session, tempDir.toString(), input);

        assertTrue(result.toDisplayText().startsWith("$ " + input.substring(1)));
        assertTrue(result.toDisplayText().contains("[exit: 0"));

        assertEquals(1, session.getMessages().size());
        ChatMessage message = session.getMessages().get(0);
        assertEquals(ChatRole.USER, message.getRole());
        assertTrue(message.getContent().contains("系统命令执行结果"));
        assertTrue(message.getContent().contains("命令：" + input.substring(1)));
        assertTrue(message.getContent().contains("工作目录：" + tempDir));
        assertTrue(message.getContent().contains(tempDir.toString()));
        assertTrue(message.getContent().contains("退出码：0"));
    }

    @Test
    public void executeAndInjectEmptyCommand() {
        FileAgentSession session = new FileAgentSession("shell-empty-test", tempDir.resolve("empty-session").toString());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ShellCommandSupport.executeAndInject(session, tempDir.toString(), "!"));

        assertEquals("系统命令不能为空", e.getMessage());
        assertTrue(session.getMessages().isEmpty());
    }

    @Test
    public void zshCommandLoadsZshrc() {
        List<String> args = ShellCommandSupport.buildShellArgs(
                new ShellCommandSupport.ShellInfo(ShellCommandSupport.ShellType.ZSH, "/bin/zsh"),
                "hello_from_alias");

        assertEquals("/bin/zsh", args.get(0));
        assertEquals("-lc", args.get(1));
        assertTrue(args.get(2).contains("ZDOTDIR"));
        assertTrue(args.get(2).contains(".zshrc"));
        assertTrue(args.get(2).endsWith("\nhello_from_alias"));
    }

    @Test
    public void bashCommandLoadsBashrcAndExpandsAliases() {
        List<String> args = ShellCommandSupport.buildShellArgs(
                new ShellCommandSupport.ShellInfo(ShellCommandSupport.ShellType.BASH, "/bin/bash"),
                "hello_from_alias");

        assertEquals("/bin/bash", args.get(0));
        assertEquals("-lc", args.get(1));
        assertTrue(args.get(2).contains("shopt -s expand_aliases"));
        assertTrue(args.get(2).contains("$HOME/.bashrc"));
        assertTrue(args.get(2).endsWith("\nhello_from_alias"));
    }

    @Test
    public void powershellCommandLoadsProfile() {
        List<String> args = ShellCommandSupport.buildShellArgs(
                new ShellCommandSupport.ShellInfo(ShellCommandSupport.ShellType.POWERSHELL, "pwsh"),
                "Get-Location");

        assertEquals("pwsh", args.get(0));
        assertFalse(args.contains("-NoProfile"));
        assertTrue(args.contains("-Command"));
        assertTrue(args.get(args.size() - 1).contains("$PROFILE"));
    }

    @Test
    public void cmdCommandLoadsOptionalCmdrcAndStillRunsCommand() {
        List<String> args = ShellCommandSupport.buildShellArgs(
                new ShellCommandSupport.ShellInfo(ShellCommandSupport.ShellType.CMD, "cmd"),
                "where java");

        assertEquals("cmd", args.get(0));
        assertEquals("/c", args.get(1));
        assertTrue(args.get(2).contains("%USERPROFILE%\\cmdrc.cmd"));
        assertTrue(args.get(2).endsWith("\r\nwhere java"));
    }

    @Test
    public void windowsPathMergeKeepsCurrentPathAndAddsMissingRegistryPaths() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("Path", "C:\\App\\bin;C:\\Windows\\System32");

        ShellCommandSupport.mergeWindowsPath(env, "C:\\Windows\\System32;C:\\Program Files\\Git\\cmd",
                "C:\\Users\\me\\AppData\\Local\\Programs\\Tool\\bin");

        assertEquals("C:\\App\\bin;C:\\Windows\\System32;C:\\Program Files\\Git\\cmd;C:\\Users\\me\\AppData\\Local\\Programs\\Tool\\bin",
                env.get("Path"));
    }
}
