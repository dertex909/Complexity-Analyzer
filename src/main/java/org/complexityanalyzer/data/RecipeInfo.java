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

import net.minecraft.world.item.crafting.RecipeType;

@SuppressWarnings("unused")
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