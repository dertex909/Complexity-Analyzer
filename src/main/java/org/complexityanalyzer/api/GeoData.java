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

package org.complexityanalyzer.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.OptionalDouble;
import java.util.Set;

/**
 * Read-only access to the world geo-scan results: how common each block is within each scanned biome. This is
 * the measured block composition the analyzer uses to price ores and naturally-occurring blocks.
 *
 * <p>Note: the scan measures block <em>composition</em> (fraction of blocks of a type within sampled chunks of
 * a biome), not biome area. A scan runs asynchronously after server start; until it completes
 * {@link #isScanned()} is {@code false} and share lookups return empty.
 */
public interface GeoData {

    /**
     * @return {@code true} once geo-scan data has been loaded and is queryable.
     */
    boolean isScanned();

    /**
     * @return the scanned dimension ids (e.g. {@code minecraft:overworld}).
     */
    Set<ResourceLocation> getScannedDimensions();

    /**
     * @return the scanned biome ids within the given dimension.
     */
    Set<ResourceLocation> getScannedBiomes(ResourceLocation dimension);

    /**
     * @return the fraction (0..1) of blocks in the given biome that are this block, or empty if the dimension
     * /biome/block was not scanned. E.g. 0.001 means one in a thousand scanned blocks was this block.
     */
    OptionalDouble getBlockShare(ResourceLocation dimension, ResourceLocation biome, Block block);

    /**
     * @return the rarest-biome share for the block across all scanned dimensions/biomes — i.e. the highest
     * share found anywhere (the easiest place to find it), or empty if never scanned.
     */
    OptionalDouble getBestBlockShare(Block block);
}