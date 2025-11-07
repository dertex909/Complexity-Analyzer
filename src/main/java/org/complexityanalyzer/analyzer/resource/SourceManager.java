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

package org.complexityanalyzer.analyzer.resource;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.sources.EmpiricalBlockSource;
import org.complexityanalyzer.analyzer.resource.sources.TheoreticalBlockSource;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.graph.IngredientSlot;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class SourceManager {
    private final List<IResourceSource> sources;
    private final Map<Item, Optional<BaseResourceData>> cache = new ConcurrentHashMap<>();

    public SourceManager(List<IResourceSource> initialSources) {
        this.sources = new ArrayList<>(initialSources);
        sortSources();
    }

    public void removeSourcesByType(Class<? extends IResourceSource> type) {
        boolean removed = sources.removeIf(type::isInstance);
        if (removed) {
            clearCache();
            ComplexityAnalyzer.LOGGER.info("Removed all sources of type {}", type.getSimpleName());
        }
    }

    private void sortSources() {
        this.sources.sort(Comparator.comparingInt(IResourceSource::getPriority).reversed());
    }

    public void addSourceAndRefresh(IResourceSource newSource) {
        ComplexityAnalyzer.LOGGER.debug("Adding new resource source: {} with priority {}", newSource.getName(), newSource.getPriority());
        sources.removeIf(s -> s.getClass().equals(newSource.getClass()));
        sources.add(newSource);
        sortSources();
        clearCache();
    }

    public void initialize(Level level) {
        for (IResourceSource source : sources) {
            try {
                source.initialize(level);
                ComplexityAnalyzer.LOGGER.debug("Initialized resource source: {}", source.getName());
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.error("Failed to initialize source: {}", source.getName(), e);
            }
        }
    }

    public double getBaseFactor(Item item) {
        return analyze(item)
                .map(BaseResourceData::getBaseFactor)
                .orElse(-1.0);
    }

    public Optional<BaseResourceData> analyze(Item item) {
        return cache.computeIfAbsent(item, this::performAnalysis);
    }

    private Optional<BaseResourceData> performAnalysis(Item item) {
        RecipeGraph graph = AnalysisEngine.getInstance().getGraph();

        if (graph != null && graph.hasRecipe(item)) {
            List<RecipeNode> recipes = graph.getRecipes(item);

            boolean hasVanillaCraft = recipes.stream().anyMatch(r -> {
                String recipeType = r.getRecipeType().toString();
                return isVanillaRecipeType(recipeType) &&
                        (r.getCategory() == RecipeCategory.PRIMARY ||
                                r.getCategory() == RecipeCategory.PROCESSING);
            });

            if (hasVanillaCraft) {
                return Optional.empty();
            }
        }

        Stream<IResourceSource> sourceStream = sources.stream();

        boolean empiricalReady = getSourceByType(EmpiricalBlockSource.class)
                .map(EmpiricalBlockSource::isReady)
                .orElse(false);

        if (empiricalReady) {
            sourceStream = sourceStream.filter(source -> !(source instanceof TheoreticalBlockSource));
        }

        return sourceStream
                .filter(source -> source.canProvide(item))
                .map(source -> source.analyze(item))
                .flatMap(Optional::stream)
                .min(Comparator.comparingDouble(BaseResourceData::getBaseFactor));
    }

    // ========== ДОБАВЛЕН вспомогательный метод ==========
    private static boolean isVanillaRecipeType(String recipeType) {
        return recipeType.equals("minecraft:crafting") || recipeType.equals("crafting") ||
                recipeType.equals("minecraft:smelting") || recipeType.equals("smelting") ||
                recipeType.equals("minecraft:blasting") || recipeType.equals("blasting") ||
                recipeType.equals("minecraft:smoking") || recipeType.equals("smoking") ||
                recipeType.equals("minecraft:campfire_cooking") || recipeType.equals("campfire_cooking") ||
                recipeType.equals("minecraft:stonecutting") || recipeType.equals("stonecutting") ||
                recipeType.equals("minecraft:smithing") || recipeType.equals("smithing");
    }

    public List<BaseResourceData> findAllSources(Item item) {
        List<BaseResourceData> results = new ArrayList<>();

        for (IResourceSource source : sources) {
            if (source.canProvide(item)) {
                if (source instanceof IMultiSourceProvider multiSource) {
                    results.addAll(multiSource.findAllSources(item));
                }
                else {
                    source.analyze(item).ifPresent(results::add);
                }
            }
        }

        return results;
    }

    public void clearCache() {
        cache.clear();
        ComplexityAnalyzer.LOGGER.debug("SourceManager cache cleared.");
    }

    public List<IResourceSource> getSources() {
        return Collections.unmodifiableList(sources);
    }

    public <T extends IResourceSource> Optional<T> getSourceByType(Class<T> type) {
        return sources.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst();
    }
}