package org.noear.solon.codecli;

import org.noear.solon.core.util.Assert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writes user-facing settings back to the global ~/.soloncode/config.yml.
 */
public final class GlobalConfigWriter {
    private static final String ROOT_KEY = "soloncode";
    private static final String UI_THEME_KEY = "uiTheme";

    private GlobalConfigWriter() {
    }

    public static Path persistUiTheme(String themeName) {
        ConfigLoader.ensureGlobalConfigDir();
        Path configPath = ConfigLoader.getGlobalConfigPath();
        persistUiTheme(configPath, themeName);
        return configPath;
    }

    static void persistUiTheme(Path configPath, String themeName) {
        try {
            String current = Files.exists(configPath)
                ? new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8)
                : "";
            String updated = upsertUiTheme(current, themeName);
            Files.write(
                configPath,
                updated.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist uiTheme to " + configPath, e);
        }
    }

    static String upsertUiTheme(String yamlContent, String themeName) {
        if (Assert.isEmpty(themeName)) {
            throw new IllegalArgumentException("themeName cannot be empty");
        }

        List<String> lines = toLines(yamlContent);
        int rootIndex = findRootBlock(lines, ROOT_KEY);

        if (rootIndex < 0) {
            appendRootBlock(lines, themeName);
            return joinLines(lines);
        }

        int rootIndent = indentOf(lines.get(rootIndex));
        int blockEnd = findBlockEnd(lines, rootIndex, rootIndent);

        for (int i = rootIndex + 1; i < blockEnd; i++) {
            String line = lines.get(i);
            if (isYamlKey(line, UI_THEME_KEY) && indentOf(line) > rootIndent) {
                lines.set(i, rebuildKeyValueLine(line, UI_THEME_KEY, themeName));
                return joinLines(lines);
            }
        }

        lines.add(rootIndex + 1, indent(rootIndent + 2) + UI_THEME_KEY + ": " + quote(themeName));
        return joinLines(lines);
    }

    private static List<String> toLines(String yamlContent) {
        if (yamlContent == null || yamlContent.isEmpty()) {
            return new ArrayList<String>();
        }

        String normalized = yamlContent.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<String>(Arrays.asList(normalized.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static int findRootBlock(List<String> lines, String key) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (indentOf(line) == 0 && isYamlKey(line, key)) {
                return i;
            }
        }

        return -1;
    }

    private static int findBlockEnd(List<String> lines, int rootIndex, int rootIndent) {
        for (int i = rootIndex + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            if (indentOf(line) <= rootIndent) {
                return i;
            }
        }

        return lines.size();
    }

    private static boolean isYamlKey(String line, String key) {
        String trimmed = line == null ? "" : line.trim();
        return trimmed.startsWith(key + ":");
    }

    private static int indentOf(String line) {
        if (line == null || line.isEmpty()) {
            return 0;
        }

        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }

        return indent;
    }

    private static String rebuildKeyValueLine(String originalLine, String key, String value) {
        int indent = indentOf(originalLine);
        String comment = extractComment(originalLine);
        String rebuilt = indent(indent) + key + ": " + quote(value);

        if (Assert.isNotEmpty(comment)) {
            rebuilt = rebuilt + " " + comment;
        }

        return rebuilt;
    }

    private static String extractComment(String line) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }

            if (ch == '"' && !inSingleQuote) {
                boolean escaped = i > 0 && line.charAt(i - 1) == '\\';
                if (!escaped) {
                    inDoubleQuote = !inDoubleQuote;
                }
                continue;
            }

            if (ch == '#' && !inSingleQuote && !inDoubleQuote) {
                return line.substring(i).trim();
            }
        }

        return "";
    }

    private static void appendRootBlock(List<String> lines, String themeName) {
        if (lines.isEmpty()) {
            lines.add(ROOT_KEY + ":");
            lines.add("  " + UI_THEME_KEY + ": " + quote(themeName));
            return;
        }

        int lastIndex = lines.size() - 1;
        while (lastIndex >= 0 && lines.get(lastIndex).trim().isEmpty()) {
            lastIndex--;
        }

        if (lastIndex >= 0 && !lines.get(lastIndex).trim().isEmpty()) {
            lines.add("");
        }

        lines.add(ROOT_KEY + ":");
        lines.add("  " + UI_THEME_KEY + ": " + quote(themeName));
    }

    private static String joinLines(List<String> lines) {
        if (lines.isEmpty()) {
            return "";
        }

        return String.join("\n", lines) + "\n";
    }

    private static String indent(int size) {
        StringBuilder builder = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            builder.append(' ');
        }
        return builder.toString();
    }

    private static String quote(String value) {
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }
}
