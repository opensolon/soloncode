#
# Solon Code Installer for Windows PowerShell
# 支持重复安装，保留已有 config.yml
#

$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "   Solon Code Installer (PowerShell)" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# =============================================
# 检查 Java 是否安装
# =============================================
Write-Host "[Pre-check] Verifying Java installation..." -ForegroundColor Yellow

$javaPath = Get-Command java -ErrorAction SilentlyContinue
if (-not $javaPath) {
    Write-Host ""
    Write-Host "[Error] Java is not installed or not in PATH" -ForegroundColor Red
    Write-Host ""
    Write-Host "  Please install Java 8 or later:" -ForegroundColor White
    Write-Host "    - Download from: https://adoptium.net/" -ForegroundColor White
    Write-Host "    - Or use: winget install EclipseAdoptium.Temurin.17" -ForegroundColor White
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit 1
}

# 获取 Java 版本
$javaVersion = & java -version 2>&1 | Select-String -Pattern "version" | Select-Object -First 1
Write-Host "      Java $javaVersion" -ForegroundColor Gray
Write-Host ""

# =============================================
# 设置源目录和目标目录
# =============================================
$SOURCE_DIR = Split-Path -Parent $MyInvocation.MyCommand.Definition
if (-not $SOURCE_DIR) { $SOURCE_DIR = $PWD.Path }
$SOURCE_BIN_DIR = Join-Path $SOURCE_DIR "bin"
$SOURCE_SKILLS_DIR = Join-Path $SOURCE_DIR "skills"
$TARGET_DIR = Join-Path $env:USERPROFILE ".soloncode"
$TARGET_BIN_DIR = Join-Path $TARGET_DIR "bin"
$TARGET_SKILLS_DIR = Join-Path $TARGET_DIR "skills"

