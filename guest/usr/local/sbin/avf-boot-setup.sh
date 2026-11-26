#!/bin/sh
set -eu

# Grow the root partition only after the host has enlarged the virtual disk;
# the seed layout keeps a fixed 1 MiB tail for the backup GPT.
blk=/sys/block/vda
if [ -r "$blk/vda2/size" ]; then
    spare=$(( $(cat "$blk/size") - $(cat "$blk/vda2/start") - $(cat "$blk/vda2/size") ))
    if [ "$spare" -gt 4096 ] && growpart /dev/vda 2 >/dev/null 2>&1; then
        resize2fs /dev/vda2 >/dev/null 2>&1 || true
    fi
fi
if ! ls /etc/ssh/ssh_host_*_key >/dev/null 2>&1; then
    ssh-keygen -A >/dev/null 2>&1 || true
fi
if grep -qE 'GICv3.*Level.*virtio' /proc/interrupts 2>/dev/null; then
    msg="avf-boot-setup: virtio PCI IRQs are still GICv3 Level-triggered (check /boot/avf-fdt-edge.efi and GRUB 09_avf_dtb)"
    echo "<4>$msg" > /dev/kmsg 2>/dev/null || echo "$msg" >&2
fi
