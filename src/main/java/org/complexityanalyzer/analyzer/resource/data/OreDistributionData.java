package org.complexityanalyzer.analyzer.resource.data;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;

import java.util.*;

public class OreDistributionData {
    private final Block block;
    private final Set<ResourceKey<Level>> dimensions;
    private final Map<ResourceKey<Biome>, BiomeOccurrence> biomeOccurrences;
    private int minY = Integer.MAX_VALUE;
    private int maxY = Integer.MIN_VALUE;
    private double globalRarity = 0.0;

    public OreDistributionData(Block block) {
        this.block = block;
        this.dimensions = new HashSet<>();
        this.biomeOccurrences = new HashMap<>();
    }

    public void addDimension(ResourceKey<Level> dimension) {
        dimensions.add(dimension);
    }

    public void addBiomeOccurrence(ResourceKey<Biome> biome, int minY, int maxY, double frequency) {
        biomeOccurrences.put(biome, new BiomeOccurrence(minY, maxY, frequency));

        this.minY = Math.min(this.minY, minY);
        this.maxY = Math.max(this.maxY, maxY);

        updateGlobalRarity();
    }

    private void updateGlobalRarity() {
        if (biomeOccurrences.isEmpty()) {
            globalRarity = 0.0;
            return;
        }

        double sum = biomeOccurrences.values().stream()
                .mapToDouble(BiomeOccurrence::frequency)
                .sum();

        globalRarity = sum / biomeOccurrences.size();
    }

    public Block getBlock() {
        return block;
    }

    public Set<ResourceKey<Level>> getDimensions() {
        return Collections.unmodifiableSet(dimensions);
    }

    public int getMinY() {
        return minY == Integer.MAX_VALUE ? 0 : minY;
    }

    public int getMaxY() {
        return maxY == Integer.MIN_VALUE ? 320 : maxY;
    }

    public double getGlobalRarity() {
        return globalRarity;
    }

    public int getBiomeCount() {
        return biomeOccurrences.size();
    }

    public boolean isFoundInDimension(ResourceKey<Level> dimension) {
        return dimensions.contains(dimension);
    }

    public record BiomeOccurrence(int minY, int maxY, double frequency) {}
}