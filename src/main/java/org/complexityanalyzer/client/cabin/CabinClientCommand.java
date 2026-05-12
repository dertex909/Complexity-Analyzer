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

package org.complexityanalyzer.client.cabin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.network.cabin.CabinPayloads;

import java.nio.file.Files;
import java.nio.file.Path;

@EventBusSubscriber(modid = ComplexityAnalyzer.MODID, value = Dist.CLIENT)
public final class CabinClientCommand {

    private CabinClientCommand() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cabin")
                .then(Commands.literal("sync").executes(CabinClientCommand::executeSync))
                .then(Commands.literal("open").executes(CabinClientCommand::executeOpen))
                .then(Commands.literal("close").executes(CabinClientCommand::executeClose))
                .then(Commands.literal("path").executes(CabinClientCommand::executePath))
                .then(Commands.literal("status").executes(CabinClientCommand::executeClientStatus))
                .executes(CabinClientCommand::executeHelp)
        );
    }

    private static int executeHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MutableComponent msg = Component.literal("🗄 Cabin commands:\n").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("  /cabin sync   ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal("— download / update from server\n").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("  /cabin open   ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal("— launch local viewer\n").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("  /cabin close  ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal("— stop viewer HTTP\n").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("  /cabin path   ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal("— show local cabin file path\n").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("  /cabin status ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal("— show local receiver state").withStyle(ChatFormatting.GRAY));
        source.sendSuccess(() -> msg, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int executeSync(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        long known = ClientCabinStorage.readKnownHash();
        try {
            PacketDistributor.sendToServer(new CabinPayloads.RequestC2S(known));
            source.sendSuccess(() -> Component.literal("📡 Requesting cabin (known hash="
                    + (known == 0 ? "none" : Long.toHexString(known)) + ")...").withStyle(ChatFormatting.AQUA), false);
            return Command.SINGLE_SUCCESS;
        } catch (Throwable t) {
            source.sendFailure(Component.literal("Failed to send request: " + t.getMessage()));
            return 0;
        }
    }

    private static int executeOpen(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            String url = CabinHttpServer.getInstance().start();
            if (url == null) {
                source.sendFailure(Component.literal("Failed to obtain viewer URL"));
                return 0;
            }
            boolean opened = CabinHttpServer.getInstance().openInBrowser();
            MutableComponent linkText = Component.literal("[Open viewer]")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(url))));
            MutableComponent msg = Component.literal("🌐 Viewer ready: ").withStyle(ChatFormatting.GREEN).append(linkText);
            if (!opened) msg.append(Component.literal("\n  (could not auto-open browser — click link or copy URL)")
                    .withStyle(ChatFormatting.GRAY));
            source.sendSuccess(() -> msg, false);
            return Command.SINGLE_SUCCESS;
        } catch (Throwable t) {
            source.sendFailure(Component.literal("Failed to start viewer HTTP: " + t.getMessage()));
            return 0;
        }
    }

    private static int executeClose(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CabinHttpServer.getInstance().stop();
        source.sendSuccess(() -> Component.literal("🛑 Viewer HTTP stopped").withStyle(ChatFormatting.YELLOW), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int executePath(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Path p = ClientCabinStorage.getCabinPath();
        boolean exists = Files.exists(p);
        MutableComponent msg = Component.literal("📂 Cabin path: ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(p.toString()).withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                        .withUnderlined(true).withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, p.toString()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to open file location")))));
        msg.append(Component.literal(exists ? "\n  status: present" : "\n  status: missing").withStyle(exists ? ChatFormatting.GREEN : ChatFormatting.RED));
        source.sendSuccess(() -> msg, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int executeClientStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CabinReceiver.State state = CabinReceiver.getInstance().getState();
        CabinReceiver.CachedSnapshot snap = CabinReceiver.getInstance().getCached();
        MutableComponent msg = Component.literal("📊 Receiver state: ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(state.name()).withStyle(stateColor(state)));
        if (snap != null) msg.append(Component.literal("\n  hash: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Long.toHexString(snap.fileHash())).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("\n  size: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(snap.bytes().length + " B").withStyle(ChatFormatting.AQUA))
                .append(Component.literal("\n  items: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(snap.itemCount())).withStyle(ChatFormatting.WHITE));
        else msg.append(Component.literal("\n  (no cabin received yet — run /cabin sync)")
                .withStyle(ChatFormatting.GRAY));
        boolean http = CabinHttpServer.getInstance().isRunning();
        msg.append(Component.literal("\n  http: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(http ? "RUNNING" : "stopped").withStyle(http ? ChatFormatting.GREEN : ChatFormatting.RED));
        source.sendSuccess(() -> msg, false);
        return Command.SINGLE_SUCCESS;
    }

    private static ChatFormatting stateColor(CabinReceiver.State s) {
        return switch (s) {
            case READY -> ChatFormatting.GREEN;
            case RECEIVING -> ChatFormatting.AQUA;
            case FAILED -> ChatFormatting.RED;
            default -> ChatFormatting.GRAY;
        };
    }
}