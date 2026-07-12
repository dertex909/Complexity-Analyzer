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

package org.complexityanalyzer.harvest;

public final class StructuralTypeClassifier {

    private StructuralTypeClassifier() {
    }

    public static Kind classifyDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) return null;
        return classifyName(descriptor.replace('/', '.'));
    }

    public static boolean isTerminalType(Class<?> type) {
        return TerminalTypeRegistry.isTerminalType(type);
    }

    private static Kind classifyName(String name) {
        if (name == null) return null;
        String clean = name;
        if (clean.length() > 2 && clean.charAt(0) == 'L' && clean.charAt(clean.length() - 1) == ';') {
            clean = clean.substring(1, clean.length() - 1);
        }
        if (clean.equals("net.minecraft.world.item.ItemStack")) return Kind.ITEM_STACK;
        if (clean.equals("net.minecraft.world.item.crafting.Ingredient")) return Kind.INGREDIENT;
        if (clean.equals("net.neoforged.neoforge.fluids.FluidStack")
                || clean.equals("net.minecraftforge.fluids.FluidStack")
                || clean.contains("FluidStack")) return Kind.FLUID_STACK;
        return switch (clean) {
            case "net.minecraft.resources.ResourceLocation" -> Kind.RESOURCE_ID;
            case "net.minecraft.tags.TagKey" -> Kind.TAG;
            case "net.minecraft.core.component.DataComponentType" -> Kind.DATA_COMPONENT;
            default -> null;
        };
    }

    public enum Kind {
        ITEM_STACK,
        INGREDIENT,
        FLUID_STACK,
        RESOURCE_ID,
        TAG,
        DATA_COMPONENT,
        NUMBER
    }
}