# =============================================
# 检查源目录是否存在
# =============================================
if (-not (Test-Path $SOURCE_BIN_DIR)) {
    Write-Host "[Error] Source bin directory not found: $SOURCE_BIN_DIR" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

# =============================================
# [1/5] 检查并备份已有的 config.yml 和 AGENTS.md
# =============================================
Write-Host "[1/5] Checking for existing configuration..." -ForegroundColor Yellow

$CONFIG_BACKUP = $null
$AGENTS_BACKUP = $null
$TARGET_CONFIG = Join-Path $TARGET_BIN_DIR "config.yml"
$TARGET_AGENTS = Join-Path $TARGET_BIN_DIR "AGENTS.md"

if (Test-Path $TARGET_CONFIG) {
    $CONFIG_BACKUP = Join-Path $env:TEMP "soloncode_config_backup_$(Get-Random).yml"
    Copy-Item $TARGET_CONFIG $CONFIG_BACKUP -Force
    Write-Host "      Found existing config.yml (will be preserved)" -ForegroundColor Gray
} else {
    Write-Host "      No existing config.yml found" -ForegroundColor Gray
}

if (Test-Path $TARGET_AGENTS) {
    $AGENTS_BACKUP = Join-Path $env:TEMP "soloncode_agents_backup_$(Get-Random).md"
    Copy-Item $TARGET_AGENTS $AGENTS_BACKUP -Force
    Write-Host "      Found existing AGENTS.md (will be preserved)" -ForegroundColor Gray
} else {
    Write-Host "      No existing AGENTS.md found" -ForegroundColor Gray
}

# =============================================
# [2/5] 创建目标目录结构
# =============================================
Write-Host ""
Write-Host "[2/5] Preparing target directory: $TARGET_DIR" -ForegroundColor Yellow

if (-not (Test-Path $TARGET_DIR)) { New-Item -ItemType Directory -Path $TARGET_DIR | Out-Null }
if (-not (Test-Path $TARGET_BIN_DIR)) { New-Item -ItemType Directory -Path $TARGET_BIN_DIR | Out-Null }
if (-not (Test-Path $TARGET_SKILLS_DIR)) { New-Item -ItemType Directory -Path $TARGET_SKILLS_DIR | Out-Null }
Write-Host "      Created directory structure" -ForegroundColor Gray

# =============================================
# [3/5] 复制文件
# =============================================
Write-Host ""
Write-Host "[3/5] Copying files to target directory..." -ForegroundColor Yellow

# 复制 bin 目录内容
Copy-Item -Path "$SOURCE_BIN_DIR\*" -Destination $TARGET_BIN_DIR -Recurse -Force
Write-Host "      Copied bin/ directory" -ForegroundColor Gray

# 复制 skills 目录（如果目标存在，先删除再复制）
if (Test-Path $SOURCE_SKILLS_DIR) {
    if (Test-Path $TARGET_SKILLS_DIR) {
        Remove-Item -Path $TARGET_SKILLS_DIR -Recurse -Force
    }
    Copy-Item -Path $SOURCE_SKILLS_DIR -Destination $TARGET_SKILLS_DIR -Recurse -Force
    Write-Host "      Copied skills/ directory" -ForegroundColor Gray
} else {
    Write-Host "      No skills/ directory to copy" -ForegroundColor Gray
}

Write-Host "      Files copied successfully" -ForegroundColor Green

# =============================================
# [4/5] 恢复 config.yml 和 AGENTS.md 并检查 jar 文件
# =============================================
Write-Host ""
Write-Host "[4/5] Finalizing installation..." -ForegroundColor Yellow

# 恢复 config.yml 备份（如果之前存在）
if ($CONFIG_BACKUP -and (Test-Path $CONFIG_BACKUP)) {
    Copy-Item $CONFIG_BACKUP $TARGET_CONFIG -Force
    Remove-Item $CONFIG_BACKUP -Force
    Write-Host "      Preserved existing config.yml" -ForegroundColor Gray
}

# 恢复 AGENTS.md 备份（如果之前存在）
if ($AGENTS_BACKUP -and (Test-Path $AGENTS_BACKUP)) {
    Copy-Item $AGENTS_BACKUP $TARGET_AGENTS -Force
    Remove-Item $AGENTS_BACKUP -Force
    Write-Host "      Preserved existing AGENTS.md" -ForegroundColor Gray
}

# 检查 jar 文件是否存在
$JAR_FILE = Join-Path $TARGET_BIN_DIR "soloncode-cli.jar"
if (-not (Test-Path $JAR_FILE)) {
    Write-Host ""
    Write-Host "[Error] soloncode-cli.jar not found in $TARGET_BIN_DIR" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}
Write-Host "      Found soloncode-cli.jar" -ForegroundColor Gray

# =============================================
# [5/5] 创建启动脚本并配置 PATH
# =============================================
Write-Host ""
Write-Host "[5/5] Setting up 'soloncode' command..." -ForegroundColor Yellow

# 创建 PowerShell 启动脚本 (soloncode.ps1)
$LAUNCHER_PS1 = Join-Path $TARGET_BIN_DIR "soloncode.ps1"
$LAUNCHER_CONTENT = @'
# Solon Code CLI Launcher for PowerShell
param([Parameter(ValueFromRemainingArguments)]$RestArgs)

$JarDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$JarFile = Join-Path $JarDir "soloncode-cli.jar"

if (-not (Test-Path $JarFile)) {
    Write-Host "[Error] soloncode-cli.jar not found" -ForegroundColor Red
    Write-Host "Expected path: $JarFile"
    exit 1
}

# 设置控制台编码为 UTF-8（兼容不同 PowerShell 环境）
try {
    $OutputEncoding = [System.Text.Encoding]::UTF8
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    [Console]::InputEncoding = [System.Text.Encoding]::UTF8
} catch {
    # 某些终端环境不支持设置编码，忽略错误
}

# 运行 Java 程序
& java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" "-Dstdin.encoding=UTF-8" -jar $JarFile @RestArgs
'@

Set-Content -Path $LAUNCHER_PS1 -Value $LAUNCHER_CONTENT -Encoding UTF8
Write-Host "      Created: soloncode.ps1" -ForegroundColor Gray

# 创建 Git Bash 启动脚本 (soloncode)
$LAUNCHER_SH = Join-Path $TARGET_BIN_DIR "soloncode"
$LAUNCHER_SH_CONTENT = @'
#!/bin/bash
# Solon Code CLI Launcher for Git Bash / WSL
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
java -Dfile.encoding=UTF-8 -jar "$SCRIPT_DIR/soloncode-cli.jar" "$@"
'@

Set-Content -Path $LAUNCHER_SH -Value $LAUNCHER_SH_CONTENT -Encoding UTF8 -NoNewline
Write-Host "      Created: soloncode (for Git Bash)" -ForegroundColor Gray

# =============================================
# 配置 PATH 环境变量
# =============================================
Write-Host ""
Write-Host "Configuring PATH..." -ForegroundColor Yellow

# 检查是否已在 PATH 中
$USER_PATH = [Environment]::GetEnvironmentVariable("Path", "User")
if ($USER_PATH -like "*$TARGET_BIN_DIR*") {
    Write-Host "      Already in user PATH" -ForegroundColor Gray
} else {
    # 添加到用户 PATH
    $NEW_PATH = if ($USER_PATH) { "$USER_PATH;$TARGET_BIN_DIR" } else { $TARGET_BIN_DIR }
    [Environment]::SetEnvironmentVariable("Path", $NEW_PATH, "User")
    Write-Host "      Added to user PATH" -ForegroundColor Green
}

# =============================================
# 完成
# =============================================
Write-Host ""
Write-Host "============================================" -ForegroundColor Green
Write-Host "   Installation Complete!" -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Green
Write-Host ""
Write-Host "  Install path: $TARGET_DIR" -ForegroundColor White
Write-Host "  Java version: $javaVersion" -ForegroundColor White
Write-Host ""
Write-Host "  Usage:" -ForegroundColor Cyan
Write-Host "    1. Open a NEW terminal window (PowerShell or Git Bash)"
Write-Host "    2. Run: soloncode"
Write-Host ""
Write-Host "  Directory structure:" -ForegroundColor Cyan
Write-Host "    `$env:USERPROFILE\.soloncode\"
Write-Host "    ├── bin\           (executables)"
Write-Host "    │   ├── soloncode-cli.jar"
Write-Host "    │   ├── soloncode.ps1   (PowerShell launcher)"
Write-Host "    │   ├── soloncode       (Git Bash launcher)"
Write-Host "    │   ├── config.yml      (configuration, preserved)"
Write-Host "    │   └── AGENTS.md       (agents config, preserved)"
Write-Host "    └── skills\        (skill modules)"
Write-Host ""
Write-Host "  [Tip] To use soloncode immediately in current terminal:" -ForegroundColor Yellow
Write-Host "    PowerShell: `$env:Path = [Environment]::GetEnvironmentVariable('Path','User')"
Write-Host ""

# If not called from setup.ps1, wait for user input
if (-not $env:SOLONCODE_SETUP) {
    Read-Host "Press Enter to exit"
}