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

package org.complexityanalyzer.export.cabin.io;

import net.minecraft.server.MinecraftServer;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.ThreadPoolManager;
import org.complexityanalyzer.export.cabin.api.CabinFormat;
import org.complexityanalyzer.export.cabin.api.LeBuf;
import org.complexityanalyzer.export.cabin.builder.CabinBuilder;
import org.complexityanalyzer.network.web.CabinWsHub;
import org.complexityanalyzer.util.ModFileManager;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

public final class CabinBackgroundService {

    private static final CabinBackgroundService INSTANCE = new CabinBackgroundService();
    private final AtomicReference<Snapshot> current = new AtomicReference<>(null);
    private final AtomicReference<Status> status = new AtomicReference<>(Status.IDLE);
    private final AtomicReference<CompletableFuture<Snapshot>> inflight = new AtomicReference<>(null);
    private final AtomicReference<Throwable> lastError = new AtomicReference<>(null);
    private volatile boolean rebuildPending = false;

    private CabinBackgroundService() {
    }

    public static CabinBackgroundService getInstance() {
        return INSTANCE;
    }

    private static Snapshot parseHeader(byte[] bytes, long hash) {
        int itemCount = 0;
        int mobCount = 0;
        int recipeCount = 0;
        try {
            var reader = new CabinReader(bytes);
            byte[] meta = reader.readSection(CabinFormat.SEC_META);
            int offset = 24;
            itemCount = LeBuf.readI32(meta, offset);
            mobCount = LeBuf.readI32(meta, offset + 4);
            recipeCount = LeBuf.readI32(meta, offset + 12);
        } catch (Throwable ignored) {
        }
        return new Snapshot(bytes, hash, System.currentTimeMillis(), itemCount, mobCount, recipeCount);
    }

    @Nullable
    public Snapshot getSnapshot() {
        return current.get();
    }

    public Status getStatus() {
        return status.get();
    }

    @Nullable
    public Throwable getLastError() {
        return lastError.get();
    }

    public CompletableFuture<Snapshot> regenerateAsync(MinecraftServer server, AnalysisEngine engine, String modVersion) {
        var existing = inflight.get();
        if (existing != null && !existing.isDone()) {
            rebuildPending = true;
            return existing;
        }

        ExecutorService pool;
        try {
            pool = ThreadPoolManager.getInstance().getComputePool();
        } catch (Throwable t) {
            var failed = new CompletableFuture<Snapshot>();
            failed.completeExceptionally(t);
            return failed;
        }

        var future = new CompletableFuture<Snapshot>();
        if (!inflight.compareAndSet(null, future)) return inflight.get();
        status.set(Status.BUILDING);
        rebuildPending = false;

        pool.execute(() -> {
            try {
                var snap = doBuild(server, engine, modVersion);
                current.set(snap);
                status.set(Status.READY);
                lastError.set(null);
                CabinWsHub.broadcast(Long.toHexString(snap.fileHash()));
                future.complete(snap);
            } catch (Throwable t) {
                status.set(Status.FAILED);
                lastError.set(t);
                ComplexityAnalyzer.LOGGER.error("[Cabin] Background build failed", t);
                future.completeExceptionally(t);
            } finally {
                inflight.set(null);
                if (rebuildPending) {
                    rebuildPending = false;
                    regenerateAsync(server, engine, modVersion);
                }
            }
        });
        return future;
    }

    private Snapshot doBuild(MinecraftServer server, AnalysisEngine engine, String modVersion) throws IOException {
        long t0 = System.currentTimeMillis();
        String serverName = server.getServerModName();
        String motd = server.getMotd();
        String name = !motd.isEmpty() ? motd : serverName;

        var builder = new CabinBuilder(engine, name, modVersion, server.registryAccess());
        var sections = builder.build();
        byte[] bytes = CabinWriter.writeToBytes(sections);
        long hash = LeBuf.readI64(bytes, 24);

        persistToFile(server, bytes);

        long elapsed = System.currentTimeMillis() - t0;
        var snap = parseHeader(bytes, hash);
        ComplexityAnalyzer.LOGGER.info("[Cabin] Wrote {} bytes (hash={}) in {} ms",
                bytes.length, Long.toHexString(hash), elapsed);
        return snap;
    }

    private void persistToFile(MinecraftServer server, byte[] bytes) throws IOException {
        var target = ModFileManager.resolve(server, "cabin", "latest.cabin");
        ModFileManager.writeBytesAtomic(target, bytes);
    }

    public void clear() {
        current.set(null);
        status.set(Status.IDLE);
        lastError.set(null);
        rebuildPending = false;
        var f = inflight.getAndSet(null);
        if (f != null && !f.isDone()) f.cancel(false);
    }

    public enum Status {IDLE, BUILDING, READY, FAILED}

    public record Snapshot(byte[] bytes, long fileHash, long generatedAtMs, int itemCount, int mobCount,
                           int recipeCount) {
    }
}