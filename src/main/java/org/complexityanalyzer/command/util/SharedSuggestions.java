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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.world.entity.MobCategory;
import org.complexityanalyzer.analyzer.resource.sources.UniversalLootSource;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.GameRegistryManager;

import java.util.Objects;
import java.util.stream.Collectors;

public final class SharedSuggestions {
    private SharedSuggestions() {
    }

    public static final SuggestionProvider<CommandSourceStack> ITEM = (context, builder) ->
            SharedSuggestionProvider.suggestResource(GameRegistryManager.getAllItems().stream().map(GameRegistryManager::getItemId).collect(Collectors.toList()), builder);

    public static final SuggestionProvider<CommandSourceStack> ENTITY = (context, builder) ->
            SharedSuggestionProvider.suggestResource(GameRegistryManager.getAllEntityTypes().stream().map(GameRegistryManager::getEntityTypeId)
                    .filter(id -> GameRegistryManager.getEntityType(id).getCategory() != MobCategory.MISC).collect(Collectors.toList()), builder);

    public static final SuggestionProvider<CommandSourceStack> LOOT_TABLE = (context, builder) -> {
        AnalysisEngine engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) return builder.buildFuture();
        var uls = engine.getSourceByType(UniversalLootSource.class);
        if (uls != null) uls.getAllLootData().values().stream().flatMap(map -> map.values().stream()).map(data -> {
            try {
                String details = data.getDetails();
                int start = details.indexOf("'") + 1;
                int end = details.indexOf("'", start);
                return details.substring(start, end);
            } catch (Exception e) {
                return null;
            }
        }).filter(Objects::nonNull).distinct().forEach(builder::suggest);
        return builder.buildFuture();
    };
}