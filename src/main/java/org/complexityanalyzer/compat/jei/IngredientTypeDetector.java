package org.complexityanalyzer.compat.jei;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * Класс-детектор для определения типа ингредиента (реальная жидкость или химикат).
 * Это необходимо для правильной обработки рецептов из модов, где газы
 * и другие химикаты могут быть представлены как FluidStack.
 */
public class IngredientTypeDetector {

    private static final List<String> CHEMICAL_KEYWORDS = List.of(
            "chemical", "chem", "gas", "plasma", "infuse", "pigment", "slurry",
            "isotope", "compound", "acid", "alkali", "oxide", "solution", "steam"
    );

    /**
     * Проверяет, является ли объект ингредиентом-химикатом.
     * @param obj объект для проверки
     * @return true, если это химикат
     */
    public static boolean isChemicalIngredient(Object obj) {
        if (obj == null) return false;

        if (hasChemicalSemantic(obj.getClass())) {
            return true;
        }

        for (Class<?> iface : obj.getClass().getInterfaces()) {
            if (hasChemicalSemantic(iface)) {
                return true;
            }
        }

        Class<?> superClass = obj.getClass().getSuperclass();
        while (superClass != null) {
            if (hasChemicalSemantic(superClass)) {
                return true;
            }
            superClass = superClass.getSuperclass();
        }

        for (Field field : obj.getClass().getDeclaredFields()) {
            if (hasChemicalSemantic(field.getType()) || hasChemicalSemantic(field.getName())) {
                return true;
            }
        }

        for (Method method : obj.getClass().getMethods()) {
            if (method.getParameterCount() == 0) {
                if (hasChemicalSemantic(method.getName()) || hasChemicalSemantic(method.getReturnType())) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Определяет, является ли данный FluidStack настоящей жидкостью (вода, лава, масло)
     * или замаскированным химикатом (газы Mekanism).
     * @param fluidStack стек жидкости для проверки
     * @return true, если это реальная жидкость
     */
    public static boolean isActualFluid(FluidStack fluidStack) {
        if (fluidStack == null || fluidStack.isEmpty()) {
            return false;
        }

        Fluid fluid = fluidStack.getFluid();
        if (fluid == null) {
            return false;
        }

        boolean looksChemical = hasChemicalSemantic(fluid.getClass());

        FluidType fluidType = fluid.getFluidType();
        if (fluidType != null && isFluidTypeGaseous(fluidType)) {
            looksChemical = true;
        }

        ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
        if (fluidId != null && (hasChemicalSemantic(fluidId.getNamespace()) || hasChemicalSemantic(fluidId.getPath()))) {
            looksChemical = true;
        }

        Item bucketItem = safeGetBucket(fluid);
        boolean hasBucket = bucketItem != null;

        if (!hasBucket) {
            // Отсутствие вёдер — сильный сигнал, но не абсолютный.
            ResourceLocation bucketId = bucketItem != null ? BuiltInRegistries.ITEM.getKey(bucketItem) : null;
            if (bucketId == null || hasChemicalSemantic(bucketId.getNamespace()) || hasChemicalSemantic(bucketId.getPath())) {
                looksChemical = true;
            }
        }

        return !looksChemical;
    }

    /**
     * Определяет, является ли данный объект реальной жидкостью.
     * @param obj объект для проверки
     * @return true, если это реальная жидкость
     */
    public static boolean isActualFluid(Object obj) {
        if (obj instanceof FluidStack fluidStack) {
            return isActualFluid(fluidStack);
        }
        return false;
    }

    private static Item safeGetBucket(Fluid fluid) {
        try {
            return fluid.getBucket();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isFluidTypeGaseous(FluidType fluidType) {
        try {
            Method method = fluidType.getClass().getMethod("isGaseous");
            Object result = method.invoke(fluidType);
            if (result instanceof Boolean bool) {
                return bool;
            }
        } catch (Throwable ignored) {
            // Метод отсутствует или недоступен — продолжаем с эвристиками ниже
        }

        try {
            Method densityMethod = fluidType.getClass().getMethod("getDensity");
            Object value = densityMethod.invoke(fluidType);
            if (value instanceof Number number) {
                return number.intValue() <= 0;
            }
        } catch (Throwable ignored) {
            // Нет информации о плотности — ничего страшного
        }

        return false;
    }

    private static boolean hasChemicalSemantic(Class<?> clazz) {
        return clazz != null && hasChemicalSemantic(clazz.getName());
    }

    private static boolean hasChemicalSemantic(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String keyword : CHEMICAL_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasChemicalSemantic(Method method) {
        if (method == null) return false;
        return hasChemicalSemantic(method.getName()) || hasChemicalSemantic(method.getReturnType());
    }

    private static boolean hasChemicalSemantic(Field field) {
        if (field == null) return false;
        return hasChemicalSemantic(field.getName()) || hasChemicalSemantic(field.getType());
    }
}