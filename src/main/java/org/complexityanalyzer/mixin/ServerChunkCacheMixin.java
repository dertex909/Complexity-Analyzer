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

package org.complexityanalyzer.mixin;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {

    @Shadow
    @Final
    public ServerLevel level;

    @Inject(method = "getChunk", at = @At("HEAD"), cancellable = true)
    private void onGetChunk(int x, int z, ChunkStatus status, boolean requireChunk, CallbackInfoReturnable<ChunkAccess> cir) {
        if (ThreadPoolManager.isComplexityThread()) {
            if (!requireChunk) {
                cir.setReturnValue(null);
                return;
            }

            var plainsBiome = this.level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
            cir.setReturnValue(new EmptyLevelChunk(this.level, new ChunkPos(x, z), plainsBiome));
        }
    }
}