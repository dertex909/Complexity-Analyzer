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

    public static Kind classifyDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) return null;
        return classifyName(descriptor.replace('/', '.'));
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
        return name.contains("EnergyStack");
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
}