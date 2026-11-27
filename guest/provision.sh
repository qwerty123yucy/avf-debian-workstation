#!/usr/bin/env bash
# Apply AVF Debian 13 guest configuration onto a running guest over SSH (default: ssh avf).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TARGET_HOST="${1:-avf}"
VMNET_BIN="${2:-$REPO_ROOT/build/vmnet-guest}"
EFI_BIN="${3:-$REPO_ROOT/build/avf-fdt-edge.efi}"

if [[ ! -f "$VMNET_BIN" ]]; then
    echo "Error: vmnet-guest binary not found at $VMNET_BIN" >&2
    exit 1
fi
if [[ ! -f "$EFI_BIN" ]]; then
    echo "Error: avf-fdt-edge.efi not found at $EFI_BIN (run ./guest/efi/build_efi.sh first)" >&2
    exit 1
fi

tar -C "$SCRIPT_DIR" -cf - etc usr | ssh "$TARGET_HOST" 'tar -C / -xf -'
ssh "$TARGET_HOST" 'systemctl stop vmnet-guest.service 2>/dev/null || true'
ssh "$TARGET_HOST" 'cat > /usr/local/sbin/vmnet-guest && chmod 755 /usr/local/sbin/vmnet-guest /usr/local/sbin/avf-boot-setup.sh /etc/grub.d/09_avf_dtb' < "$VMNET_BIN"
ssh "$TARGET_HOST" 'cat > /boot/avf-fdt-edge.efi && chmod 644 /boot/avf-fdt-edge.efi && rm -f /boot/avf-gzvm-edge.dtb' < "$EFI_BIN"
ssh "$TARGET_HOST" '
set -eu
rm -f /etc/systemd/network/10-avf-dhcp.network
purge=""
for pkg in cloud-init cloud-initramfs-growroot unattended-upgrades; do
    if dpkg -s "$pkg" >/dev/null 2>&1; then purge="$purge $pkg"; fi
done
if [ -n "$purge" ]; then
    systemctl stop unattended-upgrades.service 2>/dev/null || true
    DEBIAN_FRONTEND=noninteractive apt-get purge -y $purge
fi
rm -rf /etc/cloud /var/lib/cloud /var/log/unattended-upgrades
/usr/local/sbin/avf-boot-setup.sh
sysctl --system >/dev/null 2>&1 || true
systemctl daemon-reload
env XDG_RUNTIME_DIR=/run/user/0 systemctl --user daemon-reload 2>/dev/null || true
systemctl restart systemd-timesyncd.service systemd-udevd.service systemd-journald.service systemd-logind.service 2>/dev/null || true
systemctl disable --now apt-daily.timer apt-daily-upgrade.timer e2scrub_all.timer 2>/dev/null || true
systemctl enable vmnet-guest.service sshd-vsock.socket avf-boot-setup.service fstrim.timer
systemctl start sshd-vsock.socket
systemctl reload ssh.service 2>/dev/null || true
systemctl restart vmnet-guest.service
update-initramfs -u
update-grub
'
echo "[ok] Provisioned guest configuration onto $TARGET_HOST"
