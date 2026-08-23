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

package org.complexityanalyzer.graph;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.cache.RecipeGraphCache;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.harvest.engine.RegistryHarvestService;

public final class RecipeGraphLoader {
    private RecipeGraphLoader() {
    }

    public static RecipeGraph buildFromWorld(Level level) {
        var cache = RecipeGraphCache.INSTANCE;
        var file = ComplexityConfig.ENABLE_CACHE.get() ? cache.file(level.getServer()) : null;
        var fp = file != null ? cache.computeFingerprint(level.getRecipeManager(), level.registryAccess()) : null;

        if (file != null) {
            var cached = cache.tryLoad(file, fp, level);
            if (cached != null) {
                ComplexityAnalyzer.LOGGER.info("Loaded recipe graph from cache: {} recipes (scan skipped).", cached.getTotalRecipeCount());
                return cached;
            }
        }

        var graph = new RecipeGraph();
        new RegistryHarvestService().harvestInto(graph, level, level.getServer().getWorldPath(LevelResource.ROOT));
        if (file != null) cache.save(graph, file, fp, level);
        return graph;
    }
}