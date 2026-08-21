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

package org.complexityanalyzer.data;

import net.minecraft.ChatFormatting;

import static java.util.Locale.ROOT;

/**
 * Difficulty tier an item's complexity score falls into. Tiers grow by powers of ten — each constant's value
 * is the exclusive upper bound of its bucket — and carry a display name and chat colour for UI. Two special
 * tiers sit outside the scale: {@link #UNOBTAINABLE} (infinite score) and {@link #UNCALCULABLE} (score {@code -1}).
 *
 * <p>Use {@link #fromComplexity(double)} to bucket a raw score.
 */
public enum ComplexityCategory {
    ABSOLUTE(0, "Absolute", ChatFormatting.WHITE, "⚪"),
    TRIVIAL(10, "Trivial", ChatFormatting.GRAY, "⬜"),
    SIMPLE(100, "Simple", ChatFormatting.GREEN, "🟩"),
    MODERATE(1_000, "Moderate", ChatFormatting.YELLOW, "🟨"),
    COMPLEX(10_000, "Complex", ChatFormatting.GOLD, "🟧"),
    DIFFICULT(100_000, "Difficult", ChatFormatting.RED, "🟥"),
    EXPERT(1_000_000, "Expert", ChatFormatting.DARK_RED, "🟪"),
    MASTER(10_000_000, "Master", ChatFormatting.DARK_GREEN, "⭐"),
    MYTHICAL(100_000_000, "Mythical", ChatFormatting.AQUA, "💎"),
    TRANSCENDENT(1_000_000_000L, "Transcendent", ChatFormatting.DARK_AQUA, "👑"),
    CELESTIAL(10_000_000_000L, "Celestial", ChatFormatting.BLUE, "✨"),
    ASTRAL(100_000_000_000L, "Astral", ChatFormatting.DARK_BLUE, "🌌"),
    ETERNAL(1_000_000_000_000L, "Eternal", ChatFormatting.LIGHT_PURPLE, "⏳"),
    PRIMORDIAL(10_000_000_000_000L, "Primordial", ChatFormatting.DARK_PURPLE, "🪐"),
    SINGULARITY(100_000_000_000_000L, "Singularity", ChatFormatting.DARK_AQUA, "🌀"),
    INCONCEIVABLE(1_000_000_000_000_000L, "Inconceivable", ChatFormatting.LIGHT_PURPLE, "🔮"),
    BOUNDLESS(10_000_000_000_000_000L, "Boundless", ChatFormatting.AQUA, "♾️"),
    UNOBTAINABLE(Double.POSITIVE_INFINITY, "Unobtainable", ChatFormatting.BLACK, "🚫"),
    UNCALCULABLE(-1, "Uncalculable", ChatFormatting.DARK_GRAY, "❓");

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
    private final String icon;

    ComplexityCategory(double max, String displayName, ChatFormatting color, String icon) {
        this.maxComplexity = max;
        this.displayName = displayName;
        this.color = color;
        this.icon = icon;
    }

    /**
     * Maps a raw complexity score onto its tier.
     *
     * @param complexity the score ({@code -1} = uncalculable, {@code 0} = absolute, {@code Infinity} = unobtainable)
     * @return the matching category
     */
    public static ComplexityCategory fromComplexity(double complexity) {
        if (complexity < 0 || Double.isNaN(complexity)) return UNCALCULABLE;
        if (complexity == 0) return ABSOLUTE;
        if (Double.isInfinite(complexity)) return UNOBTAINABLE;
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

    /**
     * @return the exclusive upper complexity bound for this difficulty tier
     * ({@code 0} for {@link #ABSOLUTE}, {@code -1} for {@link #UNCALCULABLE},
     * and {@code Infinity} for {@link #UNOBTAINABLE}).
     */
    public double getMaxComplexity() {
        return maxComplexity;
    }

    /**
     * @return the English display name of the tier.
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * @return the i18n key for the tier's localized name.
     */
    public String getTranslationKey() {
        return "complexityanalyzer.category." + name().toLowerCase(ROOT);
    }

    /**
     * @return the chat colour associated with the tier.
     */
    public ChatFormatting getColor() {
        return color;
    }

    /**
     * @return the icon emoji associated with the tier.
     */
    public String getIcon() {
        return icon;
    }
}