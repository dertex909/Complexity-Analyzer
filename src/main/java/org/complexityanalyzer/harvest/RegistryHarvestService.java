package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;

import java.nio.file.Path;

/**
 * Сервис сбора рецептов из реестра — v2.0.
 * Интегрирован с {@link FullDebugTracePipeline} для полного дебага каждого рецепта.
 */
public final class RegistryHarvestService {
    private final FastHarvester harvester;

    public RegistryHarvestService() {
        this.harvester = new FastHarvester();
    }

    /**
     * Собрать все рецепты в граф с полным дебаг-трейсингом.
     */
    public void harvestInto(RecipeGraph graph, Level level, Path worldDir) {
        if (graph == null || level == null) return;

        harvester.clearCaches();

        int scanned = 0;
        int harvested = 0;
        int rejected = 0;
        int failed = 0;
        ObjectList<RecipeNode> nodes = new ObjectArrayList<>();

        // Создаём FullDebugTracePipeline для детального дебага
        FullDebugTracePipeline debugTrace = new FullDebugTracePipeline(worldDir);

        ComplexityAnalyzer.LOGGER.info("[Harvest] Starting runtime recipe scan...");

        for (var holder : level.getRecipeManager().getRecipes()) {
            scanned++;
            try {
                // Начинаем трейс для этого рецепта
                FullDebugTracePipeline.TraceBuilder trace = debugTrace.beginRecipe(
                        holder.value(), holder.id().toString());

                // Информация о классе
                trace.classInfo();

                // Информация о полях и методах
                trace.fields();
                trace.methods();
                trace.accessors(level);

                // Собственно harvest
                var items = harvester.harvest(holder.value(), level);
                RecipeNode node = HarvestedRecipeConverter.convert(items, level);

                if (node != null && (!node.getIngredients().isEmpty()
                        || !node.getFluidIngredients().isEmpty()
                        || !node.getChemicalIngredients().isEmpty())) {
                    nodes.add(node);
                    harvested++;
                    trace.harvested(items);
                } else {
                    rejected++;
                    String reason = buildRejectReason(items);
                    trace.rejected(reason);
                }
            } catch (Throwable t) {
                failed++;
                try {
                    FullDebugTracePipeline.TraceBuilder trace = debugTrace.beginRecipe(
                            holder.value(), holder.id().toString());
                    trace.classInfo();
                    trace.failed(t);
                } catch (Throwable ignored) {
                }
                ComplexityAnalyzer.LOGGER.debug("[Harvest] Failed to scan recipe {}: {}",
                        holder.id(), t.getMessage());
            }
        }

        // Добавляем все собранные ноды в граф
        for (RecipeNode node : nodes) graph.addRecipe(node);

        // Финализируем дебаг-вывод
        debugTrace.flush();

        ComplexityAnalyzer.LOGGER.info("[Harvest] Runtime scan complete: {} scanned, {} harvested, {} rejected, {} failed",
                scanned, harvested, rejected, failed);
    }

    private static String buildRejectReason(HarvestedItems items) {
        var sb = new StringBuilder("No structural recipe node: ");
        sb.append("inputItems=").append(items.inputItems().size());
        sb.append(" outputItems=").append(items.outputItems().size());
        sb.append(" inputIngredients=").append(items.inputIngredients().size());
        sb.append(" inputFluids=").append(items.inputFluids().size());
        sb.append(" outputFluids=").append(items.outputFluids().size());
        sb.append(" rootType=").append(items.root() != null ? items.root().getClass().getSimpleName() : "null");

        // Добавляем результат антивирусного детекта
        if (items.root() != null) {
            var detection = AntivirusStyleDetector.detect(items.root().getClass());
            sb.append(" antivirus=").append(detection.verdict());
        }

        return sb.toString();
    }
}
