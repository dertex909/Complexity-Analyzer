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

package org.complexityanalyzer.util;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.ints.IntComparator;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.export.format.ExportData.ItemData;
import org.complexityanalyzer.export.format.ExportData.MobData;
import org.complexityanalyzer.export.format.ExportData.SourceData;
import org.complexityanalyzer.network.web.pack.WebTabManifest;
import org.complexityanalyzer.resource.IResourceSource;
import org.complexityanalyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.resource.data.MobDropData;
import org.complexityanalyzer.resource.sources.UniversalLootSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

public final class ComplexityComparators {
    public static final Comparator<ItemStack> ITEM_STACK_BY_ID_AND_DATA = Comparator.comparing((ItemStack stack) -> GameRegistryManager.getItemId(stack.getItem())).thenComparing(ItemStackIdentity::dataKey);
    public static final Comparator<RecipeHolder<?>> RECIPE_HOLDER_BY_ID = Comparator.comparing(RecipeHolder::id);
    public static final Comparator<IResourceSource> RESOURCE_SOURCE_BY_PRIORITY_DESCENDING = Comparator.comparingInt(IResourceSource::getPriority).reversed();
    public static final Comparator<BaseResourceData> SOURCE_DATA_BY_BASE_FACTOR_THEN_TYPE = Comparator.comparingDouble(BaseResourceData::getBaseFactor).thenComparing(BaseResourceData::getSourceType);
    public static final Comparator<BaseResourceData> BASE_RESOURCE_BY_LOOT_CHANCE_DESCENDING = Comparator.comparingDouble(ComplexityComparators::lootChance).reversed();
    public static final Comparator<BaseResourceData> BLOCK_BREAK_PATH = Comparator.comparingDouble((BaseResourceData data) -> data.getSourceType().getBaseMultiplier()).thenComparingDouble(BaseResourceData::getBaseFactor).thenComparingInt(data -> data.getSourceItems().size()).thenComparingDouble(data -> sumValues(data.getSourceItems())).thenComparing(BaseResourceData::getDetails);
    public static final Comparator<MobDropData> MOB_DROP_BY_AVERAGE_YIELD_DESCENDING = Comparator.comparingDouble(MobDropData::averageYield).reversed();
    public static final Comparator<ItemData> ITEM_DATA_BY_COMPLEXITY_DESCENDING = Comparator.comparingDouble(ItemData::complexity).reversed();
    public static final Comparator<SourceData> SOURCE_DATA_BY_ESTIMATED_COST = Comparator.comparingDouble(SourceData::estimatedCost);
    public static final Comparator<MobData> MOB_DATA_BY_COMBAT_POWER_DESCENDING = Comparator.comparingDouble(MobData::combatPower).reversed();
    public static final Comparator<VillagerProfession> VILLAGER_PROFESSION_BY_ID = Comparator.comparing(ComplexityComparators::villagerProfessionId);
    public static final Comparator<JsonObject> WEB_TAB_BY_ORDER = Comparator.comparingInt(tab -> tab.has(WebTabManifest.PROP_ORDER) ? tab.get(WebTabManifest.PROP_ORDER).getAsInt() : WebTabManifest.DEFAULT_TAB_ORDER);
    public static final Comparator<Method> REFLECTION_METHOD_ORDER = Comparator.comparing(Method::getName).thenComparingInt(Method::getParameterCount).thenComparing(method -> Arrays.toString(method.getParameterTypes())).thenComparing(method -> method.getReturnType().getName());
    public static final Comparator<Class<?>> CLASS_BY_NAME = Comparator.comparing(Class::getName);
    public static final Comparator<Field> FIELD_BY_NAME = Comparator.comparing(Field::getName);
    public static final Comparator<ResourceLocation> RESOURCE_LOCATION_COMPARATOR = Comparator.nullsFirst(ResourceLocation::compareTo);
    public static final Comparator<Item> ITEM_BY_ID = Comparator.comparing(GameRegistryManager::getItemId, RESOURCE_LOCATION_COMPARATOR);
    public static final Comparator<ItemStack> ITEM_STACK_BY_ID = Comparator.comparing(stack -> GameRegistryManager.getItemId(stack.getItem()), RESOURCE_LOCATION_COMPARATOR);
    public static final Comparator<ItemStack> ITEM_STACK_BY_ID_AND_COUNT = ITEM_STACK_BY_ID.thenComparingInt(ItemStack::getCount);
    public static final Comparator<Fluid> FLUID_BY_ID = Comparator.comparing(GameRegistryManager::getFluidId, RESOURCE_LOCATION_COMPARATOR);
    public static final Comparator<FluidStack> FLUID_STACK_BY_ID_AND_AMOUNT = Comparator.comparing(FluidStack::getFluid, FLUID_BY_ID).thenComparingInt(FluidStack::getAmount);

    private ComplexityComparators() {
    }

    public static <T extends Comparable<? super T>> Comparator<T> naturalOrder() {
        return Comparator.naturalOrder();
    }

    public static <T, U extends Comparable<? super U>> Comparator<T> comparingKeyNaturalOrder(Function<? super T, ? extends U> keyExtractor) {
        return Comparator.comparing(keyExtractor);
    }

    public static <T> Comparator<T> comparingDoubleAscending(ToDoubleFunction<? super T> keyExtractor) {
        return Comparator.comparingDouble(keyExtractor);
    }

    public static <T> Comparator<T> comparingIntDescendingThenAscending(ToIntFunction<? super T> descendingKey, ToIntFunction<? super T> ascendingKey) {
        return Comparator.<T>comparingInt(descendingKey).reversed().thenComparingInt(ascendingKey);
    }

    public static <T> Comparator<T> equalValueFirst(T first) {
        return Comparator.comparing((T value) -> !Objects.equals(value, first));
    }

    public static Comparator<Integer> unsignedLongIndex(long[] values) {
        return (a, b) -> Long.compareUnsigned(values[a], values[b]);
    }

    public static IntComparator formulaOrder(int[] targets, byte[] types) {
        return (a, b) -> {
            int targetCompare = Integer.compare(targets[a], targets[b]);
            return targetCompare != 0 ? targetCompare : Byte.compare(types[a], types[b]);
        };
    }

    public static <T> Comparator<Map.Entry<VillagerProfession, T>> villagerProfessionEntryById() {
        return Map.Entry.comparingByKey(VILLAGER_PROFESSION_BY_ID);
    }

    public static String villagerProfessionId(VillagerProfession profession) {
        var id = GameRegistryManager.getProfessionId(profession);
        return id != null ? id.toString() : "minecraft:none";
    }

    public static double lootChance(BaseResourceData data) {
        String chanceMeta = data.getMetadata().get(UniversalLootSource.CHANCE_METADATA);
        if (chanceMeta == null) return 0.0;
        try {
            return Double.parseDouble(chanceMeta);
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private static double sumValues(Reference2DoubleMap<Item> map) {
        if (map.isEmpty()) return 0;
        double sum = 0;
        var values = map.values().iterator();
        while (values.hasNext()) sum += values.nextDouble();
        return sum;
    }
}