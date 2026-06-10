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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CliCompleterTest {
    @TempDir
    Path tempDir;

    @Test
    public void atTriggerCompletesAgentsAndFiles() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main"));
        Files.write(tempDir.resolve("src/main/App.java"), Collections.singletonList("class App {}"));

        HarnessEngine engine = HarnessEngine.of("test", tempDir.toString()).build();
        engine.getAgentManager().addAgent(createAgent("reviewer", "Code review specialist"));

        CliCompleter completer = new CliCompleter(engine, tempDir.toString());
        List<Candidate> candidates = new ArrayList<>();

        completer.complete(null, new TestParsedLine("@"), candidates);

        assertTrue(containsValue(candidates, "@reviewer"));
        assertTrue(containsDisplayPrefix(candidates, "@reviewer  子代理"));
        assertTrue(containsValue(candidates, "@src/"));
        assertTrue(containsValue(candidates, "@src/main/App.java"));
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

    private AgentDefinition createAgent(String name, String description) {
        AgentDefinition.Metadata metadata = new AgentDefinition.Metadata();
        metadata.setName(name);
        metadata.setDescription(description);

        AgentDefinition definition = new AgentDefinition();
        definition.setMetadata(metadata);
        definition.setSystemPrompt("You are " + name + ".");
        return definition;
    }

    private boolean containsValue(List<Candidate> candidates, String value) {
        for (Candidate candidate : candidates) {
            if (value.equals(candidate.value())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsDisplayPrefix(List<Candidate> candidates, String displayPrefix) {
        for (Candidate candidate : candidates) {
            if (candidate.displ() != null && candidate.displ().startsWith(displayPrefix)) {
                return true;
            }
        }
        return false;
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
