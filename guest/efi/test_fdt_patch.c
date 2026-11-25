#include "fdt_patch.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Minimal FDT builder for deterministic unit tests */
struct fdt_buf {
    uint8_t  st[8192];
    uint32_t st_len;
    uint8_t  str[2048];
    uint32_t str_len;
};

static void fb_init(struct fdt_buf *b) {
    memset(b, 0, sizeof(*b));
}

static void fb_be32(struct fdt_buf *b, uint32_t v) {
    b->st[b->st_len++] = (uint8_t)(v >> 24);
    b->st[b->st_len++] = (uint8_t)(v >> 16);
    b->st[b->st_len++] = (uint8_t)(v >> 8);
    b->st[b->st_len++] = (uint8_t)(v);
}

static uint32_t fb_add_str(struct fdt_buf *b, const char *s) {
    uint32_t off = b->str_len;
    size_t n = strlen(s) + 1;
    memcpy(b->str + b->str_len, s, n);
    b->str_len += (uint32_t)n;
    return off;
}

static void fb_begin_node(struct fdt_buf *b, const char *name) {
    fb_be32(b, 1U);
    size_t n = strlen(name) + 1;
    memcpy(b->st + b->st_len, name, n);
    b->st_len += (uint32_t)n;
    while (b->st_len & 3U) {
        b->st[b->st_len++] = 0;
    }
}

static void fb_end_node(struct fdt_buf *b) {
    fb_be32(b, 2U);
}

static void fb_prop_raw(struct fdt_buf *b, const char *name, const void *data, uint32_t len) {
    uint32_t noff = fb_add_str(b, name);
    fb_be32(b, 3U);
    fb_be32(b, len);
    fb_be32(b, noff);
    if (len > 0) {
        memcpy(b->st + b->st_len, data, len);
        b->st_len += len;
    }
    while (b->st_len & 3U) {
        b->st[b->st_len++] = 0;
    }
}

static void fb_prop_u32(struct fdt_buf *b, const char *name, uint32_t v) {
    uint8_t be[4] = { (uint8_t)(v >> 24), (uint8_t)(v >> 16), (uint8_t)(v >> 8), (uint8_t)v };
    fb_prop_raw(b, name, be, 4);
}

static void fb_prop_cells(struct fdt_buf *b, const char *name, const uint32_t *cells, uint32_t count) {
    uint8_t buf[512];
    for (uint32_t i = 0; i < count; i++) {
        uint32_t v = cells[i];
        buf[i * 4 + 0] = (uint8_t)(v >> 24);
        buf[i * 4 + 1] = (uint8_t)(v >> 16);
        buf[i * 4 + 2] = (uint8_t)(v >> 8);
        buf[i * 4 + 3] = (uint8_t)(v);
    }
    fb_prop_raw(b, name, buf, count * 4U);
}

static void fb_prop_str(struct fdt_buf *b, const char *name, const char *s) {
    fb_prop_raw(b, name, s, (uint32_t)strlen(s) + 1U);
}

static uint32_t fb_finish(struct fdt_buf *b, uint8_t *out) {
    fb_be32(b, 9U); /* FDT_END */
    uint32_t off_rsv = 40U;
    uint32_t off_st  = 56U; /* 16-byte empty rsvmap */
    uint32_t off_str = off_st + b->st_len;
    uint32_t total   = off_str + b->str_len;

    memset(out, 0, total);
    uint32_t hdr[10] = {
        0xd00dfeedU, total, off_st, off_str, off_rsv,
        17U, 16U, 0U, b->str_len, b->st_len,
    };
    for (int i = 0; i < 10; i++) {
        uint32_t v = hdr[i];
        out[i * 4 + 0] = (uint8_t)(v >> 24);
        out[i * 4 + 1] = (uint8_t)(v >> 16);
        out[i * 4 + 2] = (uint8_t)(v >> 8);
        out[i * 4 + 3] = (uint8_t)(v);
    }
    memcpy(out + off_st, b->st, b->st_len);
    memcpy(out + off_str, b->str, b->str_len);
    return total;
}

static uint32_t rd_be32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16)
         | ((uint32_t)p[2] << 8)  |  (uint32_t)p[3];
}

static void wr_be32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24);
    p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);
    p[3] = (uint8_t)(v);
}

static int failures = 0;
#define CHECK(cond, msg) do { \
    if (!(cond)) { fprintf(stderr, "  FAIL: %s\n", msg); failures++; } \
    else { printf("  ok  : %s\n", msg); } \
} while (0)

