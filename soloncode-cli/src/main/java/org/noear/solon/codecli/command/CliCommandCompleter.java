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

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

/**
 * 命令名 Tab 补全（兼容 Claude Code 的 argument-hint 显示）
 *
 * @author noear
 * @since 2026.4.28
 */
public class CliCommandCompleter implements Completer {
    private final CommandRegistry registry;

    public CliCommandCompleter(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (line.word().startsWith("/")) {
            String prefix = line.word().substring(1).toLowerCase();
            for (String name : registry.names()) {
                if (name.startsWith(prefix)) {
                    Command cmd = registry.find(name);
                    // 构建补全提示：description + argument-hint
                    String hint = buildHint(cmd);
                    candidates.add(new Candidate("/" + name, "/" + name, null, hint, null, null, true));
                }
            }
        }
    }

    /**
     * 构建补全提示文本
     * <p>
     * 格式：description  [argument-hint]
     */
    private String buildHint(Command cmd) {
        StringBuilder sb = new StringBuilder();
        sb.append(cmd.description());

        String argHint = cmd.argumentHint();
        if (argHint != null && !argHint.isEmpty()) {
            sb.append("  ").append(argHint);
        }

        return sb.toString();
    }
}
