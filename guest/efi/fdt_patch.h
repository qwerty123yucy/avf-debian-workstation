#ifndef AVF_FDT_PATCH_H
#define AVF_FDT_PATCH_H

#include <stdint.h>

enum avf_fdt_result {
    AVF_FDT_PATCHED      = 0, /* Flipped >= 1 GICv3 SPI level->edge entries */
    AVF_FDT_SKIP_HAS_ITS = 1, /* Platform exposes GICv3 ITS or PCI msi-map */
    AVF_FDT_NO_CHANGE    = 2, /* No level-high GICv3 SPI entries needed change */
    AVF_FDT_INVALID      = 3, /* Malformed FDT or missing /intc or /pci */
};

/*
 * In-place patch of a live Flattened Device Tree (FDT v16/v17):
 * On GICv3 platforms without ITS/MSI-X (e.g., MediaTek GenieZone), switches
 * /pci/interrupt-map SPI trigger flags from IRQ_TYPE_LEVEL_HIGH (4) to
 * IRQ_TYPE_EDGE_RISING (1) without resizing or relocating the FDT.
 */
int avf_fdt_patch_pci_edge(void *fdt, uint32_t *patched_count_out);

#endif /* AVF_FDT_PATCH_H */
