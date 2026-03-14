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
        super(chunk != null ? chunk.getPos() : new ChunkPos(0, 0));
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
        if (chunk != null) {
            ChunkStatus persisted = chunk.getPersistedStatus();
            if (persisted.isOrAfter(status)) return chunk;
            return chunk;
        }
        return null;
    }

    @Nullable
    @Override
    public ChunkAccess getChunkIfPresent(@NotNull ChunkStatus status) {
        return getChunkIfPresentUnchecked(status);
    }

    @Override
    public @NotNull ChunkPos getPos() {
        return chunk != null ? chunk.getPos() : new ChunkPos(0, 0);
    }

    @Nullable
    @Override
    public ChunkStatus getPersistedStatus() {
        if (chunk != null) return chunk.getPersistedStatus();
        return ChunkStatus.EMPTY;
    }

    @Nullable
    @Override
    public ChunkAccess getLatestChunk() {
        return chunk;
    }
}