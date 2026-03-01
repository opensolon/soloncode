@echo off
chcp 65001 > nul

:: 使用 /d 彻底切换目录，并用双引号包裹路径以防空格报错
cd /d "%~dp0"

:: 检查 jar 包是否存在，避免低级报错
if not exist "soloncode-cli.jar" (
    echo [错误] 找不到 soloncode-cli.jar，请确保脚本和程序在同一目录下。
    pause
    exit
)

:: 执行 Java 命令
java -Dfile.encoding=UTF-8 -jar "soloncode-cli.jar"

pause