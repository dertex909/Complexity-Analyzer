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

package org.complexityanalyzer.command.util;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

public class OutputManager {

    private final MinecraftServer server;

    public OutputManager(MinecraftServer server) {
        this.server = server;
    }

    public void sendSuccess(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> message, false);
    }

    public void sendFailure(CommandSourceStack source, Component message) {
        source.sendFailure(message);
    }

    public void sendInfo(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> message, false);
    }

    public void broadcast(Component message) {
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    public void broadcastWarning(Component message) {
        Component formatted = Component.literal("").append(message).withStyle(ChatFormatting.YELLOW);
        server.getPlayerList().broadcastSystemMessage(formatted, false);
    }

    public void broadcastSever(Component message) {
        Component formatted = Component.literal("").append(message).withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        server.getPlayerList().broadcastSystemMessage(formatted, false);
    }

    public void sendToAdmins(Component message) {
        Component adminMessage = Component.literal("[ADMIN] ").withStyle(ChatFormatting.GRAY)
                .append(message.copy().withStyle(ChatFormatting.ITALIC));

        server.getPlayerList().getPlayers().forEach(player -> {
            if (player.hasPermissions(2)) player.sendSystemMessage(adminMessage);
        });

        server.sendSystemMessage(adminMessage);
    }
}