package org.noear.solon.codecli.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JdkHomeUtil 测试
 *
 * @author noear
 */
public class JdkHomeUtilTest {

    @BeforeEach
    void setUp() {
        JdkHomeUtil.clearCache();
    }

    @Test
    void parseMajor_legacyAndModern() {
        assertEquals(8, JdkHomeUtil.parseMajor("1.8.0_181"));
        assertEquals(8, JdkHomeUtil.parseMajor("1.8"));
        assertEquals(21, JdkHomeUtil.parseMajor("21.0.1"));
        assertEquals(21, JdkHomeUtil.parseMajor("21"));
        assertEquals(25, JdkHomeUtil.parseMajor("25.0.1+8.1"));
        assertEquals(-1, JdkHomeUtil.parseMajor(""));
        assertEquals(-1, JdkHomeUtil.parseMajor(null));
        assertEquals(-1, JdkHomeUtil.parseMajor("not-a-version"));
    }

    @Test
    void parseMajorFromName_commonLayouts() {
        assertEquals(21, JdkHomeUtil.parseMajorFromName("/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"));
        assertEquals(23, JdkHomeUtil.parseMajorFromName("/Users/x/Library/Java/JavaVirtualMachines/openjdk-23/Contents/Home"));
        assertEquals(25, JdkHomeUtil.parseMajorFromName("/Library/Java/JavaVirtualMachines/graalvm-ce-25/Contents/Home"));
        assertEquals(17, JdkHomeUtil.parseMajorFromName("/usr/lib/jvm/java-17-openjdk-amd64"));
        // 1.8 风格目录名不应被误判成主版本 1
        assertNotEquals(1, JdkHomeUtil.parseMajorFromName("/Library/Java/JavaVirtualMachines/jdk1.8.0_181.jdk/Contents/Home"));
    }

    @Test
    void majorOf_requiresRealJavaBinary(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        // 空目录：不是 JDK
        assertEquals(-1, JdkHomeUtil.majorOf(tmp));

        // 只有 release 文件、没有 bin/java：仍然不算
        Files.write(tmp.resolve("release"), "JAVA_VERSION=\"21.0.1\"\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(-1, JdkHomeUtil.majorOf(tmp));

        // 补上 bin/java 后按 release 解析
        Files.createDirectories(tmp.resolve("bin"));
        Files.write(tmp.resolve("bin/java"), new byte[0]);
        assertEquals(21, JdkHomeUtil.majorOf(tmp));

        // 不存在的路径
        assertEquals(-1, JdkHomeUtil.majorOf(tmp.resolve("nope")));
        assertEquals(-1, JdkHomeUtil.majorOf(null));
    }

    @Test
    void majorOf_fallsBackToDirName(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path home = tmp.resolve("jdk-24.jdk/Contents/Home");
        Files.createDirectories(home.resolve("bin"));
        Files.write(home.resolve("bin/java"), new byte[0]);

        // 无 release 文件 -> 退回目录名推断
        assertEquals(24, JdkHomeUtil.majorOf(home));
    }

    @Test
    void findJavaHomeAtLeast_currentJvmAlwaysSatisfiesItsOwnVersion() {
        int self = JdkHomeUtil.parseMajor(System.getProperty("java.version"));
        assertTrue(self > 0, "无法解析当前 JVM 版本：" + System.getProperty("java.version"));

        String found = JdkHomeUtil.findJavaHomeAtLeast(self);
        assertNotNull(found, "至少应能找到当前进程自身的 JDK");
        assertTrue(JdkHomeUtil.majorOf(java.nio.file.Paths.get(found)) >= self);
    }

    @Test
    void findJavaHomeAtLeast_resultAlwaysMeetsTheFloor() {
        // 本机可能没装 21+，此时返回 null 是合法结果；返回非 null 则必须真的满足下限
        String found = JdkHomeUtil.findJavaHomeAtLeast(21);
        if (found != null) {
            assertTrue(JdkHomeUtil.majorOf(java.nio.file.Paths.get(found)) >= 21, "返回的 JDK 未达下限: " + found);
        }
    }

    @Test
    void findJavaHomeAtLeast_unreachableFloorReturnsNull() {
        assertNull(JdkHomeUtil.findJavaHomeAtLeast(9999));
    }

    @Test
    void currentJavaHomeSatisfies_consistentWithEnv() {
        String envHome = System.getenv("JAVA_HOME");
        if (envHome == null || envHome.isEmpty()) {
            assertFalse(JdkHomeUtil.currentJavaHomeSatisfies(1));
            return;
        }

        int major = JdkHomeUtil.majorOf(java.nio.file.Paths.get(envHome));
        if (major > 0) {
            assertTrue(JdkHomeUtil.currentJavaHomeSatisfies(major));
            assertFalse(JdkHomeUtil.currentJavaHomeSatisfies(major + 1));
        }
    }
}
