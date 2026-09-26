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

package org.complexityanalyzer.resource.sources;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.api.IHardcodedSourceRegistry;
import org.complexityanalyzer.resource.IResourceSource;
import org.complexityanalyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.resource.sources.hardcoded.*;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

public class HardcodedSource implements IResourceSource, IHardcodedSourceRegistry {

    private static final int NORMAL_PRIORITY = 35;
    private static HardcodedSource INSTANCE;

    private final ConcurrentHashMap<Item, BaseResourceData> staticSources = new ConcurrentHashMap<>();
    private final ObjectList<Function<Item, BaseResourceData>> dynamicResolvers = new ObjectArrayList<>();
    private final ObjectList<BiConsumer<Level, IHardcodedSourceRegistry>> initializers = new ObjectArrayList<>();

    public HardcodedSource() {
        INSTANCE = this;
        register(CoralModule::register);
        register(AxeStrippingModule::register);
        register(ToolInteractionsModule::register);
        register(ConcreteModule::register);
        register(CopperOxidationModule::register);
        register(AnvilDegradationModule::register);
        register(MossVegetationModule::register);
        register(BucketInteractionsModule::register);
        register(ManualOverridesModule::register);
    }

    public static IHardcodedSourceRegistry getRegistry() {
        return Objects.requireNonNull(INSTANCE, "HardcodedSource not initialized yet!");
    }

    public static boolean isInitialized() {
        return INSTANCE != null;
    }

    @Override
    public synchronized void register(Function<Item, BaseResourceData> resolver) {
        if (resolver != null) dynamicResolvers.add(resolver);
    }

    @Override
    public void register(Item item, BaseResourceData data) {
        if (item != null && data != null) staticSources.put(item, data);
    }

    @Override
    public synchronized void register(BiConsumer<Level, IHardcodedSourceRegistry> initializer) {
        if (initializer != null) initializers.add(initializer);
    }

    @Override
    public void initialize(Level level) {
        ComplexityAnalyzer.LOGGER.info("[{}] Initializing dynamic sources...", getName());
        staticSources.clear();

        long start = System.currentTimeMillis();
        for (var init : initializers) {
            try {
                init.accept(level, this);
            } catch (Throwable t) {
                ComplexityAnalyzer.LOGGER.error("[{}] Error running source initializer", getName(), t);
            }
        }

        ComplexityAnalyzer.LOGGER.info("[{}] Initialized in {}ms. Static entries: {}, Dynamic resolvers: {}",
                getName(), System.currentTimeMillis() - start, staticSources.size(), dynamicResolvers.size());
    }

    @Override
    public boolean isRegistered(Item item) {
        return canProvide(item);
    }

    @Override
    public boolean canProvide(Item item) {
        if (staticSources.containsKey(item)) return true;
        for (var resolver : dynamicResolvers) {
            try {
                if (resolver.apply(item) != null) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    @Override
    @Nullable
    public BaseResourceData analyze(Item item) {
        var staticData = staticSources.get(item);
        if (staticData != null && staticData.isOverride()) return staticData;

        var best = staticData;
        for (var resolver : dynamicResolvers) {
            try {
                var result = resolver.apply(item);
                if (result != null) {
                    if (result.isOverride()) return result;
                    if (best == null || result.getBaseFactor() < best.getBaseFactor()) best = result;
                }
            } catch (Throwable t) {
                ComplexityAnalyzer.LOGGER.error("[{}] Resolver threw error for item {}", getName(), item, t);
            }
        }

        return best;
    }

    @Override
    public int getPriority() {
        return NORMAL_PRIORITY;
    }

    @Override
    public String getName() {
        return "HardcodedSources";
    }

    @Override
    public BaseResourceData.ResourceSourceType getSourceType() {
        return BaseResourceData.ResourceSourceType.SPECIAL_ACTION;
    }
}