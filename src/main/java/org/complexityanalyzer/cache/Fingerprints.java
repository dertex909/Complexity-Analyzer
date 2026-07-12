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

package org.complexityanalyzer.cache;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.neoforged.fml.ModList;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.geoscan.GeoDatabase;

public final class Fingerprints {

    public static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private Fingerprints() {
    }

    public static long fnv(long h, String s) {
        if (s == null) return fnvLong(h, 0);
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= FNV_PRIME;
        }
        return h;
    }

    public static long fnvLong(long h, long v) {
        h ^= v;
        h *= FNV_PRIME;
        return h;
    }

    public static long hashAllBlocks() {
        var ids = new ObjectArrayList<String>();
        for (var block : GameRegistryManager.getAllBlocks()) {
            var id = GameRegistryManager.getBlockId(block);
            ids.add(id != null ? id.toString() : "?");
        }
        return hashSortedIds(ids);
    }

    public static long hashAllItems() {
        var ids = new ObjectArrayList<String>();
        for (var item : GameRegistryManager.getAllItems()) {
            var id = GameRegistryManager.getItemId(item);
            ids.add(id != null ? id.toString() : "?");
        }
        return hashSortedIds(ids);
    }

    public static long hashAllEntities() {
        var ids = new ObjectArrayList<String>();
        for (var type : GameRegistryManager.getAllEntityTypes()) {
            var id = GameRegistryManager.getEntityTypeId(type);
            ids.add(id != null ? id.toString() : "?");
        }
        return hashSortedIds(ids);
    }

    public static long hashMods() {
        var keys = new ObjectArrayList<String>();
        for (var mod : ModList.get().getMods()) keys.add(mod.getModId() + "@" + mod.getVersion());
        return hashSortedIds(keys);
    }

    private static long hashSortedIds(ObjectArrayList<String> ids) {
        ids.sort(null);
        long h = FNV_OFFSET;
        for (var id : ids) h = fnv(h, id);
        return h;
    }

    public static long hashGeo(GeoDatabase geo) {
        if (geo == null || !geo.isLoaded()) return 0L;
        long acc = 0L;
        try {
            for (var dimEntry : geo.getAllDimensionData().entrySet()) {
                var dimId = dimEntry.getKey().toString();
                for (var biomeEntry : dimEntry.getValue().entrySet()) {
                    var data = biomeEntry.getValue();
                    long h = FNV_OFFSET;
                    h = fnv(h, dimId);
                    h = fnv(h, biomeEntry.getKey().toString());
                    h = fnvLong(h, data.getTotalBlocks());
                    for (var bc : data.getInternalBlockCounts().reference2LongEntrySet()) {
                        long e = FNV_OFFSET;
                        var id = GameRegistryManager.getBlockId(bc.getKey());
                        e = fnv(e, id != null ? id.toString() : "?");
                        e = fnvLong(e, bc.getLongValue());
                        h += e;
                    }
                    acc += h;
                }
            }
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[Cache] Failed to hash geo data: {}", t.toString());
            return 0L;
        }
        return acc;
    }
}