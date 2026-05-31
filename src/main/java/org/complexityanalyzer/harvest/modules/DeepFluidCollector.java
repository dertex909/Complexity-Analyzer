package org.complexityanalyzer.harvest.modules;

import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import org.complexityanalyzer.harvest.RecipeReflection;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class DeepFluidCollector {

    private DeepFluidCollector() {
    }

    public static void collect(Object obj, ObjectList<FluidStack> acc, int depth, ReferenceOpenHashSet<Object> visited) {
        if (obj == null || depth > 8) return;
        switch (obj) {
            case Optional<?> opt -> {
                if (visited.add(opt)) opt.ifPresent(o -> collect(o, acc, depth + 1, visited));
                return;
            }
            case SizedFluidIngredient sfi -> {
                for (var fs : sfi.getFluids()) if (!fs.isEmpty()) acc.add(fs.copy());
                return;
            }
            case FluidStack fs when !fs.isEmpty() -> {
                acc.add(fs.copy());
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
        if (obj instanceof ItemStack || obj instanceof Ingredient) return;
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

                    if (FluidStack.class.isAssignableFrom(rt) || rt.isArray() || Iterable.class.isAssignableFrom(rt)
                            || Stream.class.isAssignableFrom(rt) || rtName.contains("Fluid")) {

                        String mName = m.getName();
                        if (mName.equals("toString") || mName.equals("hashCode") || mName.equals("getClass")
                                || mName.equals("getFluid")) continue;

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