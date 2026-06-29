package org.complexityanalyzer.mixin;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.WritableLevelData;
import org.complexityanalyzer.core.ThreadSafeDelegatingRandomSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(Level.class)
public abstract class LevelMixin {
    @Shadow
    @Final
    @Mutable
    public RandomSource random;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onLevelInit(WritableLevelData p_270739_, ResourceKey<Level> p_270683_, RegistryAccess p_270200_,
                             Holder<DimensionType> p_270240_, Supplier<ProfilerFiller> p_270692_, boolean p_270904_,
                             boolean p_270470_, long p_270248_, int p_270466_, CallbackInfo ci) {
        this.random = new ThreadSafeDelegatingRandomSource(this.random);
    }
}