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

exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
    -jar "$JAR_FILE" web
