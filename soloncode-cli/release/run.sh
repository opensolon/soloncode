#!/usr/bin/env bash
# Solon Code CLI 启动器：运行同目录下的 bin/soloncode-cli.jar，启动 Web 界面。
#
# 免安装：把整个目录放到任意位置即可运行。

set -u

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
JAR_FILE="$SCRIPT_DIR/bin/soloncode-cli.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "[Error] 未找到 $JAR_FILE" >&2
    echo "        请确认 bin/soloncode-cli.jar 与本脚本位于同一发布包内。" >&2
    exit 1
fi

if ! command -v java >/dev/null 2>&1; then
    echo "[Error] 未找到 java 命令，请先安装 Java 8 或更高版本。" >&2
    exit 1
fi

# JDK 24+ 起 System.load 属受限制方法（jansi 加载本地库会触发），会打印 “restricted method”
# 的四行 WARNING；加上 --enable-native-access=ALL-UNNAMED 即可消除。
# JDK 16+（含 17/21/25）都认识该参数，没有警告的版本加了也无害；
# JDK 8/11 会把它当未知参数并直接启动失败，所以先探测一次再决定是否附加。
JAVA_OPTS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
if java --enable-native-access=ALL-UNNAMED -version >/dev/null 2>&1; then
    JAVA_OPTS="$JAVA_OPTS --enable-native-access=ALL-UNNAMED"
fi

# JAVA_OPTS 需要按空格拆分，不能加引号
exec java $JAVA_OPTS -jar "$JAR_FILE" web
