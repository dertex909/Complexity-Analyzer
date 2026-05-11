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

package org.complexityanalyzer.analyzer.resource;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.sources.EmpiricalBlockSource;
import org.complexityanalyzer.analyzer.resource.sources.TheoreticalBlockSource;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class SourceManager {
    private final CopyOnWriteArrayList<IResourceSource> sources;
    private final Reference2ObjectMap<Item, BaseResourceData> cache = Reference2ObjectMaps.synchronize(new Reference2ObjectOpenHashMap<>());

    public SourceManager(ObjectList<IResourceSource> initialSources) {
        this.sources = new CopyOnWriteArrayList<>(initialSources);
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
        var sorted = new ObjectArrayList<>(sources);
        sorted.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
        sources.clear();
        sources.addAll(sorted);
    }

    public void addSourceAndRefresh(IResourceSource newSource) {
        ComplexityAnalyzer.LOGGER.debug("Adding new resource source: {} with priority {}", newSource.getName(), newSource.getPriority());
        sources.removeIf(s -> s.getClass().equals(newSource.getClass()));
        sources.add(newSource);
        sortSources();
        clearCache();
    }

    public void initialize(Level level) {
        ComplexityAnalyzer.LOGGER.info("Initializing {} resource sources in parallel...", sources.size());

        var successCount = new AtomicInteger(0);
        var failCount = new AtomicInteger(0);

        var sourceSnapshot = new ObjectArrayList<>(sources);
        var futures = new CompletableFuture[sourceSnapshot.size()];

        for (int i = 0; i < sourceSnapshot.size(); i++) {
            var source = sourceSnapshot.get(i);
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    source.initialize(level);
                    successCount.incrementAndGet();
                    ComplexityAnalyzer.LOGGER.debug("Initialized resource source: {}", source.getName());
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    ComplexityAnalyzer.LOGGER.error("Failed to initialize source: {}", source.getName(), e);
                }
            }, ThreadPoolManager.getInstance().getComputePool());
        }

        CompletableFuture.allOf(futures).join();

        ComplexityAnalyzer.LOGGER.info("Resource sources initialized: {} success, {} failed",
                successCount.get(), failCount.get());
    }

    public double getBaseFactor(Item item) {
        var data = analyze(item);
        return data != null ? data.getBaseFactor() : -1.0;
    }

    @Nullable
    public BaseResourceData analyze(Item item) {
        if (cache.containsKey(item)) return cache.get(item);
        var result = performAnalysis(item);
        cache.put(item, result);
        return result;
    }

    @Nullable
    private BaseResourceData performAnalysis(Item item) {
        var engine = AnalysisEngine.getInstance();
        var graph = engine != null ? engine.getGraph() : null;

        var empiricalSource = getSourceByType(EmpiricalBlockSource.class);
        var empiricalReady = empiricalSource != null && empiricalSource.isReady();

        var bestFactor = Double.POSITIVE_INFINITY;
        var bestData = (BaseResourceData) null;

        for (var source : sources) {
            if (empiricalReady && source instanceof TheoreticalBlockSource) continue;
            if (!source.canProvide(item)) continue;

            var data = source.analyze(item);
            if (data != null) if (bestData == null || data.getBaseFactor() < bestFactor) {
                bestFactor = data.getBaseFactor();
                bestData = data;
            }
        }

        var candidate = bestData;

        if (candidate != null && graph != null && graph.hasRecipe(item)) {
            var unusable = Double.isInfinite(candidate.getBaseFactor()) ||
                    candidate.getSourceType() == BaseResourceData.ResourceSourceType.UNOBTAINABLE;

            if (unusable) for (var r : graph.getRecipes(item)) if (!r.isBaseRecipe()) return null;
        }

        return candidate;
    }

    public ObjectList<BaseResourceData> findAllSources(Item item) {
        var results = new ObjectArrayList<BaseResourceData>();

        for (var source : sources) {
            if (!source.canProvide(item)) continue;

            if (source instanceof IMultiSourceProvider multiSource) {
                results.addAll(multiSource.findAllSources(item));
            } else {
                var data = source.analyze(item);
                if (data != null) results.add(data);
            }
        }

        return results;
    }

    public void clearCache() {
        cache.clear();
        ComplexityAnalyzer.LOGGER.debug("SourceManager cache cleared.");
    }

    public ObjectList<IResourceSource> getSources() {
        return new ObjectArrayList<>(sources);
    }

    @Nullable
    public <T extends IResourceSource> T getSourceByType(Class<T> type) {
        for (var source : sources) if (type.isInstance(source)) return type.cast(source);
        return null;
    }
}