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
import org.complexityanalyzer.ComplexityAnalyzer;

@EventBusSubscriber(modid = ComplexityAnalyzer.MODID, value = Dist.CLIENT)
public final class CabinClientCommand {

    private CabinClientCommand() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("complexity").then(Commands.literal("cabin")
                .then(Commands.literal("stop").executes(CabinClientCommand::executeStop))
                .then(Commands.literal("link").executes(CabinClientCommand::executeLink))));
    }

    private static int executeStop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CabinHttpServer.getInstance().stop();
        source.sendSuccess(() -> Component.literal("🛑 Viewer HTTP stopped").withStyle(ChatFormatting.YELLOW), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int executeLink(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String url = CabinHttpServer.getInstance().start();
        if (url == null) {
            source.sendFailure(Component.literal("Failed to start viewer"));
            return 0;
        }
        MutableComponent linkText = Component.literal("[Open viewer]")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(url))));
        source.sendSuccess(() -> Component.literal("🌐 Viewer link: ")
                .withStyle(ChatFormatting.GREEN).append(linkText), false);
        return Command.SINGLE_SUCCESS;
    }
}