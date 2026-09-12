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
package org.noear.solon.codecli.portal.help;

import org.junit.jupiter.api.Test;
import org.noear.solon.core.util.MultiMap;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HelpMode 单测：覆盖帮助请求识别、主题解析与帮助文本内容。
 *
 * @author noear
 */
class HelpModeTest {

    private static MultiMap<String> argx(String... args) {
        return MultiMap.from(args);
    }

    private static String render(String... args) {
        MultiMap<String> a = argx(args);
        return new HelpMode(HelpMode.resolveTopic(a), "v2026.9.12").render();
    }

    // ========== 帮助请求识别 ==========

    @Test
    void isHelpRequest_recognizesAllForms() {
        assertTrue(HelpMode.isHelpRequest(argx("help")));
        assertTrue(HelpMode.isHelpRequest(argx("--help")));
        assertTrue(HelpMode.isHelpRequest(argx("-h")));
        assertTrue(HelpMode.isHelpRequest(argx("run", "--help")));
        assertTrue(HelpMode.isHelpRequest(argx("run", "-h")));
        assertTrue(HelpMode.isHelpRequest(argx("help", "run")));
        // argx 的贪婪 lookahead 会把 run 收成 help 的值，flags 里没有 help
        assertTrue(HelpMode.isHelpRequest(argx("--help", "run")));
        assertTrue(HelpMode.isHelpRequest(argx("serve", "--help")));
    }

    @Test
    void isHelpRequest_ignoresNonHelpInvocations() {
        assertFalse(HelpMode.isHelpRequest(argx()));
        assertFalse(HelpMode.isHelpRequest(argx("--version")));
        assertFalse(HelpMode.isHelpRequest(argx("run", "What is 2+2?")));
        assertFalse(HelpMode.isHelpRequest(argx("serve")));
        assertFalse(HelpMode.isHelpRequest(null));
    }

    @Test
    void isHelpRequest_doesNotMatchPromptTextContainingHelp() {
        // 提示词是单个位置参数，整串入 flags，不应被误判为帮助请求
        assertFalse(HelpMode.isHelpRequest(argx("run", "how do I use --help")));
        assertFalse(HelpMode.isHelpRequest(argx("run", "please help me")));
    }

    @Test
    void isHelpRequest_doesNotMatchOptionValues() {
        // --model help 时 help 是 model 的值，不是帮助请求
        assertFalse(HelpMode.isHelpRequest(argx("run", "x", "--model", "help")));
    }

    // ========== 主题解析 ==========

    @Test
    void resolveTopic_returnsSubcommand() {
        assertEquals("run", HelpMode.resolveTopic(argx("help", "run")));
        assertEquals("run", HelpMode.resolveTopic(argx("run", "--help")));
        assertEquals("run", HelpMode.resolveTopic(argx("--help", "run")));
        assertEquals("serve", HelpMode.resolveTopic(argx("help", "serve")));
        assertEquals("web", HelpMode.resolveTopic(argx("web", "-h")));
        assertEquals("acp", HelpMode.resolveTopic(argx("help", "acp")));
    }

    @Test
    void resolveTopic_nullForRootHelp() {
        assertEquals(null, HelpMode.resolveTopic(argx("help")));
        assertEquals(null, HelpMode.resolveTopic(argx("--help")));
        assertEquals(null, HelpMode.resolveTopic(argx("-h")));
        assertEquals(null, HelpMode.resolveTopic(null));
    }

    // ========== 帮助文本 ==========

    @Test
    void rootHelp_listsAllCommandsAndVersion() {
        String text = render("help");

        assertTrue(text.contains("v2026.9.12"), "帮助头部应含版本号");
        assertTrue(text.contains("Usage: soloncode"), text);
        assertTrue(text.contains("run <prompt>"), text);
        assertTrue(text.contains("serve"), text);
        assertTrue(text.contains("web"), text);
        assertTrue(text.contains("acp"), text);
        assertTrue(text.contains("--version"), text);
        assertTrue(text.contains("--help"), text);
    }

    @Test
    void runHelp_documentsEveryPrintModeOption() {
        String text = render("run", "--help");

        // 与 PrintModeOptions 的解析项一一对应：新增选项必须同步进帮助，
        // 否则 SDK 侧的 flag 对齐校验会漏掉它
        String[] expected = { "--output-format", "--verbose", "--json-schema", "--model", "--fallback-model",
                "--max-turns", "--max-budget-usd", "--session-id", "--resume", "--continue", "--add-dir",
                "--allowedTools", "--disallowedTools", "--permission-mode", "--bare" };

        for (String flag : expected) {
            assertTrue(text.contains(flag), "run 帮助缺少选项: " + flag);
        }
    }

