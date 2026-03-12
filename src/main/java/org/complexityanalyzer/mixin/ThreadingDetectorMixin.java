package org.complexityanalyzer.mixin;

import net.minecraft.util.ThreadingDetector;
import org.complexityanalyzer.geoscan.worldgen.IsolatedThreadMarker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ThreadingDetector.class)
public class ThreadingDetectorMixin {

    @Inject(method = "checkAndLock", at = @At("HEAD"), cancellable = true)
    private void onCheckAndLock(CallbackInfo ci) {
        if (IsolatedThreadMarker.isIsolatedThread()) ci.cancel();
    }

    @Inject(method = "checkAndUnlock", at = @At("HEAD"), cancellable = true)
    private void onCheckAndUnlock(CallbackInfo ci) {
        if (IsolatedThreadMarker.isIsolatedThread()) ci.cancel();
    }
}