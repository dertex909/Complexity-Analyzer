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

package org.complexityanalyzer.util;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;

import static org.apache.logging.log4j.Level.WARN;

public class LootLogFilter extends AbstractFilter {

    @Override
    public Result filter(LogEvent event) {
        if (event == null) return Result.NEUTRAL;

        var msg = event.getMessage();
        if (msg != null) {
            String message = msg.getFormattedMessage();
            if (message != null && message.contains("Failed to apply component patch")
                    && message.contains("was larger than maximum")) return Result.DENY;
        }

        if (event.getLevel() != WARN) return Result.NEUTRAL;

        String loggerName = event.getLoggerName();
        if (loggerName != null && loggerName.startsWith("net.minecraft.world.level.storage.loot.functions.")) {
            String message = msg != null ? msg.getFormattedMessage() : null;
            if (message != null && (message.contains("Couldn't set damage") || message.contains("Couldn't smelt")
                    || message.contains("Couldn't find a compatible enchantment"))) return Result.DENY;
        }

        return Result.NEUTRAL;
    }
}