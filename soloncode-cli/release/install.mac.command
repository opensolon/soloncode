#!/usr/bin/env bash
# macOS 下双击安装入口：Finder 会用“终端”执行本文件，真正的安装逻辑都在同目录的 install.sh。
#
# 也可以在终端里运行：
#   ./install.mac.command
#
# 从网上下载的发布包如果被 Gatekeeper 拦住，可先在终端执行：
#   xattr -d com.apple.quarantine install.mac.command install.sh

cd "$(dirname "$0")" || exit 1

# SOLONCODE_SETUP 让 install.sh 跳过它自己的“等回车”，由本文件统一收尾：
# 无论安装成功还是中途出错，窗口都会停住，不会一闪就关掉。
SOLONCODE_SETUP=1 bash ./install.sh "$@"
status=$?

echo ""
if [ "$status" -eq 0 ]; then
    printf '%s\n' "安装完成。重新打开终端后即可使用 soloncode 命令。"
else
    printf '%s\n' "[Error] 安装没有正常结束（退出码 $status），请查看上方提示。"
fi
printf '%s' "按回车键关闭窗口..."
read -r

exit "$status"
