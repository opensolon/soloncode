@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul

:: SolonCode CLI Auto-Installer for Desktop
:: Called by Tauri app when ~/.soloncode/bin/soloncode-cli.jar not found
:: Usage: install-cli.bat [release_dir]
::
:: release_dir: soloncode-cli/release 目录路径
::   - 打包模式: 由 Tauri 资源路径传入
::   - 开发模式: 自动从脚本位置向上查找

echo [soloncode] CLI auto-install starting...

:: ==================== 确定源目录 ====================

if "%~1"=="" (
    :: 未传入参数，从脚本位置向上查找 soloncode-cli/release/
    set "RELEASE_DIR="
    set "SEARCH_DIR=%~dp0.."
    for /L %%i in (1,1,10) do (
        if "!RELEASE_DIR!"=="" (
            if exist "!SEARCH_DIR!\soloncode-cli\release\bin" (
                set "RELEASE_DIR=!SEARCH_DIR!\soloncode-cli\release"
            )
            set "SEARCH_DIR=!SEARCH_DIR!\.."
        )
    )
) else (
    set "RELEASE_DIR=%~1"
)

:: 验证源目录
if not exist "%RELEASE_DIR%\bin" (
    echo [Error] release bin directory not found: %RELEASE_DIR%\bin
    exit /b 1
)

:: ==================== 定位 JAR 文件 ====================

:: JAR 优先从 release/bin/ 找，否则从 target/ 找
set "JAR_SOURCE="
if exist "%RELEASE_DIR%\bin\soloncode-cli.jar" (
    set "JAR_SOURCE=%RELEASE_DIR%\bin\soloncode-cli.jar"
)
if "!JAR_SOURCE!"=="" (
    :: 从 release 向上查找 soloncode-cli/target/soloncode-cli.jar
    set "JAR_SEARCH=%RELEASE_DIR%"
    for /L %%i in (1,1,10) do (
        if "!JAR_SOURCE!"=="" (
            if exist "!JAR_SEARCH!\soloncode-cli\target\soloncode-cli.jar" (
                set "JAR_SOURCE=!JAR_SEARCH!\soloncode-cli\target\soloncode-cli.jar"
            )
            set "JAR_SEARCH=!JAR_SEARCH!\.."
        )
    )
)
if "!JAR_SOURCE!"=="" (
    echo [Error] soloncode-cli.jar not found in release/bin or target/
    exit /b 1
)
echo [soloncode] Found JAR: !JAR_SOURCE!

:: ==================== 确定目标目录 ====================

set "TARGET_DIR=%USERPROFILE%\.soloncode"
set "TARGET_BIN=%TARGET_DIR%\bin"
set "TARGET_SKILLS=%TARGET_DIR%\skills"

:: ==================== 检查是否已安装 ====================

if exist "%TARGET_BIN%\soloncode-cli.jar" (
    echo [soloncode] CLI already installed at %TARGET_BIN%
    exit /b 0
)

echo [soloncode] Installing to %TARGET_DIR%...

:: ==================== 备份已有配置 ====================

set "CONFIG_BACKUP="
set "AGENTS_BACKUP="

if exist "%TARGET_BIN%\config.yml" (
    set "CONFIG_BACKUP=%TEMP%\soloncode_config_%RANDOM%.yml"
    copy /Y "%TARGET_BIN%\config.yml" "!CONFIG_BACKUP!" >nul
    echo [soloncode] Backed up existing config.yml
)

if exist "%TARGET_BIN%\AGENTS.md" (
    set "AGENTS_BACKUP=%TEMP%\soloncode_agents_%RANDOM%.md"
    copy /Y "%TARGET_BIN%\AGENTS.md" "!AGENTS_BACKUP!" >nul
    echo [soloncode] Backed up existing AGENTS.md
)

:: ==================== 创建目标目录 ====================

