package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import it.unimi.dsi.fastutil.objects.ReferenceSets;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.fluids.FluidStack;

public record HarvestedItems(
        ObjectList<ItemStack> inputItems,
        ObjectList<ItemStack> outputItems,
        ObjectList<HarvestedIngredient> inputIngredients,
        ObjectList<FluidStack> inputFluids,
        ObjectList<FluidStack> outputFluids,
        Object root,
        ReferenceSet<Item> transitionalItems
) {
    public record HarvestedIngredient(Ingredient ingredient, int count) {
    }

    public static final HarvestedItems EMPTY = new HarvestedItems(
            ObjectLists.emptyList(), ObjectLists.emptyList(),
            ObjectLists.emptyList(), ObjectLists.emptyList(),
            ObjectLists.emptyList(), null,
            ReferenceSets.emptySet()
    );

    public boolean isEmpty() {
        return inputItems.isEmpty() && outputItems.isEmpty() && inputIngredients.isEmpty()
                && inputFluids.isEmpty() && outputFluids.isEmpty();
    }
}