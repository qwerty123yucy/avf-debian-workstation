#include "fdt_patch.h"

#include <stddef.h>
#include <stdint.h>

#define EFI_SUCCESS          0ULL
#define EFI_SYSTAB_SIGNATURE 0x5453595320494249ULL /* "IBI SYST" */

struct efi_guid {
    uint32_t data1;
    uint16_t data2;
    uint16_t data3;
    uint8_t  data4[8];
};

struct efi_table_header {
    uint64_t signature;
    uint32_t revision;
    uint32_t header_size;
    uint32_t crc32;
    uint32_t reserved;
};

struct efi_configuration_table {
    struct efi_guid vendor_guid;
    void           *vendor_table;
};

struct efi_system_table {
    struct efi_table_header         hdr;
    uint16_t                       *firmware_vendor;
    uint32_t                        firmware_revision;
    uint32_t                        _pad;
    void                           *console_in_handle;
    void                           *con_in;
    void                           *console_out_handle;
    void                           *con_out;
    void                           *standard_error_handle;
    void                           *std_err;
    void                           *runtime_services;
    void                           *boot_services;
    uint64_t                        number_of_table_entries;
    struct efi_configuration_table *configuration_table;
};

/* EFI_DTB_TABLE_GUID: b1b621d5-f19c-41a5-830b-d9152c69aae0 */
static int is_fdt_guid(const struct efi_guid *g) {
    return g->data1 == 0xb1b621d5U
        && g->data2 == 0xf19cU
        && g->data3 == 0x41a5U
        && g->data4[0] == 0x83U
        && g->data4[1] == 0x0bU
        && g->data4[2] == 0xd9U
        && g->data4[3] == 0x15U
        && g->data4[4] == 0x2cU
        && g->data4[5] == 0x69U
        && g->data4[6] == 0xaaU
        && g->data4[7] == 0xe0U;
}

static uint32_t read_fdt_totalsize(const uint8_t *p) {
    return ((uint32_t)p[4] << 24)
         | ((uint32_t)p[5] << 16)
         | ((uint32_t)p[6] << 8)
         |  (uint32_t)p[7];
}

static void clean_dcache_range(void *addr, uint64_t len) {
    uint64_t ctr;
    __asm__ volatile("mrs %0, ctr_el0" : "=r"(ctr));
    uint64_t line = 4ULL << ((ctr >> 16) & 0xfU);
    if (line == 0ULL) {
        line = 64ULL;
    }
    uintptr_t start = (uintptr_t)addr & ~(uintptr_t)(line - 1ULL);
    uintptr_t end   = (uintptr_t)addr + (uintptr_t)len;
    for (uintptr_t cur = start; cur < end; cur += (uintptr_t)line) {
        __asm__ volatile("dc cvac, %0" :: "r"(cur) : "memory");
    }
    __asm__ volatile("dsb sy\nisb" ::: "memory");
}

/*
 * Entry point invoked by GRUB via `chainloader /boot/avf-fdt-edge.efi; boot`.
 * Always returns EFI_SUCCESS so GRUB cleanly resumes grub.cfg and boots Linux.
 */
__attribute__((section(".text.efi_main")))
uint64_t efi_main(void *image_handle, struct efi_system_table *st) {
    (void)image_handle;

    if (!st || st->hdr.signature != EFI_SYSTAB_SIGNATURE || !st->configuration_table) {
        return EFI_SUCCESS;
    }

    uint64_t n = st->number_of_table_entries;
    if (n > 4096ULL) {
        n = 4096ULL;
    }

    for (uint64_t i = 0; i < n; i++) {
        if (is_fdt_guid(&st->configuration_table[i].vendor_guid)) {
            void *fdt = st->configuration_table[i].vendor_table;
            uint32_t patched = 0;
            if (fdt && avf_fdt_patch_pci_edge(fdt, &patched) == AVF_FDT_PATCHED && patched > 0U) {
                uint32_t sz = read_fdt_totalsize((const uint8_t *)fdt);
                clean_dcache_range(fdt, sz);
            }
            break;
        }
    }

    return EFI_SUCCESS;
}
