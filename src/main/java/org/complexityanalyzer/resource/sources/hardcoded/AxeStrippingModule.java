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

import net.minecraft.world.item.AxeItem;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.api.IHardcodedSourceRegistry;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.resource.data.BaseResourceData;

import static net.minecraft.world.item.Items.AIR;

public final class AxeStrippingModule {

    private AxeStrippingModule() {
    }

    public static void register(Level ignored, IHardcodedSourceRegistry registry) {
        for (var block : GameRegistryManager.getAllBlocks()) {
            var defaultState = block.defaultBlockState();
            var strippedState = AxeItem.getAxeStrippingState(defaultState);

            if (strippedState != null) {
                var strippedBlock = strippedState.getBlock();
                var originalItem = block.asItem();
                var strippedItem = strippedBlock.asItem();

                if (originalItem != AIR && strippedItem != AIR && originalItem != strippedItem) {
                    var data = new BaseResourceData.Builder(strippedItem)
                            .sourceType(BaseResourceData.ResourceSourceType.BLOCK_TRANSFORMATION)
                            .baseFactor(1.0)
                            .addSourceItem(originalItem, 1.0)
                            .details("Stripping with Axe")
                            .build();

                    registry.register(strippedItem, data);
                }
            }
        }
    }
}