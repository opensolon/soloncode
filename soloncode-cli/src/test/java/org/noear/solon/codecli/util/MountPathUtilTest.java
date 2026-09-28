package org.noear.solon.codecli.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MountPathUtilTest {
    @Test
    public void resolvesHomeAndWorkspaceRelativePaths() {
        String home = org.noear.solon.codecli.config.AgentFlags.getUserHome();
        assertEquals(Paths.get(home, "skills").toAbsolutePath().normalize(),
                MountPathUtil.resolve("~/skills", "/tmp/work").toAbsolutePath().normalize());
        assertEquals(Paths.get("/tmp/work", "skills").toAbsolutePath().normalize(),
                MountPathUtil.resolve("./skills", "/tmp/work").toAbsolutePath().normalize());
    }

    @Test
    public void keepsAbsolutePaths() {
        assertEquals(Paths.get("/tmp/mount").toAbsolutePath().normalize(),
                MountPathUtil.resolve("/tmp/mount", "/tmp/work"));
    }
}
