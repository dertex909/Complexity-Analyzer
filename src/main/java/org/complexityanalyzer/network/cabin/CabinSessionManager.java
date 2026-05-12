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

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.export.cabin.CabinBackgroundService;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@EventBusSubscriber(modid = ComplexityAnalyzer.MODID)
public final class CabinSessionManager {

    private static final Map<UUID, CabinSendSession> SESSIONS = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<UUID> AUTO_DELIVER_QUEUE = new CopyOnWriteArrayList<>();

    private CabinSessionManager() {
    }

    public static void onRequest(ServerPlayer player, long knownHash) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        server.execute(() -> handleRequest(player, knownHash));
    }

    public static void onAck(ServerPlayer player, int upToSequence) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        server.execute(() -> {
            CabinSendSession s = SESSIONS.get(player.getUUID());
            if (s != null) s.onAck(upToSequence);
        });
    }

    public static void onCancel(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        server.execute(() -> {
            CabinSendSession s = SESSIONS.remove(player.getUUID());
            if (s != null) s.abort("cancelled by client");
        });
    }

    public static void onPlayerLogout(ServerPlayer player) {
        CabinSendSession s = SESSIONS.remove(player.getUUID());
        if (s != null) s.abort("player disconnected");
    }

    private static void handleRequest(ServerPlayer player, long knownHash) {
        CabinBackgroundService svc = CabinBackgroundService.getInstance();
        CabinBackgroundService.Snapshot snap = svc.getSnapshot();

        if (snap == null) {
            CabinBackgroundService.Status status = svc.getStatus();
            String message;
            switch (status) {
                case BUILDING -> message = "Cabin is being prepared. Please wait a moment...";
                case FAILED -> message = "Cabin generation failed. Use /complexity cabin regenerate";
                default -> {
                    AnalysisEngine engine = AnalysisEngine.getInstance();
                    if (engine.isReady()) {
                        message = "Cabin not yet generated, building now...";
                        scheduleBuildAndDeliver(player);
                    } else {
                        message = "Analysis engine not ready: " + engine.getCurrentState();
                    }
                }
            }
            PacketDistributor.sendToPlayer(player, new CabinPayloads.PendingS2C(message));
            return;
        }

        if (knownHash == snap.fileHash() && snap.fileHash() != CabinPayloads.UNKNOWN_HASH) {
            PacketDistributor.sendToPlayer(player, new CabinPayloads.UpToDateS2C(snap.fileHash(), snap.generatedAtMs()));
            return;
        }

        startSend(player, snap);
    }

    private static void scheduleBuildAndDeliver(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        AnalysisEngine engine = AnalysisEngine.getInstance();
        if (server == null || !engine.isReady()) return;
        AUTO_DELIVER_QUEUE.addIfAbsent(player.getUUID());
        String modVersion = ModList.get()
                .getModContainerById(ComplexityAnalyzer.MODID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
        CabinBackgroundService.getInstance().regenerateAsync(server, engine, modVersion)
                .whenComplete((snap, err) -> server.execute(() -> {
                    if (!AUTO_DELIVER_QUEUE.remove(player.getUUID())) return;
                    if (err != null || snap == null) {
                        PacketDistributor.sendToPlayer(player, new CabinPayloads.ErrorS2C("build failed: " + (err != null ? err.getMessage() : "no snapshot")));
                        return;
                    }
                    startSend(player, snap);
                }));
    }

    private static void startSend(ServerPlayer player, CabinBackgroundService.Snapshot snap) {
        CabinSendSession existing = SESSIONS.remove(player.getUUID());
        if (existing != null) existing.abort("superseded by new request");
        if (snap.bytes().length > CabinPayloads.MAX_BYTES_TOTAL) {
            PacketDistributor.sendToPlayer(player, new CabinPayloads.ErrorS2C("snapshot too large: " + snap.bytes().length + " bytes"));
            return;
        }
        CabinSendSession session = new CabinSendSession(player, snap.bytes(), snap.fileHash());
        SESSIONS.put(player.getUUID(), session);
        MinecraftServer server = player.getServer();
        String name = server != null ? server.getMotd() : "Server";
        session.start(snap.itemCount(), snap.mobCount(), snap.recipeCount(), snap.generatedAtMs(), name);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (SESSIONS.isEmpty()) return;
        long now = System.currentTimeMillis();
        SESSIONS.values().removeIf(s -> {
            try {
                s.tick(now);
            } catch (Throwable t) {
                ComplexityAnalyzer.LOGGER.error("[Cabin] tick error", t);
                s.abort("tick error: " + t.getMessage());
            }
            return s.getState() != CabinSendSession.State.SENDING;
        });
    }
}
