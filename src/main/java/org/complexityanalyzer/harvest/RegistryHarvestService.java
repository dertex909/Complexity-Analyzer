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

package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.minecraft.resources.ResourceLocation;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Locale;

import static net.minecraft.core.registries.Registries.FLUID;
import static net.minecraft.world.item.Items.AIR;
import static net.minecraft.world.item.crafting.RecipeType.CRAFTING;
import static org.complexityanalyzer.graph.RecipeCategory.PRIMARY;
import static net.minecraft.world.level.material.Fluids.EMPTY;

public final class RegistryHarvestService {
    private final FastHarvester harvester;

    public RegistryHarvestService() {
        this.harvester = new FastHarvester();
    }

    public void harvestInto(RecipeGraph graph, Level level, Path worldDir) {
        if (graph == null || level == null) return;

        harvester.clearCaches();

        var recipes = level.getRecipeManager().getRecipes();
        int totalRecipes = recipes.size();
        int scanned = 0;
        int harvested = 0;
        int rejected = 0;
        int failed = 0;
        ObjectList<RecipeNode> nodes = new ObjectArrayList<>();
        ObjectSet<ResourceLocation> knownRecipeIds = new ObjectOpenHashSet<>();

        var debugTrace = new FullDebugTracePipeline(worldDir);

        ComplexityAnalyzer.LOGGER.info("[Harvest] Starting runtime recipe scan (Total: {} recipes)...", totalRecipes);
        long startTime = System.currentTimeMillis();

        for (var holder : recipes) {
            scanned++;
            knownRecipeIds.add(holder.id());
            try {
                var recipe = holder.value();
                var items = harvester.harvest(recipe, level);
                var node = HarvestedRecipeConverter.convert(items, level);

                if (node != null && (!node.getIngredients().isEmpty() || !node.getFluidIngredients().isEmpty() || !node.getChemicalIngredients().isEmpty())) {
                    nodes.add(node);
                    harvested++;
                    debugTrace.traceHarvested(holder.id().toString(), recipe.getClass().getName(), items);
                } else {
                    rejected++;
                    String reason = buildRejectReason(items);
                    debugTrace.traceRejected(holder.id().toString(), recipe, level, reason);
                }
            } catch (Throwable t) {
                failed++;
                debugTrace.traceFailed(holder.id().toString(), holder.value().getClass().getName(), t);
                ComplexityAnalyzer.LOGGER.debug("[Harvest] Failed to scan recipe {}: {}", holder.id(), t.getMessage());
            }

            if ((scanned < 1000 && scanned % 100 == 0) || (scanned >= 1000 && scanned % 1000 == 0) || scanned == totalRecipes) {
                long elapsed = System.currentTimeMillis() - startTime;
                final var remainingStr = getRemaining(elapsed, scanned, totalRecipes);

                ComplexityAnalyzer.LOGGER.info("[Harvest] Progress: {}/{} ({}%). Estimated remaining time: {}. Status: harvested={}, rejected={}, failed={}",
                        scanned, totalRecipes, String.format(Locale.US, "%.1f", (scanned * 100.0) / totalRecipes),
                        remainingStr, harvested, rejected, failed);
            }
        }

        for (var node : nodes) graph.addRecipe(node);
        debugTrace.flush();
        DynamicRecipeHarvester.harvest(graph, level, knownRecipeIds);

        try {
            var fluidRegistry = level.registryAccess().registry(FLUID);
            fluidRegistry.ifPresent(fluids -> scanRegistryForFluids(graph, fluids));
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[Harvest] Failed dynamic registry fluid scan: {}", t.getMessage());
        }

        ComplexityAnalyzer.LOGGER.info("[Harvest] Runtime scan complete: {} scanned, {} harvested, {} rejected, {} failed",
                scanned, harvested, rejected, failed);
    }

    private static @NotNull String getRemaining(long elapsed, int scanned, int totalRecipes) {
        double avgTimePerRecipe = (double) elapsed / scanned;
        long estimatedTotal = (long) (avgTimePerRecipe * totalRecipes);
        long estimatedRemaining = estimatedTotal - elapsed;

        String remainingStr;
        if (estimatedRemaining > 60000) {
            remainingStr = String.format(Locale.US, "%dm %ds", estimatedRemaining / 60000, (estimatedRemaining % 60000) / 1000);
        } else {
            remainingStr = String.format(Locale.US, "%ds", estimatedRemaining / 1000);
        }
        return remainingStr;
    }

    private void scanRegistryForFluids(RecipeGraph graph, Registry<?> registry) {
        for (var element : registry) {
            if (element == null) continue;
            try {
                var fluid = findFluidFromElement(element);
                if (fluid == null || fluid == EMPTY) continue;
                int yield = 1000;

                var builder = new RecipeNode.Builder(AIR)
                        .category(PRIMARY)
                        .resultCount(1)
                        .recipeType(CRAFTING)
                        .isPlaceholder(true);

                builder.priority(100);
                builder.placeholderId(GameRegistryManager.getFluidId(fluid).toString());

                var fluidOutputs = new ObjectArrayList<FluidStack>();
                fluidOutputs.add(new FluidStack(fluid, yield));
                builder.fluidOutputs(fluidOutputs);

                graph.addRecipe(builder.build());
            } catch (Throwable ignored) {
            }
        }
    }

    private Fluid findFluidFromElement(Object element) {
        if (element == null) return null;
        if (element instanceof Fluid f) return f;
        if (TerminalTypeRegistry.isTerminalType(element.getClass())) return null;

        Class<?> clazz = element.getClass();
        for (var method : clazz.getMethods()) {
            if (method.getParameterCount() == 0 && !method.getName().equals("toString") && !method.getName().equals("hashCode")) {
                Class<?> returnType = method.getReturnType();
                if (Fluid.class.isAssignableFrom(returnType)) {
                    try {
                        var f = (Fluid) method.invoke(element);
                        if (f != null && f != EMPTY) return f;
                    } catch (Throwable ignored) {
                    }
                } else if (Holder.class.isAssignableFrom(returnType)) {
                    try {
                        Holder<?> holder = (Holder<?>) method.invoke(element);
                        if (holder != null && holder.value() instanceof Fluid f) if (f != EMPTY) return f;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        for (var field : clazz.getDeclaredFields()) {
            if (Fluid.class.isAssignableFrom(field.getType())) try {
                field.setAccessible(true);
                var f = (Fluid) field.get(element);
                if (f != null && f != EMPTY) return f;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String buildRejectReason(HarvestedItems items) {
        var sb = new StringBuilder("No structural recipe node: ");
        sb.append("inputItems=").append(items.inputItems().size());
        sb.append(" outputItems=").append(items.outputItems().size());
        sb.append(" inputIngredients=").append(items.inputIngredients().size());
        sb.append(" inputFluids=").append(items.inputFluids().size());
        sb.append(" outputFluids=").append(items.outputFluids().size());
        sb.append(" rootType=").append(items.root() != null ? items.root().getClass().getSimpleName() : "null");
        if (items.root() != null) {
            var detection = AntivirusStyleDetector.detect(items.root().getClass());
            sb.append(" antivirus=").append(detection.verdict());
        }
        return sb.toString();
    }
}