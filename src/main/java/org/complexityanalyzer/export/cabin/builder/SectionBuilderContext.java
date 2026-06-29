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

package org.complexityanalyzer.export.cabin.builder;

import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.export.cabin.api.StringPool;

public final class SectionBuilderContext {
    private final AnalysisEngine engine;
    private final StringPool strings;
    private final ObjectList<Item> orderedItems;
    private final Reference2IntOpenHashMap<Item> itemIndex;
    private final ObjectList<EntityType<?>> orderedMobs;
    private final Reference2IntOpenHashMap<EntityType<?>> mobIndex;
    private final ObjectList<Fluid> orderedFluids;
    private final Reference2IntOpenHashMap<Fluid> fluidIndex;

    private final Reference2IntOpenHashMap<ItemStack> hoverNameIdCache;
    private final Reference2IntOpenHashMap<ItemStack> dataKeyIdCache;
    private final Reference2IntOpenHashMap<Item> plainHoverByItem;
    private final Reference2IntOpenHashMap<Item> plainDataKeyByItem;

    private final HolderLookup.Provider registryAccess;

    public SectionBuilderContext(
            AnalysisEngine engine,
            StringPool strings,
            ObjectList<Item> orderedItems,
            Reference2IntOpenHashMap<Item> itemIndex,
            ObjectList<EntityType<?>> orderedMobs,
            Reference2IntOpenHashMap<EntityType<?>> mobIndex,
            ObjectList<Fluid> orderedFluids,
            Reference2IntOpenHashMap<Fluid> fluidIndex,
            HolderLookup.Provider registryAccess
    ) {
        this.engine = engine;
        this.strings = strings;
        this.orderedItems = orderedItems;
        this.itemIndex = itemIndex;
        this.orderedMobs = orderedMobs;
        this.mobIndex = mobIndex;
        this.orderedFluids = orderedFluids;
        this.fluidIndex = fluidIndex;
        this.registryAccess = registryAccess;

        this.hoverNameIdCache = new Reference2IntOpenHashMap<>(32768);
        this.hoverNameIdCache.defaultReturnValue(-1);
        this.dataKeyIdCache = new Reference2IntOpenHashMap<>(32768);
        this.dataKeyIdCache.defaultReturnValue(-1);

        this.plainHoverByItem = new Reference2IntOpenHashMap<>(orderedItems.size());
        this.plainHoverByItem.defaultReturnValue(-1);
        this.plainDataKeyByItem = new Reference2IntOpenHashMap<>(orderedItems.size());
        this.plainDataKeyByItem.defaultReturnValue(-1);
    }

    public AnalysisEngine engine() {
        return engine;
    }

    public StringPool strings() {
        return strings;
    }

    public ObjectList<Item> orderedItems() {
        return orderedItems;
    }

    public Reference2IntOpenHashMap<Item> itemIndex() {
        return itemIndex;
    }

    public ObjectList<EntityType<?>> orderedMobs() {
        return orderedMobs;
    }

    public Reference2IntOpenHashMap<EntityType<?>> mobIndex() {
        return mobIndex;
    }

    public ObjectList<Fluid> orderedFluids() {
        return orderedFluids;
    }

    public Reference2IntOpenHashMap<Fluid> fluidIndex() {
        return fluidIndex;
    }

    public Reference2IntOpenHashMap<ItemStack> hoverNameIdCache() {
        return hoverNameIdCache;
    }

    public Reference2IntOpenHashMap<ItemStack> dataKeyIdCache() {
        return dataKeyIdCache;
    }

    public Reference2IntOpenHashMap<Item> plainHoverByItem() {
        return plainHoverByItem;
    }

    public Reference2IntOpenHashMap<Item> plainDataKeyByItem() {
        return plainDataKeyByItem;
    }

    public HolderLookup.Provider registryAccess() {
        return registryAccess;
    }
}