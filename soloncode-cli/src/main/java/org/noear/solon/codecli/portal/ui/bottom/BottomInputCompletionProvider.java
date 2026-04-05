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
package org.noear.solon.codecli.portal.ui.bottom;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.noear.solon.codecli.portal.ui.CommandRegistry;

import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 底部输入补全器，统一支持 / 命令补全与 @ 文件补全。
 */
public class BottomInputCompletionProvider implements Completer {
    private final CommandRegistry registry;
    private final Path workDir;

    public BottomInputCompletionProvider(CommandRegistry registry, Path workDir) {
        this.registry = registry;
        this.workDir = workDir == null ? Paths.get("").toAbsolutePath().normalize() : workDir.toAbsolutePath().normalize();
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String word = line.word();
        if (word == null || word.isEmpty()) {
            return;
        }

        if (word.startsWith("/")) {
            for (CompletionCandidate candidate : findCommandCandidates(word)) {
                candidates.add(new Candidate(
                        candidate.getValue(),
                        candidate.getValue(),
                        "commands",
                        candidate.getDescription(),
                        null,
                        null,
                        false
                ));
            }
            return;
        }

        if (word.startsWith("@")) {
            for (CompletionCandidate candidate : findFileCandidates(word)) {
                candidates.add(new Candidate(
                        candidate.isDirectory() ? "@" + candidate.getValue() + "/" : "@" + candidate.getValue(),
                        candidate.getDisplayWithSuffix(),
                        "files",
                        candidate.getDescription(),
                        null,
                        null,
                        false
                ));
            }
        }
    }

    public List<CompletionCandidate> findCommandCandidates(String word) {
        List<CompletionCandidate> candidates = new ArrayList<CompletionCandidate>();
        for (CommandRegistry.Command cmd : registry.findCandidates(word)) {
            candidates.add(new CompletionCandidate(cmd.getName(), cmd.getName(), false, cmd.getDescription()));
        }
        return candidates;
    }

    public List<CompletionCandidate> findFileCandidates(String word) {
        List<CompletionCandidate> candidates = new ArrayList<CompletionCandidate>();
        String userPath = word.substring(1);
        String currentPath = determineCurrentPath(userPath);
        String filter = getFilterFromPath(userPath);

        try {
            Path dir = Paths.get(currentPath);
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                return candidates;
            }

            String globPattern = filter.isEmpty() ? "*" : filter + "*";
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, globPattern)) {
                for (Path entry : stream) {
                    String name = entry.getFileName().toString();
                    boolean isDir = Files.isDirectory(entry);
                    String completedPath = buildCompletedPath(userPath, name, isDir);
                    candidates.add(new CompletionCandidate(
                            name,
                            completedPath,
                            isDir,
                            ""
                    ));
                }
            }
        } catch (IOException ignored) {
            // ignore completion IO failures
        }

        Collections.sort(candidates, new Comparator<CompletionCandidate>() {
            @Override
            public int compare(CompletionCandidate a, CompletionCandidate b) {
                if (a.isDirectory() && !b.isDirectory()) {
                    return -1;
                }
                if (!a.isDirectory() && b.isDirectory()) {
                    return 1;
                }
                return a.getDisplay().compareToIgnoreCase(b.getDisplay());
            }
        });

        return candidates;
    }

    private String determineCurrentPath(String userPath) {
        if (userPath == null || userPath.isEmpty()) {
            return workDir.toString();
        }
        if (userPath.startsWith("/")) {
            return Paths.get(userPath).normalize().toString();
        }
        if (userPath.startsWith("./")) {
            Path resolved = workDir.resolve(userPath.substring(2)).normalize();
            Path parent = resolved.getParent();
            return parent == null ? workDir.toString() : parent.toString();
        }
        if (userPath.contains("/")) {
            int lastSlash = userPath.lastIndexOf('/');
            String base = lastSlash > 0 ? userPath.substring(0, lastSlash) : ".";
            return workDir.resolve(base).normalize().toString();
        }
        return workDir.toString();
    }

    private String getFilterFromPath(String userPath) {
        if (userPath == null || userPath.isEmpty()) {
            return "";
        }
        if (userPath.contains("/")) {
            return userPath.substring(userPath.lastIndexOf('/') + 1);
        }
        return userPath;
    }

    private String buildCompletedPath(String userPath, String name, boolean isDir) {
        String completed;
        if (userPath == null || userPath.isEmpty()) {
            completed = name;
        } else if (userPath.endsWith("/")) {
            completed = userPath + name;
        } else {
            int lastSlash = userPath.lastIndexOf('/');
            if (lastSlash >= 0) {
                completed = userPath.substring(0, lastSlash + 1) + name;
            } else {
                completed = name;
            }
        }

        if (isDir) {
            return completed + "/";
        }
        return completed;
    }
}
