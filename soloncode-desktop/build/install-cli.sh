#!/bin/bash
#
# SolonCode CLI Auto-Installer for Desktop (Linux/Mac)
# Called by Tauri app when ~/.soloncode/bin/soloncode-cli.jar not found
# Usage: install-cli.sh [release_dir]
#

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${GREEN}[soloncode]${NC} $*"; }
warn()  { echo -e "${YELLOW}[soloncode]${NC} $*"; }
error() { echo -e "${RED}[Error]${NC} $*"; exit 1; }

# ==================== 确定源目录 ====================

if [ -n "$1" ]; then
    RELEASE_DIR="$1"
else
    RELEASE_DIR=""
    SEARCH_DIR="$(cd "$(dirname "$0")/.." && pwd)"
    for i in $(seq 1 10); do
        if [ -d "$SEARCH_DIR/soloncode-cli/release/bin" ]; then
            RELEASE_DIR="$SEARCH_DIR/soloncode-cli/release"
            break
        fi
        PARENT="$(dirname "$SEARCH_DIR")"
        [ "$PARENT" = "$SEARCH_DIR" ] && break
        SEARCH_DIR="$PARENT"
    done
fi

[ -z "$RELEASE_DIR" ] && error "release directory not found"
[ -d "$RELEASE_DIR/bin" ] || error "release bin directory not found: $RELEASE_DIR/bin"

# ==================== 定位 JAR 文件 ====================

JAR_SOURCE=""
if [ -f "$RELEASE_DIR/bin/soloncode-cli.jar" ]; then
    JAR_SOURCE="$RELEASE_DIR/bin/soloncode-cli.jar"
else
    SEARCH_JAR="$RELEASE_DIR"
    for i in $(seq 1 10); do
        if [ -f "$SEARCH_JAR/soloncode-cli/target/soloncode-cli.jar" ]; then
            JAR_SOURCE="$SEARCH_JAR/soloncode-cli/target/soloncode-cli.jar"
            break
        fi
        PARENT="$(dirname "$SEARCH_JAR")"
        [ "$PARENT" = "$SEARCH_JAR" ] && break
        SEARCH_JAR="$PARENT"
    done
fi
[ -z "$JAR_SOURCE" ] && error "soloncode-cli.jar not found in release/bin or target/"
info "Found JAR: $JAR_SOURCE"

# ==================== 确定目标目录 ====================

TARGET_DIR="$HOME/.soloncode"
TARGET_BIN="$TARGET_DIR/bin"
TARGET_SKILLS="$TARGET_DIR/skills"
TARGET_CONFIG="$TARGET_DIR/config.yml"
TARGET_AGENTS="$TARGET_DIR/AGENTS.md"
OLD_TARGET_CONFIG="$TARGET_BIN/config.yml"
OLD_TARGET_AGENTS="$TARGET_BIN/AGENTS.md"

# ==================== 检查是否已安装 ====================

if [ -f "$TARGET_BIN/soloncode-cli.jar" ]; then
    info "CLI already installed at $TARGET_BIN"
    exit 0
fi

info "Installing to $TARGET_DIR..."

# ==================== 备份已有配置 ====================

CONFIG_BACKUP=""
AGENTS_BACKUP=""

# 迁移旧版本的配置文件（从 bin/ 目录移动到根目录）
if [ -f "$OLD_TARGET_CONFIG" ] && [ ! -f "$TARGET_CONFIG" ]; then
    mv "$OLD_TARGET_CONFIG" "$TARGET_CONFIG"
    info "Migrated config.yml from bin/ to root directory"
fi
if [ -f "$OLD_TARGET_AGENTS" ] && [ ! -f "$TARGET_AGENTS" ]; then
    mv "$OLD_TARGET_AGENTS" "$TARGET_AGENTS"
    info "Migrated AGENTS.md from bin/ to root directory"
fi

