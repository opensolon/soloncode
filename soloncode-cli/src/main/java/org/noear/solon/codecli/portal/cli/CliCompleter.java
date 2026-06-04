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
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.skills.cli.SkillDir;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.FileVisitOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
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
    private static final int MAX_FILE_CANDIDATES = 80;
    private static final int MAX_FILE_SCAN = 2_000;
    private static final Set<String> EXCLUDED_DIRS = new HashSet<>();

    static {
        EXCLUDED_DIRS.add(".git");
        EXCLUDED_DIRS.add(".idea");
        EXCLUDED_DIRS.add(".soloncode");
        EXCLUDED_DIRS.add("node_modules");
        EXCLUDED_DIRS.add("target");
        EXCLUDED_DIRS.add("__pycache__");
        EXCLUDED_DIRS.add(".gradle");
        EXCLUDED_DIRS.add(".mvn");
        EXCLUDED_DIRS.add("build");
    }

    private final HarnessEngine engine;
    private final Path workspace;

    public CliCompleter(HarnessEngine engine, String workspace) {
        this.engine = engine;
        this.workspace = workspace == null ? null : Paths.get(workspace).toAbsolutePath().normalize();
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
            completeFiles(word, candidates);
            return;
        }

        if (word.startsWith("$")) {
            completeSkills(word, candidates);
            return;
        }

        if (word.startsWith("!")) {
            candidates.add(new Candidate("!", "!  进入本地命令模式", null, null, null, null, true));
            candidates.add(new Candidate("!pwd", "!pwd  执行一次本地命令", null, null, null, null, true));
            candidates.add(new Candidate("!ls", "!ls  列出当前工作区文件", null, null, null, null, true));
        }
    }

    private void completeCommands(String word, List<Candidate> candidates) {
        String prefix = normalize(word.substring(1));
        for (String name : engine.getCommandRegistry().names()) {
            if (normalize(name).startsWith(prefix)) {
                Command cmd = engine.getCommandRegistry().find(name);
                candidates.add(new Candidate("/" + name, "/" + name + "  " + cmd.description(), null, null, null, null, true));
            }
        }
    }

    private void completeModels(String word, List<Candidate> candidates) {
        String prefix = normalize(word.substring(1));
        for (ChatConfig c : engine.getProps().getModels()) {
            if (normalize("model " + c.getNameOrModel()).startsWith(prefix)) {
                candidates.add(new Candidate("/model " + c.getNameOrModel(), "/model " + c.getNameOrModel(), null, null, null, null, true));
            }
        }
    }

    private void completeSkills(String word, List<Candidate> candidates) {
        Set<String> added = new HashSet<>();
        String prefix = normalize(word.substring(1));
        for (SkillDir skill : engine.getPoolManager().getSkills()) {
            if (normalize(skill.getName()).startsWith(prefix)) {
                if (added.add(skill.getName()) == false) {
                    continue;
                }

                String desc = shorten(skill.getDescription(), 40);
                candidates.add(new Candidate("$" + skill.getName(), "$" + skill.getName() + "  " + desc, null, null, null, null, true));
            }
        }
    }

    private void completeFiles(String word, List<Candidate> candidates) {
        if (workspace == null || Files.isDirectory(workspace) == false) {
            return;
        }

        String query = normalize(word.substring(1));
        int[] scanned = {0};
        List<Path> matches = new ArrayList<>();

        try {
            Files.walkFileTree(workspace, EnumSet.noneOf(FileVisitOption.class), 5, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (workspace.equals(dir) == false && isVisibleWorkspacePath(dir) == false) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return collectFileCandidate(dir, query, scanned, matches);
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isVisibleWorkspacePath(file) == false) {
                        return FileVisitResult.CONTINUE;
                    }
                    return collectFileCandidate(file, query, scanned, matches);
                }
            });

            matches.sort(Comparator
                    .comparing((Path path) -> Files.isDirectory(path) ? 0 : 1)
                    .thenComparing(this::toRelativePath, String.CASE_INSENSITIVE_ORDER));

            for (Path path : matches) {
                String relative = toRelativePath(path);
                boolean dir = Files.isDirectory(path);
                String value = "@" + relative + (dir ? "/" : "");
                String display = value + "  " + (dir ? "目录" : "文件");
                candidates.add(new Candidate(value, display, null, null, null, null, true));
            }
        } catch (IOException ignored) {
        }
    }

    private FileVisitResult collectFileCandidate(Path path, String query, int[] scanned, List<Path> matches) {
        if (workspace.equals(path)) {
            return FileVisitResult.CONTINUE;
        }
        if (scanned[0]++ >= MAX_FILE_SCAN) {
            return FileVisitResult.TERMINATE;
        }

        String relative = toRelativePath(path);
        if (query.length() == 0 || normalize(relative).contains(query)) {
            matches.add(path);
        }
        return matches.size() >= MAX_FILE_CANDIDATES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
    }

    private boolean isVisibleWorkspacePath(Path path) {
        Path relative = workspace.relativize(path.toAbsolutePath().normalize());
        for (Path part : relative) {
            String name = part.toString();
            if (name.startsWith(".") || EXCLUDED_DIRS.contains(name)) {
                return false;
            }
        }
        return true;
    }

    private String toRelativePath(Path path) {
        return workspace.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
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

    private String normalize(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
