@echo off
setlocal enabledelayedexpansion

:: =============================================
::  Solon Code Uninstaller (Windows)
::  完全卸载 Solon Code，包括配置目录
:: =============================================

echo.
echo ============================================
echo    Solon Code Uninstaller
echo ============================================
echo.

:: 检测管理员权限
net session >nul 2>&1
set "IS_ADMIN=0"
if %errorLevel% equ 0 set "IS_ADMIN=1"

if %IS_ADMIN% equ 1 (
    echo [Info] Running with Administrator privileges
) else (
    echo [Info] Running without Administrator privileges
)

:: 安装目录
set "INSTALL_DIR=%USERPROFILE%\.soloncode"

:: 检查是否已安装
if not exist "%INSTALL_DIR%" (
    echo.
    echo [Info] Solon Code is not installed.
    echo        Directory not found: %INSTALL_DIR%
    pause
    exit /b 0
)

echo.
echo This will remove Solon Code completely:
echo   - Executables and configuration
echo   - Skills modules
echo   - PATH configuration
echo.
set /p CONFIRM="Continue? (Y/N): "
if /i not "%CONFIRM%"=="Y" (
    echo Cancelled.
    pause
    exit /b 0
)

:: ============================================
::  [1/4] 从 PATH 中移除
:: ============================================
echo.
echo [1/4] Removing from PATH...

:: 使用 PowerShell 处理 PATH（避免 setx 的 1024 字符限制）
:: 从用户 PATH 移除
powershell -NoProfile -Command ^
    "$p=[Environment]::GetEnvironmentVariable('Path','User');" ^
    "$p=$p -replace '[;]*[^^;]*soloncode[^^;]*[;]*','';" ^
    "$p=$p -replace ';;',';';" ^
    "$p=$p.TrimStart(';').TrimEnd(';');" ^
    "if($p){[Environment]::SetEnvironmentVariable('Path',$p,'User')}" ^
    >nul 2>&1
echo       Cleaned User PATH

:: 从系统 PATH 移除（如果是管理员）
if %IS_ADMIN% equ 1 (
    powershell -NoProfile -Command ^
        "$p=[Environment]::GetEnvironmentVariable('Path','Machine');" ^
        "$p=$p -replace '[;]*[^^;]*soloncode[^^;]*[;]*','';" ^
        "$p=$p -replace ';;',';';" ^
        "$p=$p.TrimStart(';').TrimEnd(';');" ^
        "if($p){[Environment]::SetEnvironmentVariable('Path',$p,'Machine')}" ^
        >nul 2>&1
    echo       Cleaned System PATH
)

:: ============================================
::  [2/4] 移除环境变量
:: ============================================
echo.
echo [2/4] Removing environment variables...

:: 用户级
reg delete "HKCU\Environment" /v SOLONCODE_HOME /f >nul 2>&1
echo       Removed User SOLONCODE_HOME

:: 系统级（如果是管理员）
if %IS_ADMIN% equ 1 (
    reg delete "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v SOLONCODE_HOME /f >nul 2>&1
    echo       Removed System SOLONCODE_HOME
)

:: ============================================
::  [3/4] 删除安装目录
:: ============================================
echo.
echo [3/4] Removing installation directory...

if exist "%INSTALL_DIR%" (
    rd /s /q "%INSTALL_DIR%" 2>nul
    if exist "%INSTALL_DIR%" (
        echo       [Warning] Could not remove %INSTALL_DIR%
        echo       Some files may be in use. Please restart and try again.
    ) else (
        echo       Removed: %INSTALL_DIR%
    )
) else (
    echo       Directory already removed
)

:: ============================================
::  [4/4] 删除系统级启动器目录
:: ============================================
echo.
echo [4/4] Cleaning up launcher directory...

if exist "C:\ProgramData\soloncode" (
    rd /s /q "C:\ProgramData\soloncode" 2>nul
    if exist "C:\ProgramData\soloncode" (
        echo       [Note] Could not remove C:\ProgramData\soloncode ^(need admin^)
    ) else (
        echo       Removed C:\ProgramData\soloncode
    )
) else (
    echo       No ProgramData launcher found
)

:: ============================================
::  完成
:: ============================================
echo.
echo ============================================
echo    Uninstall Complete!
echo ============================================
echo.
echo   Solon Code has been fully removed.
echo.
echo   [Note] Please restart your terminal for
echo          PATH changes to take effect.
echo.
pause