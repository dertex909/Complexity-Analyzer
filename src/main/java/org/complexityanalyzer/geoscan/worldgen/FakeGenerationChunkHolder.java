package org.complexityanalyzer.geoscan.worldgen;

import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

public class FakeGenerationChunkHolder extends GenerationChunkHolder {

    private final ChunkAccess chunk;

    public FakeGenerationChunkHolder(ChunkAccess chunk) {
        super(chunk.getPos());
        this.chunk = chunk;
    }

    @Override
    public int getTicketLevel() {
        return 33;
    }

    @Override
    public int getQueueLevel() {
        return 0;
    }

    @Nullable
    @Override
    public ChunkAccess getChunkIfPresentUnchecked(@NotNull ChunkStatus status) {
        if (chunk != null && chunk.getPersistedStatus().isOrAfter(status)) return chunk;
        return null;
    }

    @Nullable
    @Override
    public ChunkAccess getChunkIfPresent(@NotNull ChunkStatus status) {
        return getChunkIfPresentUnchecked(status);
    }

    @Override
    public @NotNull ChunkPos getPos() {
        return chunk.getPos();
    }

    @Nullable
    @Override
    public ChunkStatus getPersistedStatus() {
        return chunk != null ? chunk.getPersistedStatus() : null;
    }

    @Nullable
    @Override
    public ChunkAccess getLatestChunk() {
        return chunk;
    }
}