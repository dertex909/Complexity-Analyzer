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

package org.complexityanalyzer.analyzer;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.analyzer.resource.SourceManager;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.graph.RecipeGraph;

public class SourcePathAnalyzer {

    private final RecipeGraph graph;
    private final SourceManager sourceManager;

    public SourcePathAnalyzer(RecipeGraph graph, SourceManager sourceManager) {
        this.graph = graph;
        this.sourceManager = sourceManager;
    }

    public void findItemsWithBasePath() {
        var itemsWithBasePath = new ReferenceOpenHashSet<Item>();

        for (var item : graph.getAllItems()) {
            if (!graph.hasRecipe(item)) {
                var data = sourceManager.analyze(item);
                var type = (data != null) ? data.getSourceType() : BaseResourceData.ResourceSourceType.UNKNOWN;
                if (type != BaseResourceData.ResourceSourceType.UNKNOWN) itemsWithBasePath.add(item);
            }
        }

        int lastSize;
        do {
            lastSize = itemsWithBasePath.size();
            for (var recipe : graph.getAllRecipes()) {
                var result = recipe.getResultItem();
                if (itemsWithBasePath.contains(result)) continue;

                boolean allIngredientsHaveBasePath = true;
                for (var slot : recipe.getIngredients()) {
                    boolean hasVariantWithBasePath = false;
                    for (var variant : slot.getVariants()) {
                        if (itemsWithBasePath.contains(variant)) {
                            hasVariantWithBasePath = true;
                            break;
                        }
                    }
                    if (!hasVariantWithBasePath) {
                        allIngredientsHaveBasePath = false;
                        break;
                    }
                }

                if (allIngredientsHaveBasePath) itemsWithBasePath.add(result);
            }
        } while (itemsWithBasePath.size() > lastSize);
    }
}