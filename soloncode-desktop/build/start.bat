@echo off
setlocal enabledelayedexpansion

:: 1. 获取脚本所在目录
set "JarFile=../soloncode-cli/target/soloncode-cli.jar"

:: 2. 检查 jar 文件是否存在
if not exist "%JarFile%" (
    echo [Error] soloncode-cli.jar not found
    echo Expected path: %JarFile%
    exit /b 1
)

:: 3. 设置控制台编码为 UTF-8
:: 65001 代表 UTF-8 代码页
chcp 65001 >nul

:: 4. 准备 Java 参数
:: 基础 UTF-8 参数
set "JavaArgs=-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dstdin.encoding=UTF-8"

:: 5. 检测 Java 版本 (简化版逻辑)
:: CMD 无法像 PowerShell 那样优雅地捕获进程输出并解析正则。
:: 这里使用 'findstr' 查找版本号。
:: 注意：如果 java 命令不在环境变量中，此检测会跳过。
set "JavaMajor=0"
for /f "tokens=3 delims= " %%i in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    :: 尝试提取引号内的数字，例如 "21.0.1" -> 21
    for /f "tokens=1 delims=." %%j in ("%%i") do (
        set "JavaMajor=%%j"
        :: 去除可能的引号
        set "JavaMajor=!JavaMajor:"=!"
    )
)

:: 如果版本大于等于 21，添加 native access 参数
:: 注意：CMD 的数值比较需要特殊处理，这里假设版本号是纯数字开头
if !JavaMajor! GEQ 21 (
    set "JavaArgs=!JavaArgs! --enable-native-access=ALL-UNNAMED"
)

:: 6. 运行 Java 程序
:: %* 代表传递所有原始参数
java %JavaArgs% -jar "%JarFile%" %*