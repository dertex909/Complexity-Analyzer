package org.complexityanalyzer.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(NoiseBasedChunkGenerator.class)
public interface NoiseBasedChunkGeneratorAccessor {

    @Invoker("doFill")
    ChunkAccess invokeDoFill(
            Blender blender,
            StructureManager structureManager,
            RandomState randomState,
            ChunkAccess chunk,
            int minCellY,
            int cellCountY
    );

    @Accessor("settings")
    Holder<NoiseGeneratorSettings> getSettings();
}