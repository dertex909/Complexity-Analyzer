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
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import org.complexityanalyzer.util.ServerLanguage;

public class OutputManager {

    private static final Component SEPARATOR = Component.literal("═══════════════════════════════")
            .withStyle(ChatFormatting.DARK_GRAY);
    private static final Component THIN_SEPARATOR = Component.literal("  ─────────────────────────────")
            .withStyle(ChatFormatting.DARK_GRAY);

    private static final String[] BARS = {"[░░░░░░░░░░]", "[█░░░░░░░░░]", "[██░░░░░░░░]", "[███░░░░░░░]", "[████░░░░░░]", "[█████░░░░░]", "[██████░░░░]", "[███████░░░]", "[████████░░]", "[█████████░]", "[██████████]"};

    private final MinecraftServer server;

    public OutputManager(MinecraftServer server) {
        this.server = server;
    }

    private static MutableComponent toMutableComponent(Object obj) {
        if (obj instanceof Component c) return c.copy();
        if (obj instanceof String s) {
            if (s.indexOf('.') != -1 && s.indexOf(' ') == -1) return Component.translatable(s);
            return Component.literal(s);
        }
        return Component.literal(String.valueOf(obj));
    }

    private static String getBarString(int percent) {
        return BARS[Math.clamp(percent / 10, 0, 10)];
    }

    private static MutableComponent concat(Object... parts) {
        var root = Component.empty();
        for (var part : parts) if (part != null) root.append(toMutableComponent(part));
        return root;
    }

    private static MutableComponent styled(Object obj, ChatFormatting... formats) {
        return toMutableComponent(obj).withStyle(formats);
    }

