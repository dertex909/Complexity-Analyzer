/*
 * Complexity Analyzer
 * Copyright (C) 2025 dertex909
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

package org.complexityanalyzer.export;

import java.util.List;
import java.util.Map;

public record ExportData(
        String exportTimestamp,
        int totalItems,
        List<ItemData> items
) {
    public record ItemData(
            String itemId,
            String displayName,
            double complexity,
            String category,
            boolean hasRecipe,
            int craftingDepth,
            int usedInRecipes,
            boolean isValid,
            boolean hasCycle,
            boolean isHardcoded,
            List<SourceData> alternativeSources
    ) {}

    public record SourceData(
            String sourceType,
            double baseFactor,
            double estimatedCost,
            String details,
            Map<String, Double> requiredItems
    ) {}

    public record MobData(
            String name,
            String id,
            String category,
            double health,
            double damage,
            double armor,
            double survivability,
            double threat,
            double combatPower,
            double rarity,
            boolean isBoss,
            boolean isMiniBoss,
            List<MobDropData> drops
    ) {}

    public record MobDropData(
            String itemId,
            String itemName,
            double yieldPerKill
    ) {}
}