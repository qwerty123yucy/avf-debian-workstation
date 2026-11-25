package com.avf.linux;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.zip.GZIPInputStream;

/**
 * Stateless disk image import, reset, and sparse unpacking operations.
 *
 * Runs strictly outside state locks on the single VM maintenance executor,
 * committing changes atomically via temporary files.
 */
final class VmImageManager {
    private static final int SPARSE_MAGIC = 0xED26FF3A;

    interface LogSink {
        void log(String message);
    }

    static final class UriFormat {
        final boolean isGzip;
        final boolean isSparse;

        UriFormat(boolean isGzip, boolean isSparse) {
            this.isGzip = isGzip;
            this.isSparse = isSparse;
        }
    }

    private VmImageManager() {}

    static void ensureDiskLogicalSize(File diskFile, int diskGb) throws IOException {
        long wantBytes = ((long) diskGb) << 30;
        if (diskFile.length() < wantBytes) {
            try (RandomAccessFile raf = new RandomAccessFile(diskFile, "rw")) {
                raf.setLength(wantBytes);
            }
        }
    }

    static UriFormat probeUri(ContentResolver resolver, Uri uri) throws IOException {
        try (InputStream peek = resolver.openInputStream(uri)) {
            if (peek == null) {
                throw new IOException("Cannot open selected file");
            }
            byte[] magic = new byte[4];
            readFully(peek, magic, 4);
            boolean isGzip = ((magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b);
            boolean isSparse = (readLe32(magic, 0) == SPARSE_MAGIC);
            if (!isGzip && !isSparse) {
                throw new IOException("Unsupported file format (expected .simg.gz or .simg)");
            }
            return new UriFormat(isGzip, isSparse);
        }
    }

    static String importFromStaging(VmConfigStore store, int diskGb, LogSink sink) throws IOException {
        File staging = store.getStagingDir();
        if (staging == null || !staging.isDirectory()) {
            throw new IOException("Staging directory unavailable");
        }
        File dstDisk = store.getDiskFile();
        File dstSeed = store.getSeedFile();
        long targetBytes = ((long) diskGb) << 30;

        File srcSeedGz = new File(staging, "seed.simg.gz");
        File srcDiskSimg = new File(staging, "disk.simg");
        File srcDiskRaw = new File(staging, "disk.img");

        if (srcSeedGz.isFile()) {
            sink.log("[AVF Linux] Verifying and unpacking sparse GPT disk from seed.simg.gz...\n");
            unpackSeedToDisk(srcSeedGz, dstDisk, targetBytes, sink);
            sink.log("[AVF Linux] Saving verified golden seed image...\n");
            copyRegular(srcSeedGz, dstSeed);
            srcSeedGz.delete();
        } else if (srcDiskSimg.isFile()) {
            sink.log("[AVF Linux] Importing Android sparse GPT disk disk.simg...\n");
            try (InputStream in = new BufferedInputStream(new FileInputStream(srcDiskSimg), 1048576)) {
                copyAndroidSparseStream(in, dstDisk, targetBytes, false, sink);
            }
            srcDiskSimg.delete();
        } else if (srcDiskRaw.isFile()) {
            sink.log("[AVF Linux] Importing raw GPT disk disk.img sparsely...\n");
            try (InputStream in = new BufferedInputStream(new FileInputStream(srcDiskRaw), 1048576)) {
                copySparseStream(in, dstDisk, targetBytes);
            }
            srcDiskRaw.delete();
        } else {
            throw new IOException("No seed.simg.gz, disk.simg, or disk.img found in " + staging.getAbsolutePath());
        }

        store.cleanupLegacyFiles();
        return "Import complete. Disk actual=" + (store.getActualDiskBytes() / (1024L * 1024L))
            + " MiB, seed=" + (store.getSeedBytes() / (1024L * 1024L)) + " MiB";
    }

    static String importFromUri(
            ContentResolver resolver,
            Uri uri,
            UriFormat fmt,
            VmConfigStore store,
            int diskGb,
            LogSink sink) throws IOException {
        File dstDisk = store.getDiskFile();
        File dstSeed = store.getSeedFile();
        File seedTmp = new File(dstSeed.getAbsolutePath() + ".tmp");
        long targetBytes = ((long) diskGb) << 30;
        boolean seedSaved = false;
        try {
            if (fmt.isGzip) {
                if (seedTmp.exists()) seedTmp.delete();
                sink.log("[AVF Linux] Copying compressed seed image...\n");
                try (InputStream in = resolver.openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(seedTmp)) {
                    if (in == null) throw new IOException("Cannot open selected file");
                    byte[] buf = new byte[262144];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.flush();
                    out.getFD().sync();
                }
                sink.log("[AVF Linux] Verifying GZIP CRC and unpacking sparse GPT disk...\n");
                unpackSeedToDisk(seedTmp, dstDisk, targetBytes, sink);
                if (!seedTmp.renameTo(dstSeed)) {
                    throw new IOException("Failed to commit golden seed image");
                }
                seedSaved = true;
            } else {
                sink.log("[AVF Linux] Importing Android sparse GPT disk...\n");
                try (InputStream raw = resolver.openInputStream(uri);
                     InputStream in = new BufferedInputStream(raw, 1048576)) {
                    if (raw == null) throw new IOException("Cannot open selected file");
                    copyAndroidSparseStream(in, dstDisk, targetBytes, false, sink);
                }
            }
            store.cleanupLegacyFiles();
            return "Import complete. Disk actual=" + (store.getActualDiskBytes() / (1024L * 1024L))
                + " MiB, seed=" + (store.getSeedBytes() / (1024L * 1024L)) + " MiB";
        } finally {
            if (!seedSaved && seedTmp.exists()) {
                seedTmp.delete();
            }
        }
    }

    static String resetFromSeed(VmConfigStore store, int diskGb, LogSink sink) throws IOException {
        File seedFile = store.getSeedFile();
        if (!seedFile.isFile()) {
            throw new IOException("Golden seed (seed.simg.gz) not found; cannot reset");
        }
        long t0 = System.currentTimeMillis();
        unpackSeedToDisk(seedFile, store.getDiskFile(), ((long) diskGb) << 30, sink);
        store.cleanupLegacyFiles();
        long dtMs = System.currentTimeMillis() - t0;
        return String.format("Reset complete in %.1fs (actual=%d MiB)",
            dtMs / 1000.0, store.getActualDiskBytes() / (1024L * 1024L));
    }

    private static void unpackSeedToDisk(File seedGz, File dstDisk, long targetBytes, LogSink sink)
            throws IOException {
        try (InputStream fis = new BufferedInputStream(new FileInputStream(seedGz), 1048576);
             GZIPInputStream gzin = new GZIPInputStream(fis, 262144)) {
            copyAndroidSparseStream(gzin, dstDisk, targetBytes, true, sink);
        }
    }

    private static void copyAndroidSparseStream(
            InputStream in, File dst, long targetLogicalBytes, boolean drainEof, LogSink sink) throws IOException {
        File tmp = new File(dst.getAbsolutePath() + ".tmp");
        if (tmp.exists()) tmp.delete();
        boolean ok = false;
        try {
            try (RandomAccessFile out = new RandomAccessFile(tmp, "rw")) {
                byte[] hdr = new byte[28];
                readFully(in, hdr, 28);
                int magic = readLe32(hdr, 0);
                if (magic != SPARSE_MAGIC) {
                    throw new IOException("Invalid Android sparse magic: 0x" + Integer.toHexString(magic));
                }
                int fileHdrSz = readLe16(hdr, 8);
                int chunkHdrSz = readLe16(hdr, 10);
                long blkSz = readLe32(hdr, 12) & 0xFFFFFFFFL;
                long totalBlks = readLe32(hdr, 16) & 0xFFFFFFFFL;
                int totalChunks = readLe32(hdr, 20);
                if (fileHdrSz > 28) {
                    readFully(in, new byte[fileHdrSz - 28], fileHdrSz - 28);
                }

                byte[] chdr = new byte[chunkHdrSz];
                byte[] buf = new byte[1048576];
                long outPos = 0L;
                long writtenBytes = 0L;
                long lastLogMb = 0L;

                for (int c = 0; c < totalChunks; c++) {
                    readFully(in, chdr, chunkHdrSz);
                    int ctype = readLe16(chdr, 0);
                    long chunkBlks = readLe32(chdr, 4) & 0xFFFFFFFFL;
                    long totalSz = readLe32(chdr, 8) & 0xFFFFFFFFL;
                    long dataSz = totalSz - chunkHdrSz;
                    long outSpan = chunkBlks * blkSz;

                    if (ctype == 0xCAC1) {
                        out.seek(outPos);
                        long rem = dataSz;
                        while (rem > 0) {
                            int want = (int) Math.min(buf.length, rem);
                            readFully(in, buf, want);
                            out.write(buf, 0, want);
                            rem -= want;
                            writtenBytes += want;
                        }
                        outPos += outSpan;
                        long mb = writtenBytes / (1024L * 1024L);
                        if (mb - lastLogMb >= 512) {
                            lastLogMb = mb;
                            sink.log("[AVF Linux] Written " + mb + " MiB...\n");
                        }
                    } else if (ctype == 0xCAC2) {
                        byte[] fill = new byte[4];
                        readFully(in, fill, 4);
                        if (fill[0] != 0 || fill[1] != 0 || fill[2] != 0 || fill[3] != 0) {
                            for (int i = 0; i < buf.length; i += 4) {
                                buf[i] = fill[0]; buf[i + 1] = fill[1];
                                buf[i + 2] = fill[2]; buf[i + 3] = fill[3];
                            }
                            out.seek(outPos);
                            long rem = outSpan;
                            while (rem > 0) {
                                int w = (int) Math.min(buf.length, rem);
                                out.write(buf, 0, w);
                                rem -= w;
                            }
                        }
                        outPos += outSpan;
                    } else if (ctype == 0xCAC3 || ctype == 0xCAC4) {
                        if (dataSz > 0) readFully(in, new byte[(int) dataSz], (int) dataSz);
                        if (ctype == 0xCAC3) outPos += outSpan;
                    } else {
                        throw new IOException("Unknown sparse chunk type: 0x" + Integer.toHexString(ctype));
                    }
                }
                if (drainEof) {
                    while (in.read(buf) >= 0) {
                        // Drain to EOF so GZIPInputStream verifies its CRC32 and ISIZE trailer
                    }
                }
                long finalLen = Math.max(Math.max(outPos, totalBlks * blkSz), targetLogicalBytes);
                out.setLength(finalLen);
                out.getFD().sync();
            }
            if (!tmp.renameTo(dst)) throw new IOException("Failed to rename sparse disk.img");
            ok = true;
        } finally {
            if (!ok && tmp.exists()) {
                tmp.delete();
            }
        }
    }

    private static void copyRegular(File src, File dst) throws IOException {
        if (!src.isFile()) throw new IOException("Missing staging file: " + src.getAbsolutePath());
        File tmp = new File(dst.getAbsolutePath() + ".tmp");
        boolean ok = false;
        try {
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[262144];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
                out.getFD().sync();
            }
            if (!tmp.renameTo(dst)) throw new IOException("Failed to rename " + tmp);
            ok = true;
        } finally {
            if (!ok && tmp.exists()) {
                tmp.delete();
            }
        }
    }

