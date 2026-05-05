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

package org.complexityanalyzer.analyzer.resource.providers;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.*;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.data.OreDistributionData;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class TheoreticalDistributionProvider {

    private final Reference2ObjectMap<Block, OreDistributionData> distributionData = new Reference2ObjectOpenHashMap<>();
    private boolean initialized = false;

    public void initialize(Level level) {
        if (initialized) return;
        ComplexityAnalyzer.LOGGER.debug("Initializing TheoreticalDistributionProvider (WorldGen Analysis)...");

        if (!(level instanceof ServerLevel serverLevel)) {
            ComplexityAnalyzer.LOGGER.warn("Cannot analyze worldgen - not a server level. Theoretical analysis will be unavailable.");
            initialized = true;
            return;
        }

        try {
            analyzeServerLevel(serverLevel);
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("Error during worldgen analysis", e);
        }

        initialized = true;
        ComplexityAnalyzer.LOGGER.info("TheoreticalDistributionProvider initialized: {} blocks found in worldgen config.", distributionData.size());
    }

    private void analyzeServerLevel(ServerLevel level) {
        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        for (Holder.Reference<Biome> biomeHolder : biomeRegistry.holders().toList()) {
            analyzeBiome(level.dimension(), biomeHolder.key(), biomeHolder);
        }
    }

    private void analyzeBiome(ResourceKey<Level> dimension, ResourceKey<Biome> biomeKey, Holder<Biome> biomeHolder) {
        var generationSteps = biomeHolder.value().getGenerationSettings().features();
        int oreStepIndex = GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();

        if (oreStepIndex < generationSteps.size()) {
            for (Holder<PlacedFeature> featureHolder : generationSteps.get(oreStepIndex)) {
                analyzePlacedFeature(dimension, biomeKey, featureHolder);
            }
        }
    }

    private void analyzePlacedFeature(ResourceKey<Level> dimension, ResourceKey<Biome> biomeKey, Holder<PlacedFeature> featureHolder) {
        PlacedFeature placedFeature = featureHolder.value();
        HeightRange heightRange = extractHeightRange(placedFeature.placement());
        int count = extractCount(placedFeature.placement());
        Holder<ConfiguredFeature<?, ?>> configuredFeatureHolder = placedFeature.feature();
        ObjectSet<Block> blocks = extractBlocks(configuredFeatureHolder.value());

        if (blocks.isEmpty()) return;

        double frequency = calculateFrequency(count);
        for (Block block : blocks) {
            OreDistributionData data = distributionData.computeIfAbsent(block, OreDistributionData::new);
            data.addDimension(dimension);
            data.addBiomeOccurrence(biomeKey, heightRange.minY(), heightRange.maxY(), frequency);
        }
    }

    private HeightRange extractHeightRange(List<PlacementModifier> ignoredPlacements) {
        return new HeightRange(-64, 128);
    }

    private int extractCount(List<PlacementModifier> ignoredPlacements) {
        return 4;
    }

    private ObjectSet<Block> extractBlocks(ConfiguredFeature<?, ?> feature) {
        ObjectSet<Block> blocks = new ObjectOpenHashSet<>();
        if (feature.feature() == Feature.ORE && feature.config() instanceof OreConfiguration oreConfig) {
            for (OreConfiguration.TargetBlockState target : oreConfig.targetStates) {
                blocks.add(target.state.getBlock());
            }
        }
        return blocks;
    }

    private double calculateFrequency(int count) {
        return Math.min(1.0, count * 0.1);
    }

    @Nullable
    public OreDistributionData getDistribution(Block block) {
        return distributionData.get(block);
    }

    public boolean isInitialized() {
        return initialized;
    }

    private record HeightRange(int minY, int maxY) {
    }
}