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

package org.complexityanalyzer.event;

import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.command.*;

@EventBusSubscriber
public final class CommandEvents {

    private CommandEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal(ComplexityCommand.BASE)
                .then(SystemCommand.register())
                .then(AnalyzeCommand.register())
                .then(ExportCommand.register())
                .then(WebCommand.register())
                .then(ResourceCommand.register())
                .then(GeoScanCommands.register())
        );

        ComplexityAnalyzer.LOGGER.info("Registered '{}' command", ComplexityCommand.ROOT);
    }
}