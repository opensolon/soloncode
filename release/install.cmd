@echo off
setlocal enabledelayedexpansion

:: =============================================
::  Solon Code Installer (Windows)
::  支持重复安装，保留已有 config.yml
::  兼容 CMD、PowerShell、Git Bash 等多种终端
::  支持 Windows 7/8/10/11
:: =============================================

:: 管理员权限检测（用于后续判断是否可以创建系统级链接）
net session >nul 2>&1
set "IS_ADMIN=%ERRORLEVEL%"

:: 以 UTF-8 编码输出
chcp 65001 >nul 2>&1

echo.
echo ============================================
echo    Solon Code Installer (Windows)
echo ============================================
echo.

:: =============================================
:: 检测运行环境
:: =============================================
set "RUN_ENV=CMD"
if defined PSModulePath set "RUN_ENV=PowerShell"
if defined MSYSTEM (
    if /i "%MSYSTEM:~0,4%"=="MING" set "RUN_ENV=GitBash"
)
if defined WSL_DISTRO_NAME set "RUN_ENV=WSL"

echo [Info] Detected environment: %RUN_ENV%
echo.

:: =============================================
:: 检查 Java 是否安装
:: =============================================
echo [Pre-check] Verifying Java installation...
where java >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo.
    echo [Error] Java is not installed or not in PATH
    echo.
    echo   Please install Java 8 or later:
    echo     - Download from: https://adoptium.net/
    echo     - Or use: winget install EclipseAdoptium.Temurin.17
    echo.
    pause
    exit /b 1
)

:: 获取 Java 版本
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set "JAVA_VERSION=%%v"
    goto :got_java_version
)
:got_java_version
set "JAVA_VERSION=%JAVA_VERSION:"=%"
echo       Java version: %JAVA_VERSION%
echo.

:: =============================================
:: 设置源目录和目标目录
:: =============================================
set "SOURCE_DIR=%~dp0"
if "%SOURCE_DIR:~-1%"=="\" set "SOURCE_DIR=%SOURCE_DIR:~0,-1%"
set "SOURCE_SOLONCODE=%SOURCE_DIR%\.soloncode"
set "TARGET_DIR=%USERPROFILE%\.soloncode"
set "TARGET_BIN_DIR=%TARGET_DIR%\bin"

:: =============================================
:: 检查源目录是否存在
:: =============================================
if not exist "%SOURCE_SOLONCODE%" (
    echo [Error] Source directory not found: %SOURCE_SOLONCODE%
    pause
    exit /b 1
)

:: =============================================
:: [1/5] 检查并备份已有的 config.yml
:: =============================================
echo [1/5] Checking for existing configuration...
set "CONFIG_BACKUP="
set "TARGET_CONFIG=%TARGET_BIN_DIR%\config.yml"
if exist "%TARGET_CONFIG%" (
    set "CONFIG_BACKUP=%TEMP%\soloncode_config_backup_%RANDOM%.yml"
    copy "%TARGET_CONFIG%" "!CONFIG_BACKUP!" >nul 2>&1
    echo       Found existing config.yml (will be preserved)
) else (
    echo       No existing config.yml found
)

:: =============================================
:: [2/5] 创建目标目录结构
:: =============================================
echo.
echo [2/5] Preparing target directory: %TARGET_DIR%

if not exist "%TARGET_DIR%" mkdir "%TARGET_DIR%"
if not exist "%TARGET_BIN_DIR%" mkdir "%TARGET_BIN_DIR%"
echo       Created directory structure

:: =============================================
:: [3/5] 复制 .soloncode 目录内容到目标目录
:: =============================================
echo.
echo [3/5] Copying files to target directory...

:: 复制 bin 目录内容
if exist "%SOURCE_SOLONCODE%\bin" (
    xcopy "%SOURCE_SOLONCODE%\bin\*" "%TARGET_BIN_DIR%\" /E /Y >nul 2>&1
    echo       Copied bin/ directory
)

:: 复制 skills 目录（如果目标存在，先删除再复制）
if exist "%SOURCE_SOLONCODE%\skills" (
    if exist "%TARGET_DIR%\skills" rd /s /q "%TARGET_DIR%\skills"
    xcopy "%SOURCE_SOLONCODE%\skills" "%TARGET_DIR%\skills\" /E /I /Y >nul 2>&1
    echo       Copied skills/ directory
)

:: 复制其他文件（排除 bin 和 skills）
for %%f in ("%SOURCE_SOLONCODE%\*") do (
    copy "%%f" "%TARGET_DIR%\" >nul 2>&1
)

