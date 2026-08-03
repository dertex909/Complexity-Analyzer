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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeBufTest {

    @Test
    @DisplayName("Write and read primitive integer types (Little-Endian)")
    void testWriteAndReadPrimitives() {
        LeBuf buf = new LeBuf(16);

        buf.u8(0xAB);
        buf.u16(0x1234);
        buf.i32(0x12345678);
        buf.i64(0x1122334455667788L);

        byte[] array = buf.toByteArray();

        assertThat(array).hasSize(15);
        assertThat(array[0] & 0xFF).isEqualTo(0xAB);
        assertThat(LeBuf.readU16(array, 1)).isEqualTo(0x1234);
        assertThat(LeBuf.readI32(array, 3)).isEqualTo(0x12345678);
        assertThat(LeBuf.readI64(array, 7)).isEqualTo(0x1122334455667788L);
    }

    @Test
    @DisplayName("Write and read double precision floating point numbers")
    void testDoubleEncoding() {
        LeBuf buf = new LeBuf(8);
        double expected = 12345.6789;

        buf.f64(expected);
        byte[] bytes = buf.toByteArray();

        long bits = LeBuf.readI64(bytes, 0);
        double actual = Double.longBitsToDouble(bits);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("Automatic buffer expansion")
    void testBufferExpansion() {
        LeBuf buf = new LeBuf(4);

        for (int i = 0; i < 100; i++) {
            buf.i32(i);
        }

        assertThat(buf.size()).isEqualTo(400);
        byte[] result = buf.toByteArray();

        for (int i = 0; i < 100; i++) {
            assertThat(LeBuf.readI32(result, i * 4)).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("Overwrite values at arbitrary offsets")
    void testPutAtOffset() {
        LeBuf buf = new LeBuf(16);
        buf.i32(0);
        buf.i32(100);
        buf.i64(0L);

        buf.putI32At(0, 999);
        buf.putI64At(8, 777888999111L);

        byte[] result = buf.toByteArray();

        assertThat(LeBuf.readI32(result, 0)).isEqualTo(999);
        assertThat(LeBuf.readI32(result, 4)).isEqualTo(100);
        assertThat(LeBuf.readI64(result, 8)).isEqualTo(777888999111L);
    }

    @Test
    @DisplayName("Throw exception when writing out of bounds")
    void testPutAtOutOfBounds() {
        LeBuf buf = new LeBuf(8);
        buf.i32(1);

        assertThatThrownBy(() -> buf.putI32At(20, 50))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }
}