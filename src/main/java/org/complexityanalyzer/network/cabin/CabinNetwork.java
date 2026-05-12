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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.client.cabin.ClientCabinNetwork;

@EventBusSubscriber(modid = ComplexityAnalyzer.MODID)
public final class CabinNetwork {

    private static final String VERSION = "1";

    private CabinNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar reg = event.registrar(VERSION).optional();

        reg.playToClient(CabinPayloads.ManifestS2C.TYPE, CabinPayloads.ManifestS2C.STREAM_CODEC, CabinNetwork::clientHandleManifest);
        reg.playToClient(CabinPayloads.ChunkS2C.TYPE, CabinPayloads.ChunkS2C.STREAM_CODEC, CabinNetwork::clientHandleChunk);
        reg.playToClient(CabinPayloads.FinishS2C.TYPE, CabinPayloads.FinishS2C.STREAM_CODEC, CabinNetwork::clientHandleFinish);
        reg.playToClient(CabinPayloads.UpToDateS2C.TYPE, CabinPayloads.UpToDateS2C.STREAM_CODEC, CabinNetwork::clientHandleUpToDate);
        reg.playToClient(CabinPayloads.ErrorS2C.TYPE, CabinPayloads.ErrorS2C.STREAM_CODEC, CabinNetwork::clientHandleError);
        reg.playToClient(CabinPayloads.PendingS2C.TYPE, CabinPayloads.PendingS2C.STREAM_CODEC, CabinNetwork::clientHandlePending);
        reg.playToClient(CabinPayloads.PollHashS2C.TYPE, CabinPayloads.PollHashS2C.STREAM_CODEC, CabinNetwork::clientHandlePollHash);
        reg.playToServer(CabinPayloads.RequestC2S.TYPE, CabinPayloads.RequestC2S.STREAM_CODEC, CabinNetwork::serverHandleRequest);
        reg.playToServer(CabinPayloads.AckC2S.TYPE, CabinPayloads.AckC2S.STREAM_CODEC, CabinNetwork::serverHandleAck);
        reg.playToServer(CabinPayloads.CancelC2S.TYPE, CabinPayloads.CancelC2S.STREAM_CODEC, CabinNetwork::serverHandleCancel);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) CabinSessionManager.onPlayerLogout(sp);
    }

    private static void serverHandleRequest(CabinPayloads.RequestC2S payload, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp) CabinSessionManager.onRequest(sp, payload.knownHash());
    }

    private static void serverHandleAck(CabinPayloads.AckC2S payload, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp) CabinSessionManager.onAck(sp, payload.upToSequence());
    }

    private static void serverHandleCancel(CabinPayloads.CancelC2S payload, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp) CabinSessionManager.onCancel(sp);
    }

    private static void clientHandleManifest(CabinPayloads.ManifestS2C payload, IPayloadContext ctx) {
        if (FMLEnvironment.dist.isClient()) ClientCabinNetwork.handleManifest(payload, ctx);
    }

    private static void clientHandleChunk(CabinPayloads.ChunkS2C payload, IPayloadContext ctx) {
        if (FMLEnvironment.dist.isClient()) ClientCabinNetwork.handleChunk(payload, ctx);
    }

    private static void clientHandleFinish(CabinPayloads.FinishS2C payload, IPayloadContext ctx) {
        if (FMLEnvironment.dist.isClient()) ClientCabinNetwork.handleFinish(payload, ctx);
    }

    private static void clientHandleUpToDate(CabinPayloads.UpToDateS2C payload, IPayloadContext ctx) {
        if (FMLEnvironment.dist.isClient()) ClientCabinNetwork.handleUpToDate(payload, ctx);
    }

    private static void clientHandleError(CabinPayloads.ErrorS2C payload, IPayloadContext ctx) {
        if (FMLEnvironment.dist.isClient()) ClientCabinNetwork.handleError(payload, ctx);
    }

    private static void clientHandlePending(CabinPayloads.PendingS2C payload, IPayloadContext ctx) {
        if (FMLEnvironment.dist.isClient()) ClientCabinNetwork.handlePending(payload, ctx);
    }

    private static void clientHandlePollHash(CabinPayloads.PollHashS2C payload, IPayloadContext ctx) {
        if (FMLEnvironment.dist.isClient()) ClientCabinNetwork.handlePollHash(payload, ctx);
    }
}