#!/bin/bash
# =============================================
#  Solon Code Installer (Linux / macOS)
#  支持重复安装，保留已有 config.yml
#  兼容 bash, zsh, sh 等多种 shell
# =============================================

set -e

echo ""
echo "============================================"
echo "   Solon Code Installer"
echo "============================================"
echo ""

# 源目录（脚本所在目录）
SOURCE_DIR="$(cd "$(dirname "$0")" && pwd)"

# 目标目录
TARGET_DIR="$HOME/.soloncode"
TARGET_BIN_DIR="$TARGET_DIR/bin"

# 源 .soloncode 目录
SOURCE_SOLONCODE="$SOURCE_DIR/.soloncode"

# =============================================
# 检查源目录是否存在
# =============================================
if [ ! -d "$SOURCE_SOLONCODE" ]; then
    echo "[Error] Source directory not found: $SOURCE_SOLONCODE"
    exit 1
fi

# =============================================
# [1/5] 检查并备份已有的 config.yml
# =============================================
echo "[1/5] Checking for existing configuration..."
CONFIG_BACKUP=""
TARGET_CONFIG="$TARGET_BIN_DIR/config.yml"
if [ -f "$TARGET_CONFIG" ]; then
    CONFIG_BACKUP=$(mktemp)
    cp "$TARGET_CONFIG" "$CONFIG_BACKUP"
    echo "      Found existing config.yml (will be preserved)"
else
    echo "      No existing config.yml found"
fi

# =============================================
# [2/5] 创建目标目录结构
# =============================================
echo ""
echo "[2/5] Preparing target directory: $TARGET_DIR"

# 创建目录结构
mkdir -p "$TARGET_DIR"
mkdir -p "$TARGET_BIN_DIR"

echo "      Created directory structure"

# =============================================
# [3/5] 复制 .soloncode 目录内容到目标目录
# =============================================
echo ""
echo "[3/5] Copying files to $TARGET_DIR ..."

# 复制 bin 目录内容
if [ -d "$SOURCE_SOLONCODE/bin" ]; then
    cp -R "$SOURCE_SOLONCODE/bin/"* "$TARGET_BIN_DIR/" 2>/dev/null || true
    echo "      Copied bin/ directory"
fi

# 复制 skills 目录
if [ -d "$SOURCE_SOLONCODE/skills" ]; then
    # 如果目标 skills 目录存在，先删除再复制（更新）
    if [ -d "$TARGET_DIR/skills" ]; then
        rm -rf "$TARGET_DIR/skills"
    fi
    cp -R "$SOURCE_SOLONCODE/skills" "$TARGET_DIR/" 2>/dev/null || true
    echo "      Copied skills/ directory"
fi

# 复制其他文件（如 .gitignore 等，排除 bin 和 skills）
for item in "$SOURCE_SOLONCODE"/*; do
    item_name=$(basename "$item")
    if [ "$item_name" != "bin" ] && [ "$item_name" != "skills" ]; then
        cp -R "$item" "$TARGET_DIR/" 2>/dev/null || true
    fi
done

echo "      Files copied successfully"

# =============================================
# [4/5] 恢复 config.yml（如果之前存在）
# =============================================
echo ""
echo "[4/5] Finalizing installation..."

if [ -n "$CONFIG_BACKUP" ]; then
    cp "$CONFIG_BACKUP" "$TARGET_CONFIG"
    rm -f "$CONFIG_BACKUP"
    echo "      Preserved existing config.yml"
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
java -Dfile.encoding=UTF-8 -jar "$SCRIPT_DIR/soloncode-cli.jar" "$@"
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
    
    # 检查文件是否已包含配置
    if [ -f "$CONFIG_FILE" ]; then
        if grep -qF "$HOME/.soloncode/bin" "$CONFIG_FILE" 2>/dev/null; then
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
echo "============================================"
echo "   Installation Complete!"
echo "============================================"
echo ""
echo "  Install path: $TARGET_DIR"
echo "  Detected shell: $USER_SHELL"
echo ""

if [ "$SYMLINK_CREATED" = true ]; then
    echo "  Symlink created: /usr/local/bin/soloncode"
    echo "  You can run 'soloncode' directly now!"
else
    echo "  Usage:"
    echo "    1. Run: source ~/.${USER_SHELL}rc"
    echo "    2. Or restart your terminal"
    echo "    3. Then run: soloncode"
fi

echo ""
echo "  Directory structure:"
echo "    ~/.soloncode/"
echo "    ├── bin/           (executables)"
echo "    │   ├── soloncode-cli.jar"
echo "    │   ├── soloncode   (launcher)"
echo "    │   └── config.yml  (configuration)"
echo "    └── skills/         (skill modules)"
echo ""