#requires -Version 2.0
#
# Solon Code Uninstaller for Windows PowerShell
# 完全卸载当前用户的 Solon Code，包括配置目录
#

$ErrorActionPreference = "Stop"

function Assert-OrdinaryDirectory($Path) {
    $ITEM = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if (($ITEM.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Refusing to follow a reparse point: $Path"
    }
    if ($ITEM.PSProvider.Name -ne "FileSystem" -or -not $ITEM.PSIsContainer) {
        throw "Expected an ordinary file system directory: $Path"
    }
}

function Assert-SafeInstallation($ProfilePath, $InstallPath) {
    # Restrict deletion to a canonical, local user profile, never a drive root,
    # UNC/device path, alternate data stream or caller-selected install location.
    if ([string]::IsNullOrEmpty($ProfilePath) -or
        $ProfilePath -notmatch '^[A-Za-z]:\\[^:]*$') {
        throw "USERPROFILE must be an absolute local drive path."
    }
    $PROFILE_FULL = [System.IO.Path]::GetFullPath($ProfilePath).TrimEnd([char]'\')
    if ($PROFILE_FULL.Length -le 3 -or
        -not [string]::Equals($PROFILE_FULL, $ProfilePath.TrimEnd([char]'\'), [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "USERPROFILE must be a canonical directory below a drive root."
    }
    foreach ($PART in $PROFILE_FULL.Substring(3).Split([char]'\')) {
        if ($PART.Length -eq 0 -or $PART.EndsWith(".") -or $PART.EndsWith(" ")) {
            throw "Ambiguous USERPROFILE path component."
        }
    }
    $EXPECTED = [System.IO.Path]::Combine($PROFILE_FULL, ".soloncode")
    if (-not [string]::Equals($InstallPath, $EXPECTED, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to delete anything except the expected user installation: $EXPECTED"
    }

    # Inspect ancestors from the volume root down before accessing descendants.
    # Reject redirected profiles as well as junctions/symlinks at the target.
    $ANCESTORS = New-Object System.Collections.Stack
    $CURRENT = $PROFILE_FULL
    while ($null -ne $CURRENT) {
        $ANCESTORS.Push($CURRENT)
        $PARENT = [System.IO.Directory]::GetParent($CURRENT)
        if ($null -eq $PARENT) {
            $CURRENT = $null
        } else {
            $CURRENT = $PARENT.FullName
        }
    }
    while ($ANCESTORS.Count -gt 0) {
        Assert-OrdinaryDirectory ($ANCESTORS.Pop())
    }

    # Enumerate literally, including hidden entries, rather than treating a
    # failed Test-Path (e.g. a dangling link) as proof that nothing is installed.
    $FOUND = $false
    foreach ($ENTRY in (Get-ChildItem -LiteralPath $PROFILE_FULL -Force -ErrorAction Stop)) {
        if ([string]::Equals($ENTRY.Name, ".soloncode", [System.StringComparison]::OrdinalIgnoreCase)) {
            $FOUND = $true
            break
        }
    }
    if (-not $FOUND) {
        return $false
    }
    Assert-OrdinaryDirectory $InstallPath

    # Never recurse through an unchecked entry. Any reparse point anywhere in
    # the tree (including files/hidden entries) or enumeration error aborts.
    $PENDING = New-Object System.Collections.Stack
    $PENDING.Push($InstallPath)
    while ($PENDING.Count -gt 0) {
        $DIRECTORY = [string]$PENDING.Pop()
        Assert-OrdinaryDirectory $DIRECTORY
        foreach ($ENTRY in (Get-ChildItem -LiteralPath $DIRECTORY -Force -ErrorAction Stop)) {
            if (($ENTRY.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Refusing to delete a tree containing a reparse point: $($ENTRY.FullName)"
            }
            if ($ENTRY.PSIsContainer) {
                $PENDING.Push($ENTRY.FullName)
            }
        }
    }
    return $true
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "   Solon Code Uninstaller (PowerShell)" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "[Info] Only the current user's installation will be removed." -ForegroundColor Yellow
Write-Host "[Note] Machine PATH/environment and legacy shared ProgramData launchers" -ForegroundColor Yellow
Write-Host "       are left unchanged: ownership cannot be established by this uninstaller."

# Preflight is read-only and must finish before ANY persistent changes.
try {
    if ([string]::IsNullOrEmpty($env:USERPROFILE)) {
        throw "USERPROFILE is not set."
    }
    $PROFILE_DIR = $env:USERPROFILE.TrimEnd([char]'\')
    $INSTALL_DIR = [System.IO.Path]::Combine($PROFILE_DIR, ".soloncode")
    $BIN_DIR = [System.IO.Path]::Combine($INSTALL_DIR, "bin")
    $INSTALLED = Assert-SafeInstallation $PROFILE_DIR $INSTALL_DIR
} catch {
    Write-Host "[Error] Safety preflight failed. No uninstall changes were made." -ForegroundColor Red
    Write-Host "        $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

if (-not $INSTALLED) {
    Write-Host ""
    Write-Host "[Info] Solon Code is not installed for the current user." -ForegroundColor Yellow
    Write-Host "       Directory not found: $INSTALL_DIR" -ForegroundColor Gray
    Write-Host "       Exact matching User PATH/SOLONCODE_HOME entries will still be cleaned." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "This will remove the current user's Solon Code completely:" -ForegroundColor White
Write-Host "  - Executables and configuration: $INSTALL_DIR"
Write-Host "  - Skills modules in that directory"
Write-Host "  - Only this installation's bin entry in User PATH"
Write-Host "  - User SOLONCODE_HOME only if it points to this installation"
Write-Host ""

$CONFIRM = Read-Host "Continue? (Y/N)"
if ($CONFIRM -ne "Y" -and $CONFIRM -ne "y") {
    Write-Host "Cancelled." -ForegroundColor Yellow
    Read-Host "Press Enter to exit"
    exit 0
}

try {
    # Recheck after the prompt, before deletion or environment writes. This is
    # not an atomic lock: do not run while another process changes this tree.
    $INSTALLED = Assert-SafeInstallation $PROFILE_DIR $INSTALL_DIR

    # Read and validate environment values before deleting the installation.
    # Read the raw value: Environment.GetEnvironmentVariable would expand
    # REG_EXPAND_SZ entries and could change unrelated paths on write-back.
    $USER_ENV = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey("Environment", $true)
    try {
        $USER_PATH = $null
        $PATH_CHANGED = $false
        if ($null -ne $USER_ENV) {
            $USER_PATH = $USER_ENV.GetValue("Path", $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
        }
        if ($null -ne $USER_PATH) {
            $PATH_KIND = $USER_ENV.GetValueKind("Path")
            if ($PATH_KIND -ne [Microsoft.Win32.RegistryValueKind]::String -and
                $PATH_KIND -ne [Microsoft.Win32.RegistryValueKind]::ExpandString) {
                throw "User PATH is not a string value; refusing to rewrite it."
            }
            $KEPT_PATH = New-Object System.Collections.ArrayList
            foreach ($PART in ($USER_PATH -split ';')) {
                # Whole semicolon-delimited entries only; preserve all other text,
                # ordering and empty entries. Do not match product-name substrings.
                if ([string]::Equals($PART, $BIN_DIR, [System.StringComparison]::OrdinalIgnoreCase)) {
                    $PATH_CHANGED = $true
                } else {
                    [void]$KEPT_PATH.Add($PART)
                }
            }
            if ($PATH_CHANGED) {
                $NEW_PATH = [string]::Join(";", [string[]]$KEPT_PATH.ToArray())
            }
        }
        $USER_HOME = [Environment]::GetEnvironmentVariable("SOLONCODE_HOME", "User")

        Write-Host ""
        Write-Host "[1/3] Removing user installation and configuration..." -ForegroundColor Yellow
        if ($INSTALLED) {
            Remove-Item -LiteralPath $INSTALL_DIR -Recurse -Force -ErrorAction Stop
            if (Test-Path -LiteralPath $INSTALL_DIR -ErrorAction Stop) {
                throw "Installation directory still exists: $INSTALL_DIR"
            }
            Write-Host "      Removed: $INSTALL_DIR" -ForegroundColor Green
        } else {
            Write-Host "      Directory already removed" -ForegroundColor Gray
        }

        Write-Host ""
        Write-Host "[2/3] Removing this installation's bin from User PATH..." -ForegroundColor Yellow
        if ($PATH_CHANGED) {
            $USER_ENV.SetValue("Path", $NEW_PATH, $PATH_KIND)
            $USER_ENV.Flush()
            Write-Host "      Removed matching User PATH entries" -ForegroundColor Gray
        } else {
            Write-Host "      No matching User PATH entry; left unchanged" -ForegroundColor Gray
        }
    } finally {
        if ($null -ne $USER_ENV) {
            $USER_ENV.Close()
        }
    }

    Write-Host ""
    Write-Host "[3/3] Checking User SOLONCODE_HOME..." -ForegroundColor Yellow
    if ([string]::Equals($USER_HOME, $INSTALL_DIR, [System.StringComparison]::OrdinalIgnoreCase)) {
        [Environment]::SetEnvironmentVariable("SOLONCODE_HOME", $null, "User")
        Write-Host "      Removed User SOLONCODE_HOME for this installation" -ForegroundColor Gray
    } elseif (-not [string]::IsNullOrEmpty($USER_HOME)) {
        Write-Host "      Preserved User SOLONCODE_HOME: it does not identify this installation" -ForegroundColor Yellow
    }
} catch {
    Write-Host ""
    Write-Host "[Error] Uninstall failed or is incomplete." -ForegroundColor Red
    Write-Host "        $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "        Some changes may already have been made; no rollback was attempted." -ForegroundColor Yellow
    Write-Host "        Close processes using the installation, then inspect it before retrying." -ForegroundColor Yellow
    exit 1
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Green
Write-Host "   User Uninstall Complete!" -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Green
Write-Host ""
Write-Host "  The current user's installation and configuration have been removed." -ForegroundColor White
Write-Host "  Shared/system installations and unrelated environment entries were not changed."
Write-Host ""
Write-Host "  [Note] Sign out and back in for PATH changes to reach all new processes." -ForegroundColor Yellow
Write-Host ""
Read-Host "Press Enter to exit"
exit 0
