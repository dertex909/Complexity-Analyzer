package org.complexityanalyzer.geoscan.task;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

public record ScanTask(
        ResourceKey<Level> dimension,
        ResourceKey<Biome> biome,
        int chunksToFind
) implements Comparable<ScanTask> {

    @Override
    public int compareTo(ScanTask other) {
        int dimCompare = this.dimension.location().toString().compareTo(other.dimension.location().toString());
        if (dimCompare != 0) {
            return dimCompare;
        }
        return this.biome.location().toString().compareTo(other.biome.location().toString());
    }
}