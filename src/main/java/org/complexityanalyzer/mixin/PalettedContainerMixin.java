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

import net.minecraft.world.level.chunk.PalettedContainer;
import org.complexityanalyzer.geoscan.worldgen.IsolatedThreadMarker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PalettedContainer.class)
public class PalettedContainerMixin {

    @Inject(method = "acquire", at = @At("HEAD"), cancellable = true)
    private void onAcquire(CallbackInfo ci) {
        if (IsolatedThreadMarker.isIsolatedThread()) ci.cancel();
    }

    @Inject(method = "release", at = @At("HEAD"), cancellable = true)
    private void onRelease(CallbackInfo ci) {
        if (IsolatedThreadMarker.isIsolatedThread()) ci.cancel();
    }
}