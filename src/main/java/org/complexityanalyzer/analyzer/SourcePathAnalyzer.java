/*
 * Complexity Analyzer
 * Copyright (C) 2025 dertex909
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

package org.complexityanalyzer.analyzer;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.SourceManager;
import org.complexityanalyzer.graph.IngredientSlot;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.HashSet;
import java.util.Set;

public class SourcePathAnalyzer {

    private final RecipeGraph graph;
    private final SourceManager sourceManager;

    public SourcePathAnalyzer(RecipeGraph graph, SourceManager sourceManager) {
        this.graph = graph;
        this.sourceManager = sourceManager;
    }

    public void findItemsWithBasePath() {
        Set<Item> itemsWithBasePath = new HashSet<>();

        for (Item item : graph.getAllItems()) {
            if (!graph.hasRecipe(item)) {
                BaseResourceData.ResourceSourceType type = sourceManager.analyze(item)
                        .map(BaseResourceData::getSourceType)
                        .orElse(BaseResourceData.ResourceSourceType.UNKNOWN);

                if (type != BaseResourceData.ResourceSourceType.UNKNOWN) {
                    itemsWithBasePath.add(item);
                }
            }
        }

        int lastSize;
        do {
            lastSize = itemsWithBasePath.size();
            for (RecipeNode recipe : graph.getAllRecipes()) {
                Item result = recipe.getResultItem();
                if (itemsWithBasePath.contains(result)) {
                    continue;
                }

                boolean allIngredientsHaveBasePath = true;
                for (IngredientSlot slot : recipe.getIngredients()) {
                    if (slot.getVariants().stream().noneMatch(itemsWithBasePath::contains)) {
                        allIngredientsHaveBasePath = false;
                        break;
                    }
                }

                if (allIngredientsHaveBasePath) {
                    itemsWithBasePath.add(result);
                }
            }
        } while (itemsWithBasePath.size() > lastSize);

    }
}