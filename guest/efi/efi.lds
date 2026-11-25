OUTPUT_FORMAT("elf64-littleaarch64")
OUTPUT_ARCH(aarch64)
ENTRY(efi_main)

SECTIONS
{
    . = 0x0;

    .pe_hdr : {
        KEEP(*(.pe_hdr))
    }

    . = ALIGN(0x1000);
    _text_start = .;
    .text : {
        *(.text.efi_main)
        *(.text .text.*)
        *(.rodata .rodata.*)
        . = ALIGN(0x1000);
    }
    _text_end = .;
    _text_size = _text_end - _text_start;

    . = ALIGN(0x1000);
    _reloc_start = .;
    .reloc : {
        KEEP(*(.reloc_data))
        . = ALIGN(0x1000);
    }
    _reloc_end = .;
    _reloc_raw_size = _reloc_end - _reloc_start;

    _image_size = _reloc_end;

    /* Forbid any writable globals, GOT entries, or runtime relocations */
    .data : { *(.data .data.*) }
    .bss  : { *(.bss .bss.* COMMON) }
    .got  : { *(.got .got.plt) }
    .rela : { *(.rela.*) }

    /DISCARD/ : {
        *(.comment)
        *(.note.*)
        *(.eh_frame*)
        *(.ARM.*)
    }
}

ASSERT(SIZEOF(.data) == 0, "avf-fdt-edge.efi must not contain .data")
ASSERT(SIZEOF(.bss)  == 0, "avf-fdt-edge.efi must not contain .bss")
ASSERT(SIZEOF(.got)  == 0, "avf-fdt-edge.efi must not contain .got")
ASSERT(SIZEOF(.rela) == 0, "avf-fdt-edge.efi must not contain dynamic relocations")
ASSERT(_text_start == 0x1000, "PE header must fit in 4 KiB")
