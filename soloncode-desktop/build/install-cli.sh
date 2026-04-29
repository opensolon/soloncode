#!/bin/bash
#
# SolonCode CLI Auto-Installer for Desktop (Linux/Mac)
# Called by Tauri app when ~/.soloncode/bin/soloncode-cli.jar not found
# Usage: install-cli.sh [release_dir]
#

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

info()  { echo -e "${GREEN}[soloncode]${NC} $*"; }
error() { echo -e "${RED}[Error]${NC} $*"; exit 1; }

# ==================== 确定源目录 ====================

if [ -n "$1" ]; then
    RELEASE_DIR="$1"
else
    # 从脚本位置向上查找 soloncode-cli/release/
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

# JAR 优先从 release/bin/ 找，否则从 target/ 找
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

# ==================== 检查是否已安装 ====================

if [ -f "$TARGET_BIN/soloncode-cli.jar" ]; then
    info "CLI already installed at $TARGET_BIN"
    exit 0
fi

info "Installing to $TARGET_DIR..."

# ==================== 备份已有配置 ====================

CONFIG_BACKUP=""
AGENTS_BACKUP=""

if [ -f "$TARGET_BIN/config.yml" ]; then
    CONFIG_BACKUP="$(mktemp)"
    cp "$TARGET_BIN/config.yml" "$CONFIG_BACKUP"
    info "Backed up existing config.yml"
fi

if [ -f "$TARGET_BIN/AGENTS.md" ]; then
    AGENTS_BACKUP="$(mktemp)"
    cp "$TARGET_BIN/AGENTS.md" "$AGENTS_BACKUP"
    info "Backed up existing AGENTS.md"
fi

# ==================== 创建目标目录 ====================

mkdir -p "$TARGET_BIN"
mkdir -p "$TARGET_SKILLS"

# ==================== 复制文件 ====================

info "Copying bin/..."
cp -r "$RELEASE_DIR/bin/"* "$TARGET_BIN/" 2>/dev/null || true

info "Copying soloncode-cli.jar..."
cp "$JAR_SOURCE" "$TARGET_BIN/soloncode-cli.jar"

# 复制 config.yml 和 AGENTS.md（如果存在且目标没有）
if [ -f "$RELEASE_DIR/config.yml" ] && [ ! -f "$TARGET_BIN/config.yml" ]; then
    cp "$RELEASE_DIR/config.yml" "$TARGET_BIN/config.yml"
fi
if [ -f "$RELEASE_DIR/AGENTS.md" ] && [ ! -f "$TARGET_BIN/AGENTS.md" ]; then
    cp "$RELEASE_DIR/AGENTS.md" "$TARGET_BIN/AGENTS.md"
fi

if [ -d "$RELEASE_DIR/skills" ]; then
    info "Copying skills/..."
    rm -rf "$TARGET_SKILLS"
    cp -r "$RELEASE_DIR/skills" "$TARGET_SKILLS"
fi

# ==================== 恢复配置备份 ====================

if [ -n "$CONFIG_BACKUP" ]; then
    cp "$CONFIG_BACKUP" "$TARGET_BIN/config.yml"
    rm -f "$CONFIG_BACKUP"
    info "Preserved existing config.yml"
fi

if [ -n "$AGENTS_BACKUP" ]; then
    cp "$AGENTS_BACKUP" "$TARGET_BIN/AGENTS.md"
    rm -f "$AGENTS_BACKUP"
    info "Preserved existing AGENTS.md"
fi

# ==================== 创建启动脚本 ====================

# soloncode (Bash launcher)
cat > "$TARGET_BIN/soloncode" << 'LAUNCHER'
#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
java -Dfile.encoding=UTF-8 -jar "$SCRIPT_DIR/soloncode-cli.jar" "$@"
LAUNCHER
chmod +x "$TARGET_BIN/soloncode"
info "Created soloncode launcher"

# ==================== 验证安装 ====================

[ -f "$TARGET_BIN/soloncode-cli.jar" ] || error "soloncode-cli.jar not found after installation"

info "CLI installed successfully at $TARGET_DIR"
