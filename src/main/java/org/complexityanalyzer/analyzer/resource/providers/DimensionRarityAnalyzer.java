package org.complexityanalyzer.analyzer.resource.providers;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;
import org.complexityanalyzer.ComplexityAnalyzer;

import java.util.*;

public class DimensionRarityAnalyzer {

    private static final Map<ResourceKey<Level>, Double> DIMENSION_MULTIPLIERS = Map.of(
            Level.OVERWORLD, 1.0,
            Level.NETHER, 3.0,
            Level.END, 4.0
    );

    private static final double END_ISLANDS_MULTIPLIER = 10.0;
    private static final double CUSTOM_DIMENSION_MULTIPLIER = 4.0;

    private final Map<EntityType<?>, ResourceKey<Level>> structureSpawnCache = new HashMap<>();
    private final Map<ResourceKey<Biome>, ResourceKey<Level>> biomeToDimensionMap = new HashMap<>();
    private final Level level;

    public DimensionRarityAnalyzer(Level level) {
        this.level = level;
        analyzeStructureSpawns();
        buildBiomeToDimensionMap();
    }

    private void analyzeStructureSpawns() {
        ComplexityAnalyzer.LOGGER.info("Analyzing structure spawns for dimension detection...");

        Registry<Structure> structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);

        int structuresAnalyzed = 0;
        int mobsFound = 0;

        for (Map.Entry<ResourceKey<Structure>, Structure> entry : structureRegistry.entrySet()) {
            ResourceKey<Structure> structureKey = entry.getKey();
            Structure structure = entry.getValue();

            Map<MobCategory, StructureSpawnOverride> spawnOverrides = structure.spawnOverrides();

            if (!spawnOverrides.isEmpty()) {
                structuresAnalyzed++;

                ResourceKey<Level> dimension = guessDimensionFromStructure(structureKey, structure, biomeRegistry);

                for (StructureSpawnOverride override : spawnOverrides.values()) {
                    for (MobSpawnSettings.SpawnerData spawner : override.spawns().unwrap()) {
                        structureSpawnCache.putIfAbsent(spawner.type, dimension);
                        mobsFound++;
                    }
                }
            }
        }