echo       Files copied successfully

:: =============================================
:: [4/5] 恢复 config.yml 并检查 jar 文件
:: =============================================
echo.
echo [4/5] Finalizing installation...

:: 恢复 config.yml 备份（如果之前存在）
if not "!CONFIG_BACKUP!"=="" (
    if exist "!CONFIG_BACKUP!" (
        copy "!CONFIG_BACKUP!" "%TARGET_CONFIG%" >nul 2>&1
        del "!CONFIG_BACKUP!" >nul 2>&1
        echo       Preserved existing config.yml
    )
)

:: 检查 jar 文件是否存在
if not exist "%TARGET_BIN_DIR%\soloncode-cli.jar" (
    echo.
    echo [Error] soloncode-cli.jar not found in %TARGET_BIN_DIR%
    pause
    exit /b 1
)
echo       Found soloncode-cli.jar

:: =============================================
:: [5/5] 创建启动脚本并配置 PATH
:: =============================================
echo.
echo [5/5] Setting up 'soloncode' command...

:: 创建 Windows 批处理启动脚本 (soloncode.cmd)
set "LAUNCHER_CMD=%TARGET_BIN_DIR%\soloncode.cmd"
(
echo @echo off
echo setlocal enabledelayedexpansion
echo.
echo :: Solon Code CLI Launcher for Windows
echo :: 支持从任意目录运行，包括符号链接和 PATH 调用
echo.
echo :: 获取脚本真实路径（兼容符号链接）
echo set "SCRIPT_PATH=%%~f0"
echo.
echo :: 尝试解析符号链接（Windows Vista+）
echo if exist "%%SCRIPT_PATH%%" ^(
echo     :: 使用 dir 命令解析符号链接
echo     for /f "tokens=2 delims=[]" %%%%a in ^('dir "%%SCRIPT_PATH%%" 2^^^>nul ^| findstr /r "^\[.*\]$"'^) do set "SCRIPT_PATH=%%%%a"
echo ^)
echo.
echo :: 获取脚本所在目录
echo set "JAR_DIR=%%~dp0"
echo if "%%JAR_DIR:~-1%%"=="\" set "JAR_DIR=%%JAR_DIR:~0,-1%%"
echo set "JAR_FILE=%%JAR_DIR%%\soloncode-cli.jar"
echo.
echo :: 检查 jar 文件是否存在
echo if not exist "%%JAR_FILE%%" ^(
echo     echo [Error] soloncode-cli.jar not found
echo     echo Expected path: %%JAR_FILE%%
echo     exit /b 1
echo ^)
echo.
echo :: 设置 UTF-8 编码（兼容不同 Windows 版本）
echo chcp 65001 ^>nul 2^>nul
echo.
echo :: 运行 Java 程序
echo java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dstdin.encoding=UTF-8 -jar "%%JAR_FILE%%" %%*
) > "%LAUNCHER_CMD%"
echo       Created: soloncode.cmd (for CMD/PowerShell)

:: 创建 PowerShell 启动脚本 (soloncode.ps1) - 更好的 UTF-8 支持
set "LAUNCHER_PS1=%TARGET_BIN_DIR%\soloncode.ps1"
(
echo # Solon Code CLI Launcher for PowerShell
echo param([Parameter(ValueFromRemainingArguments)]$Args)
echo.
echo $JarDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
echo $JarFile = Join-Path $JarDir "soloncode-cli.jar"
echo.
echo if (-not (Test-Path $JarFile)) {
echo     Write-Host "[Error] soloncode-cli.jar not found" -ForegroundColor Red
echo     Write-Host "Expected path: $JarFile"
echo     exit 1
echo }
echo.
echo # 设置控制台编码为 UTF-8
echo [Console]::OutputEncoding = [System.Text.Encoding]::UTF-8
echo [Console]::InputEncoding = [System.Text.Encoding]::UTF-8
echo.
echo # 运行 Java 程序
echo & java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" "-Dstdin.encoding=UTF-8" -jar $JarFile @Args
) > "%LAUNCHER_PS1%"
echo       Created: soloncode.ps1 (for PowerShell)

:: 创建 Git Bash 启动脚本 (soloncode)
set "LAUNCHER_SH=%TARGET_BIN_DIR%\soloncode"
(
echo #!/bin/bash
echo # Solon Code CLI Launcher for Git Bash / WSL
echo SCRIPT_DIR="$(cd "$(dirname "$0")" ^&^& pwd)"
echo java -Dfile.encoding=UTF-8 -jar "$SCRIPT_DIR/soloncode-cli.jar" "$@"
) > "%LAUNCHER_SH%"
echo       Created: soloncode (for Git Bash)

