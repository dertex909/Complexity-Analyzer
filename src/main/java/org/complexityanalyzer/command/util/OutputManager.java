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
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
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

    public void sendSeparator(CommandSourceStack source) {
        sendInfo(source, SEPARATOR);
    }

    public void sendThinSeparator(CommandSourceStack source) {
        sendInfo(source, THIN_SEPARATOR);
    }

    public void sendEmptyLine(CommandSourceStack source) {
        sendInfo(source, Component.empty());
    }

    public void sendHeader(CommandSourceStack source, String icon, String title, ChatFormatting color) {
        sendSeparator(source);
        sendInfo(source, Component.literal(icon + " ").withStyle(color)
                .append(Component.literal(title).withStyle(color, ChatFormatting.BOLD)));
        sendSeparator(source);
    }

    public void sendFooter(CommandSourceStack source) {
        sendSeparator(source);
    }

    public void sendEntry(CommandSourceStack source, String icon, String label, String value, ChatFormatting labelColor, ChatFormatting valueColor) {
        source.sendSuccess(() -> Component.literal("  ")
                .append(Component.literal(icon + " ").withStyle(valueColor))
                .append(Component.literal(label + ": ").withStyle(labelColor))
                .append(Component.literal(value).withStyle(valueColor, ChatFormatting.BOLD)), false);
    }

    public void sendSubEntry(CommandSourceStack source, String icon, String label, String value, ChatFormatting labelColor, ChatFormatting valueColor) {
        source.sendSuccess(() -> Component.literal("    ")
                .append(Component.literal(icon + " ").withStyle(valueColor))
                .append(Component.literal(label + ": ").withStyle(labelColor))
                .append(Component.literal(value).withStyle(valueColor, ChatFormatting.BOLD)), false);
    }

    public void sendSubEntry(CommandSourceStack source, String label, String value, ChatFormatting labelColor, ChatFormatting valueColor) {
        source.sendSuccess(() -> Component.literal("    • ")
                .withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(label + ": ").withStyle(labelColor))
                .append(Component.literal(value).withStyle(valueColor, ChatFormatting.BOLD)), false);
    }

    public void sendTip(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal("    💡 Tip: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)), false);
    }

    public void sendClickableTip(CommandSourceStack source, String prefix, String linkText, String suffix, String command, String hoverText) {
        source.sendSuccess(() -> Component.literal("    💡 ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(prefix).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(linkText)
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
                        .withStyle(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hoverText).withStyle(ChatFormatting.GREEN)))))
                .append(Component.literal(suffix).withStyle(ChatFormatting.GRAY)), false);
    }

    public void sendProgressBar(CommandSourceStack source, String label, int percent, String textValue, ChatFormatting labelColor, ChatFormatting barColor) {
        String barStr = getBarString(percent);
        source.sendSuccess(() -> Component.literal("    ")
                .append(Component.literal(label + ": ").withStyle(labelColor))
                .append(Component.literal(barStr).withStyle(barColor))
                .append(Component.literal(" " + textValue).withStyle(ChatFormatting.WHITE)), false);
    }

    public void sendStatusLine(CommandSourceStack source, String icon, String text, ChatFormatting color) {
        source.sendSuccess(() -> Component.literal("  " + icon + " ")
                .withStyle(color)
                .append(Component.literal(text).withStyle(color)), false);
    }

    public void sendValueBar(CommandSourceStack source, int percent, ChatFormatting barColor, String suffix, ChatFormatting suffixColor) {
        String barStr = getBarString(percent);
        source.sendSuccess(() -> Component.literal("    ")
                .append(Component.literal(barStr).withStyle(barColor))
                .append(Component.literal(" " + suffix).withStyle(suffixColor)), false);
    }

    public void sendLink(CommandSourceStack source, String label, String buttonText, String url, ChatFormatting btnColor, String hover) {
        source.sendSuccess(() -> Component.literal("  ")
                .append(Component.literal(label + ": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(buttonText)
                        .withStyle(btnColor, ChatFormatting.UNDERLINE)
                        .withStyle(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover).withStyle(ChatFormatting.GREEN))))), false);
    }

    public void sendCopyAction(CommandSourceStack source, String label, String buttonText, String textToCopy, ChatFormatting btnColor, String hover) {
        source.sendSuccess(() -> Component.literal("  ")
                .append(Component.literal(label + ": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(buttonText)
                        .withStyle(btnColor, ChatFormatting.UNDERLINE)
                        .withStyle(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, textToCopy))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover).withStyle(ChatFormatting.GREEN))))), false);
    }

    private String getBarString(int percent) {
        int bars = Math.clamp(percent / 10, 0, 10);
        return "[" + "██████████".substring(0, bars) + "░░░░░░░░░░".substring(bars) + "]";
    }

    private static final Component SEPARATOR = Component.literal("═══════════════════════════════")
            .withStyle(ChatFormatting.DARK_GRAY);

    private static final Component THIN_SEPARATOR = Component.literal("  ─────────────────────────────")
            .withStyle(ChatFormatting.DARK_GRAY);
}