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
package org.noear.solon.codecli.portal.ui;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

/**
 * 斜杠命令补全器
 *
 * <p>
 * 当用户输入以 "/" 开头时，自动列出匹配的命令候选项。
 * 支持 Tab 补全和 JLine 的 AUTO_MENU_LIST 下拉菜单。
 * </p>
 *
 * @author noear
 * @since 0.0.19
 */
public class SlashCommandCompleter implements Completer {

    private final CommandRegistry registry;

    public SlashCommandCompleter(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = line.line();
        if (buffer == null || !buffer.startsWith("/")) return;

        // Only use the first word as the command prefix (e.g. "/resume 2" → "/resume")
        String prefix = buffer.trim();
        int spaceIdx = prefix.indexOf(' ');
        if (spaceIdx > 0) {
            prefix = prefix.substring(0, spaceIdx);
        }

        List<CommandRegistry.Command> matched = registry.findCandidates(prefix);
        for (CommandRegistry.Command cmd : matched) {
            candidates.add(new Candidate(
                    cmd.getName(),       // value
                    cmd.getName(),       // display
                    null,                // group
                    cmd.getDescription(),// description
                    null,                // suffix
                    null,                // key
                    false                // complete=false: show list, don't auto-complete
            ));
        }
    }
}
