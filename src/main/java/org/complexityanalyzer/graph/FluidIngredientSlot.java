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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.graph;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.world.level.material.Fluid;
import org.complexityanalyzer.core.GameRegistryManager;

import java.util.Comparator;

public record FluidIngredientSlot(ObjectList<Fluid> fluidVariants, int amount) {

    private static final Comparator<Fluid> FLUID_COMPARATOR = (f1, f2) -> {
        var id1 = GameRegistryManager.getFluidId(f1);
        var id2 = GameRegistryManager.getFluidId(f2);
        if (id1 == id2) return 0;
        if (id1 == null) return -1;
        if (id2 == null) return 1;
        return id1.compareTo(id2);
    };

    public FluidIngredientSlot(ObjectList<Fluid> fluidVariants, int amount) {
        ObjectList<Fluid> finalVariants;
        if (fluidVariants == null || fluidVariants.isEmpty()) {
            finalVariants = ObjectLists.emptyList();
        } else if (fluidVariants.size() == 1) {
            finalVariants = ObjectLists.singleton(fluidVariants.getFirst());
        } else {
            var sorted = new ObjectArrayList<>(fluidVariants);
            sorted.sort(FLUID_COMPARATOR);
            finalVariants = ObjectLists.unmodifiable(sorted);
        }
        this.fluidVariants = finalVariants;
        this.amount = amount;
    }

    public ObjectList<Fluid> getFluidVariants() {
        return fluidVariants;
    }

    public int getAmount() {
        return amount;
    }
}
