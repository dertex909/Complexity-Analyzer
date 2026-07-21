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

package org.complexityanalyzer.api;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only access to the parsed recipe graph and the raw (non-crafted) resource sources behind every item.
 * This is the harvested, normalized view of <em>all</em> recipes the analyzer discovered across every mod —
 * vanilla crafting/smelting plus modded machine recipes, fluids and chemicals.
 */
public interface RecipeData {

    /**
     * @return {@code true} if at least one recipe produces this item.
     */
    boolean hasRecipe(Item item);

    /**
     * @return every recipe that produces this item (empty if none).
     */
    List<RecipeNode> getRecipes(Item item);

    /**
     * @return the recipe the solver chose as cheapest for this item, if any.
     */
    Optional<RecipeNode> getBestRecipe(Item item);

    /**
     * @return how many distinct recipes consume this item as an ingredient.
     */
    int getUsageCount(Item item);

    /**
     * @return the set of items whose recipes use the given ingredient.
     */
    Set<Item> getItemsUsing(Item ingredient);

    /**
     * @return all raw acquisition sources (mining, loot, farming, mob drops, …) for the item, cheapest first.
     */
    List<BaseResourceData> getBaseSources(Item item);

    /**
     * @return the single cheapest raw acquisition source for the item, if any.
     */
    Optional<BaseResourceData> getBestBaseSource(Item item);

    /**
     * @return total number of recipes in the graph.
     */
    int totalRecipeCount();
}