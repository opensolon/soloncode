#!/usr/bin/env bash
# macOS 下双击运行入口：Finder 会打开“终端”并执行本文件，实际逻辑都在同目录的 run.sh。
#
# 双击（不带参数）默认启动 Web 界面并自动打开浏览器，与 run.sh 保持一致。
#
# 也可以带参数运行：
#   ./run.mac.command web 4808
#   ./run.mac.command cli      # 交互式命令行
#
# 从网上下载的发布包如果被 Gatekeeper 拦住，可先执行：
#   xattr -d com.apple.quarantine run.mac.command run.sh

cd "$(dirname "$0")" || exit 1
exec ./run.sh "$@"