if not exist "%TARGET_BIN%" mkdir "%TARGET_BIN%"
if not exist "%TARGET_SKILLS%" mkdir "%TARGET_SKILLS%"

:: ==================== 复制文件 ====================

:: 复制 bin/ 目录（不含 jar）
echo [soloncode] Copying bin/ ...
xcopy /E /Y /Q "%RELEASE_DIR%\bin\*" "%TARGET_BIN%\" >nul

:: 复制 JAR 文件
echo [soloncode] Copying soloncode-cli.jar ...
copy /Y "!JAR_SOURCE!" "%TARGET_BIN%\soloncode-cli.jar" >nul

:: 复制 config.yml 和 AGENTS.md（如果存在且目标没有）
if exist "%RELEASE_DIR%\config.yml" (
    if not exist "%TARGET_BIN%\config.yml" (
        copy /Y "%RELEASE_DIR%\config.yml" "%TARGET_BIN%\config.yml" >nul
    )
)
if exist "%RELEASE_DIR%\AGENTS.md" (
    if not exist "%TARGET_BIN%\AGENTS.md" (
        copy /Y "%RELEASE_DIR%\AGENTS.md" "%TARGET_BIN%\AGENTS.md" >nul
    )
)

:: 复制 skills/ 目录（如果存在）
if exist "%RELEASE_DIR%\skills" (
    echo [soloncode] Copying skills/ ...
    if exist "%TARGET_SKILLS%" rd /s /q "%TARGET_SKILLS%"
    xcopy /E /Y /Q /I "%RELEASE_DIR%\skills" "%TARGET_SKILLS%\" >nul
)

:: ==================== 恢复配置备份 ====================

if not "!CONFIG_BACKUP!"=="" (
    copy /Y "!CONFIG_BACKUP!" "%TARGET_BIN%\config.yml" >nul
    del /Q "!CONFIG_BACKUP!" >nul 2>&1
    echo [soloncode] Preserved existing config.yml
)

if not "!AGENTS_BACKUP!"=="" (
    copy /Y "!AGENTS_BACKUP!" "%TARGET_BIN%\AGENTS.md" >nul
    del /Q "!AGENTS_BACKUP!" >nul 2>&1
    echo [soloncode] Preserved existing AGENTS.md
)

:: ==================== 创建启动脚本 ====================

:: soloncode.ps1 (PowerShell launcher)
set "PS1_FILE=%TARGET_BIN%\soloncode.ps1"
(
echo # Solon Code CLI Launcher for PowerShell
echo param([Parameter(ValueFromRemainingArguments^)]$RestArgs^)
echo $JarDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
echo $JarFile = Join-Path $JarDir "soloncode-cli.jar"
echo if (-not ^(Test-Path $JarFile^)^) {
echo     Write-Host "[Error] soloncode-cli.jar not found" -ForegroundColor Red
echo     exit 1
echo }
echo try {
echo     $OutputEncoding = [System.Text.Encoding]::UTF8
echo     [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
echo     [Console]::InputEncoding = [System.Text.Encoding]::UTF8
echo } catch {}
echo & java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" "-Dstdin.encoding=UTF-8" -jar $JarFile @RestArgs
) > "%PS1_FILE%"
echo [soloncode] Created soloncode.ps1

:: soloncode.bat (CMD launcher)
set "BAT_FILE=%TARGET_BIN%\soloncode.bat"
(
echo @echo off
echo setlocal
echo set "JAR_DIR=%%~dp0"
echo java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dstdin.encoding=UTF-8 -jar "%%JAR_DIR%%soloncode-cli.jar" %%*
) > "%BAT_FILE%"
echo [soloncode] Created soloncode.bat

:: ==================== 验证安装 ====================

if not exist "%TARGET_BIN%\soloncode-cli.jar" (
    echo [Error] soloncode-cli.jar not found after installation
    exit /b 1
)

echo [soloncode] CLI installed successfully at %TARGET_DIR%
exit /b 0
