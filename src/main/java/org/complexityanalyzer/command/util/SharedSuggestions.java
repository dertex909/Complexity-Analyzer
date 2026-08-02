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

package org.complexityanalyzer.command.util;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.MobCategory;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.resource.sources.UniversalLootSource;

public final class SharedSuggestions {
    public static final SuggestionProvider<CommandSourceStack> ITEM = (context, builder) -> SharedSuggestionProvider.suggestResource(GameRegistryManager.getItemIds(), builder);
    public static final SuggestionProvider<CommandSourceStack> LOOT_TABLE = (context, builder) -> SharedSuggestionProvider.suggestResource(SharedSuggestions.CACHED_LOOT_TABLES, builder);
    public static final SuggestionProvider<CommandSourceStack> ENTITY = (context, builder) -> SharedSuggestionProvider.suggestResource(SharedSuggestions.CACHED_ENTITIES, builder);

    private static volatile ObjectList<ResourceLocation> CACHED_LOOT_TABLES = ObjectLists.emptyList();
    private static volatile ObjectList<ResourceLocation> CACHED_ENTITIES = ObjectLists.emptyList();

    private SharedSuggestions() {
    }

    public static void refresh() {
        refreshEntities();
        refreshLootTables();
    }

    private static void refreshEntities() {
        var temp = new ObjectArrayList<ResourceLocation>();
        for (var type : GameRegistryManager.getAllEntityTypes()) {
            if (type.getCategory() != MobCategory.MISC) {
                var id = GameRegistryManager.getEntityTypeId(type);
                if (id != null) temp.add(id);
            }
        }
        CACHED_ENTITIES = temp;
    }

    private static void refreshLootTables() {
        var engine = AnalysisEngine.getInstance();
        var uls = engine.getSourceByType(UniversalLootSource.class);
        if (uls == null) return;
        var unique = new ObjectOpenHashSet<ResourceLocation>();

        for (var map : uls.getAllLootData().values()) {
            if (map == null) continue;
            for (var data : map.values()) {
                if (data != null && data.getSourceSpecifier() != null && !data.getSourceSpecifier().isEmpty()) {
                    var loc = ResourceLocation.tryParse(data.getSourceSpecifier());
                    if (loc != null) unique.add(loc);
                }
            }
        }

        var temp = new ObjectArrayList<>(unique);
        temp.sort(null);
        CACHED_LOOT_TABLES = temp;
    }
}