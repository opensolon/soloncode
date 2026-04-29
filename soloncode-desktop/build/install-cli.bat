@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul

:: SolonCode CLI Auto-Installer for Desktop
:: Called by Tauri app when ~/.soloncode/bin/soloncode-cli.jar not found
:: Usage: install-cli.bat [release_dir]

echo [soloncode] CLI auto-install starting...

:: ==================== 确定源目录 ====================

if "%~1"=="" (
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

if not exist "%RELEASE_DIR%\bin" (
    echo [Error] release bin directory not found: %RELEASE_DIR%\bin
    exit /b 1
)

:: ==================== 定位 JAR 文件 ====================

set "JAR_SOURCE="
if exist "%RELEASE_DIR%\bin\soloncode-cli.jar" (
    set "JAR_SOURCE=%RELEASE_DIR%\bin\soloncode-cli.jar"
)
if "!JAR_SOURCE!"=="" (
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
set "TARGET_CONFIG=%TARGET_DIR%\config.yml"
set "TARGET_AGENTS=%TARGET_DIR%\AGENTS.md"
set "OLD_TARGET_CONFIG=%TARGET_BIN%\config.yml"
set "OLD_TARGET_AGENTS=%TARGET_BIN%\AGENTS.md"

:: ==================== 检查是否已安装 ====================

if exist "%TARGET_BIN%\soloncode-cli.jar" (
    echo [soloncode] CLI already installed at %TARGET_BIN%
    exit /b 0
)

echo [soloncode] Installing to %TARGET_DIR%...

:: ==================== 备份已有配置 ====================

set "CONFIG_BACKUP="
set "AGENTS_BACKUP="

:: 迁移旧版本的配置文件（从 bin/ 目录移动到根目录）
if exist "%OLD_TARGET_CONFIG%" (
    if not exist "%TARGET_CONFIG%" (
        move /Y "%OLD_TARGET_CONFIG%" "%TARGET_CONFIG%" >nul
        echo [soloncode] Migrated config.yml from bin/ to root directory
    )
)
if exist "%OLD_TARGET_AGENTS%" (
    if not exist "%TARGET_AGENTS%" (
        move /Y "%OLD_TARGET_AGENTS%" "%TARGET_AGENTS%" >nul
        echo [soloncode] Migrated AGENTS.md from bin/ to root directory
    )
)

if exist "%TARGET_CONFIG%" (
    set "CONFIG_BACKUP=%TEMP%\soloncode_config_%RANDOM%.yml"
    copy /Y "%TARGET_CONFIG%" "!CONFIG_BACKUP!" >nul
    echo [soloncode] Backed up existing config.yml
)
if exist "%TARGET_AGENTS%" (
    set "AGENTS_BACKUP=%TEMP%\soloncode_agents_%RANDOM%.md"
    copy /Y "%TARGET_AGENTS%" "!AGENTS_BACKUP!" >nul
    echo [soloncode] Backed up existing AGENTS.md
)

:: ==================== 创建目标目录 ====================

if not exist "%TARGET_DIR%" mkdir "%TARGET_DIR%"
if not exist "%TARGET_BIN%" mkdir "%TARGET_BIN%"
if not exist "%TARGET_SKILLS%" mkdir "%TARGET_SKILLS%"

:: ==================== 复制文件 ====================

echo [soloncode] Copying bin/ ...
xcopy /E /Y /Q "%RELEASE_DIR%\bin\*" "%TARGET_BIN%\" >nul

echo [soloncode] Copying soloncode-cli.jar ...
copy /Y "!JAR_SOURCE!" "%TARGET_BIN%\soloncode-cli.jar" >nul

if exist "%RELEASE_DIR%\config.yml" (
    if not exist "%TARGET_CONFIG%" (
        copy /Y "%RELEASE_DIR%\config.yml" "%TARGET_CONFIG%" >nul
    )
)
if exist "%RELEASE_DIR%\AGENTS.md" (
    if not exist "%TARGET_AGENTS%" (
        copy /Y "%RELEASE_DIR%\AGENTS.md" "%TARGET_AGENTS%" >nul
    )
)

if exist "%RELEASE_DIR%\skills" (
    echo [soloncode] Copying skills/ ...
    if exist "%TARGET_SKILLS%" rd /s /q "%TARGET_SKILLS%"
    xcopy /E /Y /Q /I "%RELEASE_DIR%\skills" "%TARGET_SKILLS%\" >nul
)

:: ==================== 恢复配置备份 ====================

if not "!CONFIG_BACKUP!"=="" (
    copy /Y "!CONFIG_BACKUP!" "%TARGET_CONFIG%" >nul
    del /Q "!CONFIG_BACKUP!" >nul 2>&1
    echo [soloncode] Preserved existing config.yml
)
if not "!AGENTS_BACKUP!"=="" (
    copy /Y "!AGENTS_BACKUP!" "%TARGET_AGENTS%" >nul
    del /Q "!AGENTS_BACKUP!" >nul 2>&1
    echo [soloncode] Preserved existing AGENTS.md
)

:: 清理旧位置的配置文件
if exist "%OLD_TARGET_CONFIG%" del /Q "%OLD_TARGET_CONFIG%" >nul 2>&1
if exist "%OLD_TARGET_AGENTS%" del /Q "%OLD_TARGET_AGENTS%" >nul 2>&1

:: ==================== 创建启动脚本（参照 CLI install.ps1） ====================

echo [soloncode] Creating 'soloncode' command...

:: soloncode.ps1 (PowerShell launcher, 含 Java 21+ 检测)
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
echo # 检测 Java 版本，如果是 21+ 则添加 --enable-native-access 参数
echo $JavaArgs = @^("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dstdin.encoding=UTF-8"^)
echo try {
echo     $VerProcess = New-Object System.Diagnostics.Process
echo     $VerProcess.StartInfo.FileName = "java"
echo     $VerProcess.StartInfo.Arguments = "-version"
echo     $VerProcess.StartInfo.RedirectStandardError = $true
echo     $VerProcess.StartInfo.RedirectStandardOutput = $true
echo     $VerProcess.StartInfo.UseShellExecute = $false
echo     $VerProcess.Start^(^) ^| Out-Null
echo     $VerOutput = $VerProcess.StandardError.ReadToEnd^(^)
echo     $VerProcess.WaitForExit^(^)
echo     if ^($VerOutput -match '"^(\d+^)'^) {
echo         $JavaMajor = [int]$Matches[1]
echo         if ^($JavaMajor -ge 21^) {
echo             $JavaArgs += "--enable-native-access=ALL-UNNAMED"
echo         }
echo     }
echo } catch {}
echo & java @JavaArgs -jar $JarFile @RestArgs
) > "%PS1_FILE%"
echo [soloncode] Created soloncode.ps1

:: soloncode (Git Bash launcher, 含 Java 21+ 检测)
set "SH_FILE=%TARGET_BIN%\soloncode"
(
echo #!/bin/bash
echo # Solon Code CLI Launcher for Git Bash / WSL
echo SCRIPT_DIR="$^(cd "$^(dirname "$0"^)" ^&^& pwd^)"
echo.
echo # 检测 Java 版本，如果是 21+ 则添加 --enable-native-access 参数
echo JAVA_VER=$^(java -version 2^>^&1 ^| head -n1 ^| grep -oE '"[0-9]+' ^| grep -oE '[0-9]+' ^| head -1^)
echo if [ -z "$JAVA_VER" ]; then
echo     JAVA_VER=$^(java -version 2^>^&1 ^| head -n1 ^| cut -d'"' -f2 ^| cut -d'.' -f1^)
echo fi
echo JAVA_OPTS="-Dfile.encoding=UTF-8"
echo if [ -n "$JAVA_VER" ] ^&^& [ "$JAVA_VER" -ge 21 ]; then
echo     JAVA_OPTS="$JAVA_OPTS --enable-native-access=ALL-UNNAMED"
echo fi
echo java $JAVA_OPTS -jar "$SCRIPT_DIR/soloncode-cli.jar" "$@"
) > "%SH_FILE%"
echo [soloncode] Created soloncode ^(for Git Bash^)

:: soloncode.bat (CMD launcher)
set "BAT_FILE=%TARGET_BIN%\soloncode.bat"
(
echo @echo off
echo setlocal
echo set "JAR_DIR=%%~dp0"
echo java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dstdin.encoding=UTF-8 -jar "%%JAR_DIR%%soloncode-cli.jar" %%*
) > "%BAT_FILE%"
echo [soloncode] Created soloncode.bat

:: ==================== 注册 PATH 环境变量（参照 CLI install.ps1） ====================

echo [soloncode] Configuring PATH...
powershell -NoProfile -Command ^
  "$TARGET_BIN='%TARGET_BIN%';" ^
  "$USER_PATH=[Environment]::GetEnvironmentVariable('Path','User');" ^
  "if ($USER_PATH -like \"*$TARGET_BIN*\") {" ^
  "  Write-Host '[soloncode] Already in user PATH';" ^
  "} else {" ^
  "  $NEW_PATH=if($USER_PATH){\"$USER_PATH;$TARGET_BIN\"}else{$TARGET_BIN};" ^
  "  [Environment]::SetEnvironmentVariable('Path',$NEW_PATH,'User');" ^
  "  Write-Host '[soloncode] Added to user PATH';" ^
  "}"

:: ==================== 验证安装 ====================

if not exist "%TARGET_BIN%\soloncode-cli.jar" (
    echo [Error] soloncode-cli.jar not found after installation
    exit /b 1
)

echo.
echo [soloncode] CLI installed successfully at %TARGET_DIR%
echo.
echo   Directory structure:
echo     %USERPROFILE%\.soloncode\
echo     +-- config.yml      ^(configuration^)
echo     +-- AGENTS.md       ^(agents config^)
echo     +-- bin\
echo     ^|   +-- soloncode-cli.jar
echo     ^|   +-- soloncode.ps1   ^(PowerShell^)
echo     ^|   +-- soloncode       ^(Git Bash^)
echo     ^|   +-- soloncode.bat   ^(CMD^)
echo     +-- skills\
echo.
exit /b 0
