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

package org.complexityanalyzer.export.cabin.api;

import java.nio.charset.StandardCharsets;

public final class XxHash64 {

    private static final long P1 = 0x9E3779B185EBCA87L;
    private static final long P2 = 0xC2B2AE3D27D4EB4FL;
    private static final long P3 = 0x165667B19E3779F9L;
    private static final long P4 = 0x85EBCA77C2B2AE63L;
    private static final long P5 = 0x27D4EB2F165667C5L;

    private XxHash64() {
    }

    public static long hash(byte[] input, long seed) {
        return hash(input, 0, input.length, seed);
    }

    public static long hashString(String s, long seed) {
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        return hash(data, 0, data.length, seed);
    }

    public static long hash(byte[] input, int off, int len, long seed) {
        int end = off + len;
        long h64;
        int p = off;

        if (len >= 32) {
            int limit = end - 32;
            long[] v = {seed + P1 + P2, seed + P2, seed, seed - P1};
            do {
                for (int i = 0; i < 4; i++) {
                    v[i] = round(v[i], LeBuf.readI64(input, p));
                    p += 8;
                }
            } while (p <= limit);

            int[] shifts = {1, 7, 12, 18};
            h64 = 0;
            for (int i = 0; i < 4; i++) h64 += Long.rotateLeft(v[i], shifts[i]);
            for (long val : v) h64 = mergeRound(h64, val);
        } else {
            h64 = seed + P5;
        }

        h64 += len;

        while (p + 8 <= end) {
            h64 ^= round(0, LeBuf.readI64(input, p));
            h64 = Long.rotateLeft(h64, 27) * P1 + P4;
            p += 8;
        }
        if (p + 4 <= end) {
            h64 ^= (LeBuf.readI32(input, p) & 0xFFFFFFFFL) * P1;
            h64 = Long.rotateLeft(h64, 23) * P2 + P3;
            p += 4;
        }
        while (p < end) {
            h64 ^= (input[p] & 0xFFL) * P5;
            h64 = Long.rotateLeft(h64, 11) * P1;
            p++;
        }

        h64 ^= h64 >>> 33;
        h64 *= P2;
        h64 ^= h64 >>> 29;
        h64 *= P3;
        h64 ^= h64 >>> 32;
        return h64;
    }

    private static long round(long acc, long input) {
        acc += input * P2;
        acc = Long.rotateLeft(acc, 31);
        acc *= P1;
        return acc;
    }

    private static long mergeRound(long acc, long val) {
        val = round(0, val);
        acc ^= val;
        return acc * P1 + P4;
    }
}