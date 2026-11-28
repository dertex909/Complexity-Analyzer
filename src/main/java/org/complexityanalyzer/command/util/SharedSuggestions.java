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

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.MobCategory;
import org.complexityanalyzer.cache.util.ManagedCache;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.export.format.ExportFormats;
import org.complexityanalyzer.geoscan.config.ScanConfig.ScanProfile;
import org.complexityanalyzer.resource.sources.UniversalLootSource;
import org.complexityanalyzer.util.ComplexityComparators;

import java.util.concurrent.CompletableFuture;

import static java.util.Locale.ROOT;

public final class SharedSuggestions {
    public static final SuggestionProvider<CommandSourceStack> ITEM = (c, b) -> SharedSuggestionProvider.suggestResource(GameRegistryManager.getItemIds(), b);
    public static final SuggestionProvider<CommandSourceStack> LOOT_TABLE = (c, b) -> SharedSuggestionProvider.suggestResource(SharedSuggestions.CACHED_LOOT_TABLES, b);
    public static final SuggestionProvider<CommandSourceStack> ENTITY = (c, b) -> SharedSuggestionProvider.suggestResource(SharedSuggestions.CACHED_ENTITIES, b);
    public static final SuggestionProvider<CommandSourceStack> EXPORT_FORMAT = (c, b) -> SharedSuggestionProvider.suggest(ExportFormats.getAvailableFormatIds(), b);
    public static final SuggestionProvider<CommandSourceStack> EXPORT_CATEGORY = (c, b) -> SharedSuggestionProvider.suggest(SharedSuggestions.ITEM_CATEGORY_NAMES, b);
    public static final SuggestionProvider<CommandSourceStack> EXPORT_MOB_CATEGORY = (c, b) -> SharedSuggestionProvider.suggest(SharedSuggestions.MOB_CATEGORY_NAMES, b);
    public static final SuggestionProvider<CommandSourceStack> GEOSCAN_PROFILE = SharedSuggestions::suggestProfiles;
    public static final SuggestionProvider<CommandSourceStack> CACHE_ID = (c, b) -> SharedSuggestionProvider.suggest(ManagedCache.ids(), b);

    private static final ObjectList<String> ITEM_CATEGORY_NAMES = createItemCategoryNames();
    private static final ObjectList<String> MOB_CATEGORY_NAMES = createMobCategoryNames();
    private static final ObjectList<String> PROFILE_NAMES = createProfileNames();
    private static volatile ObjectList<ResourceLocation> CACHED_LOOT_TABLES = ObjectLists.emptyList();
    private static volatile ObjectList<ResourceLocation> CACHED_ENTITIES = ObjectLists.emptyList();

    private SharedSuggestions() {
    }

    private static ObjectList<String> createItemCategoryNames() {
        var names = new ObjectArrayList<String>();
        for (var category : ComplexityCategory.values()) names.add(category.getDisplayName());
        return ObjectLists.unmodifiable(names);
    }

    private static ObjectList<String> createMobCategoryNames() {
        var categories = MobCategory.values();
        var names = new ObjectArrayList<String>(categories.length);
        for (var category : categories) names.add(category.getName());
        return ObjectLists.unmodifiable(names);
    }

    private static ObjectList<String> createProfileNames() {
        var profiles = ScanProfile.values();
        var names = new ObjectArrayList<String>(profiles.length);
        for (var profile : profiles) names.add(profile.commandName);
        return ObjectLists.unmodifiable(names);
    }

    private static CompletableFuture<Suggestions> suggestProfiles(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        var remaining = builder.getRemaining().toLowerCase(ROOT);
        var range = StringRange.between(builder.getStart(), builder.getInput().length());
        var suggestions = new ObjectArrayList<Suggestion>(PROFILE_NAMES.size());
        for (var name : PROFILE_NAMES) {
            if (SharedSuggestionProvider.matchesSubStr(remaining, name)) suggestions.add(new Suggestion(range, name));
        }
        return suggestions.isEmpty() ? Suggestions.empty() : CompletableFuture.completedFuture(new Suggestions(range, suggestions));
    }

    public static void refresh() {
        refreshEntities();
        refreshLootTables();
    }

    private static void refreshEntities() {
        var temp = new ObjectArrayList<ResourceLocation>();
        for (var type : GameRegistryManager.getAllEntityTypes()) {
            if (type.getCategory() == MobCategory.MISC) continue;
            var id = GameRegistryManager.getEntityTypeId(type);
            if (id != null) temp.add(id);
        }
        CACHED_ENTITIES = ObjectLists.unmodifiable(temp);
    }

    private static void refreshLootTables() {
        var engine = AnalysisEngine.getInstance();
        var uls = engine.getSourceByType(UniversalLootSource.class);
        if (uls == null) return;
        var unique = new ObjectOpenHashSet<ResourceLocation>();

        for (var map : uls.getAllLootData().values()) {
            if (map == null) continue;
            for (var data : map.values()) {
                if (data == null) continue;
                var specifier = data.getSourceSpecifier();
                if (specifier == null || specifier.isEmpty()) continue;
                var loc = ResourceLocation.tryParse(specifier);
                if (loc != null) unique.add(loc);
            }
        }

        var temp = new ObjectArrayList<>(unique);
        temp.sort(ComplexityComparators.naturalOrder());
        CACHED_LOOT_TABLES = ObjectLists.unmodifiable(temp);
    }
}