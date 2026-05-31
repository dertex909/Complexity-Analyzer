package org.complexityanalyzer.harvest.modules;

import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.harvest.HarvestedItems;
import org.complexityanalyzer.harvest.RecipeReflection;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class DeepIngredientCollector {

    private DeepIngredientCollector() {
    }

    public static void collect(Object obj, ObjectList<HarvestedItems.HarvestedIngredient> acc, int depth, ReferenceOpenHashSet<Object> visited) {
        if (obj == null || depth > 8) return;
        switch (obj) {
            case Optional<?> opt -> {
                if (visited.add(opt)) opt.ifPresent(o -> collect(o, acc, depth + 1, visited));
                return;
            }
            case SizedIngredient si when si.count() > 0 -> {
                var ing = si.ingredient();
                if (!ing.isEmpty()) if (HarvestUtility.isNotDuplicateIngredient(acc, ing))
                    acc.add(new HarvestedItems.HarvestedIngredient(ing, si.count()));
                return;
            }
            case Ingredient ing when !ing.isEmpty() -> {
                if (HarvestUtility.isNotDuplicateIngredient(acc, ing))
                    acc.add(new HarvestedItems.HarvestedIngredient(ing, 1));
                return;
            }
            case Iterable<?> coll when HarvestUtility.isTooSmall(coll) -> {
                if (visited.add(coll)) for (var item : coll) collect(item, acc, depth + 1, visited);
                return;
            }
            case Map<?, ?> map when HarvestUtility.isTooSmall(map) -> {
                if (visited.add(map)) for (var e : map.entrySet()) {
                    collect(e.getKey(), acc, depth + 1, visited);
                    collect(e.getValue(), acc, depth + 1, visited);
                }
                return;
            }
            case Object[] arr when arr.length <= 50 -> {
                if (visited.add(arr)) for (var item : arr) collect(item, acc, depth + 1, visited);
                return;
            }
            default -> {
            }
        }
        if (obj instanceof ItemStack || obj instanceof FluidStack) return;
        if (HarvestUtility.isTerminal(obj)) return;
        if (!visited.add(obj)) return;
        var meta = RecipeReflection.getMeta(obj.getClass());

        if (depth <= 5) {
            for (int i = 0; i < meta.allMethods.length; i++) {
                var m = meta.allMethods[i];
                var h = meta.allHandles[i];
                if (h == null) continue;
                try {
                    var rt = m.getReturnType();
                    String rtName = rt.getName();

                    if (Ingredient.class.isAssignableFrom(rt) || rt.isArray() || Iterable.class.isAssignableFrom(rt)
                            || Stream.class.isAssignableFrom(rt) || rtName.contains("Ingredient")) {

                        String mName = m.getName();
                        if (mName.equals("toString") || mName.equals("hashCode") || mName.equals("getClass")) continue;

                        var val = h.invoke(obj);
                        if (val != null && val != obj) if (val instanceof Stream<?> stream) {
                            stream.limit(100).forEach(element -> collect(element, acc, depth + 1, visited));
                        } else {
                            collect(val, acc, depth + 1, visited);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        for (var f : meta.scanFields) {
            try {
                collect(f.get(obj), acc, depth + 1, visited);
            } catch (Throwable ignored) {
            }
        }
    }
}