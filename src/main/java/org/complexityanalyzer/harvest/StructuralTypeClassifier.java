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

        return switch (clean) {
            case "net.minecraft.world.item.ItemStack" -> Kind.ITEM_STACK;
            case "net.minecraft.world.item.crafting.Ingredient" -> Kind.INGREDIENT;
            case "net.neoforged.neoforge.fluids.FluidStack",
                 "net.minecraftforge.fluids.FluidStack" -> Kind.FLUID_STACK;
            case "net.minecraft.resources.ResourceLocation" -> Kind.RESOURCE_ID;
            case "net.minecraft.tags.TagKey" -> Kind.TAG;
            case "net.minecraft.core.component.DataComponentType" -> Kind.DATA_COMPONENT;
            default -> {
                if (clean.contains("FluidStack")) yield Kind.FLUID_STACK;
                yield null;
            }
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