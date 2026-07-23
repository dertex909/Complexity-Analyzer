package org.complexityanalyzer.graph;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.world.level.material.Fluid;

import java.util.concurrent.ConcurrentHashMap;

import static org.complexityanalyzer.util.ComplexityComparators.FLUID_BY_ID;

public record FluidIngredientSlot(ObjectList<Fluid> fluidVariants, int amount) {

    private static final ConcurrentHashMap<FluidIngredientSlot, FluidIngredientSlot> INTERN_CACHE = new ConcurrentHashMap<>(2048);

    public FluidIngredientSlot(ObjectList<Fluid> fluidVariants, int amount) {
        ObjectList<Fluid> finalVariants;
        if (fluidVariants == null || fluidVariants.isEmpty()) {
            finalVariants = ObjectLists.emptyList();
        } else if (fluidVariants.size() == 1) {
            finalVariants = ObjectLists.singleton(fluidVariants.getFirst());
        } else {
            var sorted = new ObjectArrayList<>(fluidVariants);
            sorted.sort(FLUID_BY_ID);
            finalVariants = ObjectLists.unmodifiable(sorted);
        }
        this.fluidVariants = finalVariants;
        this.amount = amount;
    }

    public static FluidIngredientSlot intern(FluidIngredientSlot slot) {
        if (slot == null || slot.fluidVariants().isEmpty()) return slot;
        return INTERN_CACHE.computeIfAbsent(slot, s -> s);
    }

    public static void clearCache() {
        INTERN_CACHE.clear();
    }

    public ObjectList<Fluid> getFluidVariants() {
        return fluidVariants;
    }

    public int getAmount() {
        return amount;
    }
}