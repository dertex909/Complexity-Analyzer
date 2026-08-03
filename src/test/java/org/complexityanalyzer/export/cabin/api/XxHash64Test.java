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

package org.complexityanalyzer.export.cabin.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class XxHash64Test {

    private static final long SEED = CabinFormat.XXH64_SEED;

    @Test
    @DisplayName("Hash calculation is deterministic")
    void testDeterministicHash() {
        byte[] input = "ComplexityAnalyzerTest123".getBytes(StandardCharsets.UTF_8);

        long hash1 = XxHash64.hash(input, SEED);
        long hash2 = XxHash64.hash(input, SEED);

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("String hash matches raw UTF-8 byte hash")
    void testStringHash() {
        String testStr = "minecraft:diamond_sword";
        byte[] bytes = testStr.getBytes(StandardCharsets.UTF_8);

        long stringHash = XxHash64.hashString(testStr, SEED);
        long byteHash = XxHash64.hash(bytes, SEED);

        assertThat(stringHash).isEqualTo(byteHash);
    }

    @Test
    @DisplayName("Different inputs yield different hashes")
    void testDifferentInputs() {
        long hashA = XxHash64.hashString("minecraft:apple", SEED);
        long hashB = XxHash64.hashString("minecraft:golden_apple", SEED);

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    @DisplayName("Different seeds yield different hashes")
    void testDifferentSeeds() {
        byte[] input = "test_data".getBytes(StandardCharsets.UTF_8);

        long hash1 = XxHash64.hash(input, 0L);
        long hash2 = XxHash64.hash(input, SEED);

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("Respect array offset and length")
    void testOffsetAndLength() {
        byte[] fullArray = "PREFIX_TARGET_SUFFIX".getBytes(StandardCharsets.UTF_8);
        byte[] targetOnly = "TARGET".getBytes(StandardCharsets.UTF_8);

        long sliceHash = XxHash64.hash(fullArray, 7, 6, SEED);
        long directHash = XxHash64.hash(targetOnly, SEED);

        assertThat(sliceHash).isEqualTo(directHash);
    }
}