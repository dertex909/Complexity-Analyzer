/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
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

package org.complexityanalyzer.data;

import net.minecraft.ChatFormatting;

public enum ComplexityCategory {
    ABSOLUTE(0, "Absolute", ChatFormatting.WHITE),
    TRIVIAL(10, "Trivial", ChatFormatting.GRAY),
    SIMPLE(100, "Simple", ChatFormatting.GREEN),
    MODERATE(1_000, "Moderate", ChatFormatting.YELLOW),
    COMPLEX(10_000, "Complex", ChatFormatting.GOLD),
    DIFFICULT(100_000, "Difficult", ChatFormatting.RED),
    EXPERT(1_000_000, "Expert", ChatFormatting.DARK_PURPLE),
    MASTER(10_000_000, "Master", ChatFormatting.AQUA),
    MYTHICAL(100_000_000, "Mythical", ChatFormatting.LIGHT_PURPLE),
    TRANSCENDENT(1_000_000_000, "Transcendent", ChatFormatting.DARK_AQUA),
    UNOBTAINABLE(Double.POSITIVE_INFINITY, "Unobtainable", ChatFormatting.DARK_RED),
    UNCALCULABLE(-1, "Uncalculable", ChatFormatting.DARK_GRAY);

    private static final ComplexityCategory[] CALCULABLE_CATEGORIES;
    private static final double[] UPPER_BOUNDS;

    static {
        var allValues = values();
        int count = 0;
        for (var v : allValues) if (v != UNCALCULABLE && v != ABSOLUTE) count++;

        CALCULABLE_CATEGORIES = new ComplexityCategory[count];
        UPPER_BOUNDS = new double[count];

        int idx = 0;
        for (var v : allValues) {
            if (v != UNCALCULABLE && v != ABSOLUTE) {
                CALCULABLE_CATEGORIES[idx] = v;
                UPPER_BOUNDS[idx] = v.maxComplexity;
                idx++;
            }
        }
    }

    private final double maxComplexity;
    private final String displayName;
    private final ChatFormatting color;

    ComplexityCategory(double max, String displayName, ChatFormatting color) {
        this.maxComplexity = max;
        this.displayName = displayName;
        this.color = color;
    }

    public static ComplexityCategory fromComplexity(double complexity) {
        if (complexity < 0) return UNCALCULABLE;
        if (complexity == 0) return ABSOLUTE;
        int categoryIndex = getCategoryIndex(complexity);
        if (categoryIndex >= CALCULABLE_CATEGORIES.length) return UNOBTAINABLE;
        return CALCULABLE_CATEGORIES[categoryIndex];
    }

    private static int getCategoryIndex(double complexity) {
        int low = 0;
        int high = UPPER_BOUNDS.length - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            double midVal = UPPER_BOUNDS[mid];
            if (midVal < complexity) low = mid + 1;
            else if (midVal > complexity) high = mid - 1;
            else return mid;
        }
        return low;
    }

    public String getDisplayName() {
        return displayName;
    }

    public ChatFormatting getColor() {
        return color;
    }
}