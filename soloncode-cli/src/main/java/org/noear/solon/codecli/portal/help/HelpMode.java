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

import org.noear.solon.core.util.MultiMap;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Help 模式（{@code soloncode help} / {@code --help} / {@code -h}）。
 *
 * <p>帮助文本同时承担两个职责：给人看的用法说明，以及给工具链（SDK 的 flag 对齐校验、
 * 补全脚本）解析的选项清单。因此选项必须以 {@code --name} 的形式逐行列出，
 * 新增 {@code run} 选项时要同步补充到 {@link #renderRun()}，否则 SDK 侧
 * 的 flag parity 校验会漏掉它。</p>
 *
 * <p>支持的调用形态：
 * <pre>
 *   soloncode help            soloncode --help          soloncode -h
 *   soloncode help run        soloncode run --help      soloncode run -h
 *   soloncode help serve      soloncode serve --help
 * </pre>
 *
 * @author noear
 */
public class HelpMode {

    /** 帮助标记：长短两种写法 */
    private static final Set<String> HELP_FLAGS = new HashSet<>(Arrays.asList("help", "h"));

    /** 可查询详细帮助的子命令 */
    private static final Set<String> TOPICS = new HashSet<>(Arrays.asList("run", "stream", "serve", "web", "acp"));

    private final String topic;

    private final String version;

    /**
     * @param topic   子命令名（null 表示顶层帮助）
     * @param version 版本号，用于帮助头部
     */
    public HelpMode(String topic, String version) {
        this.topic = topic;
        this.version = version;
    }

    /**
     * 判断当前启动参数是否为帮助请求。
     *
     * <p>注意：提示词是位置参数，也会进入 {@code flags()}。这里只认独立的
     * {@code help}/{@code h} 条目，因此 {@code soloncode run "how to use --help"}
     * 不会被误判为帮助请求（整个提示词作为一个 flag 条目存在）。</p>
     *
     * @param argx Solon 启动参数
     * @return true 表示应输出帮助
     */
    public static boolean isHelpRequest(MultiMap<String> argx) {
        if (argx == null) {
            return false;
        }

        // containsKey 同时覆盖两种解析结果：
        //  1) `--help` / `-h` / `help` 作为布尔 flag（也会以 null 值落入 map）
        //  2) `--help run` —— argx 的贪婪 lookahead 会把 run 当作 help 的值，此时 flags 里没有 help
        for (String flag : HELP_FLAGS) {
            if (argx.containsKey(flag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从启动参数中解析帮助主题。
     *
     * <p>两种来源：{@code soloncode help run}（help 后跟主题）与
     * {@code soloncode run --help}（主题在 help 标记之前）。</p>
     *
     * @param argx Solon 启动参数
     * @return 子命令名；null 表示顶层帮助
     */
    public static String resolveTopic(MultiMap<String> argx) {
        if (argx == null) {
            return null;
        }

        List<String> flags = argx.flags();
        if (flags != null) {
            for (String flag : flags) {
                if (TOPICS.contains(flag)) {
                    return flag;
                }
            }
        }

        // `--help run` / `-h run`：主题被 lookahead 收成了 help 的值
        for (String flag : HELP_FLAGS) {
            String value = argx.get(flag);
            if (value != null && TOPICS.contains(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 输出帮助文本。
     *
     * @return 退出码（帮助总是成功，固定 0）
     */
    public int execute() {
        System.out.println(render());
        return 0;
    }

    /**
     * 渲染帮助文本。
     *
     * @return 帮助文本
     */
    public String render() {
        if ("run".equals(topic)) {
            return renderRun();
        }
        if ("stream".equals(topic)) {
            return renderStream();
        }
        if ("serve".equals(topic)) {
            return renderServe();
        }
        if ("web".equals(topic)) {
            return renderWeb();
        }
        if ("acp".equals(topic)) {
            return renderAcp();
        }
        return renderRoot();
    }

    private String renderRoot() {
        StringBuilder sb = new StringBuilder();
        sb.append(header());
        sb.append("Usage: soloncode [command] [options]\n");
        sb.append("       soloncode run <prompt> [options]\n");
        sb.append("\n");
        sb.append("Starts the interactive CLI when no command is given.\n");
        sb.append("\n");
        sb.append("Commands:\n");
        sb.append("  run <prompt>     Run a one-shot (headless) task, print the result and exit\n");
        sb.append("  stream           Serve a persistent headless session over stdio (JSONL in, JSONL out)\n");
        sb.append("  serve            Start the desktop server (WebSocket gate + Web UI)\n");
        sb.append("  web              Start the Web UI server and open a browser\n");
        sb.append("  acp              Serve the Agent Client Protocol over stdio\n");
        sb.append("  help [command]   Show help for a command\n");
        sb.append("\n");
        sb.append("Options:\n");
        sb.append("  -h, --help       Show this help and exit\n");
        sb.append("  -v, --version    Show the version and exit\n");
        sb.append("\n");
        sb.append("Run 'soloncode help run' for one-shot headless options, or\n");
        sb.append("'soloncode help stream' for the persistent stdio session.\n");
        sb.append(exitCodes());
        return sb.toString();
    }

    private String renderRun() {
        StringBuilder sb = new StringBuilder();
        sb.append(header());
        sb.append("Usage: soloncode run <prompt> [options]\n");
        sb.append("       cat input.txt | soloncode run [options]\n");
        sb.append("\n");
        sb.append("Runs a one-shot (headless) task, prints the result and exits.\n");
        sb.append("The prompt is read from stdin when it is not given as an argument.\n");
        sb.append("\n");
        sb.append("Output options:\n");
        sb.append("  --output-format <format>   text (default) | json | stream-json\n");
        sb.append("  --verbose                  Emit verbose streaming events (required by stream-json)\n");
        sb.append("  --json-schema <schema>     JSON Schema constraining the structured output\n");
        sb.append("\n");
        sb.append("Model options:\n");
        sb.append("  --model <name>             Model name or alias\n");
        sb.append("  --fallback-model <name>    Model to fall back to when the primary one fails\n");
        sb.append("  --max-turns <n>            Maximum number of agent turns\n");
        sb.append("  --max-budget-usd <amount>  Hard cost limit in USD; exceeding it exits with code 4\n");
        sb.append("\n");
        sb.append("Session options:\n");
        sb.append("  --session-id <id>          Run under the given session id\n");
        sb.append("  --resume <id>              Resume the given session\n");
        sb.append("  --continue                 Continue the most recent session\n");
        sb.append("\n");
        sb.append("Workspace and permission options:\n");
        sb.append("  --add-dir <dir>            Add an extra working directory (repeatable)\n");
        sb.append("  --allowedTools <list>      Comma separated allow list, e.g. 'Read,Bash(git log *)'\n");
        sb.append("  --disallowedTools <list>   Comma separated deny list, e.g. 'Bash(rm *)'\n");
        sb.append("  --permission-mode <mode>   default | acceptEdits | plan | dontAsk | bypassPermissions\n");
        sb.append("  --bare                     Minimal mode: skip auto-discovered skills/hooks/MCP/LSP\n");
        sb.append("\n");
        sb.append("Options:\n");
        sb.append("  -h, --help                 Show this help and exit\n");
        sb.append("\n");
        sb.append("Examples:\n");
        sb.append("  soloncode run 'What is 2+2?'\n");
        sb.append("  soloncode run 'Review this PR' --output-format json --permission-mode dontAsk\n");
        sb.append("  soloncode run 'Explain this repo' --output-format stream-json --verbose\n");
        sb.append("  cat error.log | soloncode run --output-format json 'Analyse this error'\n");
        sb.append("\n");
        sb.append("Note: 'run' is always one-shot. For a persistent session that keeps the\n");
        sb.append("process alive across turns, use 'soloncode stream'.\n");
        sb.append(exitCodes());
        return sb.toString();
    }

    private String renderStream() {
        StringBuilder sb = new StringBuilder();
        sb.append(header());
        sb.append("Usage: soloncode stream [options]\n");
        sb.append("\n");
        sb.append("Serves a persistent headless session over stdio. Each line on stdin is one\n");
        sb.append("complete JSON object; a user message starts a new turn. The process stays\n");
        sb.append("alive until stdin reaches EOF, so turns share one session and one engine\n");
        sb.append("instead of restarting the binary per turn.\n");
        sb.append("\n");
        sb.append("Input and output are always JSONL here; --input-format and --output-format\n");
        sb.append("are implied and cannot be changed.\n");
        sb.append("\n");
        sb.append("Accepted input lines:\n");
        sb.append("  {\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"...\"}}\n");
        sb.append("  {\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":\"interrupt\"}}\n");
        sb.append("\n");
        sb.append("Stream options:\n");
        sb.append("  --verbose                  Emit assistant/tool events, not just per-turn results\n");
        sb.append("  --replay-user-messages     Echo each accepted user message back on stdout\n");
        sb.append("\n");
        sb.append("Model options:\n");
        sb.append("  --model <name>             Model name or alias\n");
        sb.append("  --fallback-model <name>    Model to fall back to when the primary one fails\n");
        sb.append("  --max-turns <n>            Maximum agent turns per user message\n");
        sb.append("  --max-budget-usd <amount>  Hard cost limit in USD for the whole session\n");
        sb.append("\n");
        sb.append("Session options:\n");
        sb.append("  --session-id <id>          Run under the given session id\n");
        sb.append("  --resume <id>              Resume the given session\n");
        sb.append("  --continue                 Continue the most recent session\n");
        sb.append("\n");
        sb.append("Workspace and permission options:\n");
        sb.append("  --add-dir <dir>            Add an extra working directory (repeatable)\n");
        sb.append("  --allowedTools <list>      Comma separated allow list, e.g. 'Read,Bash(git log *)'\n");
        sb.append("  --disallowedTools <list>   Comma separated deny list, e.g. 'Bash(rm *)'\n");
        sb.append("  --permission-mode <mode>   default | acceptEdits | plan | dontAsk | bypassPermissions\n");
        sb.append("  --bare                     Minimal mode: skip auto-discovered skills/hooks/MCP/LSP\n");
        sb.append("\n");
        sb.append("Options:\n");
        sb.append("  -h, --help                 Show this help and exit\n");
        sb.append("\n");
        sb.append("Examples:\n");
        sb.append("  soloncode stream --verbose\n");
        sb.append("  soloncode stream --permission-mode dontAsk --max-budget-usd 5.0\n");
        sb.append(exitCodes());
        return sb.toString();
    }

    private String renderServe() {
        StringBuilder sb = new StringBuilder();
        sb.append(header());
        sb.append("Usage: soloncode serve [options]\n");
        sb.append("\n");
        sb.append("Starts the desktop server: the WebSocket gate plus the Web UI.\n");
        sb.append("\n");
        sb.append("Options:\n");
        sb.append("  --server.port <port>       HTTP port to listen on\n");
        sb.append("  -h, --help                 Show this help and exit\n");
        return sb.toString();
    }

    private String renderWeb() {
        StringBuilder sb = new StringBuilder();
        sb.append(header());
        sb.append("Usage: soloncode web [options]\n");
        sb.append("\n");
        sb.append("Starts the Web UI server and opens it in the default browser.\n");
        sb.append("\n");
        sb.append("Options:\n");
        sb.append("  --server.port <port>       HTTP port to listen on\n");
        sb.append("  -h, --help                 Show this help and exit\n");
        return sb.toString();
    }

    private String renderAcp() {
        StringBuilder sb = new StringBuilder();
        sb.append(header());
        sb.append("Usage: soloncode acp\n");
        sb.append("\n");
        sb.append("Serves the Agent Client Protocol over stdio, for editors and IDE plugins.\n");
        sb.append("Nothing else may be written to stdout in this mode.\n");
        sb.append("\n");
        sb.append("Options:\n");
        sb.append("  -h, --help                 Show this help and exit\n");
        return sb.toString();
    }

    private String header() {
        return "soloncode " + (version == null ? "" : version) + " - AI coding agent\n\n";
    }

    private String exitCodes() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("Exit codes:\n");
        sb.append("  0    success\n");
        sb.append("  1    runtime or usage error\n");
        sb.append("  2    max turns exceeded\n");
        sb.append("  3    no prompt provided\n");
        sb.append("  4    cost limit exceeded\n");
        sb.append("  143  terminated by SIGTERM\n");
        return sb.toString();
    }
}
