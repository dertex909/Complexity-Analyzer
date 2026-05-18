package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;

import java.nio.file.Path;

public final class RegistryHarvestService {
    private final FastHarvester harvester;

    public RegistryHarvestService() {
        this.harvester = new FastHarvester();
    }

    public int harvestInto(RecipeGraph graph, Level level) {
        return harvestInto(graph, level, null);
    }

    public int harvestInto(RecipeGraph graph, Level level, Path worldDir) {
        if (graph == null || level == null) return 0;

        int scanned = 0;
        int harvested = 0;
        ObjectList<RecipeNode> nodes = new ObjectArrayList<>();
        var debug = new StringBuilder();
        debug.append("=== RUNTIME HARVEST ===\n");

        for (var holder : level.getRecipeManager().getRecipes()) {
            scanned++;
            try {
                var items = harvester.harvest(holder.value(), level);
                RecipeNode node = HarvestedRecipeConverter.convert(items, level);
                if (node != null && !node.getIngredients().isEmpty()) {
                    nodes.add(node);
                    harvested++;
                    debug.append("HARVESTED recipe=").append(holder.id())
                            .append(" result=").append(node.getResultItem())
                            .append(" ingredients=").append(node.getIngredients().size())
                            .append('\n');
                } else {
                    debug.append("REJECTED recipe=").append(holder.id())
                            .append(" class=").append(holder.value().getClass().getName())
                            .append(" items=").append(items.items().size())
                            .append(" ingr=").append(items.ingredients().size())
                            .append(" fluids=").append(items.fluids().size())
                            .append(" reason=no_structural_recipe_node\n");
                }
            } catch (Throwable t) {
                debug.append("FAILED recipe=").append(holder.id())
                        .append(" reason=").append(t.getClass().getSimpleName()).append('\n');
                ComplexityAnalyzer.LOGGER.debug("[Harvest] Failed to scan recipe object {}: {}",
                        holder.id(), t.getMessage());
            }
        }

        for (RecipeNode node : nodes) graph.addRecipe(node);
        HarvestDebugWriter.appendRuntime(worldDir, debug.toString());

        ComplexityAnalyzer.LOGGER.info("[Harvest] Runtime scan: {} recipe objects scanned, {} harvested",
                scanned, harvested);
        HarvestDebugLog.flush(worldDir);
        return harvested;
    }

    public void clearCaches() {
        harvester.clearCaches();
    }
}
