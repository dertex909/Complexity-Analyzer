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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.resource;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.resource.data.BaseResourceData;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

public class SourceManager {
    private final ObjectList<IResourceSource> sources;

    private final Reference2ObjectMap<Item, BaseResourceData> cache = new Reference2ObjectOpenHashMap<>();
    private final StampedLock lock = new StampedLock();

    public SourceManager(ObjectList<IResourceSource> initialSources) {
        var sorted = new ObjectArrayList<>(initialSources);
        sorted.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
        this.sources = ObjectLists.unmodifiable(sorted);
    }

    public void initialize(Level level) {
        var successCount = new AtomicInteger(0);
        var failCount = new AtomicInteger(0);
        var sourceSnapshot = this.sources;

        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer().isSameThread()) {
            ComplexityAnalyzer.LOGGER.info("Initializing {} resource sources sequentially...", sources.size());
            for (var source : sourceSnapshot) initOne(source, level, successCount, failCount);
            ComplexityAnalyzer.LOGGER.info("Resource sources initialized: {} success, {} failed", successCount.get(), failCount.get());
            return;
        }

        var server = serverLevel.getServer();
        ComplexityAnalyzer.LOGGER.info("Initializing {} resource sources concurrently...", sources.size());

        var serverFutures = new ObjectArrayList<Future<?>>();
        var offThreadSources = new ObjectArrayList<IResourceSource>();
        for (var source : sourceSnapshot) {
            if (source.requiresServerThread()) {
                serverFutures.add(server.submit(() -> initOne(source, level, successCount, failCount)));
            } else {
                offThreadSources.add(source);
            }
        }

        for (var source : offThreadSources) initOne(source, level, successCount, failCount);

        for (var f : serverFutures) {
            try {
                f.get();
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.error("Server-thread source init failed", e);
            }
        }

        ComplexityAnalyzer.LOGGER.info("Resource sources initialized: {} success, {} failed", successCount.get(), failCount.get());
    }

    private void initOne(IResourceSource source, Level level, AtomicInteger successCount, AtomicInteger failCount) {
        try {
            long start = System.currentTimeMillis();
            source.initialize(level);
            successCount.incrementAndGet();
            ComplexityAnalyzer.LOGGER.info("Initialized resource source: {} in {}ms", source.getName(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            failCount.incrementAndGet();
            ComplexityAnalyzer.LOGGER.error("Failed to initialize source: {}", source.getName(), e);
        }
    }

    public double getBaseFactor(Item item) {
        var data = analyze(item);
        return data != null ? data.getBaseFactor() : -1.0;
    }

    @Nullable
    public BaseResourceData analyze(Item item) {
        long stamp = lock.tryOptimisticRead();
        boolean contains = cache.containsKey(item);
        var cachedValue = contains ? cache.get(item) : null;

        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                contains = cache.containsKey(item);
                cachedValue = contains ? cache.get(item) : null;
            } finally {
                lock.unlockRead(stamp);
            }
        }

        if (contains) return cachedValue;
        var result = performAnalysis(item);

        stamp = lock.writeLock();
        try {
            if (cache.containsKey(item)) return cache.get(item);
            cache.put(item, result);
        } finally {
            lock.unlockWrite(stamp);
        }

        return result;
    }

    @Nullable
    private BaseResourceData performAnalysis(Item item) {
        var engine = AnalysisEngine.getInstance();
        var graph = engine != null ? engine.getGraph() : null;

        var bestFactor = Double.POSITIVE_INFINITY;
        var bestData = (BaseResourceData) null;

        for (var source : sources) {
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

    public ObjectList<IResourceSource> getSources() {
        return this.sources;
    }

    @Nullable
    public <T extends IResourceSource> T getSourceByType(Class<T> type) {
        for (var source : sources) if (type.isInstance(source)) return type.cast(source);
        return null;
    }
}