package org.complexityanalyzer.harvest.modules;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.complexityanalyzer.harvest.HarvestedItems;
import org.complexityanalyzer.harvest.TerminalTypeRegistry;

import java.util.Collection;
import java.util.Map;

public final class HarvestUtility {

    private HarvestUtility() {
    }

    public static boolean isTerminal(Object obj) {
        if (obj == null) return true;
        var c = obj.getClass();
        String name = c.getName();
        if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("sun.")
                || name.startsWith("com.sun.") || name.startsWith("jdk.")) return true;
        return TerminalTypeRegistry.isTerminalType(c);
    }

    public static boolean isTooSmall(Object obj) {
        if (obj instanceof Collection<?> c) return c.size() <= 50;
        if (obj instanceof Map<?, ?> m) return m.size() <= 50;
        if (obj instanceof Object[] arr) return arr.length <= 50;
        return true;
    }

    public static boolean isEmptyContainer(Object obj) {
        if (obj instanceof Collection<?> c) return c.isEmpty();
        if (obj instanceof Map<?, ?> m) return m.isEmpty();
        if (obj instanceof Object[] arr) return arr.length == 0;
        return false;
    }

    public static boolean isNotDuplicateIngredient(Collection<HarvestedItems.HarvestedIngredient> list, Ingredient ing) {
        if (ing == null || ing.isEmpty()) return false;
        ItemStack[] ingItems = ing.getItems();
        for (var existingHi : list) {
            var existing = existingHi.ingredient();
            ItemStack[] existingItems = existing.getItems();
            if (ingItems.length == existingItems.length) {
                boolean allMatch = true;
                for (var stackA : ingItems) {
                    boolean found = false;
                    for (var stackB : existingItems) {
                        if (stackA.getItem() == stackB.getItem()) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        allMatch = false;
                        break;
                    }
                }
                if (allMatch) return false;
            }
        }
        return true;
    }
}