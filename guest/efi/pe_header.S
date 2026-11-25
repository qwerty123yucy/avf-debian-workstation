/*
 * Minimal AArch64 PE32+ header for freestanding UEFI application (avf-fdt-edge.efi).
 * Layout matches U-Boot efi_load_pe() and Debian grubaa64.efi:
 *   0x0000 .. 0x0fff : DOS + PE32+ + section headers (SizeOfHeaders = 0x1000)
 *   0x1000 ..        : .text (entry point efi_main at 0x1000, pure PC-relative)
 *   _reloc_start     : .reloc (minimal valid no-op base relocation block)
 */

    .section .pe_hdr, "a"
    .globl _pe_header
_pe_header:
    /* DOS header */
    .ascii  "MZ"
    .skip   0x3a
    .long   pe_hdr - _pe_header         /* e_lfanew = 0x80 */
    .skip   0x40                        /* pad to 0x80 */

pe_hdr:
    .ascii  "PE\0\0"
    .short  0xaa64                      /* IMAGE_FILE_MACHINE_ARM64 */
    .short  2                           /* NumberOfSections (.text, .reloc) */
    .long   0                           /* TimeDateStamp */
    .long   0                           /* PointerToSymbolTable */
    .long   0                           /* NumberOfSymbols */
    .short  section_table - optional_hdr /* SizeOfOptionalHeader = 0xf0 */
    .short  0x0206                      /* EXECUTABLE | LINE_NUMS_STRIPPED | DEBUG_STRIPPED */

optional_hdr:
    .short  0x020b                      /* PE32+ magic */
    .byte   0x02                        /* MajorLinkerVersion */
    .byte   0x14                        /* MinorLinkerVersion */
    .long   _text_size                  /* SizeOfCode */
    .long   _reloc_raw_size             /* SizeOfInitializedData */
    .long   0                           /* SizeOfUninitializedData */
    .long   efi_main - _pe_header       /* AddressOfEntryPoint */
    .long   _text_start - _pe_header    /* BaseOfCode */

    /* PE32+ Windows-specific fields */
    .quad   0                           /* ImageBase */
    .long   0x1000                      /* SectionAlignment */
    .long   0x1000                      /* FileAlignment */
    .short  0                           /* MajorOperatingSystemVersion */
    .short  0                           /* MinorOperatingSystemVersion */
    .short  0                           /* MajorImageVersion */
    .short  0                           /* MinorImageVersion */
    .short  0                           /* MajorSubsystemVersion */
    .short  0                           /* MinorSubsystemVersion */
    .long   0                           /* Win32VersionValue */
    .long   _image_size                 /* SizeOfImage */
    .long   _text_start - _pe_header    /* SizeOfHeaders (0x1000) */
    .long   0                           /* CheckSum */
    .short  10                          /* IMAGE_SUBSYSTEM_EFI_APPLICATION */
    .short  0                           /* DllCharacteristics */
    .quad   0                           /* SizeOfStackReserve */
    .quad   0                           /* SizeOfStackCommit */
    .quad   0                           /* SizeOfHeapReserve */
    .quad   0                           /* SizeOfHeapCommit */
    .long   0                           /* LoaderFlags */
    .long   16                          /* NumberOfRvaAndSizes */

    /* Data Directories (16 * 8 = 128 bytes) */
    .quad   0                           /* 0: Export Table */
    .quad   0                           /* 1: Import Table */
    .quad   0                           /* 2: Resource Table */
    .quad   0                           /* 3: Exception Table */
    .quad   0                           /* 4: Certificate Table */
    .long   _reloc_start - _pe_header   /* 5: Base Relocation Table RVA */
    .long   10                          /* 5: Base Relocation Table Size */
    .quad   0                           /* 6: Debug */
    .quad   0                           /* 7: Architecture */
    .quad   0                           /* 8: Global Ptr */
    .quad   0                           /* 9: TLS Table */
    .quad   0                           /* 10: Load Config Table */
    .quad   0                           /* 11: Bound Import */
    .quad   0                           /* 12: IAT */
    .quad   0                           /* 13: Delay Import Descriptor */
    .quad   0                           /* 14: CLR Runtime Header */
    .quad   0                           /* 15: Reserved */

section_table:
    /* Section 0: .text */
    .ascii  ".text\0\0\0"
    .long   _text_size                  /* VirtualSize */
    .long   _text_start - _pe_header    /* VirtualAddress */
    .long   _text_size                  /* SizeOfRawData */
    .long   _text_start - _pe_header    /* PointerToRawData */
    .long   0                           /* PointerToRelocations */
    .long   0                           /* PointerToLinenumbers */
    .short  0                           /* NumberOfRelocations */
    .short  0                           /* NumberOfLinenumbers */
    .long   0x60000020                  /* CNT_CODE | MEM_EXECUTE | MEM_READ */

    /* Section 1: .reloc */
    .ascii  ".reloc\0\0"
    .long   10                          /* VirtualSize */
    .long   _reloc_start - _pe_header   /* VirtualAddress */
    .long   _reloc_raw_size             /* SizeOfRawData */
    .long   _reloc_start - _pe_header   /* PointerToRawData */
    .long   0                           /* PointerToRelocations */
    .long   0                           /* PointerToLinenumbers */
    .short  0                           /* NumberOfRelocations */
    .short  0                           /* NumberOfLinenumbers */
    .long   0x42000040                  /* CNT_INITIALIZED_DATA | MEM_DISCARDABLE | MEM_READ */

    /* Minimal valid no-op base relocation block (IMAGE_REL_BASED_ABSOLUTE) */
    .section .reloc_data, "a"
    .long   0                           /* PageRVA */
    .long   10                          /* BlockSize (8-byte header + 1 entry) */
    .short  0                           /* Type=0 (ABSOLUTE/no-op), Offset=0 */
