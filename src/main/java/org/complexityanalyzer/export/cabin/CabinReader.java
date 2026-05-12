/*
 * Complexity Analyzer
 * Copyright (C) 2025-2026 dertex909
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.export.cabin;

import it.unimi.dsi.fastutil.bytes.Byte2ObjectMap;
import it.unimi.dsi.fastutil.bytes.Byte2ObjectOpenHashMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class CabinReader {

    public record Section(byte id, byte codec, long offset, long length, long uncompressed) {
    }

    private final byte[] data;
    private final long fileHash;
    private final long tocOffset;
    private final Byte2ObjectMap<Section> sectionsById;

    public CabinReader(byte[] data) throws IOException {
        this.data = data;
        if (data.length < CabinFormat.HEADER_SIZE) throw new IOException("Cabin too small: " + data.length);
        int magic = readI32(data, 0);
        if (magic != CabinFormat.MAGIC) throw new IOException("Bad magic: 0x" + Integer.toHexString(magic));
        int version = readU16(data, 4);
        if (version != (CabinFormat.VERSION & 0xFFFF)) throw new IOException("Unsupported version: 0x" + Integer.toHexString(version));
        this.tocOffset = readI64(data, 8);
        this.fileHash = readI64(data, 24);

        long zeroedExpected = computeHashWithZeroedHashSlot(data);
        this.sectionsById = parseTocAndValidateHash(data, zeroedExpected);
    }

    private Byte2ObjectMap<Section> parseTocAndValidateHash(byte[] data, long zeroedExpected) throws IOException {
        if (zeroedExpected != fileHash) throw new IOException("File hash mismatch (stored=" + Long.toHexString(fileHash)
                + ", computed=" + Long.toHexString(zeroedExpected) + ")");

        if (tocOffset < CabinFormat.HEADER_SIZE || tocOffset > data.length - 2)
            throw new IOException("TOC offset out of range: " + tocOffset);
        int p = (int) tocOffset;
        int sectionCount = readU16(data, p);
        p += 2;
        if (p + sectionCount * 26L > data.length) throw new IOException("Truncated TOC");
        Byte2ObjectMap<Section> map = new Byte2ObjectOpenHashMap<>(sectionCount);
        for (int i = 0; i < sectionCount; i++) {
            byte id = data[p];
            p++;
            byte codec = data[p];
            p++;
            long off = readI64(data, p);
            p += 8;
            long len = readI64(data, p);
            p += 8;
            long unc = readI64(data, p);
            p += 8;
            map.put(id, new Section(id, codec, off, len, unc));
        }
        return map;
    }

    private static long computeHashWithZeroedHashSlot(byte[] data) {
        byte[] copy = data.clone();
        for (int i = 24; i < 32; i++) copy[i] = 0;
        return XxHash64.hash(copy, 0, copy.length, CabinFormat.XXH64_SEED);
    }

    public long getFileHash() {
        return fileHash;
    }

    public long getTocOffset() {
        return tocOffset;
    }

    public boolean hasSection(byte id) {
        return sectionsById.containsKey(id);
    }

    public Section getSection(byte id) {
        Section s = sectionsById.get(id);
        if (s == null) throw new IllegalArgumentException("Section not found: 0x" + Integer.toHexString(id & 0xFF));
        return s;
    }

    public byte[] readSection(byte id) throws IOException {
        Section s = getSection(id);
        if (s.codec == CabinFormat.CODEC_RAW) {
            byte[] out = new byte[(int) s.length];
            System.arraycopy(data, (int) s.offset, out, 0, out.length);
            return out;
        }
        if (s.codec == CabinFormat.CODEC_DEFLATE_RAW) {
            return inflateRaw(data, (int) s.offset, (int) s.length, (int) s.uncompressed);
        }
        throw new IOException("Unknown codec " + s.codec);
    }

    private static byte[] inflateRaw(byte[] src, int off, int len, int uncompressed) throws IOException {
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(src, off, len);
            byte[] out = new byte[uncompressed];
            int total = 0;
            while (!inf.finished()) {
                int n = inf.inflate(out, total, out.length - total);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) throw new IOException("Truncated deflate stream");
                    break;
                }
                total += n;
                if (total > out.length) throw new IOException("Decompressed size exceeded declared " + uncompressed);
            }
            if (total != uncompressed)
                throw new IOException("Decompressed size mismatch: " + total + " vs " + uncompressed);

            return out;
        } catch (DataFormatException e) {
            throw new IOException(e);
        } finally {
            inf.end();
        }
    }

    public static String readPoolString(byte[] strings, int ref) {
        int n = readI32(strings, 0);
        if (ref < 0 || ref >= n) throw new IllegalArgumentException("Bad string ref: " + ref);
        int p = 4;
        for (int i = 0; i < ref; i++) {
            int len = readU16(strings, p);
            p += 2 + len;
        }
        int len = readU16(strings, p);
        return new String(strings, p + 2, len, StandardCharsets.UTF_8);
    }

    public static int readI32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    public static int readU16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    public static long readI64(byte[] b, int o) {
        return ((long) (b[o] & 0xFF)) | ((long) (b[o + 1] & 0xFF) << 8)
                | ((long) (b[o + 2] & 0xFF) << 16) | ((long) (b[o + 3] & 0xFF) << 24) | ((long) (b[o + 4] & 0xFF) << 32)
                | ((long) (b[o + 5] & 0xFF) << 40) | ((long) (b[o + 6] & 0xFF) << 48) | ((long) (b[o + 7] & 0xFF) << 56);
    }

    public static double readF64(byte[] b, int o) {
        return Double.longBitsToDouble(readI64(b, o));
    }
}
