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

package org.complexityanalyzer.resource.sources.hardcoded;

import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import org.complexityanalyzer.api.IHardcodedSourceRegistry;
import org.complexityanalyzer.resource.data.BaseResourceData;
import org.jetbrains.annotations.Nullable;

public final class GrassBonemealModule {

    private static final double AVG_SHORT_GRASS_PER_BONEMEAL = 28.0;
    private static final double AVG_TALL_GRASS_PER_BONEMEAL = 3.5;
    private static final double TOTAL_FLOWERS_PER_BONEMEAL = 4.5;

    private GrassBonemealModule() {
    }

    public static void register(Level level, IHardcodedSourceRegistry registry) {
        var registryAccess = level.registryAccess();
        var biomeRegistry = registryAccess.registry(Registries.BIOME).orElse(null);

        registerGrass(registry, Items.SHORT_GRASS, AVG_SHORT_GRASS_PER_BONEMEAL, "Sprouted by using Bone Meal on Grass Block");
        registerGrass(registry, Items.TALL_GRASS, AVG_TALL_GRASS_PER_BONEMEAL, "Grown by using Bone Meal on Grass Block (upgraded Short Grass)");

        if (biomeRegistry == null) return;
        var bestFlowerSources = new Reference2ObjectOpenHashMap<Item, BestFlowerData>();

        for (var biomeHolder : biomeRegistry.holders().toList()) {
            var biome = biomeHolder.value();
            var biomeKey = biomeHolder.unwrapKey().orElse(null);
            if (biomeKey == null) continue;

            var flowerFeatures = biome.getGenerationSettings().getFlowerFeatures();
            if (flowerFeatures.isEmpty()) continue;

            String biomeName = biomeKey.location().toString();

            for (var configured : flowerFeatures) {
                if (!(configured.config() instanceof RandomPatchConfiguration patchConfig)) continue;

                var placedHolder = patchConfig.feature();
                if (!placedHolder.isBound()) continue;

                var innerFeatureHolder = placedHolder.value().feature();
                if (!innerFeatureHolder.isBound()) continue;

                var stateProvider = extractStateProvider(innerFeatureHolder.value().config());
                if (stateProvider == null) continue;

                var random = RandomSource.create(42L);
                var sampleCounts = new Reference2DoubleOpenHashMap<Block>();
                int samples = 250;

                for (int i = 0; i < samples; i++) {
                    var pos = new BlockPos(random.nextInt(2048) - 1024, 64, random.nextInt(2048) - 1024);
                    var state = stateProvider.getState(random, pos);
                    var block = state.getBlock();
                    if (block != Blocks.AIR && block != Blocks.SHORT_GRASS && block != Blocks.TALL_GRASS) {
                        sampleCounts.addTo(block, 1.0);
                    }
                }

                for (var entry : sampleCounts.reference2DoubleEntrySet()) {
                    var block = entry.getKey();
                    var item = block.asItem();
                    if (item == Items.AIR) continue;
                    double prob = entry.getDoubleValue() / samples;
                    if (prob <= 0.0) continue;
                    double flowersPerBonemeal = TOTAL_FLOWERS_PER_BONEMEAL * prob;
                    double bonemealPerFlower = 1.0 / flowersPerBonemeal;
                    var existing = bestFlowerSources.get(item);
                    if (existing == null || bonemealPerFlower < existing.bonemealCost) {
                        bestFlowerSources.put(item, new BestFlowerData(bonemealPerFlower, biomeName, prob));
                    }
                }
            }
        }

        for (var entry : bestFlowerSources.reference2ObjectEntrySet()) {
            var item = entry.getKey();
            var data = entry.getValue();
            double roundedBonemeal = Math.round(data.bonemealCost * 100.0) / 100.0;
            String details = "Sprouted by Bone Meal on Grass Block in %s (~%.1f%% flower share)".formatted(data.biomeName, data.probability * 100.0);

            registry.register(item, new BaseResourceData.Builder(item)
                    .sourceType(BaseResourceData.ResourceSourceType.BLOCK_TRANSFORMATION)
                    .baseFactor(roundedBonemeal)
                    .addSourceItem(Items.BONE_MEAL, roundedBonemeal)
                    .details(details)
                    .build());
        }
    }

    @Nullable
    private static BlockStateProvider extractStateProvider(Object featureConfig) {
        if (featureConfig instanceof SimpleBlockConfiguration(var stateProvider)) return stateProvider;
        for (var field : featureConfig.getClass().getDeclaredFields()) {
            if (BlockStateProvider.class.isAssignableFrom(field.getType())) try {
                field.setAccessible(true);
                if (field.get(featureConfig) instanceof BlockStateProvider bsp) return bsp;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void registerGrass(IHardcodedSourceRegistry registry, Item grassItem, double yieldPerBonemeal, String details) {
        if (grassItem == Items.AIR) return;
        double bonemealCost = Math.round((1.0 / yieldPerBonemeal) * 1000.0) / 1000.0;

        var data = new BaseResourceData.Builder(grassItem)
                .sourceType(BaseResourceData.ResourceSourceType.BLOCK_TRANSFORMATION)
                .baseFactor(bonemealCost)
                .addSourceItem(Items.BONE_MEAL, bonemealCost)
                .details(details)
                .build();

        registry.register(grassItem, data);
    }

    private record BestFlowerData(double bonemealCost, String biomeName, double probability) {
    }
}