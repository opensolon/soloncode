#!/bin/bash
# =============================================
#  Solon Code Installer (Linux / macOS)
#  支持重复安装，保留已有 config.yml
#  兼容 bash, zsh, sh 等多种 shell
# =============================================

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo ""
echo "============================================"
echo -e "   Solon Code Installer"
echo "============================================"
echo ""

# =============================================
# 检查 Java 是否安装
# =============================================
echo -e "${YELLOW}[Pre-check]${NC} Verifying Java installation..."

if ! command -v java &> /dev/null; then
    echo ""
    echo -e "${RED}[Error] Java is not installed or not in PATH${NC}"
    echo ""
    echo "  Please install Java 8 or later:"
    echo "    - Download from: https://adoptium.net/"
    echo ""
    # If not called from setup.sh, wait for user input
    if [ -z "$SOLONCODE_SETUP" ]; then
        echo "Press Enter to exit..."
        read -r
    fi
    exit 1
fi

# 获取 Java 版本
JAVA_VERSION=$(java -version 2>&1 | head -n 1)
echo -e "      ${JAVA_VERSION}${NC}"
echo ""

# 源目录（脚本所在目录）
SOURCE_DIR="$(cd "$(dirname "$0")" && pwd)"

# 目标目录
TARGET_DIR="$HOME/.soloncode"
TARGET_BIN_DIR="$TARGET_DIR/bin"
TARGET_SKILLS_DIR="$TARGET_DIR/skills"

# 源目录
SOURCE_BIN_DIR="$SOURCE_DIR/bin"
SOURCE_SKILLS_DIR="$SOURCE_DIR/skills"
SOURCE_CONFIG="$SOURCE_DIR/config.yml"
SOURCE_AGENTS="$SOURCE_DIR/AGENTS.md"

# =============================================
# 检查源目录是否存在
# =============================================
if [ ! -d "$SOURCE_BIN_DIR" ]; then
    echo "[Error] Source bin directory not found: $SOURCE_BIN_DIR"
    exit 1
fi

# =============================================
# [1/5] 检查并备份已有的 config.yml 和 AGENTS.md，并迁移旧版本文件
# =============================================
echo "[1/5] Checking for existing configuration..."
CONFIG_BACKUP=""
AGENTS_BACKUP=""
TARGET_CONFIG="$TARGET_DIR/config.yml"
TARGET_AGENTS="$TARGET_DIR/AGENTS.md"
OLD_TARGET_CONFIG="$TARGET_BIN_DIR/config.yml"
OLD_TARGET_AGENTS="$TARGET_BIN_DIR/AGENTS.md"

# 迁移旧版本的配置文件（从 bin/ 目录移动到根目录）
if [ -f "$OLD_TARGET_CONFIG" ] && [ ! -f "$TARGET_CONFIG" ]; then
    mv "$OLD_TARGET_CONFIG" "$TARGET_CONFIG"
    echo "      Migrated config.yml from bin/ to root directory"
fi

if [ -f "$OLD_TARGET_AGENTS" ] && [ ! -f "$TARGET_AGENTS" ]; then
    mv "$OLD_TARGET_AGENTS" "$TARGET_AGENTS"
    echo "      Migrated AGENTS.md from bin/ to root directory"
fi

# 备份现有的配置文件
if [ -f "$TARGET_CONFIG" ]; then
    CONFIG_BACKUP=$(mktemp)
    cp "$TARGET_CONFIG" "$CONFIG_BACKUP"
    echo "      Found existing config.yml (will be preserved)"
else
    echo "      No existing config.yml found"
fi

if [ -f "$TARGET_AGENTS" ]; then
    AGENTS_BACKUP=$(mktemp)
    cp "$TARGET_AGENTS" "$AGENTS_BACKUP"
    echo "      Found existing AGENTS.md (will be preserved)"
else
    echo "      No existing AGENTS.md found"
fi

# =============================================
# [2/5] 创建目标目录结构
# =============================================
echo ""
echo "[2/5] Preparing target directory: $TARGET_DIR"

mkdir -p "$TARGET_DIR"
mkdir -p "$TARGET_BIN_DIR"
mkdir -p "$TARGET_SKILLS_DIR"

echo "      Created directory structure"

# =============================================
# [3/5] 复制文件
# =============================================
echo ""
echo "[3/5] Copying files..."

# 复制 bin 目录内容
cp -R "$SOURCE_BIN_DIR/"* "$TARGET_BIN_DIR/" 2>/dev/null || true
echo "      Copied bin/ directory"

# 复制 config.yml 和 AGENTS.md（从根目录）
if [ -f "$SOURCE_CONFIG" ]; then
    cp "$SOURCE_CONFIG" "$TARGET_CONFIG" 2>/dev/null || true
    echo "      Copied config.yml"
fi

if [ -f "$SOURCE_AGENTS" ]; then
    cp "$SOURCE_AGENTS" "$TARGET_AGENTS" 2>/dev/null || true
    echo "      Copied AGENTS.md"
fi

