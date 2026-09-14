param(
    [string]$SoloncodeTitle = 'Select Workspace Directory',
    [string]$SoloncodeStartDir = '',
    [switch]$SoloncodeProbe,
    [switch]$SoloncodeSmoke
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$OutputEncoding = [Console]::OutputEncoding

try {
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

[Flags]
public enum SolonFileDialogOptions : uint
{
    FOS_PICKFOLDERS = 0x00000020,
    FOS_FORCEFILESYSTEM = 0x00000040,
    FOS_PATHMUSTEXIST = 0x00000800
}

// IFileDialog inherits IModalWindow. COM methods must remain in this exact vtable order.
[ComImport, Guid("42f85136-db7e-439c-85f1-e4075d135fc8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface SolonIFileDialog
{
    [PreserveSig] int Show(IntPtr parent);
    [PreserveSig] int SetFileTypes(uint cFileTypes, IntPtr rgFilterSpec);
    [PreserveSig] int SetFileTypeIndex(uint iFileType);
    [PreserveSig] int GetFileTypeIndex(out uint piFileType);
    [PreserveSig] int Advise(SolonIFileDialogEvents pfde, out uint pdwCookie);
    [PreserveSig] int Unadvise(uint dwCookie);
    [PreserveSig] int SetOptions(SolonFileDialogOptions fos);
    [PreserveSig] int GetOptions(out SolonFileDialogOptions pfos);
    [PreserveSig] int SetDefaultFolder(SolonIShellItem psi);
    [PreserveSig] int SetFolder(SolonIShellItem psi);
    [PreserveSig] int GetFolder(out SolonIShellItem ppsi);
    [PreserveSig] int GetCurrentSelection(out SolonIShellItem ppsi);
    [PreserveSig] int SetFileName([MarshalAs(UnmanagedType.LPWStr)] string pszName);
    [PreserveSig] int GetFileName([MarshalAs(UnmanagedType.LPWStr)] out string pszName);
    [PreserveSig] int SetTitle([MarshalAs(UnmanagedType.LPWStr)] string pszTitle);
    [PreserveSig] int SetOkButtonLabel([MarshalAs(UnmanagedType.LPWStr)] string pszText);
    [PreserveSig] int SetFileNameLabel([MarshalAs(UnmanagedType.LPWStr)] string pszLabel);
    [PreserveSig] int GetResult(out SolonIShellItem ppsi);
    [PreserveSig] int AddPlace(SolonIShellItem psi, int fdap);
    [PreserveSig] int SetDefaultExtension([MarshalAs(UnmanagedType.LPWStr)] string pszDefaultExtension);
    [PreserveSig] int Close(int hr);
    [PreserveSig] int SetClientGuid(ref Guid guid);
    [PreserveSig] int ClearClientData();
    [PreserveSig] int SetFilter(IntPtr pFilter);
}

[ComImport, Guid("43826d1e-e718-42ee-bc55-a1e261c37bfe"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface SolonIShellItem
{
    [PreserveSig] int BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppv);
    [PreserveSig] int GetParent(out SolonIShellItem ppsi);
    [PreserveSig] int GetDisplayName(uint sigdnName, out IntPtr ppszName);
    [PreserveSig] int GetAttributes(uint sfgaoMask, out uint psfgaoAttribs);
    [PreserveSig] int Compare(SolonIShellItem psi, uint hint, out int piOrder);
}

[ComImport, Guid("00000114-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface SolonIOleWindow
{
    [PreserveSig] int GetWindow(out IntPtr phwnd);
    [PreserveSig] int ContextSensitiveHelp([MarshalAs(UnmanagedType.Bool)] bool enterMode);
}

[ComImport, Guid("973510db-7d7f-452b-8975-74a85828d354"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface SolonIFileDialogEvents
{
    [PreserveSig] int OnFileOk(SolonIFileDialog dialog);
    [PreserveSig] int OnFolderChanging(SolonIFileDialog dialog, SolonIShellItem folder);
    [PreserveSig] int OnFolderChange(SolonIFileDialog dialog);
    [PreserveSig] int OnSelectionChange(SolonIFileDialog dialog);
    [PreserveSig] int OnShareViolation(SolonIFileDialog dialog, SolonIShellItem item, out int response);
    [PreserveSig] int OnTypeChange(SolonIFileDialog dialog);
    [PreserveSig] int OnOverwrite(SolonIFileDialog dialog, SolonIShellItem item, out int response);
}

[ComVisible(true), ClassInterface(ClassInterfaceType.None)]
public sealed class SolonFileDialogEvents : SolonIFileDialogEvents, IDisposable
{
    private const int ErrorCancelled = unchecked((int)0x800704C7);
    private readonly bool smoke;
    private readonly object sync = new object();
    private System.Threading.Timer promotionTimer;
    private System.Threading.Timer smokeTimer;
    private IntPtr dialogHwnd;

    public SolonFileDialogEvents(bool smokeTest, SolonIFileDialog dialog)
    {
        smoke = smokeTest;
        // Poll the real dialog HWND independently of Shell callbacks: callbacks are not guaranteed
        // during initial creation, which previously left the dialog behind the browser.
        promotionTimer = new System.Threading.Timer(delegate { Promote(dialog); }, null, 50, 100);
        if (smoke)
            smokeTimer = new System.Threading.Timer(delegate { try { dialog.Close(ErrorCancelled); } catch { } }, null, 750, Timeout.Infinite);
    }

    private void Promote(SolonIFileDialog dialog)
    {
        try
        {
            SolonIOleWindow window = (SolonIOleWindow)dialog;
            IntPtr hwnd;
            if (window.GetWindow(out hwnd) < 0 || hwnd == IntPtr.Zero) return;
            lock (sync) { dialogHwnd = hwnd; }
            PromoteWindow(null);
        }
        catch { }
    }

    private void PromoteWindow(object state)
    {
        IntPtr hwnd;
        lock (sync) { hwnd = dialogHwnd; }
        if (hwnd == IntPtr.Zero || !SolonShellNative.IsWindow(hwnd)) return;
        SolonShellNative.SetWindowPos(hwnd, new IntPtr(-1), 0, 0, 0, 0,
            0x0001u | 0x0002u | 0x0010u | 0x0200u);
        SolonShellNative.BringWindowToTop(hwnd);
        SolonShellNative.SetForegroundWindow(hwnd);
    }

    public void Dispose()
    {
        lock (sync)
        {
            if (promotionTimer != null) { promotionTimer.Dispose(); promotionTimer = null; }
            if (smokeTimer != null) { smokeTimer.Dispose(); smokeTimer = null; }
            dialogHwnd = IntPtr.Zero;
        }
    }

    public int OnFileOk(SolonIFileDialog dialog) { Promote(dialog); return 0; }
    public int OnFolderChanging(SolonIFileDialog dialog, SolonIShellItem folder) { Promote(dialog); return 0; }
    public int OnFolderChange(SolonIFileDialog dialog) { Promote(dialog); return 0; }
    public int OnSelectionChange(SolonIFileDialog dialog) { Promote(dialog); return 0; }
    public int OnShareViolation(SolonIFileDialog dialog, SolonIShellItem item, out int response) { response = 0; Promote(dialog); return 0; }
    public int OnTypeChange(SolonIFileDialog dialog) { Promote(dialog); return 0; }
    public int OnOverwrite(SolonIFileDialog dialog, SolonIShellItem item, out int response) { response = 0; Promote(dialog); return 0; }
}

public static class SolonShellNative
{
    [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = true)]
    public static extern int SHCreateItemFromParsingName(
        [MarshalAs(UnmanagedType.LPWStr)] string path, IntPtr pbc, ref Guid riid,
        [MarshalAs(UnmanagedType.Interface)] out SolonIShellItem item);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern IntPtr GetAncestor(IntPtr hwnd, uint flags);
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] public static extern bool IsWindow(IntPtr hwnd);
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] public static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint processId);
    [DllImport("kernel32.dll")] public static extern uint GetCurrentProcessId();
    [DllImport("user32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)] public static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)] public static extern bool BringWindowToTop(IntPtr hwnd);
    [DllImport("user32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int cx, int cy, uint flags);

    public static IntPtr CaptureForegroundOwner()
    {
        IntPtr hwnd = GetForegroundWindow();
        if (hwnd == IntPtr.Zero) return IntPtr.Zero;
        IntPtr root = GetAncestor(hwnd, 2u);
        if (root != IntPtr.Zero) hwnd = root;
        uint processId;
        GetWindowThreadProcessId(hwnd, out processId);
        if (!IsWindow(hwnd) || !IsWindowVisible(hwnd) || processId == 0 || processId == GetCurrentProcessId()) return IntPtr.Zero;
        return hwnd;
    }
}

public static class SolonModernFolderPicker
{
    private const int ErrorCancelled = unchecked((int)0x800704C7);
    private const uint SigDnFileSystemPath = 0x80058000u;

    private static void Check(int hr) { if (hr < 0) Marshal.ThrowExceptionForHR(hr); }

    public static string Pick(string title, string startDir, bool smoke)
    {
        object raw = null;
        SolonIFileDialog dialog = null;
        SolonIShellItem folder = null;
        SolonIShellItem result = null;
        SolonFileDialogEvents events = null;
        Form owner = null;
        IntPtr pathPtr = IntPtr.Zero;
        uint cookie = 0;
        bool advised = false;
        try
        {
            IntPtr hwnd = SolonShellNative.CaptureForegroundOwner();
            if (hwnd == IntPtr.Zero)
            {
                owner = new Form();
                owner.FormBorderStyle = FormBorderStyle.None;
                owner.ShowInTaskbar = false;
                owner.StartPosition = FormStartPosition.Manual;
                owner.Location = new System.Drawing.Point(-32000, -32000);
                owner.Size = new System.Drawing.Size(1, 1);
                owner.Opacity = 0;
                owner.TopMost = true;
                owner.Show();
                Application.DoEvents();
                hwnd = owner.Handle;
                SolonShellNative.SetWindowPos(hwnd, new IntPtr(-1), 0, 0, 0, 0, 0x0001u | 0x0002u | 0x0010u);
            }

            raw = Activator.CreateInstance(Type.GetTypeFromCLSID(new Guid("dc1c5a9c-e88a-4dde-a5a1-60f82a20aef7"), true));
            dialog = (SolonIFileDialog)raw;
            if (dialog == null) throw new InvalidOperationException("IFileDialog activation returned null.");

            SolonFileDialogOptions options;
            Check(dialog.GetOptions(out options));
            options |= SolonFileDialogOptions.FOS_PICKFOLDERS | SolonFileDialogOptions.FOS_FORCEFILESYSTEM | SolonFileDialogOptions.FOS_PATHMUSTEXIST;
            Check(dialog.SetOptions(options));
            Check(dialog.SetTitle(String.IsNullOrEmpty(title) ? "Select Workspace Directory" : title));

            if (!String.IsNullOrWhiteSpace(startDir) && System.IO.Directory.Exists(startDir))
            {
                Guid iid = new Guid("43826d1e-e718-42ee-bc55-a1e261c37bfe");
                Check(SolonShellNative.SHCreateItemFromParsingName(startDir, IntPtr.Zero, ref iid, out folder));
                Check(dialog.SetFolder(folder));
            }

            events = new SolonFileDialogEvents(smoke, dialog);
            Check(dialog.Advise(events, out cookie));
            advised = true;
            int showHr = dialog.Show(hwnd);
            if (showHr == ErrorCancelled) return null;
            Check(showHr);
            Check(dialog.GetResult(out result));
            if (result == null) throw new InvalidOperationException("IFileDialog returned no shell item.");
            Check(result.GetDisplayName(SigDnFileSystemPath, out pathPtr));
            if (pathPtr == IntPtr.Zero) throw new InvalidOperationException("IFileDialog returned no filesystem path.");
            string path = Marshal.PtrToStringUni(pathPtr);
            if (String.IsNullOrEmpty(path)) throw new InvalidOperationException("IFileDialog returned an empty filesystem path.");
            return path;
        }
        finally
        {
            if (advised && dialog != null) { try { dialog.Unadvise(cookie); } catch { } }
            if (events != null) events.Dispose();
            if (pathPtr != IntPtr.Zero) Marshal.FreeCoTaskMem(pathPtr);
            if (result != null) try { Marshal.FinalReleaseComObject(result); } catch { }
            if (folder != null) try { Marshal.FinalReleaseComObject(folder); } catch { }
            if (raw != null) try { Marshal.FinalReleaseComObject(raw); } catch { }
            if (owner != null) { owner.Close(); owner.Dispose(); }
        }
    }
}
'@ -ReferencedAssemblies System.Windows.Forms,System.Drawing

    if ($SoloncodeProbe) {
        Write-Output ('PICK_PROBE_OK foregroundOwner=' + [SolonShellNative]::CaptureForegroundOwner())
        return
    }

    $path = [SolonModernFolderPicker]::Pick($SoloncodeTitle, $SoloncodeStartDir, $SoloncodeSmoke.IsPresent)
    if ($SoloncodeSmoke) {
        Write-Output 'PICK_SMOKE_OK'
    } elseif ([string]::IsNullOrEmpty($path)) {
        Write-Output 'PICK_NONE'
    } else {
        Write-Output ('PICK ' + $path)
    }
} catch {
    [Console]::Error.WriteLine('Modern Windows directory picker failed: ' + $_.Exception.ToString())
    throw
}
