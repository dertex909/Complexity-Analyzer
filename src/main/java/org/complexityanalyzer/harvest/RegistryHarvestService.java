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

    public void harvestInto(RecipeGraph graph, Level level, Path worldDir) {
        if (graph == null || level == null) return;

        harvester.clearCaches();

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
                if (node != null && (!node.getIngredients().isEmpty() || !node.getFluidIngredients().isEmpty() || !node.getChemicalIngredients().isEmpty())) {
                    nodes.add(node);
                    harvested++;
                    debug.append("HARVESTED recipe=").append(holder.id())
                            .append(" result=").append(node.getResultItem())
                            .append(" ingredients=").append(node.getIngredients().size() + node.getFluidIngredients().size() + node.getChemicalIngredients().size())
                            .append('\n');
                } else {
                    debug.append("REJECTED recipe=").append(holder.id())
                            .append(" class=").append(holder.value().getClass().getName())
                            .append(" items=").append(items.inputItems().size() + items.outputItems().size())
                            .append(" ingr=").append(items.inputIngredients().size())
                            .append(" fluids=").append(items.inputFluids().size() + items.outputFluids().size())
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
    }
}