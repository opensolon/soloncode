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
        File startDir = new File("/Users/test");
        List<String> command = DirectoryPickerUtil.macCommand("Choose \"workspace\"", startDir);
        assertEquals("osascript", command.get(0));
        assertTrue(command.get(2).contains("choose folder"));
        assertTrue(command.get(2).contains("default location"));
        assertTrue(command.get(2).contains(startDir.getAbsolutePath().replace("\\", "\\\\")),
                "the assertion must account for AppleScript escaping on every test platform");
        assertTrue(command.get(2).contains("\\\"workspace\\\""));
    }

    @Test
    void windowsCommand_usesModernPickerScriptFile() throws IOException {
        List<String> command = DirectoryPickerUtil.windowsCommand("Choose Bob's folder", null);
        assertEquals("powershell.exe", command.get(0));
        assertTrue(command.contains("-STA"));
        assertTrue(command.contains("-NonInteractive"));
        assertTrue(command.contains("-ExecutionPolicy"));
        assertTrue(command.contains("Bypass"));
        assertTrue(command.contains("-File"));
        assertFalse(command.contains("-EncodedCommand"),
                "a growing embedded script must not approach the Windows command-line limit");
        assertEquals("Choose Bob's folder", command.get(command.indexOf("-SoloncodeTitle") + 1));
        assertEquals("", command.get(command.indexOf("-SoloncodeStartDir") + 1));
        assertTrue(new File(command.get(command.indexOf("-File") + 1)).isFile(),
                "the packaged picker resource should be materialized as a ps1 file");

        String script = DirectoryPickerUtil.WINDOWS_PICKER_SCRIPT;
        assertNotNull(script);
        assertTrue(script.contains("IFileDialog"), "modern dialog interop should be embedded");
        assertTrue(script.contains("42f85136-db7e-439c-85f1-e4075d135fc8"),
                "the declared COM interface must be IFileDialog");
        assertTrue(script.contains("dc1c5a9c-e88a-4dde-a5a1-60f82a20aef7"),
                "the modern FileOpenDialog COM class must be instantiated");
        assertTrue(script.contains("SolonFileDialogOptions.FOS_PICKFOLDERS"));
        assertTrue(script.contains("SolonFileDialogOptions.FOS_FORCEFILESYSTEM"));
        assertTrue(script.contains("SolonFileDialogOptions.FOS_PATHMUSTEXIST"));
        assertTrue(script.contains("SetFolder"), "start directory support should be embedded");

        int captureOwner = script.indexOf("SolonShellNative.CaptureForegroundOwner()");
        int createHelper = script.indexOf("owner = new Form()");
        assertTrue(captureOwner >= 0 && captureOwner < createHelper,
                "the foreground browser must be captured before any helper window is created");
        assertTrue(script.contains("dialog.Show(hwnd)"),
                "the captured browser/root HWND must be the dialog owner");
        assertTrue(script.contains("GetAncestor(hwnd, 2u)"), "the foreground root window must be used");
        assertTrue(script.contains("processId == GetCurrentProcessId()"),
                "the picker must reject its own process window as an external owner");

        assertTrue(script.contains("SolonIFileDialogEvents"));
        assertTrue(script.contains("SolonIOleWindow"));
        assertTrue(script.contains("dialog.Advise(events"));
        assertTrue(script.contains("dialog.Unadvise(cookie)"));
        assertTrue(script.contains("new System.Threading.Timer(delegate { Promote(dialog); }, null, 50, 100)"),
                "real HWND promotion must start independently of optional Shell callbacks");
        assertTrue(script.contains("SolonShellNative.SetWindowPos(hwnd"));
        assertTrue(script.contains("events.Dispose()"), "the promotion timer must always be disposed");
        assertFalse(script.contains("AttachThreadInput"));
        assertFalse(script.contains("AllowSetForegroundWindow"));
        assertFalse(script.contains("BrowseForFolder"), "the obsolete directory picker must not be present");
        assertFalse(script.contains("Shell.Application"), "the legacy Shell automation fallback must not be present");
        assertTrue(script.contains("SigDnFileSystemPath = 0x80058000u"));
        assertTrue(script.contains("PICK_NONE"));
        assertTrue(script.contains("PICK_PROBE_OK"), "a non-interactive native compilation probe is required");
        assertTrue(script.contains("PICK_SMOKE_OK"), "an auto-closing real dialog smoke test is required");

        int interfaceStart = script.indexOf("public interface SolonIFileDialog");
        int interfaceEnd = script.indexOf("\n}", interfaceStart);
        assertTrue(interfaceStart >= 0 && interfaceEnd > interfaceStart);
        String interfaceBlock = script.substring(interfaceStart, interfaceEnd);
        int showMethod = interfaceBlock.indexOf("int Show(IntPtr parent)");
        int setFileTypesMethod = interfaceBlock.indexOf("int SetFileTypes(");
        assertTrue(showMethod >= 0 && showMethod < setFileTypesMethod,
                "IModalWindow.Show must be the first COM method to preserve IFileDialog vtable order");
    }

    @Test
    void windowsCommand_forwardsStartDirectory() throws IOException {
        File startDir = java.nio.file.Files.createTempDirectory("soloncode-pick-test").toFile();
        startDir.deleteOnExit();

        List<String> command = DirectoryPickerUtil.windowsCommand("Choose", startDir);
        assertEquals(startDir.getAbsolutePath(), command.get(command.indexOf("-SoloncodeStartDir") + 1),
                "start directory should be forwarded as a direct process argument");
    }

    @Test
    void windowsPickerProbe_compilesInteropAndCallsWin32WithoutOpeningDialog() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows"));
        String output = DirectoryPickerUtil.probeWindowsPicker(30_000L);
        assertTrue(output.contains("PICK_PROBE_OK"));
        assertTrue(output.contains("foregroundOwner="));
    }

    @Test
    void windowsPickerSmoke_opensRealDialogAndClosesAutomatically() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows"));
        String output = DirectoryPickerUtil.smokeWindowsPicker(30_000L);
        assertTrue(output.contains("PICK_SMOKE_OK"));
    }

    @Test
    void unsupportedPlatform_requestsSwingFallback() throws Exception {
        DirectoryPickerUtil.NativePickResult result =
                DirectoryPickerUtil.pickNative("Plan 9", "Choose", 1000L, null);
        assertFalse(result.supported);
        assertNull(result.path);
    }
}
