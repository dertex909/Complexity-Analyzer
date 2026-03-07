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

package org.complexityanalyzer.analyzer.resource.sources;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.providers.BlockPropertyProvider;
import org.complexityanalyzer.analyzer.resource.providers.TheoreticalDistributionProvider;
import org.complexityanalyzer.analyzer.resource.data.OreDistributionData;

import java.util.Optional;

public class TheoreticalBlockSource implements IResourceSource {

    private final BlockPropertyProvider propertyProvider;
    private final TheoreticalDistributionProvider distributionProvider;

    public TheoreticalBlockSource(BlockPropertyProvider propertyProvider, TheoreticalDistributionProvider distributionProvider) {
        this.propertyProvider = propertyProvider;
        this.distributionProvider = distributionProvider;
    }

    @Override
    public void initialize(Level level) {
    }

    @Override
    public boolean canProvide(Item item) {
        return propertyProvider.getProperties(item).isPresent();
    }

    @Override
    public Optional<BaseResourceData> analyze(Item item) {
        return propertyProvider.getProperties(item).map(properties -> {
            Block block = ((BlockItem) item).getBlock();
            Optional<OreDistributionData> distributionOpt = distributionProvider.getDistribution(block);

            BaseResourceData.ResourceSourceType sourceType;
            double baseFactor;
            String details;
            String specifier = "Unknown Dimension";

            if (distributionOpt.isPresent()) {
                OreDistributionData dist = distributionOpt.get();
                sourceType = determineSourceType(block, true);
                baseFactor = calculateBaseFactor(properties, dist, sourceType);
                specifier = dist.getDimensions().stream()
                        .findFirst()
                        .map(key -> key.location().getPath())
                        .orElse("Unknown Dimension");
                details = buildDetails(properties, dist);
            } else {
                sourceType = determineSourceType(block, false);
                baseFactor = calculateBaseFactor(properties, sourceType);
                details = buildDetails(properties);
            }

            return new BaseResourceData.Builder(item, this)
                    .sourceType(sourceType)
                    .baseFactor(baseFactor)
                    .sourceSpecifier(specifier)
                    .details(details)
                    .build();
        });
    }

    private BaseResourceData.ResourceSourceType determineSourceType(Block block, boolean isOre) {
        if (isOre) {
            return BaseResourceData.ResourceSourceType.ORE;
        }
        String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
        if (blockId.contains("log") || blockId.contains("leaves") || blockId.contains("sapling")) {
            return BaseResourceData.ResourceSourceType.RENEWABLE;
        }
        return BaseResourceData.ResourceSourceType.BLOCK;
    }

    private double calculateBaseFactor(BlockPropertyProvider.BlockProperties properties, BaseResourceData.ResourceSourceType sourceType) {
        return properties.getToolMultiplier() * properties.getHardnessMultiplier() * sourceType.getBaseMultiplier();
    }

    private double calculateBaseFactor(BlockPropertyProvider.BlockProperties properties, OreDistributionData dist, BaseResourceData.ResourceSourceType sourceType) {
        double baseBlockFactor = calculateBaseFactor(properties, sourceType);
        double rarityFactor = 1.0 + Math.log1p(1.0 / Math.max(1.0E-6, dist.getGlobalRarity()));
        double depthFactor = calculateDepthFactor(dist.getMinY(), dist.getMaxY());
        return baseBlockFactor * rarityFactor * depthFactor;
    }

    private double calculateDepthFactor(int minY, int maxY) {
        int avgY = (minY + maxY) / 2;
        if (avgY > 60) return 1.0;
        if (avgY > 0) return 1.2;
        if (avgY > -32) return 1.5;
        return 1.8;
    }

    private String buildDetails(BlockPropertyProvider.BlockProperties properties) {
        return String.format("Tool: %s, Hardness: %.1f", properties.getTierName(), properties.hardness());
    }

    private String buildDetails(BlockPropertyProvider.BlockProperties properties, OreDistributionData dist) {
        String baseDetails = buildDetails(properties);
        String oreDetails = String.format(", Rarity: %.5f%%, Depth: Y %d..%d", dist.getGlobalRarity() * 100.0, dist.getMinY(), dist.getMaxY());
        return baseDetails + oreDetails;
    }

    @Override
    public int getPriority() {
        return 50;
    }

    @Override
    public String getName() {
        return "TheoreticalBlockSource";
    }

    @Override
    public BaseResourceData.ResourceSourceType getSourceType() {
        return BaseResourceData.ResourceSourceType.ORE;
    }
}