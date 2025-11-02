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