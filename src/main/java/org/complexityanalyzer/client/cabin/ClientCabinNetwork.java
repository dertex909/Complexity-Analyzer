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

public final class ClientCabinNetwork {
    private ClientCabinNetwork() {
    }

    private static CabinReceiver r() {
        return CabinReceiver.getInstance();
    }

    public static void handleManifest(CabinPayloads.ManifestS2C p, IPayloadContext c) {
        c.enqueueWork(() -> r().onManifest(p));
    }

    public static void handleChunk(CabinPayloads.ChunkS2C p, IPayloadContext c) {
        c.enqueueWork(() -> r().onChunk(p));
    }

    public static void handleFinish(CabinPayloads.FinishS2C p, IPayloadContext c) {
        c.enqueueWork(() -> r().onFinish(p.fileHash()));
    }

    public static void handleUpToDate(CabinPayloads.UpToDateS2C p, IPayloadContext c) {
        c.enqueueWork(() -> r().onUpToDate(p.fileHash(), p.generatedAtMs()));
    }

    public static void handleError(CabinPayloads.ErrorS2C p, IPayloadContext c) {
        c.enqueueWork(() -> r().onError(p.reason()));
    }

    public static void handlePending(CabinPayloads.PendingS2C p, IPayloadContext c) {
        c.enqueueWork(() -> r().onPending(p.message()));
    }

    public static void handlePollHash(IPayloadContext c) {
        c.enqueueWork(r()::onPollHash);
    }
}