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

import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.complexityanalyzer.network.cabin.CabinPayloads;

/**
 * Client-only thunks that dispatch inbound payloads to the singleton receiver.
 * <p>
 * Loaded lazily by {@code CabinNetwork} via {@code FMLEnvironment.dist.isClient()}
 * checks so dedicated servers never see this class.
 */
public final class ClientCabinNetwork {

    private ClientCabinNetwork() {
    }

    public static void handleManifest(CabinPayloads.ManifestS2C payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CabinReceiver.getInstance().onManifest(payload));
    }

    public static void handleChunk(CabinPayloads.ChunkS2C payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CabinReceiver.getInstance().onChunk(payload));
    }

    public static void handleFinish(CabinPayloads.FinishS2C payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CabinReceiver.getInstance().onFinish(payload.fileHash()));
    }

    public static void handleUpToDate(CabinPayloads.UpToDateS2C payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CabinReceiver.getInstance().onUpToDate(payload.fileHash(), payload.generatedAtMs()));
    }

    public static void handleError(CabinPayloads.ErrorS2C payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CabinReceiver.getInstance().onError(payload.reason()));
    }

    public static void handlePending(CabinPayloads.PendingS2C payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CabinReceiver.getInstance().onPending(payload.message()));
    }

    public static void handlePollHash(CabinPayloads.PollHashS2C payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CabinReceiver.getInstance().onPollHash());
    }
}
