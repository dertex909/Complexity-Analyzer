package org.complexityanalyzer.harvest.modules;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.complexityanalyzer.harvest.RecipeMetadata;

import java.util.Map;
import java.util.Optional;

public final class DeepGraphTraverser {

    private DeepGraphTraverser() {
    }

    @FunctionalInterface
    public interface Visitor {
        boolean visit(Object node, int depth);
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
}