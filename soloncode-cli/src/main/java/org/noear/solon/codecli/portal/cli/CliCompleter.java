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
package org.noear.solon.codecli.portal.cli;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.talents.mount.SkillDir;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 命令名 Tab 补全（兼容 Claude Code 的 argument-hint 显示）
 *
 * @author noear
 * @since 2026.4.28
 */
public class CliCompleter implements Completer {
    private final HarnessEngine engine;

    public CliCompleter(HarnessEngine engine) {
        this.engine = engine;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (line.word() == null) {
            return;
        }

        if (line.word().startsWith("/")) {
            String prefix = line.word().substring(1).toLowerCase();
            for (String name : engine.getCommandRegistry().names()) {
                if (name.startsWith(prefix)) {
                    Command cmd = engine.getCommandRegistry().find(name);
                    // 构建补全提示：description + argument-hint
                    candidates.add(new Candidate("/" + name, "/" + name + "  " + cmd.description(), null, null, null, null, true));
                }
            }
        }

        if (line.word().startsWith("/m")) {
            String prefix = line.word().substring(1).toLowerCase();
            for (ChatConfig c : engine.getModels()) {
                if (("model " + c.getNameOrModel()).startsWith(prefix)) {
                    candidates.add(new Candidate("/model " + c.getNameOrModel(), "/model " + c.getNameOrModel(), null, null, null, null, true));
                }
            }
        }

        if (line.word().startsWith("@")) {
            String prefix = line.word().substring(1).toLowerCase();
            for (AgentDefinition definition : engine.getAgentManager().getAgents()) {
                if (definition.getName().startsWith(prefix)) {
                    // 构建补全提示：description + argument-hint
                    candidates.add(new Candidate("@" + definition.getName(), "@" + definition.getName() + "  " + definition.getDescription(), null, null, null, null, true));
                }
            }
        }

        if (line.word().startsWith("$")) {
            Set<String> added = new HashSet<>();
            String prefix = line.word().substring(1).toLowerCase();
            for (SkillDir skill : engine.getSkills()) {
                if (skill.getName().startsWith(prefix)) {
                    if (added.contains(skill.getName())) {
                        continue;
                    } else {
                        added.add(skill.getName());
                    }

                    // 构建补全提示：description + argument-hint
                    String desc = skill.getDescription();
                    if (desc != null) {
                        // 取第一行，并限制最大长度
                        int newlineIdx = desc.indexOf('\n');
                        if (newlineIdx > 0) {
                            desc = desc.substring(0, newlineIdx);
                        }
                        if (desc.length() > 30) {
                            desc = desc.substring(0, 30) + "...";
                        }
                    }

                    candidates.add(new Candidate("$" + skill.getName(), "$" + skill.getName() + "  " + desc, null, null, null, null, true));
                }
            }
        }
    }
}
