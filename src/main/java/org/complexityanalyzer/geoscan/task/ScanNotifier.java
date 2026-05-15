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

package org.complexityanalyzer.geoscan.task;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.jetbrains.annotations.Nullable;

public class ScanNotifier {

    private final MinecraftServer server;

    public ScanNotifier(MinecraftServer server) {
        this.server = server;
    }

    public void broadcastInfo(Component message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal("§e[CA] §f").append(message), false);
    }

    public void broadcastWarning(Component message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal("§e[CA] §6")
                .append(Component.translatable("complexityanalyzer.notifier.warning_tag"))
                .append(" §f").append(message), false);
    }

    public void broadcastSevere(Component message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal("§c[CA] §l").append(message), false);
    }

    public void broadcastSuccess(Component message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal("§a[CA] §f").append(message), false);
    }

    public void sendSuccess(@Nullable CommandSourceStack source, Component message) {
        Component component = Component.literal("§a[CA] §f").append(message);
        if (source != null) {
            source.sendSuccess(() -> component, false);
        } else {
            logInfo(message.getString());
        }
    }

    public void sendFailure(@Nullable CommandSourceStack source, Component message) {
        Component component = Component.literal("§c[CA] ").append(
                Component.translatable("complexityanalyzer.notifier.error_tag")).append(": ").append(message);
        if (source != null) source.sendFailure(component);
        else logError(message.getString());
    }

    public void logInfo(String message) {
        ComplexityAnalyzer.LOGGER.info(message);
    }

    public void logWarn(String message) {
        ComplexityAnalyzer.LOGGER.warn(message);
    }

    public void logError(String message) {
        ComplexityAnalyzer.LOGGER.error(message);
    }

    public void logError(String message, Throwable throwable) {
        ComplexityAnalyzer.LOGGER.error(message, throwable);
    }

    public void notifyScanCountdown(int secondsLeft) {
        if (secondsLeft > 0 && (secondsLeft <= 10 || secondsLeft % 15 == 0)) {
            broadcastInfo(Component.translatable("complexityanalyzer.notifier.countdown", secondsLeft));
        } else if (secondsLeft == 0) {
            broadcastSevere(Component.translatable("complexityanalyzer.notifier.started"));
        }
    }

    public void notifyScanStarting(int chunksPerBiome, String initiator) {
        logInfo(Component.translatable("complexityanalyzer.log.scan_starting", initiator, chunksPerBiome).getString());
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
        logInfo(Component.translatable("complexityanalyzer.log.refinement_complete").getString());
    }
}