/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
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

package org.complexityanalyzer.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.complexityanalyzer.geoscan.worldgen.FeatureOrderSavedData;
import org.complexityanalyzer.geoscan.worldgen.IsolatedThreadMarker;
import org.complexityanalyzer.geoscan.worldgen.IsolatedWorldGenRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkGenerator.class)
public class ChunkGeneratorFeatureOrderMixin {

    @Inject(method = "applyBiomeDecoration", at = @At("TAIL"))
    private void recordLiveFeatureCompletion(WorldGenLevel region, ChunkAccess chunk, StructureManager structureManager,
                                             CallbackInfo ci) {
        if (IsolatedThreadMarker.isIsolatedThread() || region instanceof IsolatedWorldGenRegion) return;
        ServerLevel level = region.getLevel();
        FeatureOrderSavedData.get(level).recordIfAbsent(chunk.getPos());
    }
}
