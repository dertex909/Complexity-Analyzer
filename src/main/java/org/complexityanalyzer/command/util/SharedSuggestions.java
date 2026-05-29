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

package org.complexityanalyzer.command.util;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.MobCategory;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.sources.UniversalLootSource;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.GameRegistryManager;

public final class SharedSuggestions {
    private static final ObjectList<ResourceLocation> CACHED_ENTITIES = new ObjectArrayList<>();
    private static final ObjectList<ResourceLocation> CACHED_LOOT_TABLES = new ObjectArrayList<>();

    private SharedSuggestions() {
    }

    public static final SuggestionProvider<CommandSourceStack> ITEM = (context, builder) ->
            SharedSuggestionProvider.suggestResource(GameRegistryManager.getItemIds(), builder);

    public static final SuggestionProvider<CommandSourceStack> ENTITY = (context, builder) ->
            SharedSuggestionProvider.suggestResource(CACHED_ENTITIES, builder);

    public static final SuggestionProvider<CommandSourceStack> LOOT_TABLE = (context, builder) ->
            SharedSuggestionProvider.suggestResource(CACHED_LOOT_TABLES, builder);

    public static void refresh() {
        refreshEntities();
        refreshLootTables();
    }

    private static void refreshEntities() {
        CACHED_ENTITIES.clear();
        GameRegistryManager.getAllEntityTypes().forEach(type -> {
            if (type.getCategory() != MobCategory.MISC) {
                ResourceLocation id = GameRegistryManager.getEntityTypeId(type);
                if (id != null) CACHED_ENTITIES.add(id);
            }
        });
    }

    private static void refreshLootTables() {
        CACHED_LOOT_TABLES.clear();
        var engine = AnalysisEngine.getInstance();

        var uls = engine.getSourceByType(UniversalLootSource.class);
        if (uls == null) return;

        uls.getAllLootData().values().stream()
                .flatMap(map -> map.values().stream())
                .map(BaseResourceData::getSourceSpecifier)
                .filter(s -> s != null && !s.isEmpty())
                .map(ResourceLocation::parse)
                .distinct()
                .sorted()
                .forEach(CACHED_LOOT_TABLES::add);
    }
}