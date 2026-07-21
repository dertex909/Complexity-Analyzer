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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.export.cabin.io;

import com.github.luben.zstd.Zstd;
import it.unimi.dsi.fastutil.bytes.Byte2ObjectMap;
import it.unimi.dsi.fastutil.bytes.Byte2ObjectOpenHashMap;
import org.complexityanalyzer.export.cabin.api.CabinFormat;
import org.complexityanalyzer.export.cabin.api.LeBuf;
import org.complexityanalyzer.export.cabin.api.XxHash64;

import java.io.IOException;

public final class CabinReader {

    private final byte[] data;
    private final long fileHash;
    private final long tocOffset;
    private final Byte2ObjectMap<Section> sectionsById;

    public CabinReader(byte[] data) throws IOException {
        this.data = data;
        if (data.length < CabinFormat.HEADER_SIZE) throw new IOException("Cabin too small: " + data.length);
        int magic = LeBuf.readI32(data, 0);
        if (magic != CabinFormat.MAGIC) throw new IOException("Bad magic: 0x" + Integer.toHexString(magic));
        int version = LeBuf.readU16(data, 4);
        if (version != (CabinFormat.VERSION & 0xFFFF))
            throw new IOException("Unsupported version: 0x" + Integer.toHexString(version));
        this.tocOffset = LeBuf.readI64(data, 8);
        this.fileHash = LeBuf.readI64(data, 24);

        long zeroedExpected = computeHashWithZeroedHashSlot(data);
        this.sectionsById = parseTocAndValidateHash(data, zeroedExpected);
    }

    private static long computeHashWithZeroedHashSlot(byte[] data) {
        byte[] copy = data.clone();
        for (int i = 24; i < 32; i++) copy[i] = 0;
        return XxHash64.hash(copy, CabinFormat.XXH64_SEED);
    }

    private Byte2ObjectMap<Section> parseTocAndValidateHash(byte[] data, long zeroedExpected) throws IOException {
        if (zeroedExpected != fileHash) throw new IOException("File hash mismatch (stored=" + Long.toHexString(fileHash)
                + ", computed=" + Long.toHexString(zeroedExpected) + ")");

        if (tocOffset < CabinFormat.HEADER_SIZE || tocOffset > data.length - 2)
            throw new IOException("TOC offset out of range: " + tocOffset);
        int p = (int) tocOffset;
        int sectionCount = LeBuf.readU16(data, p);
        p += 2;
        if (p + sectionCount * 26L > data.length) throw new IOException("Truncated TOC");
        var map = new Byte2ObjectOpenHashMap<Section>(sectionCount);
        for (int i = 0; i < sectionCount; i++) {
            byte id = data[p++];
            byte codec = data[p++];
            long off = LeBuf.readI64(data, p);
            p += 8;
            long len = LeBuf.readI64(data, p);
            p += 8;
            long unc = LeBuf.readI64(data, p);
            p += 8;
            map.put(id, new Section(id, codec, off, len, unc));
        }
        return map;
    }

    public Section getSection(byte id) {
        var s = sectionsById.get(id);
        if (s == null) throw new IllegalArgumentException("Section not found: 0x" + Integer.toHexString(id & 0xFF));
        return s;
    }

    public byte[] readSection(byte id) throws IOException {
        var s = getSection(id);
        if (s.codec == CabinFormat.CODEC_RAW) {
            byte[] out = new byte[(int) s.length];
            System.arraycopy(data, (int) s.offset, out, 0, out.length);
            return out;
        }
        if (s.codec == CabinFormat.CODEC_ZSTD) {
            byte[] compressed = new byte[(int) s.length];
            System.arraycopy(data, (int) s.offset, compressed, 0, compressed.length);
            try {
                return Zstd.decompress(compressed, (int) s.uncompressed);
            } catch (Throwable t) {
                throw new IOException("Zstd decompression failed for section 0x" + Integer.toHexString(id & 0xFF), t);
            }
        }
        throw new IOException("Unknown codec " + s.codec);
    }

    public record Section(byte id, byte codec, long offset, long length, long uncompressed) {
    }
}