    private static void copySparseStream(InputStream in, File dst, long targetLogicalBytes) throws IOException {
        File tmp = new File(dst.getAbsolutePath() + ".tmp");
        if (tmp.exists()) tmp.delete();
        boolean ok = false;
        try {
            try (RandomAccessFile out = new RandomAccessFile(tmp, "rw")) {
                byte[] buf = new byte[65536];
                long pos = 0;
                int filled = 0;
                while (true) {
                    int n = in.read(buf, filled, buf.length - filled);
                    if (n > 0) {
                        filled += n;
                        if (filled < buf.length) continue;
                    }
                    if (filled > 0) {
                        boolean allZero = true;
                        for (int i = 0; i < filled; i++) {
                            if (buf[i] != 0) { allZero = false; break; }
                        }
                        if (!allZero) {
                            out.seek(pos);
                            out.write(buf, 0, filled);
                        }
                        pos += filled;
                        filled = 0;
                    }
                    if (n < 0) break;
                }
                out.setLength(Math.max(pos, targetLogicalBytes));
                out.getFD().sync();
            }
            if (!tmp.renameTo(dst)) throw new IOException("Failed to rename sparse disk.img");
            ok = true;
        } finally {
            if (!ok && tmp.exists()) {
                tmp.delete();
            }
        }
    }

    private static int readLe16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static int readLe32(byte[] b, int off) {
        return (b[off] & 0xff)
            | ((b[off + 1] & 0xff) << 8)
            | ((b[off + 2] & 0xff) << 16)
            | ((b[off + 3] & 0xff) << 24);
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new IOException("Unexpected EOF in stream");
            off += n;
        }
    }
}
