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
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.export.cabin.api.CabinFormat;
import org.complexityanalyzer.export.cabin.api.CabinSection;
import org.complexityanalyzer.export.cabin.api.LeBuf;
import org.complexityanalyzer.export.cabin.api.XxHash64;

import java.util.concurrent.RecursiveAction;

public final class CabinWriter {

    private static final int TOC_ENTRY_SIZE = 1 + 1 + 8 + 8 + 8;

    private CabinWriter() {
    }

    public static byte[] writeToBytes(ObjectList<CabinSection> sections) {
        int sectionCount = sections.size();
        long estimated = CabinFormat.HEADER_SIZE + 2L + (long) sectionCount * TOC_ENTRY_SIZE;

        for (var section : sections) estimated += section.uncompressedSize();

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

        long[] offsets = new long[sectionCount];
        long[] sizes = new long[sectionCount];
        long[] uncompressed = new long[sectionCount];
        byte[] codecs = new byte[sectionCount];
        byte[][] toWrites = new byte[sectionCount][];

        if (sectionCount == 1) {
            compressSection(0, sections, toWrites, codecs);
        } else if (sectionCount > 1) {
            ThreadPoolManager.getInstance().invokeParallel(() -> new CompressTask(sections, toWrites, codecs, 0, sectionCount).invoke());
        }

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

    private static void compressSection(int i, ObjectList<CabinSection> sections, byte[][] toWrites, byte[] codecs) {
        var s = sections.get(i);
        byte[] data = s.payload();
        if (s.compress() && data.length >= 64) {
            byte[] compressed = Zstd.compress(data, 10);
            if (compressed.length < data.length) {
                toWrites[i] = compressed;
                codecs[i] = CabinFormat.CODEC_ZSTD;
                return;
            }
        }
        toWrites[i] = data;
        codecs[i] = CabinFormat.CODEC_RAW;
    }

    private static final class CompressTask extends RecursiveAction {
        private final ObjectList<CabinSection> sections;
        private final byte[][] toWrites;
        private final byte[] codecs;
        private final int start;
        private final int end;

        CompressTask(ObjectList<CabinSection> sections, byte[][] toWrites, byte[] codecs, int start, int end) {
            this.sections = sections;
            this.toWrites = toWrites;
            this.codecs = codecs;
            this.start = start;
            this.end = end;
        }

        @Override
        protected void compute() {
            int length = end - start;
            if (length <= 2) {
                for (int i = start; i < end; i++) compressSection(i, sections, toWrites, codecs);
            } else {
                int mid = (start + end) >>> 1;
                invokeAll(new CompressTask(sections, toWrites, codecs, start, mid), new CompressTask(sections, toWrites, codecs, mid, end));
            }
        }
    }
}