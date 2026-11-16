#!/usr/bin/env bash
# macOS 下双击卸载入口：Finder 会用“终端”执行本文件，真正的卸载逻辑都在同目录的 uninstall.sh。
#
# 也可以在终端里运行：
#   ./uninstall.mac.command
#
# 从网上下载的发布包如果被 Gatekeeper 拦住，可先在终端执行：
#   xattr -d com.apple.quarantine uninstall.mac.command uninstall.sh
#
# 想卸载完成后让窗口留着（默认会尝试关闭窗口）：SOLONCODE_KEEP_TERMINAL=1 ./uninstall.mac.command

cd "$(dirname "$0")" || exit 1

# ========== 卸载前的温和提醒 ==========
# uninstall.sh 会 rm -rf ~/.soloncode，但正在运行的 SolonCode 进程不会随之退出：
# 它的窗口还在、内存里的会话还在，之后写盘可能失败或把目录又建回来。这里只提示，
# 是否继续交给 uninstall.sh 自己的 Y/N 确认，避免连续两次提问。
if command -v pgrep >/dev/null 2>&1; then
    running_pids=$(pgrep -f "soloncode-cli[.]jar" 2>/dev/null | tr '\n' ' ')
    if [ -n "$running_pids" ]; then
        echo "[Note] Detected running SolonCode process(es): $running_pids"
        echo "       Please stop them first (Ctrl+C in their windows), then uninstall."
        echo ""
    fi
fi

bash ./uninstall.sh "$@"
status=$?

# ========== 收尾策略 ==========
# 双击打开的窗口是“登录 shell 只为跑本文件”的一次性窗口，卸载结束后：
#   * 停在这个窗口里显示结果，回车后再关掉（卸载输出在上面，关之前可以看完）；
#   * 用户在自己的终端里手动执行本文件时不动这个窗口，回车只是退出。
# 双击的判定：父进程命令行里带着本文件名（Finder 的启动方式是 `shell -c <本文件>`），
# 或者父进程就是“终端”本身；命中后用 tty 记住这个窗口，后续按 tty 精确处理，不碰其它窗口。
terminal_tty=""
detect_launcher_window() {
    [ -t 0 ] || return 1
    [ -z "${SOLONCODE_KEEP_TERMINAL:-}" ] || return 1
    local parent_cmd
    parent_cmd=$(ps -o command= -p "$PPID" 2>/dev/null) || return 1
    case "$parent_cmd" in
        *uninstall.mac.command* | *Terminal.app/Contents/MacOS/Terminal*) ;;
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

# 输出文案一律用英文，与 uninstall.sh / uninstall.ps1 保持一致（注释用中文，不影响输出）。
echo ""
if [ "$status" -ne 0 ]; then
    printf '[Error] Uninstall did not complete (exit code %s). See the messages above.\n' "$status"
fi
# 有终端才提示并等回车；完全没有终端（管道、无控制终端）时直接退出，不阻塞自动化调用。
if { : < /dev/tty; } 2>/dev/null || [ -t 0 ]; then
    if [ -n "$terminal_tty" ]; then
        printf '%s' "Press Enter to close this window..."
    else
        printf '%s' "Press Enter to exit..."
    fi
    # 优先读 /dev/tty：双击时标准输入未必是终端，读 stdin 会立刻 EOF（提示一闪而过、窗口马上关掉）。
    if { : < /dev/tty; } 2>/dev/null; then
        IFS= read -r < /dev/tty || true
    else
        IFS= read -r || true
    fi
    echo ""
fi
close_terminal_window

exit "$status"
