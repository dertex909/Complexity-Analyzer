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

package org.complexityanalyzer.geoscan.task;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;
import org.jetbrains.annotations.Nullable;

import static net.minecraft.world.level.block.Blocks.*;

public class ChunkAnalyzer {
    public @Nullable ChunkSnapshot createSnapshot(ChunkAccess chunk) {
        var blockCounts = new Reference2IntOpenHashMap<Block>();
        blockCounts.defaultReturnValue(0);
        var sections = chunk.getSections();

        for (var section : sections) {
            if (section == null || section.hasOnlyAir()) continue;
            section.getStates().count((state, count) -> {
                var block = state.getBlock();
                if (block != AIR && block != CAVE_AIR && block != VOID_AIR) blockCounts.addTo(block, count);
            });
        }

        var finalCounts = new Object2IntOpenHashMap<ResourceLocation>(blockCounts.size());
        finalCounts.defaultReturnValue(0);

        var it = blockCounts.reference2IntEntrySet().fastIterator();
        while (it.hasNext()) {
            var entry = it.next();
            var block = entry.getKey();
            if (block == null) continue;
            var resourceLoc = GameRegistryManager.getBlockId(block);
            if (resourceLoc == null) continue;
            finalCounts.put(resourceLoc, entry.getIntValue());
        }

        return new ChunkSnapshot(chunk.getPos().x, chunk.getPos().z, finalCounts);
    }
}