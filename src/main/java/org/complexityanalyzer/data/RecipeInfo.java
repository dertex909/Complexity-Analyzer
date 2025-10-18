package org.complexityanalyzer.data;

import net.minecraft.world.item.crafting.RecipeType;

public class RecipeInfo {
    private final RecipeType<?> type;
    private final String typeName;
    private final double multiplier;

    public RecipeInfo(RecipeType<?> type, String typeName, double multiplier) {
        this.type = type;
        this.typeName = typeName;
        this.multiplier = multiplier;
    }

    public RecipeType<?> getType() {
        return type;
    }

    public String getTypeName() {
        return typeName;
    }

    public double getMultiplier() {
        return multiplier;
    }

    public static double getMultiplierForType(RecipeType<?> recipeType) {
        if (recipeType == RecipeType.CRAFTING) {
            return 1.0;
        } else if (recipeType == RecipeType.SMELTING) {
            return 1.5;
        } else if (recipeType == RecipeType.BLASTING) {
            return 1.3;
        } else if (recipeType == RecipeType.SMOKING) {
            return 1.3;
        } else if (recipeType == RecipeType.STONECUTTING) {
            return 0.8;
        } else if (recipeType == RecipeType.SMITHING) {
            return 2.0;
        } else {
            return 1.0;
        }
    }

    public static String getTypeDisplayName(RecipeType<?> recipeType) {
        if (recipeType == RecipeType.CRAFTING) return "Crafting";
        if (recipeType == RecipeType.SMELTING) return "Smelting";
        if (recipeType == RecipeType.BLASTING) return "Blasting";
        if (recipeType == RecipeType.SMOKING) return "Smoking";
        if (recipeType == RecipeType.STONECUTTING) return "Stonecutting";
        if (recipeType == RecipeType.SMITHING) return "Smithing";
        return "Unknown";
    }
}