if [ -f "$TARGET_CONFIG" ]; then
    CONFIG_BACKUP="$(mktemp)"
    cp "$TARGET_CONFIG" "$CONFIG_BACKUP"
    info "Backed up existing config.yml"
fi

if [ -f "$TARGET_AGENTS" ]; then
    AGENTS_BACKUP="$(mktemp)"
    cp "$TARGET_AGENTS" "$AGENTS_BACKUP"
    info "Backed up existing AGENTS.md"
fi

# ==================== 创建目标目录 ====================

mkdir -p "$TARGET_DIR"
mkdir -p "$TARGET_BIN"
mkdir -p "$TARGET_SKILLS"

# ==================== 复制文件 ====================

info "Copying bin/..."
cp -r "$RELEASE_DIR/bin/"* "$TARGET_BIN/" 2>/dev/null || true

info "Copying soloncode-cli.jar..."
cp "$JAR_SOURCE" "$TARGET_BIN/soloncode-cli.jar"

if [ -f "$RELEASE_DIR/config.yml" ] && [ ! -f "$TARGET_CONFIG" ]; then
    cp "$RELEASE_DIR/config.yml" "$TARGET_CONFIG"
fi
if [ -f "$RELEASE_DIR/AGENTS.md" ] && [ ! -f "$TARGET_AGENTS" ]; then
    cp "$RELEASE_DIR/AGENTS.md" "$TARGET_AGENTS"
fi

if [ -d "$RELEASE_DIR/skills" ]; then
    info "Copying skills/..."
    rm -rf "$TARGET_SKILLS"
    cp -r "$RELEASE_DIR/skills" "$TARGET_SKILLS"
fi

# ==================== 恢复配置备份 ====================

if [ -n "$CONFIG_BACKUP" ]; then
    cp "$CONFIG_BACKUP" "$TARGET_CONFIG"
    rm -f "$CONFIG_BACKUP"
    info "Preserved existing config.yml"
fi

if [ -n "$AGENTS_BACKUP" ]; then
    cp "$AGENTS_BACKUP" "$TARGET_AGENTS"
    rm -f "$AGENTS_BACKUP"
    info "Preserved existing AGENTS.md"
fi

# 清理旧位置的配置文件
rm -f "$OLD_TARGET_CONFIG" "$OLD_TARGET_AGENTS"

# ==================== 创建 soloncode 启动脚本 ====================

