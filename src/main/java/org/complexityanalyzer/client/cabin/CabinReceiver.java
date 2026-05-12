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
import net.minecraft.network.chat.*;
import net.neoforged.neoforge.network.PacketDistributor;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.export.cabin.builder.CabinBuilder;
import org.complexityanalyzer.network.cabin.CabinPayloads;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class CabinReceiver {
    public record CachedSnapshot(byte[] bytes, long fileHash, long receivedAtMs, int itemCount, int mobCount,
                                 int recipeCount, long generatedAtMs, String serverName) {
    }

    public enum State {IDLE, RECEIVING, READY, FAILED}

    private static final CabinReceiver INSTANCE = new CabinReceiver();

    public static CabinReceiver getInstance() {
        return INSTANCE;
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicReference<CachedSnapshot> latest = new AtomicReference<>();
    private final AtomicBoolean autoOpenViewer = new AtomicBoolean(false);
    private final AtomicInteger receivedChunks = new AtomicInteger(0);
    private final AtomicInteger receivedBytes = new AtomicInteger(0);
    private volatile CabinPayloads.ManifestS2C manifest;
    private volatile byte[] buffer;
    private static final int ACK_INTERVAL = 4;

    private CabinReceiver() {
    }

    public State getState() {
        return state.get();
    }

    public CachedSnapshot getCached() {
        return latest.get();
    }

    public void onManifest(CabinPayloads.ManifestS2C m) {
        long total = m.totalSize();
        if (total <= 0 || total > Integer.MAX_VALUE - 64) {
            fail("invalid manifest size: " + total);
            return;
        }
        this.manifest = m;
        this.buffer = new byte[(int) total];
        this.receivedChunks.set(0);
        this.receivedBytes.set(0);
        this.state.set(State.RECEIVING);
        CabinProgressOverlay.beginReceive(m);
    }

    public void onChunk(CabinPayloads.ChunkS2C c) {
        if (state.get() != State.RECEIVING || manifest == null || buffer == null) return;
        long offset = (long) c.sequenceId() * manifest.chunkSize();
        if (offset + c.data().length > buffer.length) {
            fail("chunk overrun seq=" + c.sequenceId());
            return;
        }
        System.arraycopy(c.data(), 0, buffer, (int) offset, c.data().length);
        int chunks = receivedChunks.incrementAndGet();
        int bytes = receivedBytes.addAndGet(c.data().length);

        CabinProgressOverlay.update(bytes, manifest.totalSize());
        if (chunks % ACK_INTERVAL == 0 || chunks == manifest.chunkCount()) ack(c.sequenceId());
    }

    public void onFinish(long expectedHash) {
        if (state.get() != State.RECEIVING || buffer == null || manifest == null) return;
        byte[] data = buffer;
        var m = manifest;

        long actual = CabinBuilder.computeFileHash(data);
        if (actual != expectedHash) {
            fail("hash mismatch: expected " + Long.toHexString(expectedHash) + " got " + Long.toHexString(actual));
            return;
        }
        if (!ClientCabinStorage.validate(data) || !ClientCabinStorage.writeCabin(data, actual)) {
            fail("storage validation or write failed");
            return;
        }

        CachedSnapshot snap = new CachedSnapshot(data, actual, System.currentTimeMillis(),
                m.itemCount(), m.mobCount(), m.recipeCount(), m.generatedAtMs(), m.serverName());

        latest.set(snap);
        state.set(State.READY);
        manifest = null;
        buffer = null;

        CabinProgressOverlay.complete(snap);
        chat(Component.literal("✅ Cabin received (").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(CabinProgressOverlay.human(data.length)).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(String.format(", %d items, %d mobs, %d recipes)", snap.itemCount, snap.mobCount, snap.recipeCount))
                        .withStyle(ChatFormatting.GREEN)));

        if (autoOpenViewer.getAndSet(false)) openViewer();
    }

    public void onUpToDate(long hash, long time) {
        byte[] disk = ClientCabinStorage.readCabin();
        if (disk != null && ClientCabinStorage.validate(disk) && CabinBuilder.computeFileHash(disk) == hash) {
            CachedSnapshot snap = new CachedSnapshot(disk, hash, System.currentTimeMillis(), 0, 0, 0, time, "");
            latest.set(snap);
            state.set(State.READY);
            CabinProgressOverlay.upToDate();
            chat("✅ Cabin is up-to-date (" + Long.toHexString(hash) + ")", ChatFormatting.GREEN);
            if (autoOpenViewer.getAndSet(false)) openViewer();
        } else {
            chat("⚠ Local cabin missing or stale; requesting fresh copy...", ChatFormatting.YELLOW);
            request(CabinPayloads.UNKNOWN_HASH);
        }
    }

    public void onError(String reason) {
        fail(reason);
    }

    public void onPending(String msg) {
        chat("⏳ " + msg, ChatFormatting.YELLOW);
    }

    public void onPollHash() {
        autoOpenViewer.set(true);
        byte[] disk = ClientCabinStorage.readCabin();
        long h = (disk != null && ClientCabinStorage.validate(disk)) ? CabinBuilder.computeFileHash(disk) : 0;
        request(h);
    }

    public void request(long hash) {
        try {
            PacketDistributor.sendToServer(new CabinPayloads.RequestC2S(hash));
        } catch (Throwable t) {
            fail("request failed: " + t.getMessage());
        }
    }

    private void openViewer() {
        String url = CabinHttpServer.getInstance().start();
        if (url == null) {
            chat("❌ Failed to start viewer", ChatFormatting.RED);
            return;
        }
        if (!CabinHttpServer.getInstance().openInBrowser()) {
            MutableComponent link = Component.literal("[Open viewer]")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(url))));
            chat(Component.literal("⚠ Could not auto-open browser — ").withStyle(ChatFormatting.YELLOW).append(link));
        }
    }

    private void ack(int seq) {
        try {
            PacketDistributor.sendToServer(new CabinPayloads.AckC2S(seq));
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[Cabin] ACK failed: {}", t.getMessage());
        }
    }

    private void fail(String reason) {
        autoOpenViewer.set(false);
        ComplexityAnalyzer.LOGGER.warn("[Cabin] Receive failed: {}", reason);
        manifest = null;
        buffer = null;
        state.set(State.FAILED);
        CabinProgressOverlay.fail(reason);
        chat("❌ Cabin transfer failed: " + reason, ChatFormatting.RED);
    }

    private void chat(String text, ChatFormatting style) {
        chat(Component.literal(text).withStyle(style));
    }

    private void chat(Component comp) {
        try {
            var mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.displayClientMessage(comp, false);
        } catch (Throwable ignored) {
        }
    }
}