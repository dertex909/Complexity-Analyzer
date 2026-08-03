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

import static org.assertj.core.api.Assertions.assertThat;

class StringPoolTest {

    @Test
    @DisplayName("Empty and null strings intern to index 0")
    void testNullAndEmptyString() {
        StringPool pool = new StringPool(16);

        int nullIndex = pool.intern(null);
        int emptyIndex = pool.intern("");

        assertThat(nullIndex).isEqualTo(StringPool.EMPTY);
        assertThat(emptyIndex).isEqualTo(StringPool.EMPTY);
    }

    @Test
    @DisplayName("Deduplication: interning duplicate string returns same index")
    void testDeduplication() {
        StringPool pool = new StringPool(16);

        int idx1 = pool.intern("minecraft:iron_ingot");
        int idx2 = pool.intern("minecraft:gold_ingot");
        int idx1Repeat = pool.intern("minecraft:iron_ingot");

        assertThat(idx1).isNotEqualTo(idx2);
        assertThat(idx1Repeat).isEqualTo(idx1);
    }

    @Test
    @DisplayName("Sequential index assignment")
    void testSequentialIndices() {
        StringPool pool = new StringPool(16);

        int idx1 = pool.intern("first");
        int idx2 = pool.intern("second");
        int idx3 = pool.intern("third");

        assertThat(idx1).isEqualTo(1);
        assertThat(idx2).isEqualTo(2);
        assertThat(idx3).isEqualTo(3);
        assertThat(pool.size()).isEqualTo(4);
    }

    @Test
    @DisplayName("Serialize string pool to LeBuf")
    void testWriteToLeBuf() {
        StringPool pool = new StringPool(16);
        pool.intern("hello");
        pool.intern("world");

        LeBuf buf = new LeBuf(64);
        pool.writeTo(buf);

        byte[] bytes = buf.toByteArray();

        int count = LeBuf.readI32(bytes, 0);
        assertThat(count).isEqualTo(3);

        int len0 = LeBuf.readU16(bytes, 4);
        assertThat(len0).isEqualTo(0);

        int len1 = LeBuf.readU16(bytes, 6);
        assertThat(len1).isEqualTo(5);
    }

    @Test
    @DisplayName("Handle oversized strings (> 65535 bytes)")
    void testOversizedString() {
        StringPool pool = new StringPool(4);
        String hugeString = "a".repeat(70000);

        int idx = pool.intern(hugeString);

        assertThat(idx).isGreaterThan(0);
    }
}