# 复制 skills 目录（覆盖更新）
if [ -d "$SOURCE_SKILLS_DIR" ]; then
    # 如果目标 skills 目录存在，先删除再复制
    if [ -d "$TARGET_SKILLS_DIR" ]; then
        rm -rf "$TARGET_SKILLS_DIR"
    fi
    cp -R "$SOURCE_SKILLS_DIR" "$TARGET_DIR/" 2>/dev/null || true
    echo "      Copied skills/ directory"
else
    echo "      No skills/ directory to copy"
fi

# =============================================
# [4/5] 恢复 config.yml 和 AGENTS.md（如果之前存在）
# =============================================
echo ""
echo "[4/5] Finalizing installation..."

if [ -n "$CONFIG_BACKUP" ]; then
    cp "$CONFIG_BACKUP" "$TARGET_CONFIG"
    rm -f "$CONFIG_BACKUP"
    echo "      Preserved existing config.yml"
fi

if [ -n "$AGENTS_BACKUP" ]; then
    cp "$AGENTS_BACKUP" "$TARGET_AGENTS"
    rm -f "$AGENTS_BACKUP"
    echo "      Preserved existing AGENTS.md"
fi

# 清理旧位置的配置文件（如果还存在）
if [ -f "$OLD_TARGET_CONFIG" ]; then
    rm -f "$OLD_TARGET_CONFIG"
fi

if [ -f "$OLD_TARGET_AGENTS" ]; then
    rm -f "$OLD_TARGET_AGENTS"
fi

# 检查 jar 文件是否存在
if [ ! -f "$TARGET_BIN_DIR/soloncode-cli.jar" ]; then
    echo "[Error] soloncode-cli.jar not found in $TARGET_BIN_DIR"
    exit 1
fi
echo "      Found soloncode-cli.jar"

