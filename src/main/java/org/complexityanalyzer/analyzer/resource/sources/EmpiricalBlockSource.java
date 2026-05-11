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

package org.complexityanalyzer.analyzer.resource.sources;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.providers.BlockPropertyProvider;
import org.complexityanalyzer.geoscan.GeoDatabase;

import org.jetbrains.annotations.Nullable;

public class EmpiricalBlockSource implements IResourceSource {

    private final BlockPropertyProvider propertyProvider;
    private final GeoDatabase geoDatabase;

    public EmpiricalBlockSource(BlockPropertyProvider propertyProvider, GeoDatabase geoDatabase) {
        this.propertyProvider = propertyProvider;
        this.geoDatabase = geoDatabase;
    }

    @Override
    public void initialize(Level level) {
    }

    @Override
    public boolean canProvide(Item item) {
        return item instanceof BlockItem && geoDatabase.isLoaded();
    }

    @Override
    @Nullable
    public BaseResourceData analyze(Item item) {
        if (!canProvide(item)) return null;

        var block = ((BlockItem) item).getBlock();
        var properties = propertyProvider.getProperties(block);
        if (properties == null) return null;

        double bestCost = Double.POSITIVE_INFINITY;
        BaseResourceData bestData = null;

        for (var dimEntry : geoDatabase.getAllDimensionData().entrySet()) {
            var dimensionId = dimEntry.getKey();
            var biomeDataMap = dimEntry.getValue();

            long totalBlocksInDim = 0;
            long targetBlockCount = 0;
            for (var biomeData : biomeDataMap.values()) {
                totalBlocksInDim += biomeData.getTotalBlocks();
                targetBlockCount += biomeData.getBlockCount(block);
            }

            if (totalBlocksInDim == 0 || targetBlockCount == 0) continue;

            double rarity = (double) targetBlockCount / totalBlocksInDim;

            double toolMultiplier = properties.getToolMultiplier();
            double hardnessMultiplier = properties.getHardnessMultiplier();

            double inverseRarity = 1.0 / rarity;
            double rarityFactor = 0.5 + (Math.sqrt(inverseRarity) / 10.0);

            double baseFactor = toolMultiplier * hardnessMultiplier * rarityFactor * getSourceType().getBaseMultiplier();

            if (baseFactor < bestCost) {
                bestCost = baseFactor;

                String dimensionName = dimensionId.getPath();
                String details = String.format("Empirical Rarity: %.6f%% in %s, Tool: %s",
                        rarity * 100.0, dimensionName, properties.getTierName());

                bestData = new BaseResourceData.Builder(item, this)
                        .sourceType(getSourceType())
                        .baseFactor(baseFactor)
                        .sourceSpecifier(dimensionName)
                        .details(details)
                        .build();
            }
        }

        if (bestData != null) return bestData;

        return new BaseResourceData.Builder(item, this)
                .sourceType(BaseResourceData.ResourceSourceType.UNOBTAINABLE)
                .baseFactor(BaseResourceData.ResourceSourceType.UNOBTAINABLE.getBaseMultiplier())
                .details("Not found in any scanned dimension.")
                .build();
    }

    public boolean isReady() {
        return this.geoDatabase != null && this.geoDatabase.isLoaded();
    }

    @Override
    public int getPriority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public String getName() {
        return "EmpiricalBlockSource";
    }

    @Override
    public BaseResourceData.ResourceSourceType getSourceType() {
        return BaseResourceData.ResourceSourceType.EMPIRICAL_BLOCK;
    }
}