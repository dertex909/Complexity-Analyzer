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
            if (player.hasPermissions(2)) {
                player.sendSystemMessage(adminMessage);
            }
        });

        server.sendSystemMessage(adminMessage);
    }
}