static void test_geniezone_synthetic(void) {
    struct fdt_buf b;
    fb_init(&b);
    fb_begin_node(&b, "");
      /* Put /pci BEFORE /intc to verify order independence, plus a child node inside /pci */
      fb_begin_node(&b, "pci@2e000000");
        fb_prop_str(&b, "compatible", "pci-host-cam-generic");
        fb_prop_str(&b, "device_type", "pci");
        fb_prop_u32(&b, "#address-cells", 3);
        fb_prop_u32(&b, "#interrupt-cells", 1);
        uint32_t imap[] = {
            /* Entry 0: phandle=1, SPI 4, level-high (4) -> must become 1 */
            0x0800, 0, 0, 1,  1,  0, 0,  0, 4, 4,
            /* Entry 1: phandle=1, SPI 5, already edge (1) -> unchanged */
            0x1000, 0, 0, 1,  1,  0, 0,  0, 5, 1,
            /* Entry 2: phandle=9 (other intc), SPI 6, level (4) -> untouched */
            0x1800, 0, 0, 1,  9,  0, 0,  0, 6, 4,
            /* Entry 3: phandle=1, PPI (1), level (4) -> untouched */
            0x2000, 0, 0, 1,  1,  0, 0,  1, 7, 4,
            /* Entry 4: phandle=1, SPI 8, level-high (4) -> must become 1 */
            0x2800, 0, 0, 1,  1,  0, 0,  0, 8, 4,
        };
        fb_prop_cells(&b, "interrupt-map", imap, 50);
        fb_begin_node(&b, "child-bridge");
          fb_prop_u32(&b, "#address-cells", 1); /* must NOT clobber /pci's #address-cells */
        fb_end_node(&b);
      fb_end_node(&b);
      fb_begin_node(&b, "intc@3fff0000");
        fb_prop_str(&b, "compatible", "arm,gic-v3");
        fb_prop_u32(&b, "#address-cells", 2);
        fb_prop_u32(&b, "#interrupt-cells", 3);
        fb_prop_u32(&b, "phandle", 1);
        fb_begin_node(&b, "sub");
          fb_prop_u32(&b, "phandle", 99); /* must NOT clobber /intc's phandle */
        fb_end_node(&b);
      fb_end_node(&b);
    fb_end_node(&b);

    uint8_t blob[16384];
    fb_finish(&b, blob);

    uint32_t count = 0;
    int rc = avf_fdt_patch_pci_edge(blob, &count);
    CHECK(rc == AVF_FDT_PATCHED && count == 2, "order-independent GenieZone FDT patches exactly 2 matching SPI entries");

    /* Idempotency */
    int rc2 = avf_fdt_patch_pci_edge(blob, &count);
    CHECK(rc2 == AVF_FDT_NO_CHANGE && count == 0, "second pass is idempotent (NO_CHANGE)");
}

static void test_skip_when_its_or_msimap(void) {
    /* Case 1: /intc has arm,gic-v3-its subnode */
    struct fdt_buf b;
    fb_init(&b);
    fb_begin_node(&b, "");
      fb_begin_node(&b, "intc");
        fb_prop_str(&b, "compatible", "arm,gic-v3");
        fb_prop_u32(&b, "#address-cells", 2);
        fb_prop_u32(&b, "#interrupt-cells", 3);
        fb_prop_u32(&b, "phandle", 1);
        fb_begin_node(&b, "its@3ffd0000");
          fb_prop_str(&b, "compatible", "arm,gic-v3-its");
          fb_prop_raw(&b, "msi-controller", NULL, 0);
        fb_end_node(&b);
      fb_end_node(&b);
      fb_begin_node(&b, "pci");
        fb_prop_str(&b, "device_type", "pci");
        uint32_t imap[10] = { 0x0800, 0, 0, 1,  1,  0, 0,  0, 4, 4 };
        fb_prop_cells(&b, "interrupt-map", imap, 10);
      fb_end_node(&b);
    fb_end_node(&b);

    uint8_t blob[16384], orig[16384];
    uint32_t sz = fb_finish(&b, blob);
    memcpy(orig, blob, sz);

    uint32_t count = 99;
    int rc = avf_fdt_patch_pci_edge(blob, &count);
    CHECK(rc == AVF_FDT_SKIP_HAS_ITS && count == 0 && memcmp(blob, orig, sz) == 0,
          "skips completely (0 bytes modified) when /intc has arm,gic-v3-its");

    /* Case 2: /pci has msi-map without ITS node */
    fb_init(&b);
    fb_begin_node(&b, "");
      fb_begin_node(&b, "intc");
        fb_prop_str(&b, "compatible", "arm,gic-v3");
        fb_prop_u32(&b, "#address-cells", 2);
        fb_prop_u32(&b, "#interrupt-cells", 3);
        fb_prop_u32(&b, "phandle", 1);
      fb_end_node(&b);
      fb_begin_node(&b, "pci");
        fb_prop_str(&b, "device_type", "pci");
        uint32_t msimap[4] = { 0, 2, 0, 0x10000 };
        fb_prop_cells(&b, "msi-map", msimap, 4);
        fb_prop_cells(&b, "interrupt-map", imap, 10);
      fb_end_node(&b);
    fb_end_node(&b);

    sz = fb_finish(&b, blob);
    memcpy(orig, blob, sz);
    rc = avf_fdt_patch_pci_edge(blob, &count);
    CHECK(rc == AVF_FDT_SKIP_HAS_ITS && count == 0 && memcmp(blob, orig, sz) == 0,
          "skips completely when /pci has msi-map");
}