    @Test
    void runHelp_documentsPermissionModesAndOutputFormats() {
        String text = render("run", "--help");

        for (String mode : new String[] { "default", "acceptEdits", "plan", "dontAsk", "bypassPermissions" }) {
            assertTrue(text.contains(mode), "run 帮助缺少权限模式: " + mode);
        }
        for (String format : new String[] { "text", "json", "stream-json" }) {
            assertTrue(text.contains(format), "run 帮助缺少输出格式: " + format);
        }
    }

    @Test
    void runHelp_documentsExitCodes() {
        String text = render("run", "--help");

        assertTrue(text.contains("Exit codes:"), text);
        for (String code : new String[] { "0", "1", "2", "3", "4" }) {
            assertTrue(text.contains("  " + code + "  "), "缺少退出码说明: " + code);
        }
    }

    @Test
    void runHelp_flagsAreMachineParseable() {
        // SDK 的 flag parity 校验用 --([a-zA-Z][a-zA-Z0-9-]*) 提取选项名，
        // 这里以同样的正则确认帮助文本可被解析
        Matcher matcher = Pattern.compile("--([a-zA-Z][a-zA-Z0-9-]*)").matcher(render("run", "--help"));

        int count = 0;
        while (matcher.find()) {
            count++;
        }
        assertTrue(count >= 15, "可解析的选项数量偏少: " + count);
    }

    @Test
    void subcommandHelp_rendersOwnUsage() {
        assertTrue(render("help", "serve").contains("Usage: soloncode serve"));
        assertTrue(render("help", "web").contains("Usage: soloncode web"));
        assertTrue(render("help", "acp").contains("Usage: soloncode acp"));
        assertTrue(render("help", "stream").contains("Usage: soloncode stream"));
    }

    // ========== stream 子命令 ==========

    @Test
    void rootHelp_listsStreamCommand() {
        String text = render("help");

        assertTrue(text.contains("stream"), text);
        assertTrue(text.contains("persistent"), "顶层帮助要说清 stream 是常驻的：" + text);
    }

    @Test
    void streamHelp_documentsPersistentSemanticsAndOwnFlags() {
        String text = render("help", "stream");

        assertTrue(text.contains("--replay-user-messages"), text);
        assertTrue(text.contains("--verbose"), text);
        assertTrue(text.contains("EOF"), "要说清何时退出：" + text);
        assertTrue(text.contains("control_request"), "要列出可接受的控制帧：" + text);
        assertTrue(text.contains("interrupt"), text);
    }

    @Test
    void runHelp_pointsPersistentUseToStream() {
        String text = render("run", "--help");

        assertTrue(text.contains("one-shot"), text);
        assertTrue(text.contains("soloncode stream"),
                "run 帮助要给出常驻场景的去向：" + text);
        assertFalse(text.contains("--input-format"),
                "run 不接受 --input-format，帮助里不能列出来：" + text);
    }

    @Test
    void streamHelp_isMachineParseable() {
        Matcher matcher = Pattern.compile("--([a-zA-Z][a-zA-Z0-9-]*)").matcher(render("help", "stream"));

        int count = 0;
        while (matcher.find()) {
            count++;
        }
        assertTrue(count >= 12, "可解析的选项数量偏少: " + count);
    }

    @Test
    void streamHelp_documentsExitCodes() {
        String text = render("help", "stream");

        assertTrue(text.contains("Exit codes:"), text);
        for (String code : new String[] { "0", "1", "2", "3", "4", "143" }) {
            assertTrue(text.contains("  " + code + "  "), "缺少退出码说明: " + code);
        }
    }

    @Test
    void resolveTopic_recognizesStream() {
        assertEquals("stream", HelpMode.resolveTopic(argx("help", "stream")));
        assertEquals("stream", HelpMode.resolveTopic(argx("stream", "--help")));
        assertEquals("stream", HelpMode.resolveTopic(argx("stream", "-h")));
    }

    @Test
    void execute_returnsZero() {
        assertEquals(0, new HelpMode(null, "v2026.9.12").execute());
        assertEquals(0, new HelpMode("run", "v2026.9.12").execute());
        assertEquals(0, new HelpMode("stream", "v2026.9.12").execute());
    }
}
