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

import net.minecraft.ChatFormatting;
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
            for (var player : server.getPlayerList().getPlayers()) {
                var translated = ServerLanguage.translateForPlayer(message, player);
                player.sendSystemMessage(Component.literal("[CA] ").withStyle(ChatFormatting.YELLOW)
                        .append(translated.withStyle(ChatFormatting.WHITE)));
            }
            server.sendSystemMessage(Component.literal("[CA] ").withStyle(ChatFormatting.YELLOW)
                    .append(ServerLanguage.translateForPlayer(message, null).withStyle(ChatFormatting.WHITE)));
        });
    }

    public void broadcastWarning(Component message) {
        server.execute(() -> {
            for (var player : server.getPlayerList().getPlayers()) {
                var warningTag = ServerLanguage.translateForPlayer(Component.translatable("complexityanalyzer.notifier.warning_tag"), player);
                var translated = ServerLanguage.translateForPlayer(message, player);
                player.sendSystemMessage(Component.literal("[CA] ").withStyle(ChatFormatting.YELLOW)
                        .append(warningTag.withStyle(ChatFormatting.GOLD))
                        .append(" ")
                        .append(translated.withStyle(ChatFormatting.WHITE)));
            }
            var warningTagConsole = ServerLanguage.translateForPlayer(Component.translatable("complexityanalyzer.notifier.warning_tag"), null);
            server.sendSystemMessage(Component.literal("[CA] ").withStyle(ChatFormatting.YELLOW)
                    .append(warningTagConsole.withStyle(ChatFormatting.GOLD))
                    .append(" ")
                    .append(ServerLanguage.translateForPlayer(message, null).withStyle(ChatFormatting.WHITE)));
        });
    }

    public void broadcastSevere(Component message) {
        server.execute(() -> {
            for (var player : server.getPlayerList().getPlayers()) {
                var translated = ServerLanguage.translateForPlayer(message, player);
                player.sendSystemMessage(Component.literal("[CA] ").withStyle(ChatFormatting.RED)
                        .append(translated.withStyle(ChatFormatting.BOLD)));
            }
            server.sendSystemMessage(Component.literal("[CA] ").withStyle(ChatFormatting.RED)
                    .append(ServerLanguage.translateForPlayer(message, null).withStyle(ChatFormatting.BOLD)));
        });
    }

    public void broadcastSuccess(Component message) {
        server.execute(() -> {
            for (var player : server.getPlayerList().getPlayers()) {
                var translated = ServerLanguage.translateForPlayer(message, player);
                player.sendSystemMessage(Component.literal("[CA] ").withStyle(ChatFormatting.GREEN)
                        .append(translated.withStyle(ChatFormatting.WHITE)));
            }
            server.sendSystemMessage(Component.literal("[CA] ").withStyle(ChatFormatting.GREEN)
                    .append(ServerLanguage.translateForPlayer(message, null).withStyle(ChatFormatting.WHITE)));
        });
    }

    public void sendSuccess(@Nullable CommandSourceStack source, Component message) {
        if (source == null) {
            logInfo(message);
            return;
        }

        server.execute(() -> {
            var player = source.getPlayer();
            var translated = ServerLanguage.translateForPlayer(message, player);
            var component = Component.literal("[CA] ").withStyle(ChatFormatting.GREEN)
                    .append(translated.withStyle(ChatFormatting.WHITE));
            source.sendSuccess(() -> component, false);
        });
    }

    public void sendFailure(@Nullable CommandSourceStack source, Component message) {
        if (source == null) {
            var errorTag = ServerLanguage.translateForPlayer(Component.translatable("complexityanalyzer.notifier.error_tag"), null);
            var translated = ServerLanguage.translateForPlayer(message, null);
            logError(Component.empty()
                    .append(errorTag)
                    .append(": ")
                    .append(translated).getString());
            return;
        }

        server.execute(() -> {
            var player = source.getPlayer();
            var errorTag = ServerLanguage.translateForPlayer(Component.translatable("complexityanalyzer.notifier.error_tag"), player);
            var translated = ServerLanguage.translateForPlayer(message, player);
            var component = Component.literal("[CA] ").withStyle(ChatFormatting.RED)
                    .append(errorTag)
                    .append(": ")
                    .append(translated);
            source.sendFailure(component);
        });
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
            return;
        }
        broadcastSuccess(Component.translatable("complexityanalyzer.notifier.recon_complete"));
    }

    public void notifyRefinementFinished() {
        broadcastSuccess(Component.translatable("complexityanalyzer.notifier.complete"));
        logInfo(Component.translatable("complexityanalyzer.log.refinement_complete"));
    }
}