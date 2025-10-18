package org.complexityanalyzer.data;

import net.minecraft.ChatFormatting;

import java.util.Arrays;
import java.util.List;

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
    ETERNAL(Double.POSITIVE_INFINITY, "Eternal", ChatFormatting.DARK_RED),
    UNCALCULABLE(-1, "Uncalculable", ChatFormatting.DARK_GRAY);

    private static final ComplexityCategory[] CALCULABLE_CATEGORIES;
    private static final double[] UPPER_BOUNDS;

    static {
        List<ComplexityCategory> calculable = Arrays.stream(values())
                .filter(c -> c != UNCALCULABLE && c != ABSOLUTE)
                .toList();

        CALCULABLE_CATEGORIES = calculable.toArray(new ComplexityCategory[0]);
        UPPER_BOUNDS = new double[calculable.size()];
        for (int i = 0; i < calculable.size(); i++) {
            UPPER_BOUNDS[i] = calculable.get(i).maxComplexity;
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
        if (complexity < 0) {
            return UNCALCULABLE;
        }
        if (complexity == 0) {
            return ABSOLUTE;
        }

        int categoryIndex = getCategoryIndex(complexity);

        if (categoryIndex >= CALCULABLE_CATEGORIES.length) {
            return ETERNAL;
        }

        return CALCULABLE_CATEGORIES[categoryIndex];
    }

    private static int getCategoryIndex(double complexity) {
        int searchIndex = Arrays.binarySearch(UPPER_BOUNDS, complexity);

        int categoryIndex;
        if (searchIndex >= 0) {
            categoryIndex = searchIndex;
        } else {
            categoryIndex = -(searchIndex + 1);
        }
        return categoryIndex;
    }

    public String getDisplayName() {
        return displayName;
    }

    public ChatFormatting getColor() {
        return color;
    }
}