package org.complexityanalyzer.util;

import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluids;

public final class FluidNormalizer {
    private FluidNormalizer() {}

    public static Fluid normalize(Fluid fluid) {
        if (fluid == null) return Fluids.EMPTY;
        if (fluid instanceof FlowingFluid flowing) return flowing.getSource();
        return fluid;
    }
}