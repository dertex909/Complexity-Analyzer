package org.complexityanalyzer.harvest;

public final class StructuralTypeClassifier {

    public enum Kind {
        ITEM_STACK,
        INGREDIENT,
        FLUID_STACK,
        RESOURCE_ID,
        TAG,
        DATA_COMPONENT,
        NUMBER
    }

    private StructuralTypeClassifier() {
    }

    public static Kind classifyClass(Class<?> type) {
        if (type == null) return null;
        return classifyName(type.getName());
    }

    public static Kind classifyDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) return null;
        return classifyName(descriptor.replace('/', '.'));
    }

    public static boolean isContainerType(Class<?> type) {
        if (type == null) return false;
        return type.isArray()
                || Iterable.class.isAssignableFrom(type)
                || java.util.Map.class.isAssignableFrom(type)
                || type.isRecord();
    }

    public static boolean isTerminalType(Class<?> type) {
        if (type == null) return true;
        if (type.isPrimitive() || type.isEnum() || type == String.class) return true;
        if (Number.class.isAssignableFrom(type) || type == Boolean.class || type == Character.class) return true;
        String name = type.getName();
        if (name.startsWith("java.lang.invoke.") || name.startsWith("java.lang.reflect.")) return true;
        if (name.startsWith("net.minecraft.world.level.Level") ||
            name.startsWith("net.minecraft.server.") ||
            name.startsWith("net.minecraft.world.item.crafting.RecipeManager") ||
            name.startsWith("net.minecraft.core.RegistryAccess") ||
            name.startsWith("net.minecraft.world.entity.player.Player") ||
            name.startsWith("net.minecraft.world.inventory.AbstractContainerMenu") ||
            name.startsWith("net.neoforged.neoforge.server.ServerLifecycleHooks")) return true;
        if (name.contains("RecipeType") || name.contains("RecipeBuilder")) return true;
        if (name.contains("EnergyStack")) return true;
        return false;
    }

    private static Kind classifyName(String name) {
        if (name == null) return null;
        if (name.contains("net.minecraft.world.item.ItemStack")) return Kind.ITEM_STACK;
        if (name.contains("net.minecraft.world.item.crafting.Ingredient")) return Kind.INGREDIENT;
        if (name.contains("FluidStack")) return Kind.FLUID_STACK;
        if (name.contains("net.minecraft.resources.ResourceLocation")) return Kind.RESOURCE_ID;
        if (name.contains("net.minecraft.tags.TagKey")) return Kind.TAG;
        if (name.contains("net.minecraft.core.component.DataComponentType")) return Kind.DATA_COMPONENT;
        return null;
    }
}
