#!/usr/bin/env bash
# Solon Code CLI 免安装启动器（便携模式）
#
# 直接运行同目录下的 bin/soloncode-cli.jar，无需执行 install.sh。
# 适合放到 U 盘 / 共享目录里随处运行。
#
# 用法：
#   ./run.sh                 进入交互式命令行
#   ./run.sh web             启动 Web 界面（默认端口 4808）
#   ./run.sh web 4808        启动 Web 界面并指定端口
#   ./run.sh --help          查看全部参数
#
# 数据目录（可选）：默认仍然使用 ~/.soloncode（与安装版共用配置）。
# 想让配置、会话、记忆、技能都跟着程序走，设置环境变量 SOLONCODE_HOME：
#   export SOLONCODE_HOME="/media/usb/soloncode-data"
#   ./run.sh web

set -u

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
JAR_FILE="$SCRIPT_DIR/bin/soloncode-cli.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "[Error] 未找到 $JAR_FILE" >&2
    echo "        请确认 bin/soloncode-cli.jar 与本脚本位于同一发布包内。" >&2
    exit 1
fi

if ! command -v java >/dev/null 2>&1; then
    echo "[Error] 未找到 java 命令，请先安装 Java 8 或更高版本（JAVA_HOME/bin 需在 PATH 中）。" >&2
    exit 1
fi

# 用数组保存参数，路径里带空格（如 /Volumes/My USB）也不会被拆开
JAVA_ARGS=(-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8)

# 便携模式：把用户主目录指向 SOLONCODE_HOME，程序会在其下创建 .soloncode/
if [ -n "${SOLONCODE_HOME:-}" ]; then
    mkdir -p "$SOLONCODE_HOME" 2>/dev/null || {
        echo "[Error] 无法创建数据目录：$SOLONCODE_HOME" >&2
        exit 1
    }
    # 转成绝对路径，避免相对路径随调用目录变化
    SOLONCODE_HOME=$(cd "$SOLONCODE_HOME" && pwd) || exit 1
    JAVA_ARGS+=("-Duser.home=$SOLONCODE_HOME")
fi

JAVA_VER=$(java -version 2>&1 | head -n1 | grep -oE '"[0-9]+' | grep -oE '[0-9]+' | head -1)
[ -n "$JAVA_VER" ] || JAVA_VER=$(java -version 2>&1 | head -n1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ -n "$JAVA_VER" ] && [ "$JAVA_VER" -ge 21 ]; then
    JAVA_ARGS+=(--enable-native-access=ALL-UNNAMED)
fi

# Git Bash / MSYS 下的中文与终端兼容处理（与安装版启动器保持一致）
if [ -n "${MSYSTEM:-}" ]; then
    JAVA_ARGS+=(-Djline.terminal.type=xterm-256color)
    if [ -t 0 ] && [ -t 1 ] && command -v winpty >/dev/null 2>&1; then
        exec winpty java "${JAVA_ARGS[@]}" -jar "$JAR_FILE" "$@"
    fi
fi

exec java "${JAVA_ARGS[@]}" -jar "$JAR_FILE" "$@"
