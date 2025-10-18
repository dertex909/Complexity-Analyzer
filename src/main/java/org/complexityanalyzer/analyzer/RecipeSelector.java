package org.complexityanalyzer.analyzer;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;
import java.util.Comparator;

public class RecipeSelector {
    private final RecipeGraph graph;
    public RecipeSelector(RecipeGraph graph) { this.graph = graph; }

    public RecipeNode selectStaticBestRecipe(Item item) {
        return graph.getRecipes(item).stream()
                .max(Comparator.comparingInt(RecipeNode::getPriority))
                .orElse(RecipeNode.empty(item));
    }
}