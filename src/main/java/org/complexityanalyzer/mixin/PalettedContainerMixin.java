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