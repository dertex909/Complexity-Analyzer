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

public final class CabinFormat {

    public static final int MAGIC = 0x4E424143;
    public static final short VERSION = 0x0101;
    public static final int HEADER_SIZE = 32;

    public static final byte CODEC_RAW = 0;
    public static final byte CODEC_DEFLATE_RAW = 1;

    public static final byte SEC_META = 0x01;
    public static final byte SEC_STRINGS = 0x02;
    public static final byte SEC_ITEMS = 0x03;
    public static final byte SEC_BASE_DATA = 0x04;
    public static final byte SEC_SOURCES = 0x05;
    public static final byte SEC_RECIPES = 0x06;
    public static final byte SEC_USAGE = 0x07;
    public static final byte SEC_MOBS = 0x08;
    public static final byte SEC_DROPS = 0x09;
    public static final byte SEC_SCC = 0x0A;
    public static final byte SEC_CATEGORIES = 0x0B;
    public static final byte SEC_FLUIDS = 0x0C;
    public static final byte SEC_IDX_ITEM_HASH = 0x20;
    public static final byte SEC_IDX_RECIPES_BY_OUTPUT = 0x21;
    public static final byte SEC_IDX_MOB_HASH = 0x22;

    public static final int ITEM_RECORD_SIZE = 48;
    public static final int MOB_RECORD_SIZE = 80;

    public static final int ITEM_FLAG_HAS_RECIPE = 0x01;
    public static final int ITEM_FLAG_HAS_CYCLE = 0x02;
    public static final int ITEM_FLAG_IS_HARDCODED = 0x04;
    public static final int ITEM_FLAG_IS_VALID = 0x08;
    public static final int ITEM_FLAG_IS_INFINITE = 0x10;
    public static final int ITEM_FLAG_NO_RECIPE_RESULT = 0x20;

    public static final int MOB_FLAG_IS_BOSS = 0x01;
    public static final int MOB_FLAG_IS_MINIBOSS = 0x02;

    public static final int NULL_OFFSET = 0xFFFFFFFF;

    public static final long XXH64_SEED = 0xCAB1EDAEFACEC0DEL;

    private CabinFormat() {
    }
}