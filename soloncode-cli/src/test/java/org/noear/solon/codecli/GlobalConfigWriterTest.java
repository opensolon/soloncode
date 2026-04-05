package org.noear.solon.codecli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GlobalConfigWriterTest {
    @TempDir
    Path tempDir;

    @Test
    public void shouldReplaceExistingUiThemeLine() {
        String source = ""
            + "soloncode:\n"
            + "  uiTheme: \"solon\" # keep comment\n"
            + "  cliEnabled: true\n";

        String updated = GlobalConfigWriter.upsertUiTheme(source, "ocean");

        assertTrue(updated.contains("  uiTheme: \"ocean\" # keep comment\n"));
        assertTrue(updated.contains("  cliEnabled: true\n"));
    }

    @Test
    public void shouldInsertUiThemeIntoExistingSoloncodeBlock() {
        String source = ""
            + "soloncode:\n"
            + "  cliEnabled: true\n";

        String updated = GlobalConfigWriter.upsertUiTheme(source, "ocean");

        assertEquals("soloncode:\n  uiTheme: \"ocean\"\n  cliEnabled: true\n", updated);
    }

    @Test
    public void shouldAppendSoloncodeBlockWhenMissing() {
        String source = "server.port: 4808\n";

        String updated = GlobalConfigWriter.upsertUiTheme(source, "ocean");

        assertEquals("server.port: 4808\n\nsoloncode:\n  uiTheme: \"ocean\"\n", updated);
    }

    @Test
    public void shouldPersistUiThemeToConfigFile() throws Exception {
        Path configPath = tempDir.resolve("config.yml");
        Files.write(
            configPath,
            ("soloncode:\n  cliEnabled: true\n").getBytes(StandardCharsets.UTF_8));

        GlobalConfigWriter.persistUiTheme(configPath, "forest");

        String updated = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
        assertEquals("soloncode:\n  uiTheme: \"forest\"\n  cliEnabled: true\n", updated);
    }
}
