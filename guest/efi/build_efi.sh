#!/usr/bin/env bash
# Build and verify guest/efi/avf-fdt-edge.efi (freestanding AArch64 PE32+ UEFI app).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="${1:-$REPO_ROOT/build}"
OUT_EFI="$OUT_DIR/avf-fdt-edge.efi"

CC="${CC:-gcc}"
LD="${LD:-ld}"
OBJCOPY="${OBJCOPY:-objcopy}"
READELF="${READELF:-readelf}"

mkdir -p "$OUT_DIR"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# 1. Run host unit tests on fdt_patch.c (plus live /sys/firmware/fdt copy when available)
"$CC" -std=c99 -O2 -Wall -Wextra -Werror \
    "$SCRIPT_DIR/fdt_patch.c" "$SCRIPT_DIR/test_fdt_patch.c" \
    -o "$TMP_DIR/test_fdt_patch"
if [[ -r /sys/firmware/fdt ]]; then
    "$TMP_DIR/test_fdt_patch" /sys/firmware/fdt
else
    "$TMP_DIR/test_fdt_patch"
fi

# 2. Compile freestanding PC-relative AArch64 objects (zero libc, zero globals)
CFLAGS=(
    -std=c99 -O2 -Wall -Wextra -Werror
    -ffreestanding -fPIE -fvisibility=hidden
    -fno-stack-protector -fno-asynchronous-unwind-tables -fno-unwind-tables
    -fno-ident -fno-common -mgeneral-regs-only -mno-outline-atomics
)
"$CC" "${CFLAGS[@]}" -c "$SCRIPT_DIR/pe_header.S" -o "$TMP_DIR/pe_header.o"
"$CC" "${CFLAGS[@]}" -c "$SCRIPT_DIR/fdt_patch.c" -o "$TMP_DIR/fdt_patch.o"
"$CC" "${CFLAGS[@]}" -c "$SCRIPT_DIR/efi_main.c"  -o "$TMP_DIR/efi_main.o"

# 3. Link with strict zero-relocation assertions in efi.lds
"$LD" -nostdlib -static --no-dynamic-linker -z noexecstack \
    -T "$SCRIPT_DIR/efi.lds" \
    "$TMP_DIR/pe_header.o" "$TMP_DIR/efi_main.o" "$TMP_DIR/fdt_patch.o" \
    -o "$TMP_DIR/avf-fdt-edge.elf"

# Ensure no unexpected relocations or undefined symbols survived
if "$READELF" -r "$TMP_DIR/avf-fdt-edge.elf" | grep -q 'R_AARCH64_'; then
    echo "Error: unexpected relocations in ELF" >&2
    "$READELF" -r "$TMP_DIR/avf-fdt-edge.elf" >&2
    exit 1
fi

# 4. Emit raw PE32+ image
"$OBJCOPY" -O binary "$TMP_DIR/avf-fdt-edge.elf" "$OUT_EFI"
chmod 644 "$OUT_EFI"

# 5. Validate PE32+ header invariants against U-Boot efi_load_pe() requirements
python3 - "$OUT_EFI" <<'PY'
import os, struct, sys

path = sys.argv[1]
data = open(path, "rb").read()
assert data[:2] == b"MZ", "bad DOS magic"
pe_off = struct.unpack_from("<I", data, 0x3C)[0]
assert pe_off == 0x80, f"unexpected pe_off={pe_off:#x}"
sig, mach, nsec, _, _, _, opt_sz, chars = struct.unpack_from("<4sHHIIIHH", data, pe_off)
assert sig == b"PE\x00\x00", f"bad PE sig {sig!r}"
assert mach == 0xAA64, f"bad machine {mach:#x}"
assert nsec == 2, f"unexpected nsec {nsec}"
assert opt_sz == 0xF0, f"bad opt_sz {opt_sz:#x}"
assert (chars & 0x0001) == 0, "RELOCS_STRIPPED must not be set"
assert (chars & 0x0002) != 0, "EXECUTABLE_IMAGE must be set"

opt = pe_off + 24
magic, = struct.unpack_from("<H", data, opt)
code_sz, init_sz, uninit_sz, ep, code_base = struct.unpack_from("<IIIII", data, opt + 4)
base, sec_align, file_align = struct.unpack_from("<QII", data, opt + 24)
img_sz, hdr_sz, _, subsys, _ = struct.unpack_from("<IIIHH", data, opt + 56)
ndir, = struct.unpack_from("<I", data, opt + 108)
reloc_rva, reloc_sz = struct.unpack_from("<II", data, opt + 112 + 5 * 8)

assert magic == 0x020B, f"not PE32+: {magic:#x}"
assert ep == 0x1000 and code_base == 0x1000, f"unexpected ep={ep:#x}"
assert sec_align == 0x1000 and file_align == 0x1000
assert hdr_sz == 0x1000 and subsys == 10 and ndir == 16
assert img_sz == len(data), f"SizeOfImage {img_sz:#x} != file size {len(data):#x}"
assert reloc_sz == 10 and reloc_rva + 10 <= len(data)
page_rva, blk_sz, entry = struct.unpack_from("<IIH", data, reloc_rva)
assert page_rva == 0 and blk_sz == 10 and entry == 0

sec_off = opt + opt_sz
for i, want_name in enumerate((b".text\x00\x00\x00", b".reloc\x00\x00")):
    name, vsz, vaddr, rsz, rptr, _, _, _, _, schars = struct.unpack_from("<8sIIIIIIHHI", data, sec_off + i * 40)
    assert name == want_name, f"sec[{i}] name={name!r}"
    assert vaddr == rptr and rptr % 0x1000 == 0 and rsz % 0x1000 == 0
    assert rptr + rsz <= len(data)

print(f"[ok] Built & verified {path} ({len(data)} bytes, ep={ep:#x}, .text={code_sz:#x}, .reloc@{reloc_rva:#x})")
PY
