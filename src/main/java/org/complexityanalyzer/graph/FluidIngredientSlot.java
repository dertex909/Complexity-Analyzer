/*
 * Complexity Analyzer
 * Copyright (C) 2025-2026 dertex909
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.graph;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.world.level.material.Fluid;
import org.complexityanalyzer.core.GameRegistryManager;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;

public record FluidIngredientSlot(ObjectList<Fluid> fluidVariants, int amount) {
    public FluidIngredientSlot(ObjectList<Fluid> fluidVariants, int amount) {
        var sorted = new ObjectArrayList<>(fluidVariants);
        sorted.sort(Comparator.comparing(fluid -> {
            var id = GameRegistryManager.getFluidId(fluid);
            return id != null ? id.toString() : "";
        }));
        this.fluidVariants = ObjectLists.unmodifiable(sorted);
        this.amount = amount;
    }

    public ObjectList<Fluid> getFluidVariants() {
        return fluidVariants;
    }

    public int getAmount() {
        return amount;
    }

    @Nullable
    public Fluid getPrimaryFluid() {
        return fluidVariants.isEmpty() ? null : fluidVariants.getFirst();
    }
}
