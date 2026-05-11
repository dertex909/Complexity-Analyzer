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

package org.complexityanalyzer.analyzer.resource.providers;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.complexityanalyzer.ComplexityAnalyzer;

public class DimensionRarityAnalyzer {

    private static final Object2DoubleMap<ResourceKey<Level>> DIMENSION_MULTIPLIERS;

    static {
        var map = new Object2DoubleOpenHashMap<ResourceKey<Level>>();
        map.put(Level.OVERWORLD, 1.0);
        map.put(Level.NETHER, 3.0);
        map.put(Level.END, 4.0);
        DIMENSION_MULTIPLIERS = Object2DoubleMaps.unmodifiable(map);
    }

    private static final double END_ISLANDS_MULTIPLIER = 10.0;
    private static final double CUSTOM_DIMENSION_MULTIPLIER = 4.0;

    private final Reference2ObjectMap<EntityType<?>, ResourceKey<Level>> structureSpawnCache = new Reference2ObjectOpenHashMap<>();
    private final Object2ObjectMap<ResourceKey<Biome>, ResourceKey<Level>> biomeToDimensionMap = new Object2ObjectOpenHashMap<>();
    private final Level level;

    public DimensionRarityAnalyzer(Level level) {
        this.level = level;
        analyzeStructureSpawns();
        buildBiomeToDimensionMap();
    }

    private void analyzeStructureSpawns() {
        ComplexityAnalyzer.LOGGER.info("Analyzing structure spawns for dimension detection...");

        var structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);

        int structuresAnalyzed = 0;

        for (var entry : structureRegistry.entrySet()) {
            var structureKey = entry.getKey();
            var structure = entry.getValue();

            var spawnOverrides = structure.spawnOverrides();

            if (!spawnOverrides.isEmpty()) {
                structuresAnalyzed++;

                ResourceKey<Level> dimension = guessDimensionFromStructure(structureKey);

                for (var override : spawnOverrides.values()) {
                    for (var spawner : override.spawns().unwrap()) {
                        structureSpawnCache.putIfAbsent(spawner.type, dimension);
                    }
                }
            }
        }

