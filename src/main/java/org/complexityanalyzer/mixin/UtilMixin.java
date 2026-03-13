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