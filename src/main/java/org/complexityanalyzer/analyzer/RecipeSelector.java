package org.complexityanalyzer.analyzer;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;
import java.util.Comparator;
import java.util.List;

public class RecipeSelector {
    private final RecipeGraph graph;

    public RecipeSelector(RecipeGraph graph) {
        this.graph = graph;
    }

    public RecipeNode selectStaticBestRecipe(Item item) {
        List<RecipeNode> allRecipes = graph.getRecipes(item);

        if (allRecipes.isEmpty()) {
            return RecipeNode.empty(item);
        }

        List<RecipeNode> primaryRecipes = allRecipes.stream()
                .filter(r -> r.getCategory() == RecipeCategory.PRIMARY)
                .toList();

        if (!primaryRecipes.isEmpty()) {
            return primaryRecipes.stream()
                    .max(Comparator.comparingInt(RecipeNode::getPriority))
                    .orElse(primaryRecipes.getFirst());
        }

        List<RecipeNode> goodRecipes = allRecipes.stream()
                .filter(r -> r.getCategory() != RecipeCategory.UNPROCESSABLE
                        && r.getCategory() != RecipeCategory.RECYCLING
                        && r.getCategory() != RecipeCategory.STORAGE_DECOMPRESSION)
                .toList();

        if (!goodRecipes.isEmpty()) {
            return goodRecipes.stream()
                    .max(Comparator.comparingInt(RecipeNode::getPriority))
                    .orElse(goodRecipes.getFirst());
        }

        return allRecipes.stream()
                .max(Comparator.comparingInt(RecipeNode::getPriority))
                .orElse(allRecipes.getFirst());
    }
}