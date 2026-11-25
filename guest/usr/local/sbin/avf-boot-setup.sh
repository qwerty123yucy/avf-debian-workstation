#!/bin/sh
set -eu
if growpart /dev/vda 2 >/dev/null 2>&1; then
    resize2fs /dev/vda2 >/dev/null 2>&1 || true
fi
if ! ls /etc/ssh/ssh_host_*_key >/dev/null 2>&1; then
    ssh-keygen -A >/dev/null 2>&1 || true
fi
if ! swapon --show | grep -q zram0; then
    modprobe zram 2>/dev/null || true
    if [ -b /dev/zram0 ]; then
        echo lz4 > /sys/block/zram0/comp_algorithm 2>/dev/null || true
        echo 2G > /sys/block/zram0/disksize 2>/dev/null || true
        mkswap /dev/zram0 >/dev/null 2>&1 || true
        swapon -p 100 /dev/zram0 2>/dev/null || true
    fi
fi

# On hypervisors without GICv3 ITS/MSI-X (such as MediaTek GenieZone, where
# gzvm_irqfd lacks level-IRQ resampling), generate an edge-triggered PCI DTB
# from the live unpatched firmware FDT so GRUB loads it on subsequent boots.
if [ -r /sys/firmware/fdt ] \
    && command -v fdtget >/dev/null 2>&1 \
    && command -v fdtput >/dev/null 2>&1 \
    && grep -qE 'GICv3.*Level.*virtio' /proc/interrupts 2>/dev/null; then
    python3 - << 'PY' || true
import os, shutil, subprocess

src = "/sys/firmware/fdt"
tmp = "/boot/avf-gzvm-edge.dtb.tmp"
dst = "/boot/avf-gzvm-edge.dtb"

shutil.copyfile(src, tmp)
for prop in (
    "kaslr-seed",
    "bootargs",
    "linux,uefi-secure-boot",
    "linux,uefi-mmap-desc-ver",
    "linux,uefi-mmap-desc-size",
    "linux,uefi-mmap-size",
    "linux,uefi-mmap-start",
    "linux,uefi-system-table",
):
    subprocess.run(["fdtput", "-d", tmp, "/chosen", prop], stderr=subprocess.DEVNULL)

cells = subprocess.check_output(["fdtget", "-t", "x", tmp, "/pci", "interrupt-map"], text=True).split()
if len(cells) >= 10 and len(cells) % 10 == 0:
    vals = [int(x, 16) for x in cells]
    for idx in range(9, len(vals), 10):
        if vals[idx] == 4:
            vals[idx] = 1
    subprocess.run(
        ["fdtput", "-t", "x", tmp, "/pci", "interrupt-map"] + [hex(v) for v in vals],
        check=True,
    )
    with open(tmp, "rb") as f:
        os.fsync(f.fileno())
    os.chmod(tmp, 0o644)
    os.replace(tmp, dst)
else:
    os.unlink(tmp)
PY
fi
