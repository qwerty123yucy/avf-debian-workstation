#!/usr/bin/env bash
# Configure native Termux (or a local Linux environment) to manage and SSH into the AVF Debian 13 VM.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FALLBACK_KEY_SRC="$(cd "$SCRIPT_DIR/../.." && pwd)/.ssh/id_ed25519_avf_debian"
KEY_SRC="${AVF_KEY_SRC:-}"
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

# 1. Install or update avf-vm & avf-adb CLIs
install -m 755 "$SCRIPT_DIR/avf-vm" "$BIN_DIR/avf-vm"
echo "[ok] Installed CLI: $BIN_DIR/avf-vm"
if [[ -f "$SCRIPT_DIR/avf-adb" ]]; then
    install -m 755 "$SCRIPT_DIR/avf-adb" "$BIN_DIR/avf-adb"
    echo "[ok] Installed CLI: $BIN_DIR/avf-adb"
fi

# 2. Install, reuse, or generate Ed25519 SSH key
KEY_DST="$HOME/.ssh/id_ed25519_avf_debian"
if [[ -n "$KEY_SRC" ]]; then
    if [[ ! -f "$KEY_SRC" ]]; then
        echo "Error: specified SSH key not found: $KEY_SRC" >&2
        exit 1
    fi
    if [[ "$(realpath "$KEY_SRC" 2>/dev/null || echo "$KEY_SRC")" != "$(realpath "$KEY_DST" 2>/dev/null || echo "$KEY_DST")" ]]; then
        install -m 600 "$KEY_SRC" "$KEY_DST"
        [[ -f "${KEY_SRC}.pub" ]] && install -m 644 "${KEY_SRC}.pub" "${KEY_DST}.pub"
    fi
    chmod 600 "$KEY_DST"
    echo "[ok] Installed SSH key: $KEY_DST"
elif [[ -f "$KEY_DST" ]]; then
    chmod 600 "$KEY_DST"
    if [[ ! -f "${KEY_DST}.pub" ]] && command -v ssh-keygen >/dev/null 2>&1; then
        ssh-keygen -y -f "$KEY_DST" > "${KEY_DST}.pub"
        chmod 644 "${KEY_DST}.pub"
    fi
    echo "[ok] Keeping existing SSH key: $KEY_DST"
elif [[ -f "$FALLBACK_KEY_SRC" ]]; then
    install -m 600 "$FALLBACK_KEY_SRC" "$KEY_DST"
    [[ -f "${FALLBACK_KEY_SRC}.pub" ]] && install -m 644 "${FALLBACK_KEY_SRC}.pub" "${KEY_DST}.pub"
    echo "[ok] Copied SSH key: $KEY_DST"
elif command -v ssh-keygen >/dev/null 2>&1; then
    ssh-keygen -q -t ed25519 -N "" -C "termux-avf-debian" -f "$KEY_DST"
    chmod 600 "$KEY_DST"
    chmod 644 "${KEY_DST}.pub"
    echo "[ok] Generated new Ed25519 SSH key: $KEY_DST"
else
    echo "[warn] ssh-keygen not found; install openssh (pkg install openssh) and re-run." >&2
fi

# 3. Configure ~/.ssh/config idempotently and clear stale loopback host key
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
EOF
    echo "[ok] Added 'Host avf' to $SSH_CFG"
else
    sed -i '/^Host avf$/,/^Host /{ /^[[:space:]]*ServerAlive/d }' "$SSH_CFG" 2>/dev/null || true
    chmod 600 "$SSH_CFG"
    echo "[ok] 'Host avf' already present in $SSH_CFG"
fi

if [[ -f "$HOME/.ssh/known_hosts" ]] && command -v ssh-keygen >/dev/null 2>&1; then
    ssh-keygen -R "[127.0.0.1]:2222" >/dev/null 2>&1 || true
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
elif [[ -t 0 ]]; then
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
else
    echo "[info] Non-interactive shell; pass --token <ctl-token> or save token to $TOKEN_FILE (chmod 600)."
fi

# 5. Check client tools
for tool in ssh curl python3; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "[warn] '$tool' not found in PATH. In Termux, install with: pkg install openssh curl python"
    fi
done

# 6. Print Console authorization one-liner if SSH is not yet authorized
if [[ -f "${KEY_DST}.pub" ]]; then
    if ! ssh -i "$KEY_DST" -p 2222 \
        -o BatchMode=yes -o ConnectTimeout=2 -o StrictHostKeyChecking=accept-new \
        root@127.0.0.1 true >/dev/null 2>&1; then
        PUB_KEY="$(tr -d '\r\n' < "${KEY_DST}.pub")"
        echo ""
        echo "To authorize SSH access, run this once inside the AVF Linux Console tab:"
        echo "  mkdir -p /root/.ssh && chmod 700 /root/.ssh && printf '%s\\n' '$PUB_KEY' >> /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys"
    fi
fi

echo ""
echo "Done. Usage:"
echo "  avf-vm status              # check VM state, storage, overlay, and WakeLock"
echo "  avf-vm start               # start Debian 13 (works with or without ADB)"
echo "  avf-vm overlay [on|off]    # toggle floating VM status pill (default: on)"
echo "  avf-vm wakelock [on|off]   # toggle host CPU WakeLock (default: off)"
echo "  avf-vm reset               # restore initial Debian 13 image from seed"
echo "  ssh avf                    # passwordless Ed25519 SSH via local vsock"
echo "  avf-adb [on|status|off]    # optional decoupled host ADB tunnel (127.0.0.1:15555)"
echo "  avf-vm stop                # graceful poweroff"
