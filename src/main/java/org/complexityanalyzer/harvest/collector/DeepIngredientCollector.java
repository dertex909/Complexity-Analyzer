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

package org.complexityanalyzer.harvest.collector;

import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.harvest.engine.HarvestedItems;

public final class DeepIngredientCollector {

    private DeepIngredientCollector() {
    }

    public static void collect(Object obj, ObjectList<HarvestedItems.HarvestedIngredient> acc, int depth, ReferenceOpenHashSet<Object> visited) {
        DeepGraphTraverser.traverse(obj, depth, visited, (node, d) -> {
            if (CollectorHelper.extractIngredient(node, acc)) return true;
            return node instanceof ItemStack || node instanceof FluidStack;
        });
    }
}