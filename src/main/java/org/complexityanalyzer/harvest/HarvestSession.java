package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.fluids.FluidStack;

public final class HarvestSession {
    public final ObjectArrayList<ItemStack> inputItems = new ObjectArrayList<>(32);
    public final ObjectArrayList<ItemStack> outputItems = new ObjectArrayList<>(16);
    public final ObjectArrayList<FluidStack> inputFluids = new ObjectArrayList<>(16);
    public final ObjectArrayList<FluidStack> outputFluids = new ObjectArrayList<>(16);
    public final ObjectArrayList<HarvestedItems.HarvestedIngredient> inputIngredients = new ObjectArrayList<>(16);

    public final ReferenceOpenHashSet<Object> visited = new ReferenceOpenHashSet<>(128);
    public final ReferenceOpenHashSet<Object> visitedSecondary = new ReferenceOpenHashSet<>(64);
    public final ReferenceOpenHashSet<Item> transitionalItems = new ReferenceOpenHashSet<>(8);
    public final ReferenceOpenHashSet<Ingredient> visitedIngredients = new ReferenceOpenHashSet<>(64);

    public void reset() {
        inputItems.clear();
        outputItems.clear();
        inputFluids.clear();
        outputFluids.clear();
        inputIngredients.clear();
        visited.clear();
        visitedSecondary.clear();
        transitionalItems.clear();
        visitedIngredients.clear();
    }
}