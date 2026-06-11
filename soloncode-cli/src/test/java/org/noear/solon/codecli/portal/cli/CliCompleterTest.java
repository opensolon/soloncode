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
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CliCompleterTest {
    @TempDir
    Path tempDir;

    @Test
    public void atTriggerCompletesAgentsOnly() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main"));
        Files.write(tempDir.resolve("src/main/App.java"), Collections.singletonList("class App {}"));

        HarnessEngine engine = HarnessEngine.of("test", tempDir.toString()).build();
        engine.getAgentManager().addAgent(createAgent("reviewer", "Code review specialist"));

        CliCompleter completer = new CliCompleter(engine, tempDir.toString());
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, new TestParsedLine("@"), candidates);

        assertTrue(containsValue(candidates, "@reviewer"));
        assertEquals("子代理  Code review specialist", findCandidate(candidates, "@reviewer").descr());
        assertFalse(containsValue(candidates, "@src/"));
        assertFalse(containsValue(candidates, "@src/main/App.java"));
    }

    @Test
    public void atTriggerFiltersAgentsByPrefix() throws Exception {
        HarnessEngine engine = HarnessEngine.of("test", tempDir.toString()).build();
        engine.getAgentManager().addAgent(createAgent("reviewer", "Code review specialist"));
        engine.getAgentManager().addAgent(createAgent("writer", "Writing specialist"));

        CliCompleter completer = new CliCompleter(engine, tempDir.toString());
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, new TestParsedLine("@rev"), candidates);

        assertTrue(containsValue(candidates, "@reviewer"));
        assertFalse(containsValue(candidates, "@writer"));
    }

    @Test
    public void slashTriggerFiltersCommandsByPrefix() {
        HarnessEngine engine = HarnessEngine.of("test", tempDir.toString()).build();
        engine.getCommandRegistry().register(createCommand("clear", "清空会话记录"));
        engine.getCommandRegistry().register(createCommand("exit", "退出进程"));

        CliCompleter completer = new CliCompleter(engine, tempDir.toString());
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, new TestParsedLine("/c"), candidates);

        assertTrue(containsValue(candidates, "/clear"));
        assertEquals("清空会话记录", findCandidate(candidates, "/clear").descr());
        assertFalse(containsValue(candidates, "/exit"));
    }

    @Test
    public void atTriggerDoesNotCollectFileHints() throws Exception {
        for (int i = 0; i < 20; i++) {
            Files.write(tempDir.resolve(String.format("file%02d.txt", i)), Collections.singletonList("text"));
        }

        HarnessEngine engine = HarnessEngine.of("test", tempDir.toString()).build();
        CliCompleter completer = new CliCompleter(engine, tempDir.toString());
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, new TestParsedLine("@"), candidates);

        assertEquals(0, countValuePrefix(candidates, "@file"));
    }

    @Test
    public void slashTriggerKeepsAllMatchingCommandHints() {
        HarnessEngine engine = HarnessEngine.of("test", tempDir.toString()).build();
        for (int i = 0; i < 20; i++) {
            engine.getCommandRegistry().register(createCommand(String.format("zzcmd%02d", i), "command " + i));
        }

        CliCompleter completer = new CliCompleter(engine, tempDir.toString());
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, new TestParsedLine("/zzcmd"), candidates);

        assertEquals(20, candidates.size());
    }

    private AgentDefinition createAgent(String name, String description) {
        AgentDefinition.Metadata metadata = new AgentDefinition.Metadata();
        metadata.setName(name);
        metadata.setDescription(description);

        AgentDefinition definition = new AgentDefinition();
        definition.setMetadata(metadata);
        definition.setSystemPrompt("You are " + name + ".");
        return definition;
    }

    private Command createCommand(String name, String description) {
        return new Command() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public boolean execute(CommandContext ctx) {
                return true;
            }
        };
    }

    private boolean containsValue(List<Candidate> candidates, String value) {
        return findCandidate(candidates, value) != null;
    }

    private Candidate findCandidate(List<Candidate> candidates, String value) {
        for (Candidate candidate : candidates) {
            if (value.equals(candidate.value())) {
                return candidate;
            }
        }
        return null;
    }

    private int countValuePrefix(List<Candidate> candidates, String prefix) {
        int count = 0;
        for (Candidate candidate : candidates) {
            if (candidate.value().startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private static class TestParsedLine implements ParsedLine {
        private final String word;

        private TestParsedLine(String word) {
            this.word = word;
        }

        @Override
        public String word() {
            return word;
        }

        @Override
        public int wordCursor() {
            return word.length();
        }

        @Override
        public int wordIndex() {
            return 0;
        }

        @Override
        public List<String> words() {
            return Collections.singletonList(word);
        }

        @Override
        public String line() {
            return word;
        }

        @Override
        public int cursor() {
            return word.length();
        }
    }
}