info "Creating 'soloncode' command..."
cat > "$TARGET_BIN/soloncode" << 'LAUNCHER_EOF'
#!/bin/bash
# Solon Code CLI Launcher
# 获取脚本真实路径（兼容软链接）
SCRIPT_PATH="$0"
while [ -L "$SCRIPT_PATH" ]; do
    SCRIPT_DIR="$(cd "$(dirname "$SCRIPT_PATH")" && pwd)"
    SCRIPT_PATH="$(readlink "$SCRIPT_PATH")"
    case "$SCRIPT_PATH" in
        /*) ;;
        *)  SCRIPT_PATH="$SCRIPT_DIR/$SCRIPT_PATH" ;;
    esac
done
SCRIPT_DIR="$(cd "$(dirname "$SCRIPT_PATH")" && pwd)"

# 检测 Java 版本，如果是 21+ 则添加 --enable-native-access 参数
JAVA_VER=$(java -version 2>&1 | head -n1 | grep -oE '"[0-9]+' | grep -oE '[0-9]+' | head -1)
if [ -z "$JAVA_VER" ]; then
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
chmod +x "$TARGET_BIN/soloncode"
info "Created: $TARGET_BIN/soloncode"

# ==================== 注册 PATH 环境变量 ====================

info "Configuring PATH..."

PATH_LINE='export PATH="$PATH:$HOME/.soloncode/bin"'
PATH_MARKER='# Solon Code CLI'

USER_SHELL=$(basename "$SHELL" 2>/dev/null || echo "unknown")

declare -a CONFIG_FILES=()
case "$USER_SHELL" in
    zsh)
        CONFIG_FILES+=("$HOME/.zshrc")
        ;;
    bash)
        if [[ "$(uname -s)" == "Darwin" ]]; then
            CONFIG_FILES+=("$HOME/.bash_profile")
            CONFIG_FILES+=("$HOME/.bashrc")
        else
            CONFIG_FILES+=("$HOME/.bashrc")
            CONFIG_FILES+=("$HOME/.bash_profile")
        fi
        ;;
    fish)
        CONFIG_FILES+=("$HOME/.config/fish/config.fish")
        PATH_LINE='set -gx PATH $PATH $HOME/.soloncode/bin'
        ;;
    *)
        CONFIG_FILES+=("$HOME/.profile")
        CONFIG_FILES+=("$HOME/.bashrc")
        CONFIG_FILES+=("$HOME/.zshrc")
        ;;
esac

CONFIG_UPDATED=false
for CONFIG_FILE in "${CONFIG_FILES[@]}"; do
    if [[ "$USER_SHELL" == "fish" && "$CONFIG_FILE" == *".fish" ]]; then
        PATH_LINE='set -gx PATH $PATH $HOME/.soloncode/bin'
    else
        PATH_LINE='export PATH="$PATH:$HOME/.soloncode/bin"'
    fi

    if [ -f "$CONFIG_FILE" ]; then
        if grep -qF "$HOME/.soloncode/bin" "$CONFIG_FILE" 2>/dev/null; then
            info "PATH already configured in $(basename "$CONFIG_FILE")"
            CONFIG_UPDATED=true
            continue
        fi
    fi

    CONFIG_DIR=$(dirname "$CONFIG_FILE")
    [ ! -d "$CONFIG_DIR" ] && mkdir -p "$CONFIG_DIR" 2>/dev/null || continue

    echo "" >> "$CONFIG_FILE" 2>/dev/null || continue
    echo "$PATH_MARKER" >> "$CONFIG_FILE" 2>/dev/null || continue
    echo "$PATH_LINE" >> "$CONFIG_FILE" 2>/dev/null || continue
    info "Added to PATH in $(basename "$CONFIG_FILE")"
    CONFIG_UPDATED=true
done

# ==================== 尝试创建软链接 ====================

SYMLINK_CREATED=false
if [ ! -e "/usr/local/bin/soloncode" ]; then
    if [ -w "/usr/local/bin" ] 2>/dev/null; then
        ln -sf "$TARGET_BIN/soloncode" /usr/local/bin/soloncode 2>/dev/null && SYMLINK_CREATED=true
    elif command -v sudo >/dev/null 2>&1; then
        if sudo -n true 2>/dev/null; then
            sudo ln -sf "$TARGET_BIN/soloncode" /usr/local/bin/soloncode 2>/dev/null && SYMLINK_CREATED=true
        fi
    fi
fi

[ "$SYMLINK_CREATED" = true ] && info "Created symlink: /usr/local/bin/soloncode"

# ==================== 验证安装 ====================

[ -f "$TARGET_BIN/soloncode-cli.jar" ] || error "soloncode-cli.jar not found after installation"

echo ""
info "CLI installed successfully at $TARGET_DIR"
echo ""
echo "  Directory structure:"
echo "    ~/.soloncode/"
echo "    ├── config.yml      (configuration)"
echo "    ├── AGENTS.md       (agents config)"
echo "    ├── bin/"
echo "    │   ├── soloncode-cli.jar"
echo "    │   └── soloncode       (launcher)"
echo "    └── skills/        (skill modules)"
echo ""
if [ "$SYMLINK_CREATED" = true ]; then
    echo -e "  ${CYAN}You can run: soloncode${NC}"
else
    echo -e "  ${CYAN}Run: source ~/.${USER_SHELL}rc && soloncode${NC}"
fi
echo ""
