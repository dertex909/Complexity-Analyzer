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

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.complexityanalyzer.api.IHardcodedSourceRegistry;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.resource.data.BaseResourceData;

import static net.minecraft.core.BlockPos.ZERO;
import static net.minecraft.core.Direction.UP;
import static net.minecraft.world.InteractionHand.MAIN_HAND;
import static net.neoforged.neoforge.common.ItemAbilities.HOE_TILL;

public final class ToolInteractionsModule {

    private ToolInteractionsModule() {
    }

    public static void register(Level level, IHardcodedSourceRegistry registry) {
        var hitResult = new BlockHitResult(Vec3.atCenterOf(ZERO), UP, ZERO, false);
        var context = new UseOnContext(level, null, MAIN_HAND, new ItemStack(Items.WOODEN_HOE), hitResult);

        for (var block : GameRegistryManager.getAllBlocks()) {
            var state = block.defaultBlockState();
            var tilledState = state.getToolModifiedState(context, HOE_TILL, false);

            if (tilledState != null) {
                var tilledBlock = tilledState.getBlock();
                var originalItem = block.asItem();
                var tilledItem = tilledBlock.asItem();

                if (originalItem != Items.AIR && tilledItem != Items.AIR && originalItem != tilledItem) {
                    var data = new BaseResourceData.Builder(tilledItem)
                            .sourceType(BaseResourceData.ResourceSourceType.BLOCK_TRANSFORMATION)
                            .baseFactor(1.0)
                            .addSourceItem(originalItem, 1.0)
                            .addSourceItem(Items.WOODEN_HOE, 0.01)
                            .details("Tilling with Hoe")
                            .build();

                    registry.register(tilledItem, data);
                }
            }
        }
    }
}