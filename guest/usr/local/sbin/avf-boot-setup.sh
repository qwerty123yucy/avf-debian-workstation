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
