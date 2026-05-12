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

package org.complexityanalyzer.network.cabin;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.complexityanalyzer.ComplexityAnalyzer;

public final class CabinSendSession {

    public enum State {SENDING, FINISHED, ABORTED}

    private final ServerPlayer player;
    private final byte[] payload;
    private final long fileHash;
    private final int chunkCount;
    private final int chunkSize;

    private int nextSequenceToSend;
    private int lastAckedSequence = -1;
    private long lastActivityMs;
    private int retransmitCount;
    private State state = State.SENDING;

    public CabinSendSession(ServerPlayer player, byte[] payload, long fileHash) {
        this.player = player;
        this.payload = payload;
        this.fileHash = fileHash;
        this.chunkSize = CabinPayloads.CHUNK_SIZE;
        this.chunkCount = (payload.length + chunkSize - 1) / chunkSize;
        this.lastActivityMs = System.currentTimeMillis();
    }

    public State getState() {
        return state;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public long getFileHash() {
        return fileHash;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public long getTotalSize() {
        return payload.length;
    }

    public void start(int itemCount, int mobCount, int recipeCount, long generatedAtMs, String serverName) {
        if (state != State.SENDING) return;
        var manifest = new CabinPayloads.ManifestS2C(
                fileHash, payload.length, chunkCount, chunkSize,
                itemCount, mobCount, recipeCount, generatedAtMs, serverName
        );
        PacketDistributor.sendToPlayer(player, manifest);
        pump();
    }

    public void onAck(int upToSequence) {
        if (state != State.SENDING) return;
        if (upToSequence > lastAckedSequence && upToSequence < nextSequenceToSend) {
            lastAckedSequence = upToSequence;
            retransmitCount = 0;
            lastActivityMs = System.currentTimeMillis();
        }
        if (lastAckedSequence >= chunkCount - 1) {
            finish();
            return;
        }
        pump();
    }

    public void abort(String reason) {
        if (state == State.ABORTED || state == State.FINISHED) return;
        state = State.ABORTED;
        try {
            PacketDistributor.sendToPlayer(player, new CabinPayloads.ErrorS2C(reason));
        } catch (Throwable ignored) {
        }
        ComplexityAnalyzer.LOGGER.warn("[Cabin] Aborted send to {}: {}", player.getName().getString(), reason);
    }

    public void tick(long nowMs) {
        if (state != State.SENDING) return;
        long sinceActivity = nowMs - lastActivityMs;
        if (sinceActivity > CabinPayloads.ACK_TIMEOUT_MS) {
            if (retransmitCount >= CabinPayloads.MAX_RETRANSMITS) {
                abort("ACK timeout after " + CabinPayloads.MAX_RETRANSMITS + " retries");
                return;
            }
            retransmitCount++;
            lastActivityMs = nowMs;
            int rewind = Math.max(0, lastAckedSequence + 1);
            ComplexityAnalyzer.LOGGER.debug("[Cabin] ACK timeout for {}: retry={}, rewinding to seq {}",
                    player.getName().getString(), retransmitCount, rewind);
            nextSequenceToSend = rewind;
            pump();
        }
    }

    private void pump() {
        if (state != State.SENDING) return;
        int windowEnd = lastAckedSequence + CabinPayloads.WINDOW_SIZE;
        while (nextSequenceToSend < chunkCount && nextSequenceToSend <= windowEnd) {
            sendChunk(nextSequenceToSend);
            nextSequenceToSend++;
        }
    }

    private void sendChunk(int seq) {
        int start = seq * chunkSize;
        int end = Math.min(start + chunkSize, payload.length);
        int len = end - start;
        byte[] data = new byte[len];
        System.arraycopy(payload, start, data, 0, len);
        try {
            PacketDistributor.sendToPlayer(player, new CabinPayloads.ChunkS2C(seq, data));
        } catch (Throwable t) {
            abort("send failed: " + t.getMessage());
        }
    }

    private void finish() {
        if (state != State.SENDING) return;
        state = State.FINISHED;
        try {
            PacketDistributor.sendToPlayer(player, new CabinPayloads.FinishS2C(fileHash));
        } catch (Throwable ignored) {
        }
    }
}
