package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.Set;

public record HarvestedItems(
        ObjectList<ItemStack> inputItems,
        ObjectList<ItemStack> outputItems,
        ObjectList<Ingredient> inputIngredients,
        ObjectList<FluidStack> inputFluids,
        ObjectList<FluidStack> outputFluids,
        Object root,
        Set<Item> transitionalItems
) {
    public boolean isEmpty() {
        return inputItems.isEmpty() && outputItems.isEmpty() && inputIngredients.isEmpty()
                && inputFluids.isEmpty() && outputFluids.isEmpty();
    }
}