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

import net.minecraft.Util;
import org.complexityanalyzer.geoscan.worldgen.IsolatedThreadMarker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.Executor;

@Mixin(Util.class)
public class UtilMixin {

    @Unique
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    @Inject(method = "backgroundExecutor", at = @At("HEAD"), cancellable = true)
    private static void onBackgroundExecutor(CallbackInfoReturnable<Executor> cir) {
        if (IsolatedThreadMarker.isIsolatedThread()) cir.setReturnValue(DIRECT_EXECUTOR);
    }
}