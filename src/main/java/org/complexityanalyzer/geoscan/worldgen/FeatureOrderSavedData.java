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

package org.complexityanalyzer.geoscan.worldgen;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.jetbrains.annotations.NotNull;

public class FeatureOrderSavedData extends SavedData {
    private static final String DATA_NAME = ComplexityAnalyzer.MODID + "_feature_order";
    private static final String TAG_NEXT_SEQUENCE = "next_sequence";
    private static final String TAG_ENTRIES = "entries";

    private final Long2LongOpenHashMap featureSequenceByChunk = new Long2LongOpenHashMap();
    private long nextSequence = 1L;

    public FeatureOrderSavedData() {
        featureSequenceByChunk.defaultReturnValue(-1L);
    }

    public static SavedData.Factory<FeatureOrderSavedData> factory() {
        return new SavedData.Factory<>(FeatureOrderSavedData::new, FeatureOrderSavedData::load, DataFixTypes.LEVEL);
    }

    public static FeatureOrderSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public static FeatureOrderSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        FeatureOrderSavedData data = new FeatureOrderSavedData();
        data.nextSequence = Math.max(1L, tag.getLong(TAG_NEXT_SEQUENCE));

        long[] rawEntries = tag.getLongArray(TAG_ENTRIES);
        for (int i = 0; i + 1 < rawEntries.length; i += 2) {
            data.featureSequenceByChunk.put(rawEntries[i], rawEntries[i + 1]);
        }

        if (data.nextSequence == 1L && !data.featureSequenceByChunk.isEmpty()) {
            long maxSeen = 0L;
            for (long sequence : data.featureSequenceByChunk.values()) {
                if (sequence > maxSeen) maxSeen = sequence;
            }
            data.nextSequence = maxSeen + 1L;
        }

        return data;
    }

    @Override
    public @NotNull CompoundTag save(CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        tag.putLong(TAG_NEXT_SEQUENCE, nextSequence);

        long[] rawEntries = new long[featureSequenceByChunk.size() * 2];
        int index = 0;

        for (Long2LongOpenHashMap.Entry entry : featureSequenceByChunk.long2LongEntrySet()) {
            rawEntries[index++] = entry.getLongKey();
            rawEntries[index++] = entry.getLongValue();
        }

        tag.putLongArray(TAG_ENTRIES, rawEntries);
        return tag;
    }

    public synchronized long getSequence(ChunkPos pos) {
        return featureSequenceByChunk.get(pos.toLong());
    }

    public synchronized void recordIfAbsent(ChunkPos pos) {
        long key = pos.toLong();
        long existing = featureSequenceByChunk.get(key);
        if (existing != -1L) return;
        long sequence = nextSequence++;
        featureSequenceByChunk.put(key, sequence);
        setDirty();
    }
}
