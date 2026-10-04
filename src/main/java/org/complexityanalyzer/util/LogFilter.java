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

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.functions.EnchantRandomlyFunction;
import net.minecraft.world.level.storage.loot.functions.SetItemDamageFunction;
import net.minecraft.world.level.storage.loot.functions.SmeltItemFunction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;

import static org.apache.logging.log4j.Level.ERROR;
import static org.apache.logging.log4j.Level.WARN;

public final class LogFilter extends AbstractFilter implements AutoCloseable {

    private static final String MARKER_DISTXFORM = "DISTXFORM";

    private static final String ITEM_STACK_LOGGER = ItemStack.class.getName();
    private static final String DAMAGE_LOGGER = SetItemDamageFunction.class.getName();
    private static final String SMELT_LOGGER = SmeltItemFunction.class.getName();
    private static final String ENCHANT_LOGGER = EnchantRandomlyFunction.class.getName();

    private static final String MSG_DIST_CLEANER = "Attempted to load class {} for invalid dist {}";
    private static final String MSG_ITEM_STACK_PATCH = "Failed to apply component patch '{}' to item: '{}'";
    private static final String MSG_SET_DAMAGE = "Couldn't set damage of loot item {}";
    private static final String MSG_SMELT = "Couldn't smelt {} because there is no smelting recipe";
    private static final String MSG_ENCHANT = "Couldn't find a compatible enchantment for {}";

    private final Logger rootLogger;

    private LogFilter() {
        this.rootLogger = (Logger) LogManager.getRootLogger();
        this.start();
        this.rootLogger.addFilter(this);
    }

    public static LogFilter open() {
        return new LogFilter();
    }

    @Override
    public Result filter(LogEvent event) {
        if (event == null) return Result.NEUTRAL;
        if (event.getMarker() != null && MARKER_DISTXFORM.equals(event.getMarker().getName())) return Result.DENY;

        var format = event.getMessage() != null ? event.getMessage().getFormat() : null;
        if (format == null) return Result.NEUTRAL;

        return switch (format) {
            case MSG_ITEM_STACK_PATCH ->
                    event.getLevel() == ERROR && ITEM_STACK_LOGGER.equals(event.getLoggerName()) ? Result.DENY : Result.NEUTRAL;
            case MSG_SET_DAMAGE ->
                    event.getLevel() == WARN && DAMAGE_LOGGER.equals(event.getLoggerName()) ? Result.DENY : Result.NEUTRAL;
            case MSG_SMELT ->
                    event.getLevel() == WARN && SMELT_LOGGER.equals(event.getLoggerName()) ? Result.DENY : Result.NEUTRAL;
            case MSG_ENCHANT ->
                    event.getLevel() == WARN && ENCHANT_LOGGER.equals(event.getLoggerName()) ? Result.DENY : Result.NEUTRAL;
            case MSG_DIST_CLEANER -> Result.DENY;
            default -> Result.NEUTRAL;
        };
    }

    @Override
    public void close() {
        try {
            this.rootLogger.get().removeFilter(this);
            this.stop();
        } catch (Throwable ignored) {
        }
    }
}