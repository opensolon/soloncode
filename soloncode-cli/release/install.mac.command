#!/usr/bin/env bash
# macOS 下双击安装入口：Finder 会用“终端”执行本文件，真正的安装逻辑都在同目录的 install.sh。
#
# 也可以在终端里运行：
#   ./install.mac.command
#
# 从网上下载的发布包如果被 Gatekeeper 拦住，可先在终端执行：
#   xattr -d com.apple.quarantine install.mac.command install.sh

# 记下双击时的目录（通常是用户主目录），安装完成后要把它作为 soloncode web 的工作区。
start_dir="$PWD"
cd "$(dirname "$0")" || exit 1

# SOLONCODE_SETUP 让 install.sh 跳过它自己的“等回车”，由本文件统一收尾。
SOLONCODE_SETUP=1 bash ./install.sh "$@"
status=$?

# ========== 收尾策略 ==========
# 双击打开的窗口是“登录 shell 只为跑本文件”的一次性窗口，回车后：
#   * 安装成功：直接借这个窗口启动 soloncode web（装完马上能用，不必再开终端敲命令）；
#   * 安装失败：关掉窗口（错误信息在上面，回车前可以看）。
# 用户在自己的终端里手动执行本文件时不动这个窗口，回车只是退出。
# 双击的判定：父进程命令行里带着本文件名（Finder 的启动方式是 `shell -c <本文件>`），
# 或者父进程就是“终端”本身；命中后用 tty 记住这个窗口，后续按 tty 精确处理，不碰其它窗口。
# 想回车只退出（不开 web、不关窗）：SOLONCODE_KEEP_TERMINAL=1 ./install.mac.command
terminal_tty=""
detect_launcher_window() {
    [ -t 0 ] || return 1
    [ -z "${SOLONCODE_KEEP_TERMINAL:-}" ] || return 1
    local parent_cmd
    parent_cmd=$(ps -o command= -p "$PPID" 2>/dev/null) || return 1
    case "$parent_cmd" in
        *install.mac.command* | *Terminal.app/Contents/MacOS/Terminal*) ;;
        *) return 1 ;;
    esac
    terminal_tty=$(tty 2>/dev/null) || return 1
    case "$terminal_tty" in
        /dev/*) return 0 ;;
        *) return 1 ;;
    esac
}

# 关窗交给 AppleScript：Terminal.app 会不会自己关窗取决于用户的“shell 退出后”偏好设置，
# 脚本里改不了它。放到后台单独跑（本脚本马上要退出，不能等它返回），并先延迟一下：
# 这样终端能把“Saving session...”打完，也不会因为窗口里还有进程而弹“是否终止”的确认框。
close_terminal_window() {
    [ "${TERM_PROGRAM:-}" = "Apple_Terminal" ] || return 0
    command -v osascript >/dev/null 2>&1 || return 0
    [ -n "$terminal_tty" ] || return 0
    local script="tell application \"Terminal\"
    repeat with w in windows
        repeat with t in tabs of w
            if tty of t is \"$terminal_tty\" then
                delay 0.5
                close w saving no
                return
            end if
        end repeat
    end repeat
end tell"
    ( osascript -e "$script" >/dev/null 2>&1 & ) >/dev/null 2>&1
}

detect_launcher_window
soloncode_bin="$HOME/.soloncode/bin/soloncode"

echo ""
if [ "$status" -ne 0 ]; then
    printf '[Error] 安装没有正常结束（退出码 %s），请查看上方提示。\n' "$status"
    printf '%s' "按回车键关闭窗口..."
    read -r || true
    echo ""
    close_terminal_window
    exit "$status"
fi

if [ -n "$terminal_tty" ] && [ -x "$soloncode_bin" ]; then
    printf '%s\n' "安装完成。"
    printf '%s' "按回车键在这个窗口里启动 soloncode web（之后这个窗口就是 SolonCode 的终端，Ctrl+C 可停止）..."
    read -r || true
    echo ""
    # 回到双击时所在的目录：它会被当作这次 Web 会话的工作区，而不是安装包目录。
    cd "$start_dir" 2>/dev/null || cd "$HOME" || true
    printf '%s\n' "Starting SolonCode web in: $PWD"
    "$soloncode_bin" web
    web_status=$?
    # 服务退出后（Ctrl+C 或出错）窗口不会自己消失，这里再收一次尾。
    echo ""
    printf '%s' "按回车键关闭窗口..."
    read -r || true
    echo ""
    close_terminal_window
    [ "$web_status" -eq 0 ] || exit "$web_status"
    exit 0
fi

printf '%s\n' "安装完成。重新打开终端后即可使用 soloncode 命令。"
if [ -n "$terminal_tty" ]; then
    printf '%s' "按回车键关闭窗口..."
else
    printf '%s' "按回车键退出..."
fi
read -r || true
echo ""
close_terminal_window

exit 0
