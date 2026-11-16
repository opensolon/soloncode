#!/usr/bin/env bash
# macOS 双击入口：Finder 会用“终端”打开本文件，实际逻辑在同目录的 run.sh。
#
# 从网上下载的发布包如果被 Gatekeeper 拦住，可先执行：
#   xattr -d com.apple.quarantine run.mac.command run.sh

cd "$(dirname "$0")" || exit 1
exec ./run.sh
