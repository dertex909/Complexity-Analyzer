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

package org.complexityanalyzer.geoscan.data;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.resources.ResourceLocation;
import org.complexityanalyzer.core.GameRegistryManager;

import static net.minecraft.world.level.block.Blocks.AIR;

public class BiomeDataMapper {
    public void prepareForSave(BiomeScanData data) {
        data.serializableBlockCounts.clear();

        var it = data.getInternalBlockCounts().reference2LongEntrySet().fastIterator();
        while (it.hasNext()) {
            var entry = it.next();
            var block = entry.getKey();
            if (block == AIR) continue;
            var key = GameRegistryManager.getBlockId(block);
            if (key != null) data.serializableBlockCounts.put(key.toString(), entry.getLongValue());
        }

        var set = data.getInternalScannedChunksSet();
        if (set != null) {
            var list = new LongArrayList(set.size());
            var chunkIt = set.iterator();
            while (chunkIt.hasNext()) list.add(chunkIt.nextLong());
            data.scannedChunks = list;
        } else {
            data.scannedChunks = new LongArrayList();
        }
    }

    public void afterLoad(BiomeScanData data) {
        data.getInternalBlockCounts().clear();
        if (data.serializableBlockCounts != null) {
            var it = data.serializableBlockCounts.object2LongEntrySet().fastIterator();
            while (it.hasNext()) {
                var entry = it.next();
                var block = GameRegistryManager.getBlock(ResourceLocation.parse(entry.getKey()));
                if (block != null && block != AIR) data.getInternalBlockCounts().put(block, entry.getLongValue());
            }
        }

        var target = new LongOpenHashSet();
        var list = data.scannedChunks;
        if (list != null) for (int i = 0, n = list.size(); i < n; i++) target.add(list.getLong(i));
        data.setInternalScannedChunksSet(target);
    }
}