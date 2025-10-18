package org.complexityanalyzer.analyzer.resource.providers;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TheoreticalDistributionProvider {

    private final Map<Block, OreDistributionData> distributionData = new ConcurrentHashMap<>();
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
        List<HolderSet<PlacedFeature>> generationSteps = biomeHolder.value().getGenerationSettings().features();
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
        Set<Block> blocks = extractBlocks(configuredFeatureHolder.value());

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

    private Set<Block> extractBlocks(ConfiguredFeature<?, ?> feature) {
        Set<Block> blocks = new HashSet<>();
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

    public Optional<OreDistributionData> getDistribution(Block block) {
        return Optional.ofNullable(distributionData.get(block));
    }

    public boolean isInitialized() { return initialized; }

    private record HeightRange(int minY, int maxY) {}
}