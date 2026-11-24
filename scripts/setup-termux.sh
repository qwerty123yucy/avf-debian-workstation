#!/usr/bin/env bash
# Configure native (non-PRoot) Termux to manage and SSH into the AVF Debian 13 VM.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROOT_HOME="$(cd "$SCRIPT_DIR/../.." && pwd)"
KEY_SRC="${AVF_KEY_SRC:-$PROOT_HOME/.ssh/id_ed25519_avf_debian}"
TOKEN_ARG=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --token) TOKEN_ARG="${2:-}"; shift 2 ;;
        --key)   KEY_SRC="${2:-}"; shift 2 ;;
        -h|--help)
            echo "Usage: $0 [--token <ctl-token>] [--key <path/to/id_ed25519_avf_debian>]"
            exit 0
            ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

BIN_DIR="${PREFIX:-/usr/local}/bin"
mkdir -p "$BIN_DIR" "$HOME/.ssh" "$HOME/.config/avf-linux"
chmod 700 "$HOME/.ssh" "$HOME/.config/avf-linux"

# 1. Install avf-vm CLI
install -m 755 "$SCRIPT_DIR/avf-vm" "$BIN_DIR/avf-vm"
echo "[ok] Installed CLI: $BIN_DIR/avf-vm"

# 2. Install Ed25519 SSH key (kept strictly inside Termux private storage)
KEY_DST="$HOME/.ssh/id_ed25519_avf_debian"
if [[ -f "$KEY_SRC" ]]; then
    if [[ "$(realpath "$KEY_SRC" 2>/dev/null || echo "$KEY_SRC")" != "$(realpath "$KEY_DST" 2>/dev/null || echo "$KEY_DST")" ]]; then
        install -m 600 "$KEY_SRC" "$KEY_DST"
        [[ -f "${KEY_SRC}.pub" ]] && install -m 644 "${KEY_SRC}.pub" "${KEY_DST}.pub"
    fi
    chmod 600 "$KEY_DST"
    echo "[ok] Installed SSH key: $KEY_DST"
elif [[ -f "$KEY_DST" ]]; then
    chmod 600 "$KEY_DST"
    echo "[ok] Using existing SSH key: $KEY_DST"
else
    echo "[warn] SSH key not found at $KEY_SRC; pass --key <path> to copy it." >&2
fi

# 3. Configure ~/.ssh/config idempotently
SSH_CFG="$HOME/.ssh/config"
touch "$SSH_CFG"
chmod 600 "$SSH_CFG"
if ! grep -qE '^Host avf$' "$SSH_CFG"; then
    cat >> "$SSH_CFG" <<'EOF'

Host avf
    HostName 127.0.0.1
    Port 2222
    User root
    IdentityFile ~/.ssh/id_ed25519_avf_debian
    IdentitiesOnly yes
    StrictHostKeyChecking accept-new
    ServerAliveInterval 30
    ServerAliveCountMax 3
EOF
    echo "[ok] Added 'Host avf' to $SSH_CFG"
else
    echo "[ok] 'Host avf' already present in $SSH_CFG"
fi

# 4. Save control token for local HTTP API (127.0.0.1:2288)
TOKEN_FILE="$HOME/.config/avf-linux/token"
if [[ -n "$TOKEN_ARG" ]]; then
    printf '%s\n' "$TOKEN_ARG" > "$TOKEN_FILE"
    chmod 600 "$TOKEN_FILE"
    echo "[ok] Saved control token to $TOKEN_FILE"
elif [[ -s "$TOKEN_FILE" ]]; then
    chmod 600 "$TOKEN_FILE"
    echo "[ok] Keeping existing control token at $TOKEN_FILE"
else
    echo ""
    echo "Tap 'Copy Token' in the AVF Linux app, then paste the token below (or press Enter to skip):"
    read -r -p "Control token: " input_tok || true
    input_tok="$(printf '%s' "$input_tok" | tr -d ' \r\n')"
    if [[ -n "$input_tok" ]]; then
        printf '%s\n' "$input_tok" > "$TOKEN_FILE"
        chmod 600 "$TOKEN_FILE"
        echo "[ok] Saved control token to $TOKEN_FILE"
    else
        echo "[info] Skipped token; save it later to $TOKEN_FILE (chmod 600)."
    fi
fi

# 5. Check client tools
for tool in ssh curl python3; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "[warn] '$tool' not found in PATH. In Termux, install with: pkg install openssh curl python"
    fi
done

echo ""
echo "Done. Usage in native Termux:"
echo "  avf-vm status   # check VM state"
echo "  avf-vm start    # start Debian 13 (works with or without ADB)"
echo "  ssh avf         # passwordless Ed25519 SSH via local vsock"
echo "  avf-vm stop     # graceful poweroff"
