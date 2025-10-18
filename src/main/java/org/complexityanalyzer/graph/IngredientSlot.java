package org.complexityanalyzer.graph;

import net.minecraft.world.item.Item;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class IngredientSlot {
    private final List<Item> variants;
    private final int count;

    public IngredientSlot(List<Item> variants, int count) {
        this.variants = new ArrayList<>(variants);
        this.count = Math.max(1, count);
    }

    public IngredientSlot(Item singleItem, int count) {
        this.variants = List.of(singleItem);
        this.count = Math.max(1, count);
    }

    public List<Item> getVariants() {
        return Collections.unmodifiableList(variants);
    }

    public int getCount() {
        return count;
    }

    public boolean hasMultipleVariants() {
        return variants.size() > 1;
    }

    public Item getFirstVariant() {
        return variants.isEmpty() ? null : variants.getFirst();
    }

    @Override
    public String toString() {
        if (variants.size() == 1) {
            return count + "x " + variants.getFirst();
        }
        return count + "x [" + variants.size() + " variants]";
    }
}