:: =============================================
:: 配置 PATH 环境变量
:: =============================================
echo.
echo Configuring PATH...

:: 方法1：用户级 PATH（最可靠，无需管理员权限）
set "PATH_UPDATED=0"
for /f "usebackq tokens=*" %%p in (`powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('Path','User')"`) do set "USER_PATH=%%p"

:: 检查是否已在 PATH 中
if "!USER_PATH:%TARGET_BIN_DIR%=!" neq "!USER_PATH!" (
    echo       Already in user PATH
) else (
    :: 添加到用户 PATH
    powershell -NoProfile -Command "$p=[Environment]::GetEnvironmentVariable('Path','User');$np=if($p -ne ''){'$p;%TARGET_BIN_DIR%'}else{'%TARGET_BIN_DIR%'};[Environment]::SetEnvironmentVariable('Path',$np,'User')" >nul 2>&1
    if !ERRORLEVEL! equ 0 (
        echo       Added to user PATH
        set "PATH_UPDATED=1"
    ) else (
        echo       [Warning] Failed to add to user PATH
    )
)

:: 方法2：尝试创建符号链接到系统目录（需要管理员权限，可选）
set "SYMLINK_CREATED=0"
if "%IS_ADMIN%"=="0" (
    :: 检查系统 PATH 中是否已有
    for /f "usebackq tokens=*" %%p in (`powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('Path','Machine')"`) do set "MACHINE_PATH=%%p"
    
    if "!MACHINE_PATH:%TARGET_BIN_DIR%=!" equ "!MACHINE_PATH!" (
        :: 尝试创建符号链接（需要管理员权限）
        set "LINK_DIR=C:\ProgramData\soloncode"
        set "LINK_FILE=!LINK_DIR!\soloncode.cmd"
        
        if not exist "!LINK_DIR!" mkdir "!LINK_DIR!" >nul 2>&1
        
        if exist "!LINK_DIR!" (
            copy "%LAUNCHER_CMD%" "!LINK_FILE!" >nul 2>&1
            if exist "!LINK_FILE!" (
                :: 添加到系统 PATH
                powershell -NoProfile -Command "$p=[Environment]::GetEnvironmentVariable('Path','Machine');$np=if($p -ne ''){'$p;!LINK_DIR!'}else{'!LINK_DIR!'};[Environment]::SetEnvironmentVariable('Path',$np,'Machine')" >nul 2>&1
                if !ERRORLEVEL! equ 0 (
                    echo       Created system-wide link: !LINK_DIR!
                    set "SYMLINK_CREATED=1"
                )
            )
        )
    )
)

:: 刷新当前会话的 PATH（不持久，但方便后续命令）
set "PATH=%PATH%;%TARGET_BIN_DIR%"

:: =============================================
:: 完成
:: =============================================
echo.
echo ============================================
echo    Installation Complete!
echo ============================================
echo.
echo   Install path: %TARGET_DIR%
echo   Java version: %JAVA_VERSION%
echo.

if "%SYMLINK_CREATED%"=="1" (
    echo   [System-wide] soloncode command is available for all users
    echo.
    echo   Usage:
    echo     1. Open a NEW terminal window
    echo     2. Run: soloncode
) else (
    echo   [User-level] soloncode command configured for current user
    echo.
    echo   Usage:
    echo     1. Open a NEW terminal window (CMD, PowerShell, or Git Bash)
    echo     2. Run: soloncode
    echo.
    echo   Terminal-specific notes:
    echo     - CMD:         soloncode
    echo     - PowerShell:  soloncode  (or .\soloncode.ps1 for better UTF-8)
    echo     - Git Bash:    ./soloncode
)

echo.
echo   Directory structure:
echo     %%USERPROFILE%%\.soloncode\
echo     ├── bin\           (executables)
echo     │   ├── soloncode-cli.jar
echo     │   ├── soloncode.cmd
echo     │   └── config.yml (configuration)
echo     └── skills\        (skill modules)
echo.

:: 提供刷新 PATH 的选项
if "%PATH_UPDATED%"=="1" (
    echo   [Tip] To use soloncode immediately in current terminal:
    echo     CMD:        refreshenv [if available, or restart terminal]
    echo     PowerShell: $env:Path = [Environment]::GetEnvironmentVariable('Path','User')
    echo.
)

pause