# =============================================
# [5/5] 创建 soloncode 命令脚本
# =============================================
echo ""
echo "[5/5] Creating 'soloncode' command..."
cat > "$TARGET_BIN_DIR/soloncode" << 'LAUNCHER_EOF'
#!/bin/bash
# Solon Code CLI Launcher
# 获取脚本真实路径（兼容软链接）
SCRIPT_PATH="$0"
# 解析软链接（兼容 macOS 和 Linux）
while [ -L "$SCRIPT_PATH" ]; do
    SCRIPT_DIR="$(cd "$(dirname "$SCRIPT_PATH")" && pwd)"
    SCRIPT_PATH="$(readlink "$SCRIPT_PATH")"
    # 如果是相对路径，转换为绝对路径
    case "$SCRIPT_PATH" in
        /*) ;;  # 已经是绝对路径
        *)  SCRIPT_PATH="$SCRIPT_DIR/$SCRIPT_PATH" ;;
    esac
done
SCRIPT_DIR="$(cd "$(dirname "$SCRIPT_PATH")" && pwd)"

# 检测 Java 版本，如果是 21+ 则添加 --enable-native-access 参数
JAVA_VER=$(java -version 2>&1 | head -n1 | grep -oE '"[0-9]+' | grep -oE '[0-9]+' | head -1)
if [ -z "$JAVA_VER" ]; then
    # 如果提取失败，尝试另一种方式
    JAVA_VER=$(java -version 2>&1 | head -n1 | cut -d'"' -f2 | cut -d'.' -f1)
fi
JAVA_OPTS="-Dfile.encoding=UTF-8"
if [ -n "$JAVA_VER" ] && [ "$JAVA_VER" -ge 21 ]; then
    JAVA_OPTS="$JAVA_OPTS --enable-native-access=ALL-UNNAMED"
fi

# Git Bash / MSYS terminals on Windows often need winpty for correct line editing.
if [ -n "$MSYSTEM" ]; then
    JAVA_OPTS="$JAVA_OPTS -Djline.terminal.type=xterm-256color"
    if [ -t 0 ] && [ -t 1 ] && command -v winpty >/dev/null 2>&1; then
        exec winpty java $JAVA_OPTS -jar "$SCRIPT_DIR/soloncode-cli.jar" "$@"
    fi
fi

java $JAVA_OPTS -jar "$SCRIPT_DIR/soloncode-cli.jar" "$@"
LAUNCHER_EOF
chmod +x "$TARGET_BIN_DIR/soloncode"
echo "      Created: $TARGET_BIN_DIR/soloncode"

# =============================================
# 配置 PATH 环境变量（兼容多种 shell 和系统）
# =============================================
echo ""
echo "Configuring PATH..."

# 要添加的 PATH 配置
PATH_LINE='export PATH="$PATH:$HOME/.soloncode/bin"'
PATH_MARKER='# Solon Code CLI'

# 检测当前用户默认 shell
USER_SHELL=$(basename "$SHELL" 2>/dev/null || echo "unknown")

# 定义需要配置的 shell 配置文件（按优先级排序）
declare -a CONFIG_FILES=()

case "$USER_SHELL" in
    zsh)
        # Zsh: 优先 .zshrc
        CONFIG_FILES+=("$HOME/.zshrc")
        ;;
    bash)
        # Bash: 不同系统读取不同文件
        # macOS 默认读取 .bash_profile
        # Linux 通常读取 .bashrc
        if [[ "$(uname -s)" == "Darwin" ]]; then
            # macOS
            CONFIG_FILES+=("$HOME/.bash_profile")
            # 同时也写入 .bashrc 以兼容非登录 shell
            CONFIG_FILES+=("$HOME/.bashrc")
        else
            # Linux
            CONFIG_FILES+=("$HOME/.bashrc")
            # 同时也写入 .bash_profile 以兼容登录 shell
            CONFIG_FILES+=("$HOME/.bash_profile")
        fi
        ;;
    fish)
        # Fish shell
        CONFIG_FILES+=("$HOME/.config/fish/config.fish")
        PATH_LINE='set -gx PATH $PATH $HOME/.soloncode/bin'
        ;;
    *)
        # 未知 shell，尝试写入多个文件
        CONFIG_FILES+=("$HOME/.profile")
        CONFIG_FILES+=("$HOME/.bashrc")
        CONFIG_FILES+=("$HOME/.zshrc")
        ;;
esac

# 写入配置文件
CONFIG_UPDATED=false
for CONFIG_FILE in "${CONFIG_FILES[@]}"; do
    # 确保 Fish 使用正确的配置语法
    if [[ "$USER_SHELL" == "fish" && "$CONFIG_FILE" == *".fish" ]]; then
        PATH_LINE='set -gx PATH $PATH $HOME/.soloncode/bin'
    else
        PATH_LINE='export PATH="$PATH:$HOME/.soloncode/bin"'
    fi
    
    # 检查文件是否已包含配置（使用 .soloncode/bin 作为匹配关键词，避免 $HOME 展开导致匹配失败）
    if [ -f "$CONFIG_FILE" ]; then
        if grep -qF '.soloncode/bin' "$CONFIG_FILE" 2>/dev/null; then
            echo "      PATH already configured in $(basename "$CONFIG_FILE")"
            CONFIG_UPDATED=true
            continue
        fi
    fi
    
    # 创建目录（针对 Fish 等需要子目录的情况）
    CONFIG_DIR=$(dirname "$CONFIG_FILE")
    if [ ! -d "$CONFIG_DIR" ]; then
        mkdir -p "$CONFIG_DIR" 2>/dev/null || continue
    fi
    
    # 追加配置
    echo "" >> "$CONFIG_FILE" 2>/dev/null || continue
    echo "$PATH_MARKER" >> "$CONFIG_FILE" 2>/dev/null || continue
    echo "$PATH_LINE" >> "$CONFIG_FILE" 2>/dev/null || continue
    echo "      Added to PATH in $(basename "$CONFIG_FILE")"
    CONFIG_UPDATED=true
done

# =============================================
# 尝试创建软链接到 /usr/local/bin（可选）
# =============================================
SYMLINK_CREATED=false
if [ ! -e "/usr/local/bin/soloncode" ]; then
    if [ -w "/usr/local/bin" ] 2>/dev/null; then
        # 有写权限，直接创建
        ln -sf "$TARGET_BIN_DIR/soloncode" /usr/local/bin/soloncode 2>/dev/null && SYMLINK_CREATED=true
    elif command -v sudo >/dev/null 2>&1; then
        # 尝试用 sudo（非交互式，静默失败）
        if sudo -n true 2>/dev/null; then
            sudo ln -sf "$TARGET_BIN_DIR/soloncode" /usr/local/bin/soloncode 2>/dev/null && SYMLINK_CREATED=true
        fi
    fi
fi

if [ "$SYMLINK_CREATED" = true ]; then
    echo "      Created symlink: /usr/local/bin/soloncode"
fi

# =============================================
# 完成
# =============================================
echo ""
echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}   Installation Complete!${NC}"
echo -e "${GREEN}============================================${NC}"
echo ""
echo "  Install path: $TARGET_DIR"
echo "  Java version: $JAVA_VERSION"
echo ""

if [ "$SYMLINK_CREATED" = true ]; then
    echo -e "  ${CYAN}Symlink created: /usr/local/bin/soloncode${NC}"
    echo -e "  You can run ${CYAN}soloncode${NC} directly now!"
else
    echo -e "  ${CYAN}Usage:${NC}"
    echo "    1. Run: source ~/.${USER_SHELL}rc"
    echo "    2. Or restart your terminal"
    echo "    3. Then run: soloncode"
fi

echo ""
echo -e "  ${CYAN}Directory structure:${NC}"
echo "    ~/.soloncode/"
echo "    ├── config.yml      (configuration, preserved if exists)"
echo "    ├── AGENTS.md       (agents config, preserved if exists)"
echo "    ├── bin/            (executables)"
echo "    │   ├── soloncode-cli.jar"
echo "    │   ├── soloncode       (launcher)"
echo "    │   └── uninstall.sh    (uninstall script)"
echo "    └── skills/        (skill modules)"
echo ""
echo -e "  ${YELLOW}[Tip]${NC} To use soloncode immediately in current terminal:"
echo "    source ~/.${USER_SHELL}rc"
echo ""

# If not called from setup.sh, wait for user input
if [ -z "$SOLONCODE_SETUP" ]; then
    echo "Press Enter to exit..."
    read -r
fi