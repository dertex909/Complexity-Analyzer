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