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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.client.cabin;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.export.cabin.CabinBuilder;
import org.complexityanalyzer.network.cabin.CabinPayloads;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Client-side reassembly buffer for an inbound .cabin transfer.
 * <p>
 * Single inflight transfer at a time (a new manifest replaces any prior partial state).
 * Chunks are written into a pre-allocated buffer by sequence id so out-of-order delivery
 * is tolerated, though the server's sliding-window sender currently always sends in order.
 * <p>
 * On {@link #onFinish(long)} the file hash is verified, persisted to
 * {@link ClientCabinStorage} and the cached snapshot reference is updated atomically.
 */
public final class CabinReceiver {

    public record CachedSnapshot(byte[] bytes, long fileHash, long receivedAtMs,
                                 int itemCount, int mobCount, int recipeCount,
                                 long generatedAtMs, String serverName) {
    }

    public enum State {IDLE, RECEIVING, READY, FAILED}

    private static final CabinReceiver INSTANCE = new CabinReceiver();

    public static CabinReceiver getInstance() {
        return INSTANCE;
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicReference<CachedSnapshot> latest = new AtomicReference<>(null);
    private volatile CabinPayloads.ManifestS2C manifest;
    private volatile byte[] buffer;
    private volatile int receivedChunks;
    private volatile int receivedBytes;
    private static final int ACK_INTERVAL = 4;

    private CabinReceiver() {
    }

    public State getState() {
        return state.get();
    }

    public CachedSnapshot getCached() {
        return latest.get();
    }

    public CabinPayloads.ManifestS2C getManifest() {
        return manifest;
    }

    public int getReceivedChunks() {
        return receivedChunks;
    }

    public int getReceivedBytes() {
        return receivedBytes;
    }

    public int getTotalChunks() {
        CabinPayloads.ManifestS2C m = manifest;
        return m != null ? m.chunkCount() : 0;
    }

    public long getTotalSize() {
        CabinPayloads.ManifestS2C m = manifest;
        return m != null ? m.totalSize() : 0L;
    }

    public void onManifest(CabinPayloads.ManifestS2C m) {
        this.manifest = m;
        long total = m.totalSize();
        if (total <= 0 || total > Integer.MAX_VALUE - 64) {
            failTransfer("invalid manifest size: " + total);
            return;
        }
        this.buffer = new byte[(int) total];
        this.receivedChunks = 0;
        this.receivedBytes = 0;
        this.state.set(State.RECEIVING);
        CabinProgressOverlay.beginReceive(m);
    }

    public void onChunk(CabinPayloads.ChunkS2C c) {
        if (state.get() != State.RECEIVING) return;
        CabinPayloads.ManifestS2C m = manifest;
        if (m == null || buffer == null) return;
        long offset = (long) c.sequenceId() * m.chunkSize();
        if (offset + c.data().length > buffer.length) {
            failTransfer("chunk overrun seq=" + c.sequenceId());
            return;
        }
        System.arraycopy(c.data(), 0, buffer, (int) offset, c.data().length);
        int currentChunks = receivedChunks + 1;
        int currentBytes = receivedBytes + c.data().length;
        receivedChunks = currentChunks;
        receivedBytes = currentBytes;
        CabinProgressOverlay.update(currentBytes, m.totalSize());

        if (currentChunks % ACK_INTERVAL == 0 || currentChunks == m.chunkCount()) {
            ackUpTo(c.sequenceId());
        }
    }

    public void onFinish(long expectedHash) {
        if (state.get() != State.RECEIVING) return;
        byte[] data = buffer;
        CabinPayloads.ManifestS2C m = manifest;
        if (data == null || m == null) {
            failTransfer("finish without buffer");
            return;
        }
        long actual = CabinBuilder.computeFileHash(data);
        if (actual != expectedHash) {
            failTransfer("hash mismatch: expected " + Long.toHexString(expectedHash)
                    + " got " + Long.toHexString(actual));
            return;
        }
        if (!ClientCabinStorage.validate(data)) {
            failTransfer("downloaded cabin failed validation");
            return;
        }
        boolean persisted = ClientCabinStorage.writeCabin(data, actual);
        if (!persisted) {
            failTransfer("failed to persist cabin to disk");
            return;
        }
        CachedSnapshot snap = new CachedSnapshot(
                data, actual, System.currentTimeMillis(),
                m.itemCount(), m.mobCount(), m.recipeCount(),
                m.generatedAtMs(), m.serverName()
        );
        latest.set(snap);
        state.set(State.READY);
        manifest = null;
        buffer = null;
        CabinProgressOverlay.complete(snap);
        sendChat(Component.literal("✅ Cabin received (")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal(humanBytes(data.length)).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(", " + snap.itemCount + " items, "
                                + snap.mobCount + " mobs, " + snap.recipeCount + " recipes)")
                        .withStyle(ChatFormatting.GREEN)));
    }

    public void onUpToDate(long fileHash, long generatedAtMs) {
        byte[] disk = ClientCabinStorage.readCabin();
        if (disk != null && ClientCabinStorage.validate(disk)) {
            long h = CabinBuilder.computeFileHash(disk);
            if (h == fileHash) {
                CachedSnapshot snap = new CachedSnapshot(
                        disk, fileHash, System.currentTimeMillis(), 0, 0, 0, generatedAtMs, "");
                latest.set(snap);
                state.set(State.READY);
                CabinProgressOverlay.upToDate();
                sendChat(Component.literal("✅ Cabin is up-to-date (hash " + Long.toHexString(fileHash) + ")")
                        .withStyle(ChatFormatting.GREEN));
                return;
            }
        }
        sendChat(Component.literal("⚠ Local cabin is missing or stale; requesting fresh copy...")
                .withStyle(ChatFormatting.YELLOW));
        request(CabinPayloads.UNKNOWN_HASH);
    }

    public void onError(String reason) {
        failTransfer(reason);
    }

    public void onPending(String message) {
        sendChat(Component.literal("⏳ " + message).withStyle(ChatFormatting.YELLOW));
    }

    public void onPollHash() {
        byte[] disk = ClientCabinStorage.readCabin();
        long h = CabinPayloads.UNKNOWN_HASH;
        if (disk != null && ClientCabinStorage.validate(disk)) h = CabinBuilder.computeFileHash(disk);
        request(h);
    }

    public void request(long knownHash) {
        try {
            PacketDistributor.sendToServer(new CabinPayloads.RequestC2S(knownHash));
        } catch (Throwable t) {
            failTransfer("failed to request: " + t.getMessage());
        }
    }

    public void cancel() {
        try {
            PacketDistributor.sendToServer(new CabinPayloads.CancelC2S());
        } catch (Throwable ignored) {
        }
        manifest = null;
        buffer = null;
        receivedBytes = 0;
        receivedChunks = 0;
        state.set(State.IDLE);
        CabinProgressOverlay.dismiss();
    }

    public void reset() {
        manifest = null;
        buffer = null;
        receivedBytes = 0;
        receivedChunks = 0;
        state.set(State.IDLE);
    }

    private void ackUpTo(int seq) {
        try {
            PacketDistributor.sendToServer(new CabinPayloads.AckC2S(seq));
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[Cabin] Failed to send ACK: {}", t.getMessage());
        }
    }

    private void failTransfer(String reason) {
        ComplexityAnalyzer.LOGGER.warn("[Cabin] Receive failed: {}", reason);
        manifest = null;
        buffer = null;
        receivedBytes = 0;
        receivedChunks = 0;
        state.set(State.FAILED);
        CabinProgressOverlay.fail(reason);
        sendChat(Component.literal("❌ Cabin transfer failed: " + reason).withStyle(ChatFormatting.RED));
    }

    private void sendChat(Component component) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.displayClientMessage(component, false);
        } catch (Throwable ignored) {
        }
    }

    private static String humanBytes(long n) {
        if (n < 1024) return n + " B";
        double k = n / 1024.0;
        if (k < 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", m);
        return String.format(java.util.Locale.ROOT, "%.2f GB", m / 1024.0);
    }
}
