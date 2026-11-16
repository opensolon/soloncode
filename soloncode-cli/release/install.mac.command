#!/usr/bin/env bash
# macOS 双击安装入口：Finder 会用“终端”执行本文件，真正的安装逻辑都在同目录的 install.sh。
#
# 也可以在终端里运行：
#   ./install.mac.command
#
# 从网上下载的发布包如果被 Gatekeeper 拦住，可先在终端执行：
#   xattr -d com.apple.quarantine install.mac.command install.sh

cd "$(dirname "$0")" || exit 1

# SOLONCODE_SETUP 让 install.sh 跳过它自己的“等回车”，由本文件统一收尾（避免提示两次）。
SOLONCODE_SETUP=1 bash ./install.sh "$@"
status=$?

# 收尾与 Windows 的 install.window.cmd 保持一致：只提示“按回车键退出”，回车后脚本结束。
# 脚本只能决定自己何时退出；窗口关不关由“终端 → 设置 → 描述文件 → Shell → shell 退出后”决定。
# 输出文案一律用英文，与 install.sh / install.ps1 保持一致（注释用中文，不影响输出）。
has_tty() { { : < /dev/tty; } 2>/dev/null; }

if [ "$status" -ne 0 ]; then
    printf '[Error] Installation did not complete (exit code %s). See the messages above.\n' "$status"
fi

# 有终端才等回车；完全没有终端（管道、无控制终端）时直接退出。
# 优先读 /dev/tty：双击时标准输入未必是终端，读 stdin 会立刻 EOF（提示一闪而过）。
if has_tty; then
    printf '%s' "Press Enter to exit..."
    IFS= read -r < /dev/tty || true
elif [ -t 0 ]; then
    printf '%s' "Press Enter to exit..."
    IFS= read -r || true
fi

exit "$status"