static void test_dynamic_8cell_stride(void) {
    /* /intc omits #address-cells (spec default = 0) -> stride = 3 + 1 + 1 + 0 + 3 = 8 cells */
    struct fdt_buf b;
    fb_init(&b);
    fb_begin_node(&b, "");
      fb_begin_node(&b, "intc");
        fb_prop_str(&b, "compatible", "arm,gic-v3");
        fb_prop_u32(&b, "#interrupt-cells", 3);
        fb_prop_u32(&b, "phandle", 7);
      fb_end_node(&b);
      fb_begin_node(&b, "pci");
        fb_prop_str(&b, "compatible", "pci-host-ecam-generic");
        uint32_t imap[16] = {
            0x0800, 0, 0, 1,  7,  0, 10, 4,
            0x1000, 0, 0, 1,  7,  0, 11, 4,
        };
        fb_prop_cells(&b, "interrupt-map", imap, 16);
      fb_end_node(&b);
    fb_end_node(&b);

    uint8_t blob[16384];
    fb_finish(&b, blob);
    uint32_t count = 0;
    int rc = avf_fdt_patch_pci_edge(blob, &count);
    CHECK(rc == AVF_FDT_PATCHED && count == 2, "dynamic 8-cell stride (missing intc #address-cells) works");
}

static void test_malformed_inputs(void) {
    uint8_t bad[64];
    memset(bad, 0, sizeof(bad));
    uint32_t count = 1;
    CHECK(avf_fdt_patch_pci_edge(NULL, &count) == AVF_FDT_INVALID, "NULL FDT rejected safely");
    CHECK(avf_fdt_patch_pci_edge(bad, &count) == AVF_FDT_INVALID, "zeroed buffer rejected safely");

    /* Valid magic but out-of-range struct offset */
    wr_be32(bad + 0, 0xd00dfeedU);
    wr_be32(bad + 4, 64U);
    wr_be32(bad + 8, 128U);
    wr_be32(bad + 20, 17U);
    wr_be32(bad + 24, 16U);
    CHECK(avf_fdt_patch_pci_edge(bad, &count) == AVF_FDT_INVALID, "out-of-bounds offset rejected safely");
}

static void test_live_fdt_copy(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) {
        fprintf(stderr, "  SKIP: cannot open %s\n", path);
        return;
    }
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t *blob = (uint8_t *)malloc((size_t)sz);
    uint8_t *orig = (uint8_t *)malloc((size_t)sz);
    if (!blob || !orig || fread(blob, 1, (size_t)sz, f) != (size_t)sz) {
        fclose(f);
        free(blob);
        free(orig);
        failures++;
        return;
    }
    fclose(f);
    memcpy(orig, blob, (size_t)sz);

    /* Revert any existing edge flags (1) back to GenieZone raw level flags (4) in the copy
       if booted with old edge DTB, by scanning for our known 10-cell crosvm SPI entries. */
    uint32_t off_st  = rd_be32(blob + 8);
    uint32_t off_str = rd_be32(blob + 12);
    uint32_t sz_st   = rd_be32(blob + 36);
    uint8_t *p = blob + off_st, *end = p + sz_st;
    int reverted = 0;
    while (p + 4 <= end) {
        uint32_t tok = rd_be32(p); p += 4;
        if (tok == 1U) { while (*p) p++; p++; while (((uintptr_t)(p - blob) & 3U)) p++; }
        else if (tok == 3U) {
            uint32_t len = rd_be32(p), noff = rd_be32(p + 4); p += 8;
            if (strcmp((const char *)(blob + off_str + noff), "interrupt-map") == 0 && (len % 40U) == 0) {
                for (uint32_t i = 0; i < len; i += 40U) {
                    if (rd_be32(p + i + 36) == 1U) {
                        wr_be32(p + i + 36, 4U);
                        reverted++;
                    }
                }
            }
            p += (len + 3U) & ~3U;
        } else if (tok == 9U) break;
    }

    uint32_t count = 0;
    int rc = avf_fdt_patch_pci_edge(blob, &count);
    CHECK(rc == AVF_FDT_PATCHED && count == (uint32_t)(reverted > 0 ? reverted : 7),
          "live /sys/firmware/fdt copy patches all PCI INTx entries cleanly");
    if (reverted > 0) {
        CHECK(memcmp(blob, orig, (size_t)sz) == 0,
              "patched copy matches live edge-triggered FDT byte-for-byte");
    }

    free(blob);
    free(orig);
}

int main(int argc, char **argv) {
    test_geniezone_synthetic();
    test_skip_when_its_or_msimap();
    test_dynamic_8cell_stride();
    test_malformed_inputs();
    if (argc > 1) {
        test_live_fdt_copy(argv[1]);
    }
    if (failures == 0) {
        printf("ALL TESTS PASSED\n");
        return 0;
    }
    fprintf(stderr, "FAILED (%d)\n", failures);
    return 1;
}
