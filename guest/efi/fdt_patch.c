#include "fdt_patch.h"

#include <stddef.h>

#define FDT_MAGIC         0xd00dfeedU
#define FDT_BEGIN_NODE    0x00000001U
#define FDT_END_NODE      0x00000002U
#define FDT_PROP          0x00000003U
#define FDT_NOP           0x00000004U
#define FDT_END           0x00000009U

#define FDT_HDR_SIZE      40U
#define FDT_MAX_TOTALSIZE (16U << 20)

#define GIC_SPI           0U
#define IRQ_EDGE_RISING   1U
#define IRQ_LEVEL_HIGH    4U

static uint32_t rd_be32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24)
         | ((uint32_t)p[1] << 16)
         | ((uint32_t)p[2] << 8)
         |  (uint32_t)p[3];
}

static void wr_be32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24);
    p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);
    p[3] = (uint8_t)(v);
}

static int str_eq_bounded(const uint8_t *s, const uint8_t *end, const char *lit) {
    while (*lit) {
        if (s >= end || *s != (uint8_t)*lit) {
            return 0;
        }
        s++;
        lit++;
    }
    return (s < end && *s == 0);
}

/* Check whether a NUL-separated Devicetree stringlist property contains `needle`. */
static int strlist_contains(const uint8_t *buf, uint32_t len, const char *needle) {
    uint32_t pos = 0;
    while (pos < len) {
        uint32_t start = pos;
        while (pos < len && buf[pos] != 0) {
            pos++;
        }
        if (pos < len && str_eq_bounded(buf + start, buf + len, needle)) {
            return 1;
        }
        pos++;
    }
    return 0;
}

struct node_scan {
    int is_gicv3;
    int is_pci;
    int has_addr_cells;
    int has_irq_cells;
    int has_phandle;
    int has_msi;
    uint32_t addr_cells;
    uint32_t irq_cells;
    uint32_t phandle;
    uint8_t *imap_ptr;
    uint32_t imap_len;
};

static void reset_node_scan(struct node_scan *n) {
    n->is_gicv3 = 0;
    n->is_pci = 0;
    n->has_addr_cells = 0;
    n->has_irq_cells = 0;
    n->has_phandle = 0;
    n->has_msi = 0;
    n->addr_cells = 0;
    n->irq_cells = 0;
    n->phandle = 0;
    n->imap_ptr = NULL;
    n->imap_len = 0;
}

