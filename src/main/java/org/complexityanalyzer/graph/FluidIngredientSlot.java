package org.complexityanalyzer.graph;

import net.minecraft.world.level.material.Fluid;
import java.util.List;

public record FluidIngredientSlot(List<Fluid> fluidVariants, int amount) {
    public List<Fluid> getFluidVariants() { return fluidVariants; }
    public int getAmount() { return amount; }
    
    public net.minecraft.world.level.material.Fluid getPrimaryFluid() {
        return fluidVariants.isEmpty() ? null : fluidVariants.get(0);
    }
}
