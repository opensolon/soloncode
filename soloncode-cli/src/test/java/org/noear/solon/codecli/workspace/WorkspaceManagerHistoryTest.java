package org.noear.solon.codecli.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 工作区历史严格读取回归测试。
 *
 * @author noear
 */
public class WorkspaceManagerHistoryTest {
    @TempDir
    Path tempDir;

    @Test
    void strictRead_parsesObjectHistory() throws IOException {
        Path file = tempDir.resolve("workspaces.json");
        Files.write(file, ("{\"ws-one\":{\"name\":\"one\",\"path\":\"/tmp/one\",\"lastAccessed\":7}}")
                .getBytes(StandardCharsets.UTF_8));

        Collection<WorkspaceMeta> entries = WorkspaceManager.readWorkspaceEntriesStrict(file);

        assertEquals(1, entries.size());
        WorkspaceMeta entry = entries.iterator().next();
        assertEquals("ws-one", entry.getId());
        assertEquals("/tmp/one", entry.getPath());
        assertEquals(7L, entry.getLastAccessed());
    }

    @Test
    void strictRead_rejectsMalformedOrNonObjectHistory() throws IOException {
        Path malformed = tempDir.resolve("malformed.json");
        Files.write(malformed, "{broken".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> WorkspaceManager.readWorkspaceEntriesStrict(malformed));

        Path array = tempDir.resolve("array.json");
        Files.write(array, "[]".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> WorkspaceManager.readWorkspaceEntriesStrict(array));
    }
}
