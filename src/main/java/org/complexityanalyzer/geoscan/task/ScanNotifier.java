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

package org.complexityanalyzer.geoscan.task;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.util.ServerLanguage;
import org.jetbrains.annotations.Nullable;

public class ScanNotifier {

    private final MinecraftServer server;

    public ScanNotifier(MinecraftServer server) {
        this.server = server;
    }

    public void broadcastInfo(Component message) {
        server.execute(() -> {
            server.getPlayerList().getPlayers().forEach(player -> {
                var translated = ServerLanguage.translateForPlayer(message, player);
                player.sendSystemMessage(Component.literal("§e[CA] §f").append(translated));
            });
            server.sendSystemMessage(Component.literal("§e[CA] §f").append(ServerLanguage.translateForPlayer(message, null)));
        });
    }

    public void broadcastWarning(Component message) {
        server.execute(() -> {
            server.getPlayerList().getPlayers().forEach(player -> {
                var warningTag = ServerLanguage.translateForPlayer(Component.translatable("complexityanalyzer.notifier.warning_tag"), player);
                var translated = ServerLanguage.translateForPlayer(message, player);
                player.sendSystemMessage(Component.literal("§e[CA] §6").append(warningTag).append(" §f").append(translated));
            });
            var warningTagConsole = ServerLanguage.translateForPlayer(Component.translatable("complexityanalyzer.notifier.warning_tag"), null);
            server.sendSystemMessage(Component.literal("§e[CA] §6").append(warningTagConsole).append(" §f").append(ServerLanguage.translateForPlayer(message, null)));
        });
    }

    public void broadcastSevere(Component message) {
        server.execute(() -> {
            server.getPlayerList().getPlayers().forEach(player -> {
                var translated = ServerLanguage.translateForPlayer(message, player);
                player.sendSystemMessage(Component.literal("§c[CA] §l").append(translated));
            });
            server.sendSystemMessage(Component.literal("§c[CA] §l").append(ServerLanguage.translateForPlayer(message, null)));
        });
    }

    public void broadcastSuccess(Component message) {
        server.execute(() -> {
            server.getPlayerList().getPlayers().forEach(player -> {
                var translated = ServerLanguage.translateForPlayer(message, player);
                player.sendSystemMessage(Component.literal("§a[CA] §f").append(translated));
            });
            server.sendSystemMessage(Component.literal("§a[CA] §f").append(ServerLanguage.translateForPlayer(message, null)));
        });
    }

    public void sendSuccess(@Nullable CommandSourceStack source, Component message) {
        if (source != null) {
            server.execute(() -> {
                var player = source.getPlayer();
                var translated = ServerLanguage.translateForPlayer(message, player);
                var component = Component.literal("§a[CA] §f").append(translated);
                source.sendSuccess(() -> component, false);
            });
        } else {
            logInfo(message);
        }
    }

    public void sendFailure(@Nullable CommandSourceStack source, Component message) {
        if (source != null) {
            server.execute(() -> {
                var player = source.getPlayer();
                var errorTag = ServerLanguage.translateForPlayer(Component.translatable("complexityanalyzer.notifier.error_tag"), player);
                var translated = ServerLanguage.translateForPlayer(message, player);
                var component = Component.literal("§c[CA] ").append(errorTag).append(": ").append(translated);
                source.sendFailure(component);
            });
        } else {
            server.execute(() -> {
                var errorTagConsole = ServerLanguage.translateForPlayer(Component.translatable("complexityanalyzer.notifier.error_tag"), null);
                var translatedConsole = ServerLanguage.translateForPlayer(message, null);
                logError(Component.literal("").append(errorTagConsole).append(": ").append(translatedConsole).getString());
            });
        }
    }

    public void logInfo(Component message) {
        ComplexityAnalyzer.LOGGER.info(ServerLanguage.translateForPlayer(message, null).getString());
    }

    public void logWarn(Component message) {
        ComplexityAnalyzer.LOGGER.warn(ServerLanguage.translateForPlayer(message, null).getString());
    }

    public void logError(String message) {
        ComplexityAnalyzer.LOGGER.error(message);
    }

    public void logError(Component message, Throwable throwable) {
        ComplexityAnalyzer.LOGGER.error(ServerLanguage.translateForPlayer(message, null).getString(), throwable);
    }

    public void notifyScanCountdown(int secondsLeft) {
        if (secondsLeft > 0 && (secondsLeft <= 10 || secondsLeft % 15 == 0)) {
            broadcastInfo(Component.translatable("complexityanalyzer.notifier.countdown", secondsLeft));
        } else if (secondsLeft == 0) {
            broadcastSevere(Component.translatable("complexityanalyzer.notifier.started"));
        }
    }

    public void notifyScanStarting(int chunksPerBiome, Component initiator) {
        logInfo(Component.translatable("complexityanalyzer.log.scan_starting", initiator, chunksPerBiome));
    }

    public void notifyDatabaseIsUpToDate() {
        broadcastSuccess(Component.translatable("complexityanalyzer.notifier.up_to_date"));
    }

    public void notifyReconnaissanceFinished(boolean wasStopped) {
        if (wasStopped) {
            broadcastInfo(Component.translatable("complexityanalyzer.notifier.stopped"));
        } else {
            broadcastSuccess(Component.translatable("complexityanalyzer.notifier.recon_complete"));
        }
    }

    public void notifyRefinementFinished() {
        broadcastSuccess(Component.translatable("complexityanalyzer.notifier.complete"));
        logInfo(Component.translatable("complexityanalyzer.log.refinement_complete"));
    }
}