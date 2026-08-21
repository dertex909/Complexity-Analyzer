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

package org.complexityanalyzer.harvest.inspector;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.fluids.FluidStack;

public final class StructuralTypeClassifier {

    private StructuralTypeClassifier() {
    }

    public static Kind classify(Class<?> type) {
        if (type == null || type == void.class || type == Void.class) return null;
        if (ItemStack.class.isAssignableFrom(type)) return Kind.ITEM_STACK;
        if (Ingredient.class.isAssignableFrom(type)) return Kind.INGREDIENT;
        if (FluidStack.class.isAssignableFrom(type)) return Kind.FLUID_STACK;
        if (ResourceLocation.class.isAssignableFrom(type)) return Kind.RESOURCE_ID;
        if (TagKey.class.isAssignableFrom(type)) return Kind.TAG;
        if (DataComponentType.class.isAssignableFrom(type)) return Kind.DATA_COMPONENT;
        return type.getName().contains("FluidStack") ? Kind.FLUID_STACK : null;
    }

    public static boolean isTerminalType(Class<?> type) {
        return TerminalTypeRegistry.isTerminalType(type);
    }

    public enum Kind {
        ITEM_STACK,
        INGREDIENT,
        FLUID_STACK,
        RESOURCE_ID,
        TAG,
        DATA_COMPONENT,
    }
}