    private static MutableComponent clickable(Object obj, String command, Object hover) {
        return styled(obj, ChatFormatting.AQUA, ChatFormatting.UNDERLINE).withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, toMutableComponent(hover))));
    }

    private static MutableComponent link(Object obj, String url, Object hover, ChatFormatting... formats) {
        return styled(obj, formats).withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, toMutableComponent(hover))));
    }

    private static MutableComponent copyable(Object obj, String textToCopy, Object hover, ChatFormatting... formats) {
        return styled(obj, formats).withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, textToCopy))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, toMutableComponent(hover))));
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

    public void sendSeparator(CommandSourceStack source) {
        sendInfo(source, SEPARATOR);
    }

    public void sendThinSeparator(CommandSourceStack source) {
        sendInfo(source, THIN_SEPARATOR);
    }

    public void sendEmptyLine(CommandSourceStack source) {
        sendInfo(source, Component.empty());
    }

    public void sendFooter(CommandSourceStack source) {
        sendSeparator(source);
    }

    public void broadcast(Component message) {
        server.execute(() -> {
            var players = server.getPlayerList().getPlayers();
            for (int i = 0; i < players.size(); i++) {
                var player = players.get(i);
                player.sendSystemMessage(ServerLanguage.translateForPlayer(message, player));
            }
            server.sendSystemMessage(ServerLanguage.translateForPlayer(message, null));
        });
    }

    public void broadcastWarning(Component message) {
        server.execute(() -> {
            var players = server.getPlayerList().getPlayers();
            for (int i = 0; i < players.size(); i++) {
                var player = players.get(i);
                var translated = ServerLanguage.translateForPlayer(message, player);
                player.sendSystemMessage(styled(translated, ChatFormatting.YELLOW));
            }
            var consoleTranslated = ServerLanguage.translateForPlayer(message, null);
            server.sendSystemMessage(styled(consoleTranslated, ChatFormatting.YELLOW));
        });
    }

    public void broadcastSever(Component message) {
        server.execute(() -> {
            var players = server.getPlayerList().getPlayers();
            for (int i = 0; i < players.size(); i++) {
                var player = players.get(i);
                var translated = ServerLanguage.translateForPlayer(message, player);
                player.sendSystemMessage(styled(translated, ChatFormatting.RED, ChatFormatting.BOLD));
            }
            var consoleTranslated = ServerLanguage.translateForPlayer(message, null);
            server.sendSystemMessage(styled(consoleTranslated, ChatFormatting.RED, ChatFormatting.BOLD));
        });
    }

    public void sendToAdmins(Component message) {
        server.execute(() -> {
            var players = server.getPlayerList().getPlayers();
            for (int i = 0; i < players.size(); i++) {
                var player = players.get(i);
                if (player.hasPermissions(2)) {
                    var adminPrefix = styled(ServerLanguage.translateForPlayer(Component.translatable("complexityanalyzer.output.admin_prefix"), player), ChatFormatting.GRAY);
                    var translatedBody = styled(ServerLanguage.translateForPlayer(message, player), ChatFormatting.ITALIC);
                    player.sendSystemMessage(concat(adminPrefix, translatedBody));
                }
            }

            var consolePrefix = styled(ServerLanguage.translateForPlayer(Component.translatable("complexityanalyzer.output.admin_prefix"), null), ChatFormatting.GRAY);
            var consoleBody = styled(ServerLanguage.translateForPlayer(message, null), ChatFormatting.ITALIC);
            server.sendSystemMessage(concat(consolePrefix, consoleBody));
        });
    }

    public void sendHeader(CommandSourceStack source, String icon, Object title, ChatFormatting color) {
        sendSeparator(source);
        var header = concat(
                styled(icon + " ", color),
                styled(title, color, ChatFormatting.BOLD)
        );
        source.sendSuccess(() -> translate(source, header), false);
        sendSeparator(source);
    }

    public void sendEntry(CommandSourceStack source, Object icon, Object label, Object value, ChatFormatting labelColor, ChatFormatting valueColor) {
        var entry = concat(
                "  ",
                styled(icon, valueColor), " ",
                styled(label, labelColor), ": ",
                styled(value, valueColor, ChatFormatting.BOLD)
        );
        source.sendSuccess(() -> translate(source, entry), false);
    }

    public void sendSubEntry(CommandSourceStack source, Object icon, Object label, Object value, ChatFormatting labelColor, ChatFormatting valueColor) {
        var entry = concat(
                "    ",
                styled(icon, valueColor), " ",
                styled(label, labelColor), ": ",
                styled(value, valueColor, ChatFormatting.BOLD)
        );
        source.sendSuccess(() -> translate(source, entry), false);
    }

    public void sendSubEntry(CommandSourceStack source, Object label, Object value, ChatFormatting labelColor, ChatFormatting valueColor) {
        var entry = concat(
                styled("    • ", ChatFormatting.DARK_GRAY),
                styled(label, labelColor), ": ",
                styled(value, valueColor, ChatFormatting.BOLD)
        );
        source.sendSuccess(() -> translate(source, entry), false);
    }

    public void sendTip(CommandSourceStack source, String textKey, Object... args) {
        var tip = concat(
                "    💡 ",
                styled(Component.translatable("complexityanalyzer.output.tip", Component.translatable(textKey, args)), ChatFormatting.GRAY, ChatFormatting.ITALIC)
        );
        source.sendSuccess(() -> translate(source, tip), false);
    }

    private Component translate(CommandSourceStack source, Component message) {
        return ServerLanguage.translateForPlayer(message, source.getPlayer());
    }

    public void sendTipLiteral(CommandSourceStack source, Object literal) {
        var tip = concat(
                "    💡 ",
                styled(Component.translatable("complexityanalyzer.output.tip", toMutableComponent(literal)), ChatFormatting.GRAY, ChatFormatting.ITALIC)
        );
        source.sendSuccess(() -> translate(source, tip), false);
    }

    public void sendClickableTip(CommandSourceStack source, Object prefix, Object linkText, Object suffix, String command, Object hoverText) {
        var tip = concat(
                styled("    💡 ", ChatFormatting.GRAY),
                styled(prefix, ChatFormatting.GRAY),
                clickable(linkText, command, hoverText),
                styled(suffix, ChatFormatting.GRAY)
        );
        source.sendSuccess(() -> translate(source, tip), false);
    }

    public void sendProgressBar(CommandSourceStack source, Object label, int percent, Object textValue, ChatFormatting labelColor, ChatFormatting barColor) {
        String barStr = getBarString(percent);
        var bar = concat(
                "    ",
                styled(concat(label, ": "), labelColor),
                styled(barStr, barColor),
                styled(textValue, ChatFormatting.WHITE)
        );
        source.sendSuccess(() -> translate(source, bar), false);
    }

    public void sendStatusLine(CommandSourceStack source, String icon, Object text, ChatFormatting color) {
        var line = concat(
                styled("  " + icon + " ", color),
                styled(text, color)
        );
        source.sendSuccess(() -> translate(source, line), false);
    }

    public void sendValueBar(CommandSourceStack source, int percent, ChatFormatting barColor, Object suffix, ChatFormatting suffixColor) {
        String barStr = getBarString(percent);
        var bar = concat(
                "    ",
                styled(barStr, barColor),
                styled(suffix, suffixColor)
        );
        source.sendSuccess(() -> translate(source, bar), false);
    }

    public void sendLink(CommandSourceStack source, Object label, Object buttonText, String url, ChatFormatting btnColor, String hoverKey, Object... hoverArgs) {
        var hover = styled(Component.translatable(hoverKey, hoverArgs), ChatFormatting.GREEN);
        var link = concat(
                "  ",
                styled(concat(label, ": "), ChatFormatting.GRAY),
                link(buttonText, url, hover, btnColor, ChatFormatting.UNDERLINE)
        );
        source.sendSuccess(() -> translate(source, link), false);
    }

    public void sendCopyAction(CommandSourceStack source, Object label, Object buttonText, String textToCopy, ChatFormatting btnColor, String hoverKey, Object... hoverArgs) {
        var hover = styled(Component.translatable(hoverKey, hoverArgs), ChatFormatting.GREEN);
        var copy = concat(
                "  ",
                styled(concat(label, ": "), ChatFormatting.GRAY),
                copyable(buttonText, textToCopy, hover, btnColor, ChatFormatting.UNDERLINE)
        );
        source.sendSuccess(() -> translate(source, copy), false);
    }
}