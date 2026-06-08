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

package org.complexityanalyzer.api;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the machine registry: which block/item acts as the "machine" that performs recipes of a
 * given {@link RecipeType}. Used by the machine-tax feature to add a share of the machine's own complexity to
 * items it crafts.
 */
public interface MachineData {

    /**
     * @return the representative machine item for a recipe type (e.g. furnace for smelting), if known.
     */
    Optional<Item> getMachineForRecipe(RecipeType<?> type);

    /**
     * @return all machine items capable of performing the given recipe type.
     */
    List<Item> getMachinesForRecipe(RecipeType<?> type);
}