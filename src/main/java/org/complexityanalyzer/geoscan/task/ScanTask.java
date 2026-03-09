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
        if (dimCompare != 0) return dimCompare;
        return this.biome.location().toString().compareTo(other.biome.location().toString());
    }
}