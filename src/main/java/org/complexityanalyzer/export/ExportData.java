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
}