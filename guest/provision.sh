#!/usr/bin/env bash
# Apply AVF Debian 13 guest configuration onto a running guest over SSH (default: ssh avf).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TARGET_HOST="${1:-avf}"
VMNET_BIN="${2:-$REPO_ROOT/build/vmnet-guest}"

if [[ ! -f "$VMNET_BIN" ]]; then
    echo "Error: vmnet-guest binary not found at $VMNET_BIN" >&2
    exit 1
fi

tar -C "$SCRIPT_DIR" -cf - etc usr | ssh "$TARGET_HOST" 'tar -C / -xf -'
ssh "$TARGET_HOST" 'systemctl stop vmnet-guest.service 2>/dev/null || true'
ssh "$TARGET_HOST" 'cat > /usr/local/sbin/vmnet-guest && chmod 755 /usr/local/sbin/vmnet-guest /usr/local/sbin/avf-boot-setup.sh' < "$VMNET_BIN"
ssh "$TARGET_HOST" '
set -eu
rm -f /etc/systemd/network/10-avf-dhcp.network
systemctl daemon-reload
systemctl enable vmnet-guest.service sshd-vsock.socket avf-boot-setup.service fstrim.timer
systemctl start sshd-vsock.socket
systemctl restart vmnet-guest.service
update-grub
'
echo "[ok] Provisioned guest configuration onto $TARGET_HOST"
