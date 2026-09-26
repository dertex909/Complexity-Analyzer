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
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.CaveFeatures;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import org.complexityanalyzer.api.IHardcodedSourceRegistry;
import org.complexityanalyzer.resource.data.BaseResourceData;

public final class MossVegetationModule {

    private MossVegetationModule() {
    }

    public static void register(Level level, IHardcodedSourceRegistry registry) {
        var featureRegistry = level.registryAccess().registry(Registries.CONFIGURED_FEATURE).orElse(null);
        if (featureRegistry == null) return;

        var azaleaTreeHolder = featureRegistry.getHolder(TreeFeatures.AZALEA_TREE).orElse(null);
        if (azaleaTreeHolder != null && azaleaTreeHolder.value().config() instanceof TreeConfiguration treeConfig) {
            var dirtBlock = treeConfig.dirtProvider.getState(level.getRandom(), BlockPos.ZERO).getBlock();
            var dirtItem = dirtBlock.asItem();

            if (dirtItem != Items.AIR && dirtBlock != Blocks.DIRT) {
                double avgBonemeal = 1.0 / 0.45;

                var ingredients = new Reference2DoubleOpenHashMap<Item>();
                ingredients.put(Items.AZALEA, 1.0);
                ingredients.put(Items.DIRT, 1.0);
                ingredients.put(Items.BONE_MEAL, Math.round(avgBonemeal * 100.0) / 100.0);

                registry.register(dirtItem, new BaseResourceData.Builder(dirtItem)
                        .sourceType(BaseResourceData.ResourceSourceType.BLOCK_TRANSFORMATION)
                        .baseFactor(avgBonemeal)
                        .sourceItems(ingredients)
                        .details("Formed under azalea tree via " + TreeFeatures.AZALEA_TREE.location())
                        .build());
            }
        }

        var mossVegHolder = featureRegistry.getHolder(CaveFeatures.MOSS_VEGETATION).orElse(null);
        if (mossVegHolder != null && mossVegHolder.value().config() instanceof SimpleBlockConfiguration(
                var stateProvider
        )) {
            var random = RandomSource.create(42L);
            var sampleCounts = new Reference2DoubleOpenHashMap<Block>();
            int samples = 200;
            for (int i = 0; i < samples; i++) {
                var state = stateProvider.getState(random, BlockPos.ZERO);
                var block = state.getBlock();
                if (block != Blocks.SHORT_GRASS && block != Blocks.TALL_GRASS) {
                    sampleCounts.put(block, sampleCounts.getDouble(block) + 1.0);
                }
            }

            for (var entry : sampleCounts.reference2DoubleEntrySet()) {
                var block = entry.getKey();
                var item = block.asItem();
                if (item == Items.AIR) continue;

                double probability = entry.getDoubleValue() / samples;
                if (probability <= 0.0) continue;
                double attemptsPerItem = 1.0 / probability;

                var ingredients = new Reference2DoubleOpenHashMap<Item>();
                ingredients.put(Items.MOSS_BLOCK, 1.0);
                ingredients.put(Items.BONE_MEAL, Math.round(attemptsPerItem * 100.0) / 100.0);

                registry.register(item, new BaseResourceData.Builder(item)
                        .sourceType(BaseResourceData.ResourceSourceType.BLOCK_TRANSFORMATION)
                        .baseFactor(attemptsPerItem)
                        .sourceItems(ingredients)
                        .details("Sprouted from moss via " + CaveFeatures.MOSS_VEGETATION.location())
                        .build());
            }
        }
    }
}