        ComplexityAnalyzer.LOGGER.info("Structure spawn analysis complete: {} structures, {} unique mob types",
                structuresAnalyzed, structureSpawnCache.size());
    }

    private void buildBiomeToDimensionMap() {
        ComplexityAnalyzer.LOGGER.info("Building biome-to-dimension map from loaded levels...");

        if (!(level instanceof ServerLevel serverLevel)) {
            ComplexityAnalyzer.LOGGER.warn("Level is not ServerLevel, cannot analyze dimensions");
            return;
        }

        MinecraftServer server = serverLevel.getServer();
        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);

        int dimensionsFound = 0;
        int biomeMappings;
        int skippedDimensions = 0;

        for (var dimension : server.getAllLevels()) {
            var dimensionKey = dimension.dimension();
            dimensionsFound++;

            try {
                var chunkGenerator = dimension.getChunkSource().getGenerator();
                var biomeSource = chunkGenerator.getBiomeSource();

                ObjectSet<Holder<Biome>> biomeHolders = new ObjectOpenHashSet<>(biomeSource.possibleBiomes());

                for (var biomeHolder : biomeHolders) {
                    biomeHolder.unwrapKey().ifPresent(biomeKey ->
                            biomeToDimensionMap.putIfAbsent(biomeKey, dimensionKey)
                    );
                }
            } catch (Exception e) {
                skippedDimensions++;
                ComplexityAnalyzer.LOGGER.warn("Failed to analyze dimension {} ({}), skipping: {}",
                        dimensionKey.location(), e.getClass().getSimpleName(), e.getMessage());
            }
        }

        for (var entry : biomeRegistry.entrySet()) {
            var biomeKey = entry.getKey();

            if (!biomeToDimensionMap.containsKey(biomeKey)) {
                var dimension = getDimensionForBiome(biomeKey);
                biomeToDimensionMap.put(biomeKey, dimension);
            }
        }

        biomeMappings = biomeToDimensionMap.size();

        if (skippedDimensions > 0) {
            ComplexityAnalyzer.LOGGER.info("Found {} dimensions (skipped {} problematic), mapped {} biomes",
                    dimensionsFound, skippedDimensions, biomeMappings);
        } else {
            ComplexityAnalyzer.LOGGER.info("Found {} dimensions, mapped {} biomes", dimensionsFound, biomeMappings);
        }
    }

    private ResourceKey<Level> guessDimensionFromStructure(ResourceKey<Structure> structureKey) {
        String structurePath = structureKey.location().getPath();

        if (structurePath.contains("nether") || structurePath.contains("fortress") || structurePath.contains("bastion")) {
            return Level.NETHER;
        }

        if (structurePath.contains("end") || structurePath.contains("city")) return Level.END;

        return Level.OVERWORLD;
    }

    public double getStructureMultiplier(EntityType<?> entityType) {
        var structureDimension = structureSpawnCache.get(entityType);
        if (structureDimension != null) {
            return DIMENSION_MULTIPLIERS.getOrDefault(structureDimension, CUSTOM_DIMENSION_MULTIPLIER);
        }
        return 0.0;
    }

    public double getBiomeMultiplier(EntityType<?> entityType) {
        var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);

        Object2ObjectMap<ResourceKey<Level>, ObjectSet<ResourceKey<Biome>>> dimensionBiomes = new Object2ObjectOpenHashMap<>();

        for (var entry : biomeRegistry.entrySet()) {
            var biomeKey = entry.getKey();
            var biome = entry.getValue();

            if (mobSpawnsInBiome(entityType, biome)) {
                var dimension = biomeToDimensionMap.getOrDefault(biomeKey, getDimensionForBiome(biomeKey));
                dimensionBiomes.computeIfAbsent(dimension, k -> new ObjectOpenHashSet<>()).add(biomeKey);
            }
        }

        if (!dimensionBiomes.isEmpty()) {
            double maxMultiplier = 1.0;

            for (var entry : dimensionBiomes.entrySet()) {
                var dimension = entry.getKey();
                var biomes = entry.getValue();
                double multiplier;

                if (dimension.equals(Level.END)) {
                    var isEndIslands = biomes.stream().anyMatch(DimensionRarityAnalyzer::isEndIslandsBiome);
                    multiplier = isEndIslands ? END_ISLANDS_MULTIPLIER : DIMENSION_MULTIPLIERS.getDouble(Level.END);
                } else {
                    multiplier = DIMENSION_MULTIPLIERS.getOrDefault(dimension, CUSTOM_DIMENSION_MULTIPLIER);
                }

                maxMultiplier = Math.max(maxMultiplier, multiplier);
            }

            return maxMultiplier;
        }

        return 0.0;
    }

    private static boolean mobSpawnsInBiome(EntityType<?> entityType, Biome biome) {
        MobSpawnSettings spawnSettings = biome.getMobSettings();

        for (MobCategory category : MobCategory.values()) {
            WeightedRandomList<MobSpawnSettings.SpawnerData> spawners = spawnSettings.getMobs(category);
            for (MobSpawnSettings.SpawnerData spawner : spawners.unwrap()) if (spawner.type == entityType) return true;
        }

        return false;
    }

    private static ResourceKey<Level> getDimensionForBiome(ResourceKey<Biome> biomeKey) {
        String path = biomeKey.location().getPath();

        if (path.contains("nether") || path.contains("crimson") || path.contains("warped") || path.contains("basalt")) {
            return Level.NETHER;
        }

        if (path.contains("end") || path.contains("the_end")) return Level.END;

        return Level.OVERWORLD;
    }

    private static boolean isEndIslandsBiome(ResourceKey<Biome> biomeKey) {
        String path = biomeKey.location().getPath();
        return path.contains("end_highlands") || path.contains("end_midlands") || path.contains("small_end_islands");
    }
}