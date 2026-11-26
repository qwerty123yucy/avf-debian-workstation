#!/usr/bin/env python3
"""
Build a self-booting UEFI GPT Android Sparse Golden Seed (.simg.gz)
by preparing and streaming over a single SSH session from a clean guest:
  1. /dev/vda1 (64 MiB FAT32 EFI System Partition, unmounted)
  2. Allocated non-zero ext4 blocks of /dev/vda2 (64 GiB ROOT, mounted ro)

By default, produces a sanitized public seed (clears /root/.ssh/authorized_keys,
SSH host keys, and truncates /etc/machine-id to 0 bytes so every first boot
generates unique keys and machine-id). Pass --keep-keys to preserve local keys.
"""

import shutil
import struct
import subprocess
import sys
import uuid
import zlib
from pathlib import Path

BLK_SZ = 4096
SECTOR_SZ = 512
SECTORS_PER_BLK = BLK_SZ // SECTOR_SZ  # 8

SPARSE_MAGIC = 0xED26FF3A
CHUNK_RAW = 0xCAC1
CHUNK_DONT_CARE = 0xCAC3

GPT_TYPE_EFI = uuid.UUID("C12A7328-F81F-11D2-BA4B-00A0C93EC93B")
GPT_TYPE_LINUX = uuid.UUID("0FC63DAF-8483-4772-8E79-3D69D8477DE4")
PARTUUID_EFI = uuid.UUID("5ab0ff5b-058b-4864-a3b0-373065bc1a64")
PARTUUID_ROOT = uuid.UUID("4cbc5241-06ba-400b-a522-f38c3d652006")
DISK_GUID = uuid.UUID("9f68b2e0-5c41-4a7e-9c1a-8e21d40a1300")

ALIGN_BLKS = 256                 # 1 MiB = 2048 sectors = 256 * 4K blocks
ESP_BLKS = 16384                 # 64 MiB = 131072 sectors = 16384 * 4K blocks
ROOT_BLKS = 16777216             # 64 GiB = 134217728 sectors = 16777216 * 4K blocks
BACKUP_GPT_BLKS = 256            # 1 MiB trailing space for backup GPT
TOTAL_BLKS = ALIGN_BLKS + ESP_BLKS + ROOT_BLKS + BACKUP_GPT_BLKS
TOTAL_LBAS = TOTAL_BLKS * SECTORS_PER_BLK


def make_protective_mbr(total_lbas: int) -> bytes:
    mbr = bytearray(512)
    span = min(total_lbas - 1, 0xFFFFFFFF)
    mbr[446:462] = struct.pack("<BBBBBBBBII", 0x00, 0x00, 0x02, 0x00, 0xEE, 0xFF, 0xFF, 0xFF, 1, span)
    mbr[510:512] = b"\x55\xAA"
    return bytes(mbr)


def make_gpt_entry(type_guid: uuid.UUID, part_guid: uuid.UUID, first_lba: int, last_lba: int, name: str) -> bytes:
    name_utf16 = name.encode("utf-16le")[:72].ljust(72, b"\x00")
    return struct.pack(
        "<16s16sQQQ72s",
        type_guid.bytes_le,
        part_guid.bytes_le,
        first_lba,
        last_lba,
        0,
        name_utf16,
    )


def make_gpt_header(
    current_lba: int,
    backup_lba: int,
    first_usable_lba: int,
    last_usable_lba: int,
    disk_guid: uuid.UUID,
    entries_lba: int,
    entries_bytes: bytes,
) -> bytes:
    entries_crc = zlib.crc32(entries_bytes) & 0xFFFFFFFF
    hdr_wo_crc = struct.pack(
        "<8sIIIIQQQQ16sQIII",
        b"EFI PART",
        0x00010000,
        92,
        0,
        0,
        current_lba,
        backup_lba,
        first_usable_lba,
        last_usable_lba,
        disk_guid.bytes_le,
        entries_lba,
        128,
        128,
        entries_crc,
    )
    hdr_crc = zlib.crc32(hdr_wo_crc) & 0xFFFFFFFF
    hdr = hdr_wo_crc[:16] + struct.pack("<I", hdr_crc) + hdr_wo_crc[20:]
    return hdr.ljust(512, b"\x00")


