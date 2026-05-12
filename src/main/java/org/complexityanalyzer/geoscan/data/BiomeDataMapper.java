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

package org.complexityanalyzer.geoscan.data;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.complexityanalyzer.core.GameRegistryManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicLong;

public class BiomeDataMapper {
    public void prepareForSave(BiomeScanData data) {
        data.serializableBlockCounts.clear();
        data.getInternalBlockCounts().forEach((block, count) -> {
            if (block != Blocks.AIR) {
                ResourceLocation key = GameRegistryManager.getBlockId(block);
                if (key != null) data.serializableBlockCounts.put(key.toString(), count.get());
            }
        });

        if (data.getInternalScannedChunksSet() != null) {
            data.scannedChunks = new ArrayList<>(data.getInternalScannedChunksSet());
        } else {
            data.scannedChunks = new ArrayList<>();
        }
    }

    public void afterLoad(BiomeScanData data) {
        data.getInternalBlockCounts().clear();
        if (data.serializableBlockCounts != null) data.serializableBlockCounts.forEach((key, count) -> {
            Block block = GameRegistryManager.getBlock(ResourceLocation.parse(key));
            if (block != null && block != Blocks.AIR) data.getInternalBlockCounts().put(block, new AtomicLong(count));
        });

        if (data.scannedChunks != null) {
            data.setInternalScannedChunksSet(new HashSet<>(data.scannedChunks));
        } else {
            data.setInternalScannedChunksSet(new HashSet<>());
        }
    }
}