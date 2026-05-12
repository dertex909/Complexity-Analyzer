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
            long v1 = seed + P1 + P2;
            long v2 = seed + P2;
            long v3 = seed;
            long v4 = seed - P1;
            do {
                v1 = round(v1, readLong(input, p));
                p += 8;
                v2 = round(v2, readLong(input, p));
                p += 8;
                v3 = round(v3, readLong(input, p));
                p += 8;
                v4 = round(v4, readLong(input, p));
                p += 8;
            } while (p <= limit);

            h64 = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7) + Long.rotateLeft(v3, 12)
                    + Long.rotateLeft(v4, 18);
            h64 = mergeRound(h64, v1);
            h64 = mergeRound(h64, v2);
            h64 = mergeRound(h64, v3);
            h64 = mergeRound(h64, v4);
        } else {
            h64 = seed + P5;
        }

        h64 += len;

        while (p + 8 <= end) {
            long k1 = round(0, readLong(input, p));
            h64 ^= k1;
            h64 = Long.rotateLeft(h64, 27) * P1 + P4;
            p += 8;
        }
        if (p + 4 <= end) {
            h64 ^= (readInt(input, p) & 0xFFFFFFFFL) * P1;
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

    private static long readLong(byte[] b, int o) {
        return ((long) (b[o] & 0xFF)) | ((long) (b[o + 1] & 0xFF) << 8)
                | ((long) (b[o + 2] & 0xFF) << 16) | ((long) (b[o + 3] & 0xFF) << 24) | ((long) (b[o + 4] & 0xFF) << 32)
                | ((long) (b[o + 5] & 0xFF) << 40) | ((long) (b[o + 6] & 0xFF) << 48) | ((long) (b[o + 7] & 0xFF) << 56);
    }

    private static int readInt(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }
}
