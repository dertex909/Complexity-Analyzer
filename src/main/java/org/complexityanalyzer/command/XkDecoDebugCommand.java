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

package org.complexityanalyzer.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.harvest.XkDecoRecipeDebugger;

import java.nio.file.Path;

public final class XkDecoDebugCommand {
    private XkDecoDebugCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("xkdecodebug")
                .requires(source -> source.hasPermission(2))
                .executes(XkDecoDebugCommand::execute);
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());

        try {
            output.sendEmptyLine(source);
            output.sendStatusLine(source, "🧪", "Starting XKDeco and Kiwi Recipes Debug Scan...", ChatFormatting.YELLOW);
            output.sendTipLiteral(source, "Analyzing recipes registry data...");

            Path worldDir = source.getServer().getWorldPath(LevelResource.ROOT);
            String reportPath = XkDecoRecipeDebugger.runDebug(source.getLevel(), worldDir);

            output.sendEmptyLine(source);
            output.sendEntry(source, "✓", "Diagnostics", "finished successfully!", ChatFormatting.GREEN, ChatFormatting.WHITE);
            output.sendTipLiteral(source, "Report written to: " + reportPath);
            output.sendEmptyLine(source);

            return 1;
        } catch (Throwable t) {
            output.sendFailure(source, Component.literal("An error occurred during diagnostics: " + t.getMessage()));
            return 0;
        }
    }
}
