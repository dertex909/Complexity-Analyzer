package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
        int rejected = 0;
        int failed = 0;
        ObjectList<RecipeNode> nodes = new ObjectArrayList<>();

        FullDebugTracePipeline debugTrace = new FullDebugTracePipeline(worldDir);

        ComplexityAnalyzer.LOGGER.info("[Harvest] Starting runtime recipe scan...");

        for (var holder : level.getRecipeManager().getRecipes()) {
            scanned++;
            try {
                var items = harvester.harvest(holder.value(), level);
                RecipeNode node = HarvestedRecipeConverter.convert(items, level);

                if (node != null && (!node.getIngredients().isEmpty() || !node.getFluidIngredients().isEmpty() || !node.getChemicalIngredients().isEmpty())) {
                    nodes.add(node);
                    harvested++;
                    debugTrace.traceHarvested(holder.id().toString(), holder.value().getClass().getName(), items);
                } else {
                    rejected++;
                    String reason = buildRejectReason(items);
                    debugTrace.traceRejected(holder.id().toString(), holder.value(), level, reason);
                }
            } catch (Throwable t) {
                failed++;
                debugTrace.traceFailed(holder.id().toString(), holder.value().getClass().getName(), t);
                ComplexityAnalyzer.LOGGER.debug("[Harvest] Failed to scan recipe {}: {}", holder.id(), t.getMessage());
            }
        }

        for (RecipeNode node : nodes) graph.addRecipe(node);
        debugTrace.flush();

        try {
            level.registryAccess().registries().forEach(entry -> {
                var key = entry.key();
                var registry = entry.value();
                String namespace = key.location().getNamespace();
                if (namespace.equals("minecraft") || namespace.equals("neoforge") || namespace.equals("forge")) return;
                scanRegistryForFluids(graph, registry);
            });
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[Harvest] Failed dynamic registry fluid scan: {}", t.getMessage());
        }

        ComplexityAnalyzer.LOGGER.info("[Harvest] Runtime scan complete: {} scanned, {} harvested, {} rejected, {} failed",
                scanned, harvested, rejected, failed);
    }

    private void scanRegistryForFluids(RecipeGraph graph, Registry<?> registry) {
        for (Object element : registry) {
            if (element == null) continue;
            try {
                Fluid fluid = findFluidFromElement(element);
                if (fluid == null || fluid == Fluids.EMPTY) continue;
                int yield = 1000;

                var builder = new RecipeNode.Builder(Items.AIR)
                        .category(RecipeCategory.PRIMARY)
                        .resultCount(1)
                        .recipeType(RecipeType.CRAFTING)
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
        if (element instanceof Fluid f) return f;

        Class<?> clazz = element.getClass();
        for (Method method : clazz.getMethods()) {
            if (method.getParameterCount() == 0 && !method.getName().equals("toString") && !method.getName().equals("hashCode")) {
                Class<?> returnType = method.getReturnType();
                if (Fluid.class.isAssignableFrom(returnType)) {
                    try {
                        Fluid f = (Fluid) method.invoke(element);
                        if (f != null && f != Fluids.EMPTY) return f;
                    } catch (Throwable ignored) {
                    }
                } else if (Holder.class.isAssignableFrom(returnType)) {
                    try {
                        Holder<?> holder = (Holder<?>) method.invoke(element);
                        if (holder != null && holder.value() instanceof Fluid f) if (f != Fluids.EMPTY) return f;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        for (Field field : clazz.getDeclaredFields()) {
            if (Fluid.class.isAssignableFrom(field.getType())) try {
                field.setAccessible(true);
                Fluid f = (Fluid) field.get(element);
                if (f != null && f != Fluids.EMPTY) return f;
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