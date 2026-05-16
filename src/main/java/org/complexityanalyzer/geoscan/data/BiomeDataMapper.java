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

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.complexityanalyzer.core.GameRegistryManager;

public class BiomeDataMapper {
    public void prepareForSave(BiomeScanData data) {
        data.serializableBlockCounts.clear();

        ObjectIterator<Reference2LongMap.Entry<Block>> it = data.getInternalBlockCounts().reference2LongEntrySet().fastIterator();
        while (it.hasNext()) {
            Reference2LongMap.Entry<Block> entry = it.next();
            Block block = entry.getKey();
            if (block == Blocks.AIR) continue;
            ResourceLocation key = GameRegistryManager.getBlockId(block);
            if (key != null) data.serializableBlockCounts.put(key.toString(), entry.getLongValue());
        }

        LongOpenHashSet set = data.getInternalScannedChunksSet();
        if (set != null) {
            LongArrayList list = new LongArrayList(set.size());
            LongIterator chunkIt = set.iterator();
            while (chunkIt.hasNext()) list.add(chunkIt.nextLong());
            data.scannedChunks = list;
        } else {
            data.scannedChunks = new LongArrayList();
        }
    }

    public void afterLoad(BiomeScanData data) {
        data.getInternalBlockCounts().clear();
        if (data.serializableBlockCounts != null) {
            ObjectIterator<Object2LongMap.Entry<String>> it = data.serializableBlockCounts.object2LongEntrySet().fastIterator();
            while (it.hasNext()) {
                Object2LongMap.Entry<String> entry = it.next();
                Block block = GameRegistryManager.getBlock(ResourceLocation.parse(entry.getKey()));
                if (block != null && block != Blocks.AIR) {
                    data.getInternalBlockCounts().put(block, entry.getLongValue());
                }
            }
        }

        LongOpenHashSet target = new LongOpenHashSet();
        LongArrayList list = data.scannedChunks;
        if (list != null) for (int i = 0, n = list.size(); i < n; i++) target.add(list.getLong(i));
        data.setInternalScannedChunksSet(target);
    }
}