        ComplexityAnalyzer.LOGGER.info("Structure spawn analysis complete: {} structures, {} unique mob types",
                structuresAnalyzed, structureSpawnCache.size());
    }

    private void buildBiomeToDimensionMap() {
        ComplexityAnalyzer.LOGGER.info("Building biome-to-dimension map from loaded levels...");

        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            ComplexityAnalyzer.LOGGER.warn("Level is not ServerLevel, cannot analyze dimensions");
            return;
        }

        net.minecraft.server.MinecraftServer server = serverLevel.getServer();

        int dimensionsFound = 0;

        for (net.minecraft.server.level.ServerLevel dimension : server.getAllLevels()) {
            ResourceKey<Level> dimensionKey = dimension.dimension();
            dimensionsFound++;

            // Пока просто логируем измерения
            ComplexityAnalyzer.LOGGER.debug("Found dimension: {}", dimensionKey.location());
        }

        ComplexityAnalyzer.LOGGER.info("Found {} dimensions", dimensionsFound);
    }

    private ResourceKey<Level> guessDimensionFromStructure(
            ResourceKey<Structure> structureKey,
            Structure structure,
            Registry<Biome> biomeRegistry
    ) {
        String structurePath = structureKey.location().getPath();

        if (structurePath.contains("nether") || structurePath.contains("fortress") || structurePath.contains("bastion")) {
            return Level.NETHER;
        }

        if (structurePath.contains("end") || structurePath.contains("city")) {
            return Level.END;
        }

        return Level.OVERWORLD;
    }

    public double getMobRarityMultiplier(EntityType<?> entityType) {
        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);

        Map<ResourceKey<Level>, Set<ResourceKey<Biome>>> dimensionBiomes = new HashMap<>();

        for (Map.Entry<ResourceKey<Biome>, Biome> entry : biomeRegistry.entrySet()) {
            ResourceKey<Biome> biomeKey = entry.getKey();
            Biome biome = entry.getValue();

            if (mobSpawnsInBiome(entityType, biome)) {
                ResourceKey<Level> dimension = biomeToDimensionMap.getOrDefault(
                        biomeKey,
                        getDimensionForBiome(biomeKey)
                );
                dimensionBiomes.computeIfAbsent(dimension, k -> new HashSet<>())
                        .add(biomeKey);
            }
        }

        if (!dimensionBiomes.isEmpty()) {
            double maxMultiplier = 1.0;

            for (Map.Entry<ResourceKey<Level>, Set<ResourceKey<Biome>>> entry : dimensionBiomes.entrySet()) {
                ResourceKey<Level> dimension = entry.getKey();
                Set<ResourceKey<Biome>> biomes = entry.getValue();

                double multiplier;

                if (dimension.equals(Level.END)) {
                    boolean isEndIslands = biomes.stream()
                            .anyMatch(DimensionRarityAnalyzer::isEndIslandsBiome);

                    multiplier = isEndIslands ? END_ISLANDS_MULTIPLIER : DIMENSION_MULTIPLIERS.get(Level.END);
                } else {
                    multiplier = DIMENSION_MULTIPLIERS.getOrDefault(dimension, CUSTOM_DIMENSION_MULTIPLIER);
                }

                maxMultiplier = Math.max(maxMultiplier, multiplier);
            }

            return maxMultiplier;
        }

        ResourceKey<Level> structureDimension = structureSpawnCache.get(entityType);
        if (structureDimension != null) {
            double multiplier = DIMENSION_MULTIPLIERS.getOrDefault(structureDimension, CUSTOM_DIMENSION_MULTIPLIER);

            ComplexityAnalyzer.LOGGER.debug("Mob {} spawns in structure (dimension: {}), multiplier: {}x",
                    entityType.getDescription().getString(),
                    structureDimension.location(),
                    multiplier);

            return multiplier;
        }

        ComplexityAnalyzer.LOGGER.debug("Mob {} has no known spawns, fireImmune: {}, using fallback multiplier",
                entityType.getDescription().getString(),
                entityType.fireImmune());

        double fallback = entityType.fireImmune() ? DIMENSION_MULTIPLIERS.get(Level.NETHER) : 10.0;

        ComplexityAnalyzer.LOGGER.debug("Fallback multiplier for {}: {}x",
                entityType.getDescription().getString(),
                fallback);

        return fallback;
    }

    public double getStructureMultiplier(EntityType<?> entityType) {
        ResourceKey<Level> structureDimension = structureSpawnCache.get(entityType);
        if (structureDimension != null) {
            return DIMENSION_MULTIPLIERS.getOrDefault(structureDimension, CUSTOM_DIMENSION_MULTIPLIER);
        }
        return 0.0;
    }

    public double getBiomeMultiplier(EntityType<?> entityType) {
        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);

        Map<ResourceKey<Level>, Set<ResourceKey<Biome>>> dimensionBiomes = new HashMap<>();

        for (Map.Entry<ResourceKey<Biome>, Biome> entry : biomeRegistry.entrySet()) {
            ResourceKey<Biome> biomeKey = entry.getKey();
            Biome biome = entry.getValue();

            if (mobSpawnsInBiome(entityType, biome)) {
                ResourceKey<Level> dimension = biomeToDimensionMap.getOrDefault(
                        biomeKey,
                        getDimensionForBiome(biomeKey)
                );
                dimensionBiomes.computeIfAbsent(dimension, k -> new HashSet<>())
                        .add(biomeKey);
            }
        }

        if (!dimensionBiomes.isEmpty()) {
            double maxMultiplier = 1.0;

            for (Map.Entry<ResourceKey<Level>, Set<ResourceKey<Biome>>> entry : dimensionBiomes.entrySet()) {
                ResourceKey<Level> dimension = entry.getKey();
                Set<ResourceKey<Biome>> biomes = entry.getValue();

                double multiplier;

                if (dimension.equals(Level.END)) {
                    boolean isEndIslands = biomes.stream()
                            .anyMatch(DimensionRarityAnalyzer::isEndIslandsBiome);

                    multiplier = isEndIslands ? END_ISLANDS_MULTIPLIER : DIMENSION_MULTIPLIERS.get(Level.END);
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
            net.minecraft.util.random.WeightedRandomList<MobSpawnSettings.SpawnerData> spawners = spawnSettings.getMobs(category);

            for (MobSpawnSettings.SpawnerData spawner : spawners.unwrap()) {
                if (spawner.type == entityType) {
                    return true;
                }
            }
        }

        return false;
    }

    private static ResourceKey<Level> getDimensionForBiome(ResourceKey<Biome> biomeKey) {
        String path = biomeKey.location().getPath();

        if (path.contains("nether") || path.contains("crimson") || path.contains("warped") || path.contains("basalt")) {
            return Level.NETHER;
        }

        if (path.contains("end") || path.contains("the_end")) {
            return Level.END;
        }

        return Level.OVERWORLD;
    }

    private static boolean isEndIslandsBiome(ResourceKey<Biome> biomeKey) {
        String path = biomeKey.location().getPath();
        return path.contains("end_highlands") ||
                path.contains("end_midlands") ||
                path.contains("small_end_islands");
    }
}