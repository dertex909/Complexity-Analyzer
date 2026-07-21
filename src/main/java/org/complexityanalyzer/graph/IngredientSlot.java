package org.complexityanalyzer.graph;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.world.item.ItemStack;
import org.complexityanalyzer.harvest.ItemStackIdentity;

import static org.complexityanalyzer.util.ComplexityComparators.ITEM_STACK_BY_ID;

public class IngredientSlot {

    private final ObjectList<ItemStack> variants;
    private final int count;

    public IngredientSlot(ObjectList<ItemStack> variants, int count) {
        this.count = Math.max(1, count);

        var processed = new ObjectArrayList<ItemStack>(variants.size());
        for (var variant : variants) {
            if (variant != null && !variant.isEmpty()) processed.add(variant.copyWithCount(1));
        }

        if (processed.isEmpty()) {
            this.variants = ObjectLists.emptyList();
        } else if (processed.size() == 1) {
            this.variants = ObjectLists.singleton(processed.getFirst());
        } else {
            processed.sort(ITEM_STACK_BY_ID);
            this.variants = ObjectLists.unmodifiable(processed);
        }
    }

    public ObjectList<ItemStack> getVariants() {
        return variants;
    }

    public int getCount() {
        return count;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IngredientSlot that)) return false;
        if (count != that.count || variants.size() != that.variants.size()) return false;
        for (int i = 0; i < variants.size(); i++) {
            if (!ItemStackIdentity.sameItemData(variants.get(i), that.variants.get(i))) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = count;
        for (var stack : variants) result = 31 * result + ItemStackIdentity.hashItemData(stack);
        return result;
    }

    @Override
    public String toString() {
        if (variants.size() == 1) return count + "x " + variants.getFirst();
        return count + "x [" + variants.size() + " variants]";
    }
}