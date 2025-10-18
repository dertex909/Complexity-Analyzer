package org.complexityanalyzer.data;

import net.minecraft.ChatFormatting;

public enum ComplexityCategory {
    TRIVIAL(0, 10, "Trivial", ChatFormatting.GRAY),
    EASY(10, 50, "Easy", ChatFormatting.GREEN),
    MEDIUM(50, 150, "Medium", ChatFormatting.YELLOW),
    HARD(150, 500, "Hard", ChatFormatting.GOLD),
    INSANE(500, Double.MAX_VALUE, "Insane", ChatFormatting.RED),
    UNCALCULABLE(-1, -1, "Uncalculable", ChatFormatting.DARK_RED);

    private final double minComplexity;
    private final double maxComplexity;
    private final String displayName;
    private final ChatFormatting color;

    ComplexityCategory(double min, double max, String displayName, ChatFormatting color) {
        this.minComplexity = min;
        this.maxComplexity = max;
        this.displayName = displayName;
        this.color = color;
    }

    public static ComplexityCategory fromComplexity(double complexity) {
        if (complexity < 0) {
            return UNCALCULABLE;
        }

        for (ComplexityCategory category : values()) {
            if (category == UNCALCULABLE) continue;

            if (complexity >= category.minComplexity && complexity < category.maxComplexity) {
                return category;
            }
        }

        return INSANE;
    }

    public double getMinComplexity() {
        return minComplexity;
    }

    public double getMaxComplexity() {
        return maxComplexity;
    }

    public String getDisplayName() {
        return displayName;
    }

    public ChatFormatting getColor() {
        return color;
    }
}