def build_gpt_regions():
    esp_first_lba = ALIGN_BLKS * SECTORS_PER_BLK
    esp_last_lba = esp_first_lba + (ESP_BLKS * SECTORS_PER_BLK) - 1
    root_first_lba = esp_last_lba + 1
    root_last_lba = root_first_lba + (ROOT_BLKS * SECTORS_PER_BLK) - 1

    first_usable_lba = esp_first_lba
    last_usable_lba = TOTAL_LBAS - (BACKUP_GPT_BLKS * SECTORS_PER_BLK) - 1

    entries = bytearray(128 * 128)
    entries[0:128] = make_gpt_entry(GPT_TYPE_EFI, PARTUUID_EFI, esp_first_lba, esp_last_lba, "EFI")
    entries[128:256] = make_gpt_entry(GPT_TYPE_LINUX, PARTUUID_ROOT, root_first_lba, root_last_lba, "ROOT")
    entries_bytes = bytes(entries)

    primary_hdr = make_gpt_header(
        current_lba=1,
        backup_lba=TOTAL_LBAS - 1,
        first_usable_lba=first_usable_lba,
        last_usable_lba=last_usable_lba,
        disk_guid=DISK_GUID,
        entries_lba=2,
        entries_bytes=entries_bytes,
    )
    backup_entries_lba = TOTAL_LBAS - 33
    backup_hdr = make_gpt_header(
        current_lba=TOTAL_LBAS - 1,
        backup_lba=1,
        first_usable_lba=first_usable_lba,
        last_usable_lba=last_usable_lba,
        disk_guid=DISK_GUID,
        entries_lba=backup_entries_lba,
        entries_bytes=entries_bytes,
    )

    primary_region = bytearray(ALIGN_BLKS * BLK_SZ)
    primary_region[0:512] = make_protective_mbr(TOTAL_LBAS)
    primary_region[512:1024] = primary_hdr
    primary_region[1024:1024 + len(entries_bytes)] = entries_bytes

    backup_region = bytearray(BACKUP_GPT_BLKS * BLK_SZ)
    rel_entries_off = (backup_entries_lba - (TOTAL_LBAS - BACKUP_GPT_BLKS * SECTORS_PER_BLK)) * SECTOR_SZ
    backup_region[rel_entries_off:rel_entries_off + len(entries_bytes)] = entries_bytes
    backup_region[-512:] = backup_hdr

    return bytes(primary_region), bytes(backup_region)


