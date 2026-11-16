@echo off
rem ===========================================================================
rem Solon Code CLI launcher (Windows): runs bin\soloncode-cli.jar web
rem
rem Keep this file PURE ASCII with CRLF line endings. cmd.exe reads a .cmd file
rem as ANSI (GBK on Chinese Windows), so UTF-8 Chinese comments turn into
rem garbage and can abort the script - the window then flashes and closes.
rem Chinese messages must come from the Java program, never from this file.
rem ===========================================================================

rem UTF-8 console, so the Chinese output of the Java program is readable.
chcp 65001 >nul 2>nul

setlocal

rem Double-clicking runs "cmd /c ""...\run.window.cmd" "" and the script name
rem appears in CMDCMDLINE. Typing the command in an existing window does not.
rem Use that to pause at the end, otherwise a failing start closes the window
rem before the message can be read.
set "DOUBLECLICK="
echo "%CMDCMDLINE%" | find /i "%~nx0" >nul
if not errorlevel 1 set "DOUBLECLICK=1"

title Solon Code CLI
set "RC=1"

set "JAR_FILE=%~dp0bin\soloncode-cli.jar"
if not exist "%JAR_FILE%" (
    echo [Error] JAR not found: %JAR_FILE%
    echo         Make sure bin\soloncode-cli.jar ships in the same package.
    goto :fail
)

where java >nul 2>nul
if errorlevel 1 (
    echo [Error] java not found. Install Java 8 or newer and add JAVA_HOME\bin to PATH.
    goto :fail
)

rem JDK 24+ treats System.load as a restricted method; jansi loading its native
rem library then prints four "restricted method" WARNING lines. Passing
rem --enable-native-access=ALL-UNNAMED silences them (JDK 16+, so 17/21/25, all
rem accept the flag; JDK 8/11 treat it as an unknown option and refuse to start,
rem hence the probe below instead of an unconditional flag).
set "NATIVE_ACCESS="
java --enable-native-access=ALL-UNNAMED -version >nul 2>nul
if not errorlevel 1 set "NATIVE_ACCESS=--enable-native-access=ALL-UNNAMED"

java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 %NATIVE_ACCESS% -jar "%JAR_FILE%" web
set "RC=%ERRORLEVEL%"

:fail
if defined DOUBLECLICK (
    echo.
    echo Program exited with code %RC% - press any key to close this window.
    pause >nul
)
exit /b %RC%
