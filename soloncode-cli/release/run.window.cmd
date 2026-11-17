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

rem Prefer a JDK shipped next to this script (portable USB setups): jdk\bin\java.exe.
rem Only the exact name "jdk" is recognized on purpose (no jdk* wildcard).
rem Fall back to the java found on PATH when there is none.
set "JAVA_EXE="
if exist "%~dp0jdk\bin\java.exe" (
    set "JAVA_EXE=%~dp0jdk\bin\java.exe"
    set "JAVA_HOME=%~dp0jdk"
    set "PATH=%~dp0jdk\bin;%PATH%"
) else (
    where java >nul 2>nul
    if errorlevel 1 (
        echo [Error] java not found. Install Java 8 or newer and add JAVA_HOME\bin to PATH.
        echo         Or unpack a JDK into the jdk directory next to this script.
        goto :fail
    )
    set "JAVA_EXE=java"
)

rem JDK 24+ treats System.load as a restricted method; jansi loading its native
rem library then prints four "restricted method" WARNING lines. Passing
rem --enable-native-access=ALL-UNNAMED silences them (JDK 16+, so 17/21/25, all
rem accept the flag; JDK 8/11 treat it as an unknown option and refuse to start,
rem hence the probe below instead of an unconditional flag).
set "NATIVE_ACCESS="
"%JAVA_EXE%" --enable-native-access=ALL-UNNAMED -version >nul 2>nul
if not errorlevel 1 set "NATIVE_ACCESS=--enable-native-access=ALL-UNNAMED"

"%JAVA_EXE%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 %NATIVE_ACCESS% -jar "%JAR_FILE%" web
set "RC=%ERRORLEVEL%"

:fail
if defined DOUBLECLICK (
    echo.
    echo Program exited with code %RC% - press any key to close this window.
    pause >nul
)
exit /b %RC%
