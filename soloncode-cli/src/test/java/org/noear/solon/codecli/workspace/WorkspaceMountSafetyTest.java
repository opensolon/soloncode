package org.noear.solon.codecli.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.talents.mount.Mount;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.ai.talents.mount.source.FileMountSource;
import org.noear.solon.ai.talents.mount.source.FindOptions;
import org.noear.solon.ai.talents.mount.source.MountCapabilities;
import org.noear.solon.ai.talents.mount.source.MountEntry;
import org.noear.solon.ai.talents.mount.source.MountSource;
import org.noear.solon.ai.talents.mount.source.MoveOptions;
import org.noear.solon.ai.talents.mount.source.WriteOptions;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 工作区挂载注册的容错边界：目录不可用必须跳过，但配置错误必须暴露。
 *
 * <p>前置缺陷：Windows junction 越界曾让整个默认工作区初始化失败；
 * 反向风险则是把所有异常一并吞掉，使用户配置错误的挂载点被静默丢弃。</p>
 */
public class WorkspaceMountSafetyTest {
    @TempDir
    Path tempDir;

    /** 发现阶段即抛异常的来源，模拟 junction 越界 / 网络盘丢失。 */
    private static final class BrokenMountSource implements MountSource {
        @Override
        public String getScheme() {
            return "broken";
        }

        @Override
        public String normalize(String path) {
            return path == null ? "" : path;
        }

        @Override
        public MountEntry stat(String path) {
            throw new SecurityException("Symbolic link escapes mount root: ego-browser/SKILL.md");
        }

        @Override
        public List<MountEntry> list(String path) {
            throw new SecurityException("Symbolic link escapes mount root: ego-browser");
        }

        @Override
        public List<MountEntry> find(String path, FindOptions options) {
            throw new SecurityException("Symbolic link escapes mount root: ego-browser/SKILL.md");
        }

        @Override
        public InputStream openRead(String path) {
            throw new SecurityException("Symbolic link escapes mount root: ego-browser/SKILL.md");
        }

        @Override
        public OutputStream openWrite(String path, WriteOptions options) {
            throw new SecurityException("Symbolic link escapes mount root: ego-browser/SKILL.md");
        }

        @Override
        public void delete(String path) {
            throw new SecurityException("Symbolic link escapes mount root: ego-browser");
        }

        @Override
        public void move(String source, String target, MoveOptions options) {
            throw new SecurityException("Symbolic link escapes mount root: ego-browser");
        }

        @Override
        public MountCapabilities capabilities() {
            return new MountCapabilities(true, true, true, true, true, true, true, false, false, false);
        }
    }

    private HarnessEngine newEngine() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("ws"));
        return HarnessEngine.of(workspace.toString(), ".soloncode/")
                .sessionProvider(new org.noear.solon.codecli.session.SessionManager(workspace.toString()))
                .build();
    }

    @Test
    public void unavailableMountShouldBeSkippedInsteadOfFailingWorkspace() throws Exception {
        HarnessEngine engine = newEngine();

        // 扫描期异常（junction 越界）只能跳过该挂载，不能让工作区构建失败
        assertDoesNotThrow(() -> WorkspaceManager.addMountSafely(engine, Mount.builder()
                .alias("@ego-browser")
                .type(MountType.SKILLS)
                .source(new BrokenMountSource())
                .build()));

        // 重点：不抛异常且挂载已注册（扫描失败只影响技能发现，不影响挂载本身）
        assertNotNull(engine.getMount("@ego-browser"));
    }

    @Test
    public void configurationErrorShouldStillBeThrown() throws Exception {
        HarnessEngine engine = newEngine();

        // 内置挂载不可被同名覆盖：这是配置错误，静默跳过等于悄悄丢弃用户的挂载配置
        assertThrows(IllegalArgumentException.class, () -> WorkspaceManager.addMountSafely(engine,
                Mount.builder()
                        .alias("harness-agents")
                        .type(MountType.AGENTS)
                        .source(FileMountSource.of(tempDir))
                        .build()));
    }
}
