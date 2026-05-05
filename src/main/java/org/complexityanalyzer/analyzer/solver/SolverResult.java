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

package org.complexityanalyzer.analyzer.solver;

import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.graph.RecipeNode;
import org.jetbrains.annotations.Nullable;

public record SolverResult(
        Reference2DoubleMap<Item> optimalComplexities,
        Reference2ObjectMap<Item, RecipeNode> optimalRecipes,
        int iterations,
        long executionTimeMs,
        boolean converged
) {
    @Nullable
    public Double getComplexity(Item item) {
        return optimalComplexities.containsKey(item) ? optimalComplexities.getDouble(item) : null;
    }

    @Nullable
    public RecipeNode getOptimalRecipe(Item item) {
        return optimalRecipes.get(item);
    }
}