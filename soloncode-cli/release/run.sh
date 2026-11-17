#!/usr/bin/env bash
# Solon Code CLI 启动器：运行同目录下的 bin/soloncode-cli.jar，启动 Web 界面。
#
# 免安装：把整个目录放到任意位置即可运行。

set -u

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
JAR_FILE="$SCRIPT_DIR/bin/soloncode-cli.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "[Error] JAR not found: $JAR_FILE" >&2
    echo "        Make sure bin/soloncode-cli.jar ships in the same package." >&2
    exit 1
fi

# 优先使用与本脚本同级的 jdk 目录（便携/ U 盘自带 JDK 的场景），找不到再用 PATH 上的 java：
#   jdk/bin/java                 Linux / Windows 解压出来的 JDK
#   jdk/Contents/Home/bin/java   macOS 的 JDK 包（解压后多一层 Contents/Home）
# 只认 jdk 这一个目录名（好识别），不做 jdk* 通配匹配。
JAVA_BIN="java"
JAVA_HOME_LOCAL=""
if [ -x "$SCRIPT_DIR/jdk/bin/java" ]; then
    JAVA_HOME_LOCAL="$SCRIPT_DIR/jdk"
elif [ -x "$SCRIPT_DIR/jdk/Contents/Home/bin/java" ]; then
    JAVA_HOME_LOCAL="$SCRIPT_DIR/jdk/Contents/Home"
fi
if [ -n "$JAVA_HOME_LOCAL" ]; then
    # 自带的 JDK 可能与当前机器架构不符（例如把 Linux 包拷到 macOS），先跑一次 -version 确认可用
    if "$JAVA_HOME_LOCAL/bin/java" -version >/dev/null 2>&1; then
        JAVA_BIN="$JAVA_HOME_LOCAL/bin/java"
        JAVA_HOME="$JAVA_HOME_LOCAL"
        PATH="$JAVA_HOME_LOCAL/bin:$PATH"
        export JAVA_HOME PATH
    else
        echo "[Warn] $JAVA_HOME_LOCAL cannot be executed, falling back to the java on PATH." >&2
        JAVA_HOME_LOCAL=""
    fi
fi

if [ -z "$JAVA_HOME_LOCAL" ] && ! command -v java >/dev/null 2>&1; then
    echo "[Error] java not found. Install Java 8 or newer and add its bin directory to PATH." >&2
    echo "        Or unpack a JDK into the jdk directory next to this script." >&2
    exit 1
fi

# JDK 24+ 起 System.load 属受限制方法（jansi 加载本地库会触发），会打印 “restricted method”
# 的四行 WARNING；加上 --enable-native-access=ALL-UNNAMED 即可消除。
# JDK 16+（含 17/21/25）都认识该参数，没有警告的版本加了也无害；
# JDK 8/11 会把它当未知参数并直接启动失败，所以先探测一次再决定是否附加。
JAVA_OPTS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
if "$JAVA_BIN" --enable-native-access=ALL-UNNAMED -version >/dev/null 2>&1; then
    JAVA_OPTS="$JAVA_OPTS --enable-native-access=ALL-UNNAMED"
fi

# JAVA_OPTS 需要按空格拆分，不能加引号
exec "$JAVA_BIN" $JAVA_OPTS -jar "$JAR_FILE" web
