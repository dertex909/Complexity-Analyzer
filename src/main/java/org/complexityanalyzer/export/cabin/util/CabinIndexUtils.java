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

package org.complexityanalyzer.export.cabin.util;

import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.world.item.Item;
import org.complexityanalyzer.export.cabin.api.CabinFormat;
import org.complexityanalyzer.export.cabin.api.LeBuf;
import org.complexityanalyzer.export.cabin.api.XxHash64;
import org.complexityanalyzer.graph.RecipeGraph;

import java.util.Arrays;
import java.util.Collection;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class CabinIndexUtils {

    private CabinIndexUtils() {
    }

    public static byte[] encodeRecipeOutputIndex(int[] firstOffset, int[] count) {
        int n = firstOffset.length;
        var buf = new LeBuf(4 + n * 6);
        buf.i32(n);
        for (int i = 0; i < n; i++) {
            buf.i32(firstOffset[i]);
            buf.u16(Math.min(count[i], 0xFFFF));
        }
        return buf.toByteArray();
    }

    public static <T> byte[] buildHashIndex(ObjectList<T> items, Function<T, String> idExtractor) {
        int n = items.size();
        long[] hashes = new long[n];
        for (int i = 0; i < n; i++) {
            String s = idExtractor.apply(items.get(i));
            hashes[i] = XxHash64.hashString(s != null ? s : "", CabinFormat.XXH64_SEED);
        }
        var order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Long.compareUnsigned(hashes[a], hashes[b]));

        var out = new LeBuf(4 + n * 12);
        out.i32(n);
        for (int i = 0; i < n; i++) {
            int idx = order[i];
            out.i64(hashes[idx]);
            out.i32(idx);
        }
        return out.toByteArray();
    }

    public static <T> byte[] buildUsageSection(ObjectList<T> elements, Reference2IntOpenHashMap<Item> itemIndex, RecipeGraph graph,
                                               BiFunction<RecipeGraph, T, ? extends Collection<Item>> usagesExtractor, int initialFlatCapacity) {
        int n = elements.size();
        if (graph == null) {
            var empty = new LeBuf(8);
            empty.i32(n);
            empty.i32(0);
            return empty.toByteArray();
        }

        int[] firstOffset = new int[n];
        int[] count = new int[n];
        var flat = new LeBuf(initialFlatCapacity);

        for (int i = 0; i < n; i++) {
            var element = elements.get(i);
            var users = usagesExtractor.apply(graph, element);
            if (users == null || users.isEmpty()) {
                firstOffset[i] = CabinFormat.NULL_OFFSET;
                continue;
            }

            firstOffset[i] = flat.position();
            int written = 0;
            for (var u : users) {
                int ui = itemIndex.getInt(u);
                if (ui < 0) continue;
                flat.i32(ui);
                written++;
            }
            count[i] = written;
        }

        var out = new LeBuf(8 + n * 8 + flat.size());
        out.i32(n);
        out.i32(flat.size());
        for (int i = 0; i < n; i++) {
            out.i32(firstOffset[i]);
            out.i32(count[i]);
        }
        out.bytes(flat.array(), 0, flat.size());
        return out.toByteArray();
    }
}