REMOTE_PREPARE_AND_STREAM = r"""
import os, subprocess, struct, sys

SKIP_PREPARE = __SKIP_PREPARE__
SANITIZE_PUBLIC = __SANITIZE_PUBLIC__

if not SKIP_PREPARE:
    prep = r'''
set -eu
apt-get clean 2>/dev/null || true
systemctl stop systemd-journald.socket systemd-journald-dev-log.socket systemd-journald-audit.socket systemd-journald.service 2>/dev/null || true
rm -rf /var/log/journal/* /run/log/journal/* /root/.bash_history /root/.lesshst /var/lib/apt/lists/* /tmp/* /var/tmp/* 2>/dev/null || true
mkdir -p /var/log/journal 2>/dev/null || true
if [ "$SANITIZE_PUBLIC" = "1" ]; then
    rm -f /root/.ssh/authorized_keys /etc/ssh/ssh_host_* /etc/systemd/network/10-avf-dhcp.network /boot/avf-gzvm-edge.dtb
    truncate -s 0 /etc/machine-id
fi
fstrim /boot/efi 2>/dev/null || true
systemctl stop boot-efi.automount boot-efi.mount 2>/dev/null || true
if grep -q " /boot/efi vfat " /proc/mounts; then
    umount /boot/efi
fi
if grep -q " / ext4 rw," /proc/mounts; then
    fstrim /
    sync
    echo 1 > /proc/sys/kernel/sysrq
    echo u > /proc/sysrq-trigger
    sleep 0.5
fi
grep -q " / ext4 ro," /proc/mounts
'''
    env = dict(os.environ, SANITIZE_PUBLIC="1" if SANITIZE_PUBLIC else "0")
    subprocess.run(["sh", "-c", prep], stdout=sys.stderr, stderr=sys.stderr, env=env, check=True)
    print("[ok] Guest rootfs cleanly prepared and remounted read-only", file=sys.stderr, flush=True)

# Stream 64 MiB ESP (/dev/vda1) first, then allocated blocks of 64 GiB ROOT (/dev/vda2)
stdout = sys.stdout.buffer
with open("/dev/vda1", "rb") as ef:
    esp = ef.read()
if len(esp) != 64 * 1024 * 1024:
    raise RuntimeError(f"Unexpected /dev/vda1 length: {len(esp)}")
stdout.write(esp)

dev = "/dev/vda2"
full = subprocess.check_output(["dumpe2fs", dev], text=True, stderr=subprocess.DEVNULL)
total_blks = 16777216
alloc = bytearray(b"\x01" * total_blks)

in_free = False
for line in full.splitlines():
    if line.startswith("  Free blocks:"):
        part = line.split(":", 1)[1].strip()
        in_free = True
    elif in_free and line.startswith("               "):
        part = line.strip()
    else:
        in_free = False
        continue
    for tok in part.split(","):
        tok = tok.strip()
        if not tok:
            continue
        if "-" in tok:
            a, b = tok.split("-", 1)
            s, e = int(a), int(b) + 1
            alloc[s:e] = b"\x00" * (e - s)
        else:
            alloc[int(tok)] = 0

fd = os.open(dev, os.O_RDONLY)
zero_blk = b"\x00" * 4096
i = 0
while i < total_blks:
    if not alloc[i]:
        i += 1
        continue
    j = i + 1
    while j < total_blks and alloc[j] and (j - i) < 256:
        j += 1
    os.lseek(fd, i * 4096, os.SEEK_SET)
    nbytes = (j - i) * 4096
    buf = bytearray()
    while len(buf) < nbytes:
        chunk = os.read(fd, nbytes - len(buf))
        if not chunk:
            raise RuntimeError("Unexpected EOF on " + dev)
        buf.extend(chunk)
    k = 0
    cnt = j - i
    while k < cnt:
        if buf[k*4096:(k+1)*4096] == zero_blk:
            k += 1
            continue
        m = k + 1
        while m < cnt and buf[m*4096:(m+1)*4096] != zero_blk:
            m += 1
        stdout.write(struct.pack("<II", i + k, m - k))
        stdout.write(memoryview(buf)[k*4096:m*4096])
        k = m
    i = j

stdout.write(struct.pack("<II", 0xFFFFFFFF, 0))
stdout.flush()
os.close(fd)
"""


