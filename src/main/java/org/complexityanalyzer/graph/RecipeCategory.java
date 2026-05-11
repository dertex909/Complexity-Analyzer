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

package org.complexityanalyzer.graph;

public enum RecipeCategory {
    PRIMARY(1000, "Primary"),
    JEI_IMPORTED(900, "JEI Plugin"),
    PROCESSING(50, "Processing"),
    STORAGE_COMPRESSION(100, "Compression"),
    RECYCLING(20, "Recycling"),
    STORAGE_DECOMPRESSION(10, "Decompression"),
    RECOLORING(5, "Recoloring"),
    UNPROCESSABLE(0, "Unprocessable");

    private final int priority;
    private final String displayName;

    RecipeCategory(int priority, String displayName) {
        this.priority = priority;
        this.displayName = displayName;
    }

    public int getPriority() {
        return priority;
    }

    public String getDisplayName() {
        return displayName;
    }
}