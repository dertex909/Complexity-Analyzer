package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.graph.RecipeGraph;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Vector;

public final class HeuristicCustomRecipeHarvester {

    private HeuristicCustomRecipeHarvester() {
    }

    public static void harvest(RecipeGraph graph, Level level) {
        var classes = getAllLoadedClasses();
        var visited = new ReferenceOpenHashSet<>();

        for (var clazz : classes) {
            if (clazz == null || clazz.getClassLoader() == null) continue;
            try {
                for (var f : clazz.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) try {
                        f.setAccessible(true);
                        var val = f.get(null);
                        if (val != null) scanAndHarvest(val, graph, level, 0, visited);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void scanAndHarvest(Object obj, RecipeGraph graph, Level level, int depth, ReferenceOpenHashSet<Object> visited) {
        if (obj == null || depth > 4 || !visited.add(obj)) return;
        var clazz = obj.getClass();

        if (obj instanceof Iterable<?> iterable) {
            var candidates = new ObjectArrayList<>();
            boolean allRecipes = true;

            for (var item : iterable) {
                if (item == null) continue;
                var detection = AntivirusStyleDetector.detect(item.getClass());
                if (detection.isRecipe()) {
                    candidates.add(item);
                } else {
                    allRecipes = false;
                    break;
                }
            }

            if (allRecipes && !candidates.isEmpty()) {
                var harvester = new FastHarvester();
                for (var recipe : candidates) {
                    try {
                        var items = harvester.harvest(recipe, level);
                        var node = HarvestedRecipeConverter.convert(items, level);
                        if (node != null) graph.addRecipe(node);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }

            for (var item : iterable) scanAndHarvest(item, graph, level, depth + 1, visited);
        } else if (obj instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                scanAndHarvest(entry.getKey(), graph, level, depth + 1, visited);
                scanAndHarvest(entry.getValue(), graph, level, depth + 1, visited);
            }
        } else if (clazz.isArray()) {
            int length = Array.getLength(obj);
            for (int i = 0; i < length; i++) scanAndHarvest(Array.get(obj, i), graph, level, depth + 1, visited);
        } else {
            var meta = RecipeReflection.getMeta(clazz);
            if (meta != null && meta.scanFields != null) for (var f : meta.scanFields) {
                try {
                    var val = f.get(obj);
                    if (val != null) scanAndHarvest(val, graph, level, depth + 1, visited);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static ObjectSet<Class<?>> getAllLoadedClasses() {
        var result = new ObjectOpenHashSet<Class<?>>();
        try {
            var cl = HeuristicCustomRecipeHarvester.class.getClassLoader();
            while (cl != null) {
                for (var f : ClassLoader.class.getDeclaredFields()) {
                    if (f.getType() == Vector.class) try {
                        f.setAccessible(true);
                        var vec = (Vector<?>) f.get(cl);
                        if (vec != null) for (var obj : vec) if (obj instanceof Class<?> clazz) result.add(clazz);
                    } catch (Throwable ignored) {
                    }
                }
                cl = cl.getParent();
            }
        } catch (Throwable ignored) {
        }
        return result;
    }
}