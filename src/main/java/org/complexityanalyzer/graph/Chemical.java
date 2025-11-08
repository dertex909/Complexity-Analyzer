package org.complexityanalyzer.graph;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Универсальное представление для химических веществ, газов, жидкостей и т.д.
 * из Mekanism и других модов.
 */
public class Chemical {

    // Кэш для быстрого получения Chemical по имени
    private static final Map<String, Chemical> REGISTRY = new ConcurrentHashMap<>();
    
    // Кэш для сопоставления Fluid -> Chemical
    private static final Map<Fluid, Chemical> FLUID_TO_CHEMICAL_CACHE = new ConcurrentHashMap<>();

    private final String modId;
    private final String path;
    private final String fullId;
    private final String displayName;

    private Chemical(String modId, String path, String displayName) {
        this.modId = modId;
        this.path = path;
        this.fullId = modId + ":" + path;
        this.displayName = displayName;
        REGISTRY.put(this.fullId, this);
    }
    
    /**
     * Создает или получает Chemical из универсального объекта.
     * Работает с GasStack, InfusionStack, PigmentStack, SlurryStack, FluidStack.
     * @param obj объект-химикат
     * @return Chemical или null, если не удалось распознать
     */
    @Nullable
    public static Chemical fromObject(Object obj) {
        if (obj == null) return null;

        // Если это уже Chemical, возвращаем его
        if (obj instanceof Chemical chemical) {
            return chemical;
        }

        // Если это FluidStack, используем кэш
        if (obj instanceof net.neoforged.neoforge.fluids.FluidStack fluidStack) {
            return FLUID_TO_CHEMICAL_CACHE.computeIfAbsent(fluidStack.getFluid(), Chemical::fromFluid);
        }

        // Используем рефлексию для получения внутреннего объекта (Gas, InfuseType, etc.)
        try {
            Method getTypeMethod = obj.getClass().getMethod("getType");
            Object typeObj = getTypeMethod.invoke(obj);
            if (typeObj != null) {
                // Если тип уже Chemical, возвращаем его
                if (typeObj instanceof Chemical chemical) {
                    return chemical;
                }
                // Пробуем получить ResourceLocation
                Method registryNameMethod = typeObj.getClass().getMethod("getRegistryName");
                Object registryNameObj = registryNameMethod.invoke(typeObj);
                if (registryNameObj instanceof ResourceLocation rl) {
                    return fromResourceLocation(rl);
                }
            }
        } catch (Exception ignored) {
            // Игнорируем ошибки, пробуем другие методы
        }
        
        // Запасной вариант: пытаемся получить ResourceLocation напрямую
        try {
            Method registryNameMethod = obj.getClass().getMethod("getRegistryName");
            Object registryNameObj = registryNameMethod.invoke(obj);
            if (registryNameObj instanceof ResourceLocation rl) {
                return fromResourceLocation(rl);
            }
        } catch (Exception ignored) {}

        return null;
    }
    
    /**
     * Создает или получает Chemical из Fluid.
     * @param fluid жидкость
     * @return Chemical или null
     */
    @Nullable
    public static Chemical fromFluid(Fluid fluid) {
        if (fluid == null) return null;
        ResourceLocation rl = BuiltInRegistries.FLUID.getKey(fluid);
        if (rl == null) return null;
        return fromResourceLocation(rl);
    }

    /**
     * Создает или получает Chemical из ResourceLocation.
     * @param rl ResourceLocation
     * @return Chemical
     */
    public static Chemical fromResourceLocation(ResourceLocation rl) {
        return REGISTRY.computeIfAbsent(rl.toString(), id ->
                new Chemical(rl.getNamespace(), rl.getPath(), formatDisplayName(rl.getPath()))
        );
    }
    
    /**
     * Форматирует путь в отображаемое имя (e.g., "hydrogen_chloride" -> "Hydrogen Chloride").
     */
    private static String formatDisplayName(String path) {
        String[] parts = path.replace('_', ' ').split(" ");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(" ");
            }
        }
        return sb.toString().trim();
    }
    
    public String getModId() {
        return modId;
    }

    public String getPath() {
        return path;
    }
    
    public String getFullId() {
        return fullId;
    }

    public String getDisplayName() {
        return displayName;
    }
    
    public String getRegistryName() {
        return getFullId();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Chemical chemical = (Chemical) o;
        return fullId.equals(chemical.fullId);
    }

    @Override
    public int hashCode() {
        return fullId.hashCode();
    }

    @Override
    public String toString() {
        return "Chemical{" + fullId + '}';
    }
}
