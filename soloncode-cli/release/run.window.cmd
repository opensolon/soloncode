@echo off
chcp 65001 >nul 2>nul
setlocal

rem Solon Code CLI 免安装启动器（便携模式，Windows）
rem
rem 用法（不带参数时默认启动 Web 界面，和双击启动一致）：
rem   run.cmd                 启动 Web 界面（默认端口 4808，自动打开浏览器）
rem   run.cmd web 4808        启动 Web 界面并指定端口
rem   run.cmd cli             进入交互式命令行
rem   run.cmd --help          查看全部参数
rem
rem 数据目录（可选）：默认仍然使用 %USERPROFILE%\.soloncode（与安装版共用配置）。
rem 想让配置、会话、记忆、技能都跟着程序走，先设置环境变量 SOLONCODE_HOME：
rem   set SOLONCODE_HOME=E:\soloncode-data
rem   run.cmd

rem 双击运行时 cmdcmdline 里会带上本脚本名（在已有窗口里敲命令则不会），
rem 据此在程序退出或出错后暂停，避免窗口一闪而过看不到提示。
set "DOUBLECLICK="
echo %cmdcmdline% | find /i "%~nx0" >nul && set "DOUBLECLICK=1"

rem 默认动作是 Web 界面（双击启动就是这种场景）。
rem 位置参数会被 CLI 当成 flag 序列解析，所以这里补一个 "web"；
rem 想要交互式命令行请显式执行 run.cmd cli。
set "RUN_ARGS=%*"
if not defined RUN_ARGS set "RUN_ARGS=web"

title Solon Code CLI
set "RC=1"

set "JAR_FILE=%~dp0bin\soloncode-cli.jar"
if not exist "%JAR_FILE%" (
    echo [Error] 未找到 %JAR_FILE%
    echo         请确认 bin\soloncode-cli.jar 与本脚本位于同一发布包内。
    goto :fail
)

where java >nul 2>nul
if errorlevel 1 (
    echo [Error] 未找到 java 命令，请先安装 Java 8 或更高版本（JAVA_HOME\bin 需在 PATH 中）。
    goto :fail
)

rem 参数里可能带空格，所以这里用不带外层引号的 set，给每项自带引号
set JAVA_OPTS=-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8
if defined SOLONCODE_HOME (
    if not exist "%SOLONCODE_HOME%" mkdir "%SOLONCODE_HOME%" >nul 2>nul
    set JAVA_OPTS=%JAVA_OPTS% "-Duser.home=%SOLONCODE_HOME%"
)

rem Java 21+ 追加 --enable-native-access，消除 JLine 的原生访问告警。
rem Java 8 的版本号是 1.x，取第一段得 1，不会命中。
set "JAVA_VER="
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VER=%%~v"
if not defined JAVA_VER goto :run_java
for /f "tokens=1 delims=." %%a in ("%JAVA_VER%") do set "JAVA_MAJOR=%%a"
if not defined JAVA_MAJOR goto :run_java
if %JAVA_MAJOR% GEQ 21 set JAVA_OPTS=%JAVA_OPTS% --enable-native-access=ALL-UNNAMED

:run_java
java %JAVA_OPTS% -jar "%JAR_FILE%" %RUN_ARGS%
set "RC=%ERRORLEVEL%"

:fail
if defined DOUBLECLICK pause
exit /b %RC%
