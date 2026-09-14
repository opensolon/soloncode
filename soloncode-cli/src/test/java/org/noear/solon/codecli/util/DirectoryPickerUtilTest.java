package org.noear.solon.codecli.util;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DirectoryPickerUtil 单测（不弹真框）：覆盖类路径解析与常量可达性。
 *
 * @author noear
 */
public class DirectoryPickerUtilTest {

    /**
     * 开发模式（surefire 下 classes 目录）能解析出非空 classpath，
     * 且该位置确实包含子进程类
     */
    @Test
    void resolveClasspath_devMode_found() {
        String cp = DirectoryPickerUtil.resolveClasspath();
        assertNotNull("classpath should resolve under test (classes dir mode)", cp);

        File base = new File(cp);
        assertTrue(base.exists(), "resolved classpath should exist");
        File sub = new File(base, DirectoryPickerUtil.SUBPROCESS_ENTRY);
        assertTrue(sub.isFile(), "subprocess class should be reachable from resolved classpath");
    }

    /**
     * isAvailable 不抛异常（返回值随环境变化，不强断言）
     */
    @Test
    void isAvailable_noThrow() {
        DirectoryPickerUtil.isAvailable();
    }

    @Test
    void isAvailable_detectsHeadlessAndRemoteSessions() {
        Map<String, String> env = new HashMap<String, String>();
        assertFalse(DirectoryPickerUtil.isAvailable("Linux", env, null));

        env.put("DISPLAY", ":0");
        assertTrue(DirectoryPickerUtil.isAvailable("Linux", env, null));

        env.put("SSH_CONNECTION", "client server");
        assertFalse(DirectoryPickerUtil.isAvailable("Linux", env, null));
        assertFalse(DirectoryPickerUtil.isAvailable("Mac OS X", env, null));

        env.clear();
        env.put("SESSIONNAME", "Services");
        assertFalse(DirectoryPickerUtil.isAvailable("Windows Server 2022", env, null));
    }

    @Test
    void isAvailable_allowsExplicitOverride() {
        Map<String, String> env = new HashMap<String, String>();
        assertTrue(DirectoryPickerUtil.isAvailable("Linux", env, "true"));

        env.put("DISPLAY", ":0");
        assertFalse(DirectoryPickerUtil.isAvailable("Linux", env, "false"));
    }

    @Test
    void outputPath_onlyStripsLineBreaks() {
        assertEquals("/Users/test/project/", DirectoryPickerUtil.outputPath("/Users/test/project/\r\n"));
        assertEquals("/Users/test/space ", DirectoryPickerUtil.outputPath("/Users/test/space \n"));
        assertNull(DirectoryPickerUtil.outputPath("\r\n"));
    }

    @Test
    void protocolPath_distinguishesPickAndCancel() throws IOException {
        assertEquals("C:\\work\\目录 ", DirectoryPickerUtil.protocolPath("PICK C:\\work\\目录 \r\n"));
        assertNull(DirectoryPickerUtil.protocolPath("PICK_NONE\n"));
        assertThrows(IOException.class, () -> DirectoryPickerUtil.protocolPath("PICK \n"));
        assertThrows(IOException.class, () -> DirectoryPickerUtil.protocolPath("unexpected\n"));
    }

    @Test
    void macCommand_usesFinderAndStartDirectory() {
        List<String> command = DirectoryPickerUtil.macCommand("Choose \"workspace\"", new File("/Users/test"));
        assertEquals("osascript", command.get(0));
        assertTrue(command.get(2).contains("choose folder"));
        assertTrue(command.get(2).contains("default location"));
        assertTrue(command.get(2).contains("/Users/test"));
        assertTrue(command.get(2).contains("\\\"workspace\\\""));
    }

    @Test
    void windowsCommand_usesEncodedModernPicker() {
        List<String> command = DirectoryPickerUtil.windowsCommand("Choose Bob's folder", null);
        assertEquals("powershell.exe", command.get(0));
        assertTrue(command.contains("-STA"));
        assertTrue(command.contains("-NonInteractive"));
        assertEquals("-EncodedCommand", command.get(command.size() - 2));

        String encoded = command.get(command.size() - 1);
        assertTrue(encoded.length() < 30000,
                "encoded script must stay under the Windows command line limit");

        String script = decodePowerShell(encoded);
        assertTrue(script.contains("$__soloncodeTitle='Choose Bob''s folder'"),
                "title should be injected as a PowerShell single-quoted literal");
        assertTrue(script.contains("IFileDialog"), "modern dialog interop should be embedded");
        assertTrue(script.contains("FOS_PICKFOLDERS"), "folder mode flag should be set");
        assertTrue(script.contains("SetFolder"), "start directory support should be embedded");
        assertTrue(script.contains("TopMost"), "owner window must be topmost to avoid being hidden");
        assertTrue(script.contains("BrowseForFolder"), "legacy in-script fallback should be kept");
        assertTrue(script.contains("PICK_NONE"));
    }

    @Test
    void windowsCommand_forwardsStartDirectory() throws IOException {
        File startDir = java.nio.file.Files.createTempDirectory("soloncode-pick-test").toFile();
        startDir.deleteOnExit();

        List<String> command = DirectoryPickerUtil.windowsCommand("Choose", startDir);
        String script = decodePowerShell(command.get(command.size() - 1));
        assertTrue(script.contains("$__soloncodeStartDir='" + startDir.getAbsolutePath() + "'"),
                "start directory should be forwarded to the dialog");
    }

    @Test
    void psLiteral_escapesQuotesAndFlattensLineBreaks() {
        assertEquals("a''b", DirectoryPickerUtil.psLiteral("a'b"));
        assertEquals("a b", DirectoryPickerUtil.psLiteral("a\r\nb"));
        assertEquals("a b", DirectoryPickerUtil.psLiteral("a\nb"));
    }

    @Test
    void windowsCommand_blankStartDirIsInjectedEmpty() {
        List<String> command = DirectoryPickerUtil.windowsCommand("Choose", null);
        String script = decodePowerShell(command.get(command.size() - 1));
        assertTrue(script.contains("$__soloncodeStartDir=''"));
    }

    private static String decodePowerShell(String encoded) {
        return new String(java.util.Base64.getDecoder().decode(encoded),
                java.nio.charset.StandardCharsets.UTF_16LE);
    }

    @Test
    void unsupportedPlatform_requestsSwingFallback() throws Exception {
        DirectoryPickerUtil.NativePickResult result =
                DirectoryPickerUtil.pickNative("Plan 9", "Choose", 1000L, null);
        assertFalse(result.supported);
        assertNull(result.path);
    }
}
