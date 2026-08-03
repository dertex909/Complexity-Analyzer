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

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.complexityanalyzer.export.cabin.api.CabinFormat;
import org.complexityanalyzer.export.cabin.api.CabinSection;
import org.complexityanalyzer.export.cabin.api.LeBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CabinReadWriteRoundtripTest {

    @Test
    @DisplayName("Roundtrip write and read sections (Raw and ZSTD Compressed)")
    void testRoundtripWriteAndRead() throws IOException {
        byte[] metaData = "META_SECTION_DATA_CONTENT".getBytes(StandardCharsets.UTF_8);

        LeBuf itemsBuf = new LeBuf(1024);
        for (int i = 0; i < 200; i++) {
            itemsBuf.i32(i);
            itemsBuf.f64(i * 1.5);
        }
        byte[] itemsData = itemsBuf.toByteArray();

        ObjectList<CabinSection> sections = new ObjectArrayList<>();
        sections.add(CabinSection.raw(CabinFormat.SEC_META, metaData));
        sections.add(CabinSection.compressed(CabinFormat.SEC_ITEMS, itemsData));

        byte[] cabinFileBytes = CabinWriter.writeToBytes(sections);

        assertThat(cabinFileBytes).isNotEmpty();
        assertThat(cabinFileBytes.length).isGreaterThan(CabinFormat.HEADER_SIZE);

        CabinReader reader = new CabinReader(cabinFileBytes);

        CabinReader.Section metaHeader = reader.getSection(CabinFormat.SEC_META);
        assertThat(metaHeader.codec()).isEqualTo(CabinFormat.CODEC_RAW);
        byte[] readMeta = reader.readSection(CabinFormat.SEC_META);
        assertThat(readMeta).isEqualTo(metaData);

        CabinReader.Section itemsHeader = reader.getSection(CabinFormat.SEC_ITEMS);
        assertThat(itemsHeader.codec()).isEqualTo(CabinFormat.CODEC_ZSTD);
        byte[] readItems = reader.readSection(CabinFormat.SEC_ITEMS);
        assertThat(readItems).isEqualTo(itemsData);
    }

    @Test
    @DisplayName("Fail on corrupted magic header")
    void testCorruptedMagic() {
        byte[] metaData = "TEST".getBytes(StandardCharsets.UTF_8);
        ObjectList<CabinSection> sections = new ObjectArrayList<>();
        sections.add(CabinSection.raw(CabinFormat.SEC_META, metaData));

        byte[] cabinBytes = CabinWriter.writeToBytes(sections);

        cabinBytes[0] = 0x00;
        cabinBytes[1] = 0x00;

        assertThatThrownBy(() -> new CabinReader(cabinBytes))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Bad magic");
    }

    @Test
    @DisplayName("Fail on data corruption (XXH64 hash mismatch)")
    void testHashMismatch() {
        byte[] metaData = "IMPORTANT_DATA".getBytes(StandardCharsets.UTF_8);
        ObjectList<CabinSection> sections = new ObjectArrayList<>();
        sections.add(CabinSection.raw(CabinFormat.SEC_META, metaData));

        byte[] cabinBytes = CabinWriter.writeToBytes(sections);

        cabinBytes[15] = (byte) (cabinBytes[15] ^ 0xFF);

        assertThatThrownBy(() -> new CabinReader(cabinBytes))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("File hash mismatch");
    }
}