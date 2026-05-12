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

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.jetbrains.annotations.NotNull;

public final class CabinPayloads {

    public static final int CHUNK_SIZE = 480 * 1024;
    public static final int WINDOW_SIZE = 8;
    public static final long ACK_TIMEOUT_MS = 5_000L;
    public static final int MAX_RETRANSMITS = 6;
    public static final int MAX_BYTES_TOTAL = 256 * 1024 * 1024;

    public static final long UNKNOWN_HASH = 0L;

    private CabinPayloads() {
    }

    public record RequestC2S(long knownHash) implements CustomPacketPayload {
        public static final Type<RequestC2S> TYPE = new Type<>(id("cabin_request"));
        public static final StreamCodec<ByteBuf, RequestC2S> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_LONG, RequestC2S::knownHash, RequestC2S::new);

        @Override
        public @NotNull Type<RequestC2S> type() {
            return TYPE;
        }
    }

    public record AckC2S(int upToSequence) implements CustomPacketPayload {
        public static final Type<AckC2S> TYPE = new Type<>(id("cabin_ack"));
        public static final StreamCodec<ByteBuf, AckC2S> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, AckC2S::upToSequence, AckC2S::new);

        @Override
        public @NotNull Type<AckC2S> type() {
            return TYPE;
        }
    }

    public record CancelC2S() implements CustomPacketPayload {
        public static final Type<CancelC2S> TYPE = new Type<>(id("cabin_cancel"));
        public static final StreamCodec<ByteBuf, CancelC2S> STREAM_CODEC = StreamCodec.unit(new CancelC2S());

        @Override
        public @NotNull Type<CancelC2S> type() {
            return TYPE;
        }
    }

    public record ManifestS2C(long fileHash, long totalSize, int chunkCount, int chunkSize, int itemCount, int mobCount,
                              int recipeCount, long generatedAtMs, String serverName) implements CustomPacketPayload {
        public static final Type<ManifestS2C> TYPE = new Type<>(id("cabin_manifest"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ManifestS2C> STREAM_CODEC = StreamCodec.of(
                (buf, m) -> {
                    buf.writeLong(m.fileHash);
                    buf.writeLong(m.totalSize);
                    buf.writeVarInt(m.chunkCount);
                    buf.writeVarInt(m.chunkSize);
                    buf.writeVarInt(m.itemCount);
                    buf.writeVarInt(m.mobCount);
                    buf.writeVarInt(m.recipeCount);
                    buf.writeLong(m.generatedAtMs);
                    buf.writeUtf(m.serverName, 256);
                }, buf -> new ManifestS2C(buf.readLong(), buf.readLong(), buf.readVarInt(), buf.readVarInt(),
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readLong(), buf.readUtf(256))
        );

        @Override
        public @NotNull Type<ManifestS2C> type() {
            return TYPE;
        }
    }

    public record ChunkS2C(int sequenceId, byte[] data, int offset, int length) implements CustomPacketPayload {
        public static final Type<ChunkS2C> TYPE = new Type<>(id("cabin_chunk"));

        public ChunkS2C(int sequenceId, byte[] data) {
            this(sequenceId, data, 0, data.length);
        }

        public static final StreamCodec<ByteBuf, ChunkS2C> STREAM_CODEC = StreamCodec.of(
                (buf, c) -> {
                    ByteBufCodecs.VAR_INT.encode(buf, c.sequenceId);
                    ByteBufCodecs.VAR_INT.encode(buf, c.length);
                    buf.writeBytes(c.data, c.offset, c.length);
                },
                buf -> {
                    int seq = ByteBufCodecs.VAR_INT.decode(buf);
                    int len = ByteBufCodecs.VAR_INT.decode(buf);
                    if (len < 0 || len > CHUNK_SIZE * 2) throw new IllegalStateException("Bad chunk length: " + len);
                    byte[] data = new byte[len];
                    buf.readBytes(data);
                    return new ChunkS2C(seq, data);
                }
        );

        @Override
        public @NotNull Type<ChunkS2C> type() {
            return TYPE;
        }
    }

    public record FinishS2C(long fileHash) implements CustomPacketPayload {
        public static final Type<FinishS2C> TYPE = new Type<>(id("cabin_finish"));
        public static final StreamCodec<ByteBuf, FinishS2C> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_LONG, FinishS2C::fileHash, FinishS2C::new);

        @Override
        public @NotNull Type<FinishS2C> type() {
            return TYPE;
        }
    }

    public record UpToDateS2C(long fileHash, long generatedAtMs) implements CustomPacketPayload {
        public static final Type<UpToDateS2C> TYPE = new Type<>(id("cabin_up_to_date"));
        public static final StreamCodec<ByteBuf, UpToDateS2C> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, UpToDateS2C::fileHash, ByteBufCodecs.VAR_LONG, UpToDateS2C::generatedAtMs, UpToDateS2C::new
        );

        @Override
        public @NotNull Type<UpToDateS2C> type() {
            return TYPE;
        }
    }

    public record ErrorS2C(String reason) implements CustomPacketPayload {
        public static final Type<ErrorS2C> TYPE = new Type<>(id("cabin_error"));
        public static final StreamCodec<ByteBuf, ErrorS2C> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(1024), ErrorS2C::reason, ErrorS2C::new
        );

        @Override
        public @NotNull Type<ErrorS2C> type() {
            return TYPE;
        }
    }

    public record PendingS2C(String message) implements CustomPacketPayload {
        public static final Type<PendingS2C> TYPE = new Type<>(id("cabin_pending"));
        public static final StreamCodec<ByteBuf, PendingS2C> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(256), PendingS2C::message, PendingS2C::new
        );

        @Override
        public @NotNull Type<PendingS2C> type() {
            return TYPE;
        }
    }

    public record PollHashS2C() implements CustomPacketPayload {
        public static final Type<PollHashS2C> TYPE = new Type<>(id("cabin_poll_hash"));
        public static final StreamCodec<ByteBuf, PollHashS2C> STREAM_CODEC = StreamCodec.unit(new PollHashS2C());

        @Override
        public @NotNull Type<PollHashS2C> type() {
            return TYPE;
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(ComplexityAnalyzer.MODID, path);
    }
}