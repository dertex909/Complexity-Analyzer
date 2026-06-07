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

package org.complexityanalyzer.export.cabin.io;

import it.unimi.dsi.fastutil.objects.ObjectList;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.export.cabin.api.CabinFormat;
import org.complexityanalyzer.export.cabin.api.CabinSection;
import org.complexityanalyzer.export.cabin.api.LeBuf;
import org.complexityanalyzer.export.cabin.api.XxHash64;

import java.util.stream.IntStream;
import java.util.zip.Deflater;

public final class CabinWriter {

    private static final int TOC_ENTRY_SIZE = 1 + 1 + 8 + 8 + 8;

    private CabinWriter() {
    }

    public static byte[] writeToBytes(ObjectList<CabinSection> sections) {
        long estimated = CabinFormat.HEADER_SIZE + 2L + (long) sections.size() * TOC_ENTRY_SIZE;
        for (var s : sections) estimated += s.uncompressedSize();
        if (estimated > Integer.MAX_VALUE - 1024)
            throw new IllegalStateException("Cabin payload too large: " + estimated);
        var out = new LeBuf((int) estimated);

        out.i32(CabinFormat.MAGIC);
        out.u16(CabinFormat.VERSION);
        out.u16(0);
        long tocOffsetSlot = out.position();
        out.i64(0);
        long flagsLong = 0L;
        out.i64(flagsLong);
        long hashSlot = out.position();
        out.i64(0);

        int sectionCount = sections.size();
        long[] offsets = new long[sectionCount];
        long[] sizes = new long[sectionCount];
        long[] uncompressed = new long[sectionCount];
        byte[] codecs = new byte[sectionCount];
        byte[][] toWrites = new byte[sectionCount][];

        ThreadPoolManager.getInstance().invokeParallel(() -> IntStream.range(0, sectionCount).parallel().forEach(i -> {
            var s = sections.get(i);
            byte[] data = s.payload();
            if (s.compress() && data.length >= 64) {
                byte[] compressed = deflateRaw(data);
                if (compressed.length < data.length) {
                    toWrites[i] = compressed;
                    codecs[i] = CabinFormat.CODEC_DEFLATE_RAW;
                } else {
                    toWrites[i] = data;
                    codecs[i] = CabinFormat.CODEC_RAW;
                }
            } else {
                toWrites[i] = data;
                codecs[i] = CabinFormat.CODEC_RAW;
            }
        }));

        for (int i = 0; i < sectionCount; i++) {
            byte[] toWrite = toWrites[i];
            offsets[i] = out.position();
            sizes[i] = toWrite.length;
            uncompressed[i] = sections.get(i).uncompressedSize();
            out.bytes(toWrite);
        }

        long tocOffset = out.position();
        out.u16(sectionCount);
        for (int i = 0; i < sectionCount; i++) {
            var s = sections.get(i);
            out.u8(s.id() & 0xFF);
            out.u8(codecs[i] & 0xFF);
            out.i64(offsets[i]);
            out.i64(sizes[i]);
            out.i64(uncompressed[i]);
        }

        out.putI64At((int) tocOffsetSlot, tocOffset);

        long fileHash = XxHash64.hash(out.array(), 0, out.size(), CabinFormat.XXH64_SEED);
        out.putI64At((int) hashSlot, fileHash);

        return out.toByteArray();
    }

    private static byte[] deflateRaw(byte[] data) {
        var def = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            def.setInput(data);
            def.finish();
            byte[] out = new byte[Math.max(64, data.length)];
            int total = 0;
            while (!def.finished()) {
                if (total == out.length) {
                    byte[] grown = new byte[out.length + (out.length >>> 1) + 16];
                    System.arraycopy(out, 0, grown, 0, total);
                    out = grown;
                }
                int n = def.deflate(out, total, out.length - total, Deflater.SYNC_FLUSH);
                total += n;
                if (n == 0 && !def.needsInput()) {
                    byte[] grown = new byte[out.length + (out.length >>> 1) + 16];
                    System.arraycopy(out, 0, grown, 0, total);
                    out = grown;
                }
            }
            byte[] result = new byte[total];
            System.arraycopy(out, 0, result, 0, total);
            return result;
        } finally {
            def.end();
        }
    }
}