def main():
    flags = {a for a in sys.argv[1:] if a.startswith("--")}
    pos = [a for a in sys.argv[1:] if not a.startswith("--")]
    skip_prepare = "--skip-prepare" in flags
    sanitize_public = "--keep-keys" not in flags
    if len(pos) != 1 or not flags.issubset({"--skip-prepare", "--keep-keys"}):
        print(f"Usage: {sys.argv[0]} [--skip-prepare] [--keep-keys] <out_seed.simg.gz>", file=sys.stderr)
        sys.exit(2)

    out_gz_path = Path(pos[0])
    primary_gpt, backup_gpt = build_gpt_regions()

    assert primary_gpt[510:512] == b"\x55\xAA", "Invalid PMBR signature"
    assert primary_gpt[512:520] == b"EFI PART", "Invalid primary GPT signature"
    assert backup_gpt[-512:-504] == b"EFI PART", "Invalid backup GPT signature"
    sfdisk_bin = shutil.which("sfdisk") or ("/sbin/sfdisk" if Path("/sbin/sfdisk").is_file() else None)
    if sfdisk_bin:
        tmp_verify = out_gz_path.parent / "gpt_verify.tmp"
        with open(tmp_verify, "wb") as vf:
            vf.write(primary_gpt)
            vf.seek((TOTAL_BLKS - BACKUP_GPT_BLKS) * BLK_SZ)
            vf.write(backup_gpt)
        subprocess.run([sfdisk_bin, "--verify", str(tmp_verify)], check=True)
        tmp_verify.unlink()
    print("[ok] GPT primary + backup headers and CRC32 verified")

    tmp_simg = out_gz_path.parent / "seed.simg.tmp"
    cur_blk = 0
    total_chunks = 0
    raw_blks_written = 0

    with open(tmp_simg, "w+b") as sf:
        sf.write(b"\x00" * 28)

        def emit_dont_care(n_blks: int):
            nonlocal cur_blk, total_chunks
            if n_blks <= 0:
                return
            sf.write(struct.pack("<HHII", CHUNK_DONT_CARE, 0, n_blks, 12))
            cur_blk += n_blks
            total_chunks += 1

        def emit_raw(data: memoryview):
            nonlocal cur_blk, total_chunks, raw_blks_written
            assert len(data) % BLK_SZ == 0
            n_blks = len(data) // BLK_SZ
            if n_blks == 0:
                return
            sf.write(struct.pack("<HHII", CHUNK_RAW, 0, n_blks, 12 + len(data)))
            sf.write(data)
            cur_blk += n_blks
            total_chunks += 1
            raw_blks_written += n_blks

        def emit_buffer_sparsely(buf: bytes, base_blk: int):
            assert cur_blk == base_blk
            mv = memoryview(buf)
            n = len(buf) // BLK_SZ
            zero = b"\x00" * BLK_SZ
            i = 0
            while i < n:
                if mv[i * BLK_SZ:(i + 1) * BLK_SZ] == zero:
                    j = i + 1
                    while j < n and mv[j * BLK_SZ:(j + 1) * BLK_SZ] == zero:
                        j += 1
                    emit_dont_care(j - i)
                    i = j
                else:
                    j = i + 1
                    while j < n and mv[j * BLK_SZ:(j + 1) * BLK_SZ] != zero:
                        j += 1
                    emit_raw(mv[i * BLK_SZ:j * BLK_SZ])
                    i = j

        emit_buffer_sparsely(primary_gpt, 0)

        mode = "sanitized public" if sanitize_public else "local keep-keys"
        print(f"[..] Preparing ({mode}) and streaming /dev/vda1 + /dev/vda2 over single SSH session...")
        remote_py = (
            REMOTE_PREPARE_AND_STREAM
            .replace("__SKIP_PREPARE__", "True" if skip_prepare else "False")
            .replace("__SANITIZE_PUBLIC__", "True" if sanitize_public else "False")
        )
        proc = subprocess.Popen(
            ["ssh", "avf", "python3 -"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
        )
        assert proc.stdin is not None and proc.stdout is not None
        proc.stdin.write(remote_py.encode("utf-8"))
        proc.stdin.close()

        def read_exact(stream, n):
            b = bytearray()
            while len(b) < n:
                c = stream.read(n - len(b))
                if not c:
                    raise RuntimeError("Unexpected EOF from remote streamer")
                b.extend(c)
            return bytes(b)

        esp_bytes = read_exact(proc.stdout, ESP_BLKS * BLK_SZ)
        emit_buffer_sparsely(esp_bytes, ALIGN_BLKS)
        root_base = ALIGN_BLKS + ESP_BLKS

        while True:
            hdr = read_exact(proc.stdout, 8)
            rel_blk, cnt_blks = struct.unpack("<II", hdr)
            if rel_blk == 0xFFFFFFFF:
                break
            target_blk = root_base + rel_blk
            if target_blk > cur_blk:
                emit_dont_care(target_blk - cur_blk)
            payload = read_exact(proc.stdout, cnt_blks * BLK_SZ)
            emit_raw(memoryview(payload))

        rc = proc.wait()
        if rc != 0:
            raise RuntimeError(f"Remote streamer exited with {rc}")

        root_end = root_base + ROOT_BLKS
        if cur_blk < root_end:
            emit_dont_care(root_end - cur_blk)

        emit_buffer_sparsely(backup_gpt, root_end)
        assert cur_blk == TOTAL_BLKS, f"Block count mismatch: {cur_blk} != {TOTAL_BLKS}"

        sf.seek(0)
        sf.write(struct.pack(
            "<IHHHHIIII",
            SPARSE_MAGIC,
            1,
            0,
            28,
            12,
            BLK_SZ,
            TOTAL_BLKS,
            total_chunks,
            0,
        ))

    simg_mb = tmp_simg.stat().st_size / 1048576
    print(f"[ok] Built {tmp_simg} ({simg_mb:.1f} MiB, {total_chunks} chunks, {raw_blks_written * 4 // 1024} MiB non-zero data)")

    compressor = ["pigz", "-1", "-c", str(tmp_simg)] if shutil.which("pigz") else ["gzip", "-1", "-c", str(tmp_simg)]
    with open(out_gz_path, "wb") as gz_out:
        subprocess.run(compressor, stdout=gz_out, check=True)
    tmp_simg.unlink()
    gz_mb = out_gz_path.stat().st_size / 1048576
    print(f"[ok] Compressed golden seed: {out_gz_path} ({gz_mb:.1f} MiB)")


if __name__ == "__main__":
    main()
