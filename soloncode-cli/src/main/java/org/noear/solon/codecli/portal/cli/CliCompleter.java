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
import java.util.Locale;
import java.util.Set;

/**
 * 命令名 Tab 补全（兼容 Claude Code 的 argument-hint 显示）
 *
 * @author noear
 * @since 2026.4.28
 */
public class CliCompleter implements Completer {
    private final HarnessEngine engine;

    public CliCompleter(HarnessEngine engine, String workspace) {
        this.engine = engine;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (line.word() == null) {
            return;
        }

        String word = line.word();

        if (word.startsWith("/")) {
            completeCommands(word, candidates);
            completeModels(word, candidates);
            return;
        }

        if (word.startsWith("@")) {
            completeAgents(word, candidates);
            return;
        }

        if (word.startsWith("$")) {
            completeSkills(word, candidates);
            return;
        }

        if (word.startsWith("!")) {
            candidates.add(new Candidate("!", "!", null, "进入本地命令模式", null, null, true));
            candidates.add(new Candidate("!pwd", "!pwd", null, "执行一次本地命令", null, null, true));
            candidates.add(new Candidate("!ls", "!ls", null, "列出当前工作区文件", null, null, true));
        }
    }

    private void completeCommands(String word, List<Candidate> candidates) {
        String prefix = normalize(word.substring(1));
        for (String name : engine.getCommandRegistry().names()) {
            if (normalize(name).startsWith(prefix)) {
                Command cmd = engine.getCommandRegistry().find(name);
                candidates.add(new Candidate("/" + name, "/" + name, null, cmd.description(), null, null, true));
            }
        }
    }

    private void completeModels(String word, List<Candidate> candidates) {
        String prefix = normalize(word.substring(1));
        for (ChatConfig c : engine.getModels()) {
            if (normalize("model " + c.getNameOrModel()).startsWith(prefix)) {
                candidates.add(new Candidate("/model " + c.getNameOrModel(), "/model " + c.getNameOrModel(), null, null, null, null, true));
            }
        }
    }

    private void completeSkills(String word, List<Candidate> candidates) {
        Set<String> added = new HashSet<>();
        String prefix = normalize(word.substring(1));
        for (SkillDir skill : engine.getSkills()) {
            if (normalize(skill.getName()).startsWith(prefix)) {
                if (added.add(skill.getName()) == false) {
                    continue;
                }

                String desc = shorten(skill.getDescription(), 40);
                candidates.add(new Candidate("$" + skill.getName(), "$" + skill.getName(), null, desc, null, null, true));
            }
        }
    }

    private void completeAgents(String word, List<Candidate> candidates) {
        String prefix = normalize(word.substring(1));
        for (AgentDefinition agent : engine.getAgentManager().getAgents()) {
            if (agent.isHidden()) {
                continue;
            }

            if (normalize(agent.getName()).startsWith(prefix)) {
                String desc = formatDescription("子代理", shorten(agent.getDescription(), 40));
                candidates.add(new Candidate("@" + agent.getName(), "@" + agent.getName(), null, desc, null, null, true, 0));
            }
        }
    }

    private String shorten(String desc, int maxLength) {
        if (desc == null) {
            return "";
        }

        int newlineIdx = desc.indexOf('\n');
        if (newlineIdx > 0) {
            desc = desc.substring(0, newlineIdx);
        }
        if (desc.length() > maxLength) {
            desc = desc.substring(0, maxLength - 3) + "...";
        }
        return desc;
    }

    private String formatDescription(String type, String desc) {
        return desc.length() == 0 ? type : type + "  " + desc;
    }

    private String normalize(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
