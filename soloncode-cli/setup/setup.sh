#!/bin/bash
#
# SolonCode CLI Installer
# Usage: curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash
#

set -e

VERSION="v2026.8.28"
PACKAGE_URL="https://gitee.com/opensolon/soloncode/releases/download/${VERSION}/soloncode-cli-bin-${VERSION}.tar.gz"
TEMP_DIR="/tmp/soloncode-install"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

DOWNLOAD_PID=""

cleanup() {
    if [ -n "$DOWNLOAD_PID" ]; then
        kill "$DOWNLOAD_PID" 2>/dev/null || true
    fi
    rm -rf "$TEMP_DIR"
}

trap cleanup EXIT

# ---------------------------------------------------------------------------
# Download progress (kept in sync with setup.ps1)
# ---------------------------------------------------------------------------

BAR_WIDTH=30

# Fractional sleep is not available on every platform
if sleep 0.2 2>/dev/null; then
    POLL_INTERVAL=0.2
else
    POLL_INTERVAL=1
fi

format_size() {
    awk -v b="$1" 'BEGIN {
        if (b < 0) { printf "?"; }
        else if (b >= 1073741824) { printf "%.2f GB", b / 1073741824; }
        else if (b >= 1048576) { printf "%.2f MB", b / 1048576; }
        else if (b >= 1024) { printf "%.1f KB", b / 1024; }
        else { printf "%d B", b; }
    }'
}

file_size() {
    if [ -f "$1" ]; then
        wc -c < "$1" 2>/dev/null | tr -d '[:space:]'
    else
        echo 0
    fi
}

remote_size() {
    local url="$1"
    local size=""

    if command -v curl &> /dev/null; then
        size=$(curl -fsIL "$url" 2>/dev/null | tr -d '\r' \
            | grep -i '^content-length:' | tail -1 | awk '{print $2}')
    elif command -v wget &> /dev/null; then
        size=$(wget --spider -S "$url" 2>&1 | tr -d '\r' \
            | grep -i '^ *content-length:' | tail -1 | awk '{print $2}')
    fi

    case "$size" in
        ''|*[!0-9]*) echo 0 ;;
        *) echo "$size" ;;
    esac
}

draw_bar() {
    local current="$1"
    local total="$2"
    local pct=0
    local filled=0
    local bar=""
    local i=0

    if [ "$total" -gt 0 ]; then
        pct=$(( current * 100 / total ))
        [ "$pct" -gt 100 ] && pct=100
        filled=$(( pct * BAR_WIDTH / 100 ))

        while [ "$i" -lt "$BAR_WIDTH" ]; do
            if [ "$i" -lt "$filled" ]; then
                bar="${bar}="
            elif [ "$i" -eq "$filled" ]; then
                bar="${bar}>"
            else
                bar="${bar} "
            fi
            i=$(( i + 1 ))
        done

        printf '\r  [%s] %3d%%  %s / %s   ' \
            "$bar" "$pct" "$(format_size "$current")" "$(format_size "$total")"
    else
        printf '\r  Downloaded %s   ' "$(format_size "$current")"
    fi
}

download_with_progress() {
    local url="$1"
    local out="$2"
    local total
    local final

    total=$(remote_size "$url")

    if command -v curl &> /dev/null; then
        curl -fsSL "$url" -o "$out" &
    elif command -v wget &> /dev/null; then
        wget -q "$url" -O "$out" &
    else
        error "curl or wget is required"
        return 1
    fi

    DOWNLOAD_PID=$!
    draw_bar 0 "$total"

    while kill -0 "$DOWNLOAD_PID" 2>/dev/null; do
        draw_bar "$(file_size "$out")" "$total"
        sleep "$POLL_INTERVAL"
    done

    if ! wait "$DOWNLOAD_PID"; then
        DOWNLOAD_PID=""
        echo ""
        return 1
    fi
    DOWNLOAD_PID=""

    final=$(file_size "$out")
    [ "$total" -le 0 ] && total="$final"
    draw_bar "$final" "$total"
    echo ""
}

# Create temp directory
mkdir -p "$TEMP_DIR"

info "Downloading SolonCode CLI ${VERSION}..."

# Download package (with progress bar)
if ! download_with_progress "$PACKAGE_URL" "$TEMP_DIR/package.tar.gz"; then
    error "Download failed: $PACKAGE_URL"
    exit 1
fi

info "Extracting package..."

# Extract
tar -xzf "$TEMP_DIR/package.tar.gz" -C "$TEMP_DIR"

# Find install.sh
INSTALL_SCRIPT=$(find "$TEMP_DIR" -name "install.sh" -type f | head -1)

if [ -z "$INSTALL_SCRIPT" ]; then
    error "install.sh not found in package"
    exit 1
fi

info "Running installer..."

# Set environment variable to tell install.sh not to wait
export SOLONCODE_SETUP=1

# Run installer
bash "$INSTALL_SCRIPT"

# Detect user's default shell
USER_SHELL=$(basename "$SHELL" 2>/dev/null || echo "bash")

# Check if symlink was created (by install.sh)
SYMLINK_EXISTS=false
if [ -L "/usr/local/bin/soloncode" ]; then
    SYMLINK_EXISTS=true
fi

echo ""
info "Installation complete!"
echo ""

if [ "$SYMLINK_EXISTS" = true ]; then
    echo -e "You can now run: ${CYAN}soloncode cli${NC} or ${CYAN}soloncode web 0${NC}"
else
    echo -e "To use soloncode immediately, run:"
    echo -e "  ${CYAN}source ~/.${USER_SHELL}rc${NC}"
    echo ""
    echo -e "Then run: ${CYAN}soloncode cli${NC} or ${CYAN}soloncode web 0${NC}"
fi

echo ""

# Note: For immediate use in current shell session, user needs to manually source
# This is a limitation of piping to bash - subshell cannot modify parent shell's PATH