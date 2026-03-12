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

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public class ChunkAnalyzer {

    public @Nullable ChunkSnapshot createSnapshot(ChunkAccess chunk) {
        Reference2IntOpenHashMap<Block> blockCounts = new Reference2IntOpenHashMap<>();
        LevelChunkSection[] sections = chunk.getSections();

        for (LevelChunkSection section : sections) {
            if (section == null || section.hasOnlyAir()) continue;

            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        Block block = section.getBlockState(x, y, z).getBlock();
                        if (block != Blocks.AIR && block != Blocks.CAVE_AIR && block != Blocks.VOID_AIR)
                            blockCounts.addTo(block, 1);
                    }
                }
            }
        }

        if (blockCounts.isEmpty()) return null;

        Map<String, Integer> finalCounts = new HashMap<>(blockCounts.size());
        for (Reference2IntOpenHashMap.Entry<Block> entry : blockCounts.reference2IntEntrySet()) {
            finalCounts.put(BuiltInRegistries.BLOCK.getKey(entry.getKey()).toString(), entry.getIntValue());
        }

        return new ChunkSnapshot(chunk.getPos().x, chunk.getPos().z, finalCounts);
    }
}