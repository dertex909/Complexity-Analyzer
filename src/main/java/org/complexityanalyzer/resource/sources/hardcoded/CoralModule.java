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

package org.complexityanalyzer.resource.sources.hardcoded;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import org.complexityanalyzer.api.IHardcodedSourceRegistry;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.mixin.accessors.CoralBlockAccessor;
import org.complexityanalyzer.mixin.accessors.CoralFanBlockAccessor;
import org.complexityanalyzer.mixin.accessors.CoralPlantBlockAccessor;
import org.complexityanalyzer.mixin.accessors.CoralWallFanBlockAccessor;
import org.complexityanalyzer.resource.data.BaseResourceData;
import org.jetbrains.annotations.Nullable;

import static net.minecraft.world.item.Items.AIR;

public final class CoralModule {

    private CoralModule() {
    }

    public static void register(Level ignored, IHardcodedSourceRegistry registry) {
        for (var block : GameRegistryManager.getAllBlocks()) {
            var deadBlock = getDeadBlock(block);

            if (deadBlock != null) {
                var aliveItem = block.asItem();
                var deadItem = deadBlock.asItem();

                if (aliveItem != AIR && deadItem != AIR && aliveItem != deadItem) {
                    var data = new BaseResourceData.Builder(deadItem)
                            .sourceType(BaseResourceData.ResourceSourceType.BLOCK_TRANSFORMATION)
                            .baseFactor(1.0)
                            .addSourceItem(aliveItem, 1.0)
                            .details("Coral drying out")
                            .build();

                    registry.register(deadItem, data);
                }
            }
        }
    }

    private static @Nullable Block getDeadBlock(Block block) {
        if (block instanceof CoralBlock b) return ((CoralBlockAccessor) b).getDeadBlock();
        if (block instanceof CoralPlantBlock b) return ((CoralPlantBlockAccessor) b).getDeadBlock();
        if (block instanceof CoralFanBlock b) return ((CoralFanBlockAccessor) b).getDeadBlock();
        if (block instanceof CoralWallFanBlock b) return ((CoralWallFanBlockAccessor) b).getDeadBlock();
        return null;
    }
}