int avf_fdt_patch_pci_edge(void *fdt, uint32_t *patched_count_out) {
    if (patched_count_out) {
        *patched_count_out = 0;
    }
    if (!fdt) {
        return AVF_FDT_INVALID;
    }

    uint8_t *base = (uint8_t *)fdt;
    if (rd_be32(base + 0) != FDT_MAGIC) {
        return AVF_FDT_INVALID;
    }

    uint32_t totalsize        = rd_be32(base + 4);
    uint32_t off_dt_struct    = rd_be32(base + 8);
    uint32_t off_dt_strings   = rd_be32(base + 12);
    uint32_t version          = rd_be32(base + 20);
    uint32_t last_comp_ver    = rd_be32(base + 24);
    uint32_t size_dt_strings  = rd_be32(base + 32);
    uint32_t size_dt_struct   = rd_be32(base + 36);

    if (totalsize < FDT_HDR_SIZE || totalsize > FDT_MAX_TOTALSIZE) {
        return AVF_FDT_INVALID;
    }
    if (version < 16U || last_comp_ver > 17U) {
        return AVF_FDT_INVALID;
    }
    if (off_dt_struct < FDT_HDR_SIZE || (off_dt_struct & 3U) != 0U) {
        return AVF_FDT_INVALID;
    }
    if (off_dt_struct > totalsize || size_dt_struct > totalsize - off_dt_struct) {
        return AVF_FDT_INVALID;
    }
    if (off_dt_strings < FDT_HDR_SIZE || off_dt_strings > totalsize
            || size_dt_strings > totalsize - off_dt_strings) {
        return AVF_FDT_INVALID;
    }

    uint8_t *p          = base + off_dt_struct;
    uint8_t *struct_end = p + size_dt_struct;
    const uint8_t *str_base = base + off_dt_strings;
    const uint8_t *str_end  = str_base + size_dt_strings;

    int depth = -1;
    int has_its = 0;
    int saw_end = 0;

    struct node_scan cur;
    reset_node_scan(&cur);

    int gic_found = 0;
    int gic_has_phandle = 0;
    uint32_t gic_phandle = 0;
    uint32_t gic_addr_cells = 0;
    uint32_t gic_irq_cells = 3;

    int pci_found = 0;
    uint32_t pci_addr_cells = 3;
    uint32_t pci_irq_cells = 1;
    uint8_t *pci_imap_ptr = NULL;
    uint32_t pci_imap_len = 0;

    while (p + 4 <= struct_end) {
        uint32_t tok = rd_be32(p);
        p += 4;

        if (tok == FDT_BEGIN_NODE) {
            /* Advance past NUL-terminated node name, padded to 4 bytes */
            while (p < struct_end && *p != 0) {
                p++;
            }
            if (p >= struct_end) {
                return AVF_FDT_INVALID;
            }
            p++; /* NUL */
            while (((uintptr_t)(p - base) & 3U) != 0U) {
                if (p >= struct_end) {
                    return AVF_FDT_INVALID;
                }
                p++;
            }
            depth++;
            if (depth == 1) {
                reset_node_scan(&cur);
            }
        } else if (tok == FDT_END_NODE) {
            if (depth < 0) {
                return AVF_FDT_INVALID;
            }
            if (depth == 1) {
                if (cur.is_gicv3) {
                    gic_found = 1;
                    gic_has_phandle = cur.has_phandle;
                    gic_phandle = cur.phandle;
                    /* Devicetree spec: missing #address-cells on an intc defaults to 0 */
                    gic_addr_cells = cur.has_addr_cells ? cur.addr_cells : 0U;
                    gic_irq_cells = cur.has_irq_cells ? cur.irq_cells : 3U;
                }
                if (cur.is_pci) {
                    if (cur.has_msi) {
                        has_its = 1;
                    }
                    if (cur.imap_ptr != NULL) {
                        pci_found = 1;
                        pci_addr_cells = cur.has_addr_cells ? cur.addr_cells : 3U;
                        pci_irq_cells = cur.has_irq_cells ? cur.irq_cells : 1U;
                        pci_imap_ptr = cur.imap_ptr;
                        pci_imap_len = cur.imap_len;
                    }
                }
            }
            depth--;
        } else if (tok == FDT_PROP) {
            if (p + 8 > struct_end) {
                return AVF_FDT_INVALID;
            }
            uint32_t len     = rd_be32(p + 0);
            uint32_t nameoff = rd_be32(p + 4);
            p += 8;
            if (len > (uint32_t)(struct_end - p)) {
                return AVF_FDT_INVALID;
            }
            if (nameoff >= size_dt_strings) {
                return AVF_FDT_INVALID;
            }
            uint8_t *val = p;
            const uint8_t *name = str_base + nameoff;
            uint32_t padded = (len + 3U) & ~3U;
            if (padded > (uint32_t)(struct_end - p)) {
                return AVF_FDT_INVALID;
            }
            p += padded;

            /* Any GICv3 ITS node anywhere in the tree indicates MSI-X support */
            if (str_eq_bounded(name, str_end, "compatible")) {
                if (strlist_contains(val, len, "arm,gic-v3-its")) {
                    has_its = 1;
                }
                if (depth == 1) {
                    if (strlist_contains(val, len, "arm,gic-v3")) {
                        cur.is_gicv3 = 1;
                    }
                    if (strlist_contains(val, len, "pci-host-cam-generic")
                            || strlist_contains(val, len, "pci-host-ecam-generic")) {
                        cur.is_pci = 1;
                    }
                }
            } else if (depth >= 2 && cur.is_gicv3
                    && str_eq_bounded(name, str_end, "msi-controller")) {
                has_its = 1;
            } else if (depth == 1) {
                if (str_eq_bounded(name, str_end, "device_type")
                        && strlist_contains(val, len, "pci")) {
                    cur.is_pci = 1;
                } else if (str_eq_bounded(name, str_end, "#address-cells") && len == 4U) {
                    cur.addr_cells = rd_be32(val);
                    cur.has_addr_cells = 1;
                } else if (str_eq_bounded(name, str_end, "#interrupt-cells") && len == 4U) {
                    cur.irq_cells = rd_be32(val);
                    cur.has_irq_cells = 1;
                } else if ((str_eq_bounded(name, str_end, "phandle")
                            || str_eq_bounded(name, str_end, "linux,phandle")) && len == 4U) {
                    cur.phandle = rd_be32(val);
                    cur.has_phandle = 1;
                } else if (str_eq_bounded(name, str_end, "msi-map")
                        || str_eq_bounded(name, str_end, "msi-parent")) {
                    cur.has_msi = 1;
                } else if (str_eq_bounded(name, str_end, "interrupt-map") && len > 0U) {
                    cur.imap_ptr = val;
                    cur.imap_len = len;
                }
            }
        } else if (tok == FDT_NOP) {
            continue;
        } else if (tok == FDT_END) {
            saw_end = 1;
            break;
        } else {
            return AVF_FDT_INVALID;
        }
    }

    if (!saw_end || depth != -1) {
        return AVF_FDT_INVALID;
    }
    if (has_its) {
        return AVF_FDT_SKIP_HAS_ITS;
    }
    if (!gic_found || !gic_has_phandle || !pci_found || !pci_imap_ptr) {
        return AVF_FDT_INVALID;
    }
    if (gic_irq_cells != 3U || pci_addr_cells > 4U || pci_irq_cells > 4U || gic_addr_cells > 4U) {
        return AVF_FDT_INVALID;
    }

    uint32_t parent_idx   = pci_addr_cells + pci_irq_cells;
    uint32_t gic_spec_idx = parent_idx + 1U + gic_addr_cells;
    uint32_t stride_cells = gic_spec_idx + gic_irq_cells;
    uint32_t stride_bytes = stride_cells * 4U;

    if (stride_bytes == 0U || (pci_imap_len % stride_bytes) != 0U) {
        return AVF_FDT_INVALID;
    }

    uint32_t patched = 0;
    for (uint32_t off = 0; off < pci_imap_len; off += stride_bytes) {
        uint8_t *entry = pci_imap_ptr + off;
        uint32_t ph        = rd_be32(entry + parent_idx * 4U);
        uint32_t irq_type  = rd_be32(entry + gic_spec_idx * 4U);
        uint32_t irq_flags = rd_be32(entry + (gic_spec_idx + 2U) * 4U);

        if (ph == gic_phandle && irq_type == GIC_SPI && irq_flags == IRQ_LEVEL_HIGH) {
            wr_be32(entry + (gic_spec_idx + 2U) * 4U, IRQ_EDGE_RISING);
            patched++;
        }
    }

    if (patched_count_out) {
        *patched_count_out = patched;
    }
    return (patched > 0U) ? AVF_FDT_PATCHED : AVF_FDT_NO_CHANGE;
}
