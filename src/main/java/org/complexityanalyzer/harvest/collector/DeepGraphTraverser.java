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

package org.complexityanalyzer.harvest.collector;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.complexityanalyzer.harvest.inspector.RecipeMetadata;

import java.util.Map;
import java.util.Optional;

public final class DeepGraphTraverser {

    private DeepGraphTraverser() {
    }

    public static void traverse(Object obj, int depth, ReferenceOpenHashSet<Object> visited, Visitor visitor) {
        if (obj == null || depth > 8) return;
        if (visitor.visit(obj, depth)) return;

        switch (obj) {
            case Optional<?> opt -> {
                if (visited.add(opt)) opt.ifPresent(o -> traverse(o, depth + 1, visited, visitor));
                return;
            }
            case Either<?, ?> either -> {
                if (visited.add(either)) {
                    either.left().ifPresent(o -> traverse(o, depth + 1, visited, visitor));
                    either.right().ifPresent(o -> traverse(o, depth + 1, visited, visitor));
                }
                return;
            }
            case Pair<?, ?> pair -> {
                if (visited.add(pair)) {
                    traverse(pair.getFirst(), depth + 1, visited, visitor);
                    traverse(pair.getSecond(), depth + 1, visited, visitor);
                }
                return;
            }
            case Iterable<?> coll when HarvestUtility.isTooSmall(coll) -> {
                if (visited.add(coll)) for (var item : coll) traverse(item, depth + 1, visited, visitor);
                return;
            }
            case Map<?, ?> map when HarvestUtility.isTooSmall(map) -> {
                if (visited.add(map)) for (var e : map.entrySet()) {
                    traverse(e.getKey(), depth + 1, visited, visitor);
                    traverse(e.getValue(), depth + 1, visited, visitor);
                }
                return;
            }
            case Object[] arr when arr.length <= 50 -> {
                if (visited.add(arr)) for (var item : arr) traverse(item, depth + 1, visited, visitor);
                return;
            }
            default -> {
            }
        }

        if (HarvestUtility.isTerminal(obj)) return;
        if (!visited.add(obj)) return;

        var meta = RecipeMetadata.getMeta(obj.getClass());
        for (var f : meta.scanFields()) {
            try {
                traverse(f.get(obj), depth + 1, visited, visitor);
            } catch (Throwable ignored) {
            }
        }
    }

    @FunctionalInterface
    public interface Visitor {
        boolean visit(Object node, int depth);
    }
}