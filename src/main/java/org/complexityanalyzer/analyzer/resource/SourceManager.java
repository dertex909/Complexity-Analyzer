/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
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

import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.sources.EmpiricalBlockSource;
import org.complexityanalyzer.analyzer.resource.sources.TheoreticalBlockSource;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.graph.RecipeGraph;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class SourceManager {
    private final CopyOnWriteArrayList<IResourceSource> sources;
    private final Map<Item, Optional<BaseResourceData>> cache = new ConcurrentHashMap<>();

    public SourceManager(List<IResourceSource> initialSources) {
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
        List<IResourceSource> sorted = new ArrayList<>(sources);
        sorted.sort(Comparator.comparingInt(IResourceSource::getPriority).reversed());
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

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        List<IResourceSource> sourceSnapshot = new ArrayList<>(sources);

        List<CompletableFuture<Void>> futures = sourceSnapshot.stream()
                .map(source -> CompletableFuture.runAsync(() -> {
                    try {
                        source.initialize(level);
                        successCount.incrementAndGet();
                        ComplexityAnalyzer.LOGGER.debug("Initialized resource source: {}", source.getName());
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                        ComplexityAnalyzer.LOGGER.error("Failed to initialize source: {}", source.getName(), e);
                    }
                }, ThreadPoolManager.getInstance().getComputePool()))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        ComplexityAnalyzer.LOGGER.info("Resource sources initialized: {} success, {} failed",
                successCount.get(), failCount.get());
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

        boolean empiricalReady = getSourceByType(EmpiricalBlockSource.class)
                .map(EmpiricalBlockSource::isReady)
                .orElse(false);

        Stream<IResourceSource> sourceStream = sources.stream();

        if (empiricalReady) {
            sourceStream = sourceStream.filter(source -> !(source instanceof TheoreticalBlockSource));
        }

        Optional<BaseResourceData> candidate = sourceStream
                .filter(source -> source.canProvide(item))
                .map(source -> source.analyze(item))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .min(Comparator.comparingDouble(BaseResourceData::getBaseFactor));

        if (candidate.isPresent() && graph != null && graph.hasRecipe(item)) {
            BaseResourceData data = candidate.get();

            boolean hasNonBaseRecipe = graph.getRecipes(item).stream().anyMatch(r -> !r.isBaseRecipe());
            if (hasNonBaseRecipe) {
                boolean unusable = Double.isInfinite(data.getBaseFactor()) ||
                        data.getSourceType() == BaseResourceData.ResourceSourceType.UNOBTAINABLE;
                if (unusable) return Optional.empty();
            }
        }

        return candidate;
    }

    public List<BaseResourceData> findAllSources(Item item) {
        return sources.parallelStream()
                .filter(source -> source.canProvide(item))
                .flatMap(source -> {
                    if (source instanceof IMultiSourceProvider multiSource) {
                        return multiSource.findAllSources(item).stream();
                    } else {
                        return source.analyze(item).stream();
                    }
                })
                .collect(java.util.stream.Collectors.toList());
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