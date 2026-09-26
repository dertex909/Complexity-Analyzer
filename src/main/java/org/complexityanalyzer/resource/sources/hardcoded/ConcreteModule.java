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
import net.minecraft.world.level.block.ConcretePowderBlock;
import org.complexityanalyzer.api.IHardcodedSourceRegistry;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.mixin.accessors.ConcretePowderBlockAccessor;
import org.complexityanalyzer.resource.data.BaseResourceData;

import static net.minecraft.world.item.Items.AIR;

public final class ConcreteModule {

    private ConcreteModule() {
    }

    public static void register(Level ignored, IHardcodedSourceRegistry registry) {
        for (var block : GameRegistryManager.getAllBlocks()) {
            if (block instanceof ConcretePowderBlock powder) {
                var concrete = ((ConcretePowderBlockAccessor) powder).getConcrete();
                var powderItem = block.asItem();
                var concreteItem = concrete.asItem();

                if (powderItem != AIR && concreteItem != AIR) {
                    var data = new BaseResourceData.Builder(concreteItem)
                            .sourceType(BaseResourceData.ResourceSourceType.BLOCK_TRANSFORMATION)
                            .baseFactor(1.0)
                            .addSourceItem(powderItem, 1.0)
                            .details("Hydrating concrete powder with water")
                            .build();

                    registry.register(concreteItem, data);
                }
            }
        }
    }
}