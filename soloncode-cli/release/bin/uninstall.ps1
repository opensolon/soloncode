#
# Solon Code Uninstaller for Windows PowerShell
# 完全卸载 Solon Code，包括配置目录
#

$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "   Solon Code Uninstaller (PowerShell)" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# 检测管理员权限
$IS_ADMIN = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if ($IS_ADMIN) {
    Write-Host "[Info] Running with Administrator privileges" -ForegroundColor Yellow
} else {
    Write-Host "[Info] Running without Administrator privileges" -ForegroundColor Yellow
}

# 安装目录
$INSTALL_DIR = Join-Path $env:USERPROFILE ".soloncode"

# 检查是否已安装
if (-not (Test-Path $INSTALL_DIR)) {
    Write-Host ""
    Write-Host "[Info] Solon Code is not installed." -ForegroundColor Yellow
    Write-Host "       Directory not found: $INSTALL_DIR" -ForegroundColor Gray
    Read-Host "Press Enter to exit"
    exit 0
}

Write-Host ""
Write-Host "This will remove Solon Code completely:" -ForegroundColor White
Write-Host "  - Executables and configuration"
Write-Host "  - Skills modules"
Write-Host "  - PATH configuration"
Write-Host ""

$CONFIRM = Read-Host "Continue? (Y/N)"
if ($CONFIRM -ne "Y" -and $CONFIRM -ne "y") {
    Write-Host "Cancelled." -ForegroundColor Yellow
    Read-Host "Press Enter to exit"
    exit 0
}

# ============================================
#  [1/4] 从 PATH 中移除
# ============================================
Write-Host ""
Write-Host "[1/4] Removing from PATH..." -ForegroundColor Yellow

# 从用户 PATH 移除
$USER_PATH = [Environment]::GetEnvironmentVariable("Path", "User")
if ($USER_PATH) {
    $NEW_PATH = ($USER_PATH -split ';' | Where-Object { $_ -notmatch 'soloncode' }) -join ';'
    $NEW_PATH = $NEW_PATH.TrimStart(';').TrimEnd(';')
    [Environment]::SetEnvironmentVariable("Path", $NEW_PATH, "User")
    Write-Host "      Cleaned User PATH" -ForegroundColor Gray
}

# 从系统 PATH 移除（如果是管理员）
if ($IS_ADMIN) {
    $MACHINE_PATH = [Environment]::GetEnvironmentVariable("Path", "Machine")
    if ($MACHINE_PATH) {
        $NEW_PATH = ($MACHINE_PATH -split ';' | Where-Object { $_ -notmatch 'soloncode' }) -join ';'
        $NEW_PATH = $NEW_PATH.TrimStart(';').TrimEnd(';')
        [Environment]::SetEnvironmentVariable("Path", $NEW_PATH, "Machine")
        Write-Host "      Cleaned System PATH" -ForegroundColor Gray
    }
}

# ============================================
#  [2/4] 移除环境变量
# ============================================
Write-Host ""
Write-Host "[2/4] Removing environment variables..." -ForegroundColor Yellow

# 用户级
[Environment]::SetEnvironmentVariable("SOLONCODE_HOME", $null, "User")
Write-Host "      Removed User SOLONCODE_HOME" -ForegroundColor Gray

# 系统级（如果是管理员）
if ($IS_ADMIN) {
    [Environment]::SetEnvironmentVariable("SOLONCODE_HOME", $null, "Machine")
    Write-Host "      Removed System SOLONCODE_HOME" -ForegroundColor Gray
}

# ============================================
#  [3/4] 删除安装目录
# ============================================
Write-Host ""
Write-Host "[3/4] Removing installation directory..." -ForegroundColor Yellow

if (Test-Path $INSTALL_DIR) {
    try {
        Remove-Item -Path $INSTALL_DIR -Recurse -Force -ErrorAction Stop
        Write-Host "      Removed: $INSTALL_DIR" -ForegroundColor Green
    } catch {
        Write-Host "      [Warning] Could not remove $INSTALL_DIR" -ForegroundColor Yellow
        Write-Host "      Some files may be in use. Please restart and try again." -ForegroundColor Yellow
    }
} else {
    Write-Host "      Directory already removed" -ForegroundColor Gray
}

# ============================================
#  [4/4] 删除系统级启动器目录
# ============================================
Write-Host ""
Write-Host "[4/4] Cleaning up launcher directory..." -ForegroundColor Yellow

$PROGRAM_DATA_DIR = "C:\ProgramData\soloncode"
if (Test-Path $PROGRAM_DATA_DIR) {
    try {
        Remove-Item -Path $PROGRAM_DATA_DIR -Recurse -Force -ErrorAction Stop
        Write-Host "      Removed $PROGRAM_DATA_DIR" -ForegroundColor Green
    } catch {
        Write-Host "      [Note] Could not remove $PROGRAM_DATA_DIR (need admin)" -ForegroundColor Yellow
    }
} else {
    Write-Host "      No ProgramData launcher found" -ForegroundColor Gray
}

# ============================================
#  完成
# ============================================
Write-Host ""
Write-Host "============================================" -ForegroundColor Green
Write-Host "   Uninstall Complete!" -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Green
Write-Host ""
Write-Host "  Solon Code has been fully removed." -ForegroundColor White
Write-Host ""
Write-Host "  [Note] Please restart your terminal for" -ForegroundColor Yellow
Write-Host "         PATH changes to take effect." -ForegroundColor Yellow
Write-Host ""

Read-Host "Press Enter to exit"