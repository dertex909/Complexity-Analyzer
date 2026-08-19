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
        if (type.getName().contains("FluidStack")) return Kind.FLUID_STACK;
        return null;
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
        NUMBER
    }
}