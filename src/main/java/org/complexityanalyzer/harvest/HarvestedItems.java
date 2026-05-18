package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.fluids.FluidStack;

public record HarvestedItems(
        ObjectList<ItemStack> inputItems,
        ObjectList<ItemStack> outputItems,
        ObjectList<Ingredient> inputIngredients,
        ObjectList<FluidStack> inputFluids,
        ObjectList<FluidStack> outputFluids,
        Object root
) {
    public boolean isEmpty() {
        return inputItems.isEmpty() && outputItems.isEmpty() && inputIngredients.isEmpty()
                && inputFluids.isEmpty() && outputFluids.isEmpty();
    }
}