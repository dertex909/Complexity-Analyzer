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

package org.complexityanalyzer.command.util;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import org.complexityanalyzer.util.ServerLanguage;

public class OutputManager {

    private static final Component SEPARATOR = Component.literal("═══════════════════════════════")
            .withStyle(ChatFormatting.DARK_GRAY);
    private static final Component THIN_SEPARATOR = Component.literal("  ─────────────────────────────")
            .withStyle(ChatFormatting.DARK_GRAY);
    private final MinecraftServer server;

    public OutputManager(MinecraftServer server) {
        this.server = server;
    }

    public void sendSuccess(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> translate(source, message), false);
    }

    public void sendFailure(CommandSourceStack source, Component message) {
        source.sendFailure(translate(source, message));
    }

    public void sendInfo(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> translate(source, message), false);
    }

    public void broadcast(Component message) {
        server.execute(() -> {
            server.getPlayerList().getPlayers().forEach(player -> player.sendSystemMessage(ServerLanguage.translateForPlayer(message, player)));
            server.sendSystemMessage(ServerLanguage.translateForPlayer(message, null));
        });
    }

    public void broadcastWarning(Component message) {
        server.execute(() -> {
            server.getPlayerList().getPlayers().forEach(player -> {
                var translated = ServerLanguage.translateForPlayer(message, player);
                player.sendSystemMessage(Component.literal("").append(translated).withStyle(ChatFormatting.YELLOW));
            });
            server.sendSystemMessage(Component.literal("").append(ServerLanguage.translateForPlayer(message, null)).withStyle(ChatFormatting.YELLOW));
        });
    }

    public void broadcastSever(Component message) {
        server.execute(() -> {
            server.getPlayerList().getPlayers().forEach(player -> {
                var translated = ServerLanguage.translateForPlayer(message, player);
                player.sendSystemMessage(Component.literal("").append(translated).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            });
            server.sendSystemMessage(Component.literal("").append(ServerLanguage.translateForPlayer(message, null)).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        });
    }

    public void sendToAdmins(Component message) {
        server.execute(() -> {
            server.getPlayerList().getPlayers().forEach(player -> {
                if (player.hasPermissions(2)) {
                    var adminPrefix = ServerLanguage.translateForPlayer(Component.translatable("complexityanalyzer.output.admin_prefix"), player).copy().withStyle(ChatFormatting.GRAY);
                    var translatedBody = ServerLanguage.translateForPlayer(message, player);
                    var adminMessage = Component.literal("").append(adminPrefix)
                            .append(translatedBody.copy().withStyle(ChatFormatting.ITALIC));
                    player.sendSystemMessage(adminMessage);
                }
            });

            var consolePrefix = ServerLanguage.translateForPlayer(Component.translatable("complexityanalyzer.output.admin_prefix"), null).copy().withStyle(ChatFormatting.GRAY);
            server.sendSystemMessage(Component.literal("").append(consolePrefix).append(ServerLanguage.translateForPlayer(message, null).copy().withStyle(ChatFormatting.ITALIC)));
        });
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

    public void sendHeader(CommandSourceStack source, String icon, Object title, ChatFormatting color) {
        sendSeparator(source);
        var header = Component.literal(icon + " ").withStyle(color)
                .append(toComponent(title).copy().withStyle(color, ChatFormatting.BOLD));
        source.sendSuccess(() -> translate(source, header), false);
        sendSeparator(source);
    }

    public void sendFooter(CommandSourceStack source) {
        sendSeparator(source);
    }

    public void sendEntry(CommandSourceStack source, Object icon, Object label, Object value, ChatFormatting labelColor, ChatFormatting valueColor) {
        var entry = Component.literal("  ")
                .append(toComponent(icon).copy().withStyle(valueColor).append(" "))
                .append(toComponent(label).copy().append(": ").withStyle(labelColor))
                .append(toComponent(value).copy().withStyle(valueColor, ChatFormatting.BOLD));
        source.sendSuccess(() -> translate(source, entry), false);
    }

    public void sendSubEntry(CommandSourceStack source, Object icon, Object label, Object value, ChatFormatting labelColor, ChatFormatting valueColor) {
        var entry = Component.literal("    ")
                .append(toComponent(icon).copy().withStyle(valueColor).append(" "))
                .append(toComponent(label).copy().append(": ").withStyle(labelColor))
                .append(toComponent(value).copy().withStyle(valueColor, ChatFormatting.BOLD));
        source.sendSuccess(() -> translate(source, entry), false);
    }

    public void sendSubEntry(CommandSourceStack source, Object label, Object value, ChatFormatting labelColor, ChatFormatting valueColor) {
        var entry = Component.literal("    • ")
                .withStyle(ChatFormatting.DARK_GRAY)
                .append(toComponent(label).copy().append(": ").withStyle(labelColor))
                .append(toComponent(value).copy().withStyle(valueColor, ChatFormatting.BOLD));
        source.sendSuccess(() -> translate(source, entry), false);
    }

    public void sendTip(CommandSourceStack source, String textKey, Object... args) {
        var tip = Component.literal("    💡 ")
                .append(Component.translatable("complexityanalyzer.output.tip", Component.translatable(textKey, args))
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        source.sendSuccess(() -> translate(source, tip), false);
    }

    public void sendTipLiteral(CommandSourceStack source, Object literal) {
        var tip = Component.literal("    💡 ")
                .append(Component.translatable("complexityanalyzer.output.tip", toComponent(literal))
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        source.sendSuccess(() -> translate(source, tip), false);
    }

    public void sendClickableTip(CommandSourceStack source, Object prefix, Object linkText, Object suffix, String command, Object hoverText) {
        var tip = Component.literal("    💡 ")
                .withStyle(ChatFormatting.GRAY)
                .append(toComponent(prefix).copy().withStyle(ChatFormatting.GRAY))
                .append(toComponent(linkText).copy()
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
                        .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, toComponent(hoverText)))))
                .append(toComponent(suffix).copy().withStyle(ChatFormatting.GRAY));
        source.sendSuccess(() -> translate(source, tip), false);
    }

    public void sendProgressBar(CommandSourceStack source, Object label, int percent, Object textValue, ChatFormatting labelColor, ChatFormatting barColor) {
        String barStr = getBarString(percent);
        var bar = Component.literal("    ")
                .append(toComponent(label).copy().append(": ").withStyle(labelColor))
                .append(Component.literal(barStr).withStyle(barColor))
                .append(toComponent(textValue).copy().withStyle(ChatFormatting.WHITE));
        source.sendSuccess(() -> translate(source, bar), false);
    }

    public void sendStatusLine(CommandSourceStack source, String icon, Object text, ChatFormatting color) {
        var line = Component.literal("  " + icon + " ")
                .withStyle(color)
                .append(toComponent(text).copy().withStyle(color));
        source.sendSuccess(() -> translate(source, line), false);
    }

    public void sendValueBar(CommandSourceStack source, int percent, ChatFormatting barColor, Object suffix, ChatFormatting suffixColor) {
        String barStr = getBarString(percent);
        var bar = Component.literal("    ")
                .append(Component.literal(barStr).withStyle(barColor))
                .append(toComponent(suffix).copy().withStyle(suffixColor));
        source.sendSuccess(() -> translate(source, bar), false);
    }

    public void sendLink(CommandSourceStack source, Object label, Object buttonText, String url, ChatFormatting btnColor, String hoverKey, Object... hoverArgs) {
        var link = Component.literal("  ")
                .append(toComponent(label).copy().append(": ").withStyle(ChatFormatting.GRAY))
                .append(toComponent(buttonText).copy()
                        .withStyle(btnColor, ChatFormatting.UNDERLINE)
                        .withStyle(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(hoverKey, hoverArgs).withStyle(ChatFormatting.GREEN)))));
        source.sendSuccess(() -> translate(source, link), false);
    }

    public void sendCopyAction(CommandSourceStack source, Object label, Object buttonText, String textToCopy, ChatFormatting btnColor, String hoverKey, Object... hoverArgs) {
        var copy = Component.literal("  ")
                .append(toComponent(label).copy().append(": ").withStyle(ChatFormatting.GRAY))
                .append(toComponent(buttonText).copy()
                        .withStyle(btnColor, ChatFormatting.UNDERLINE)
                        .withStyle(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, textToCopy))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(hoverKey, hoverArgs).withStyle(ChatFormatting.GREEN)))));
        source.sendSuccess(() -> translate(source, copy), false);
    }

    private Component translate(CommandSourceStack source, Component message) {
        return ServerLanguage.translateForPlayer(message, source.getPlayer());
    }

    private Component toComponent(Object obj) {
        if (obj instanceof Component c) return c;
        if (obj instanceof String s) {
            if (s.contains(".") && !s.contains(" ")) return Component.translatable(s);
            return Component.literal(s);
        }
        return Component.literal(String.valueOf(obj));
    }

    private String getBarString(int percent) {
        int bars = Math.clamp(percent / 10, 0, 10);
        return "[" + "██████████".substring(0, bars) + "░░░░░░░░░░".substring(bars) + "]";
    }
}