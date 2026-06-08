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

package org.complexityanalyzer;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.complexityanalyzer.config.ComplexityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(ComplexityAnalyzer.MODID)
public class ComplexityAnalyzer {
    public static final String MODID = "complexityanalyzer";
    public static final String MOD_NAME = "Complexity Analyzer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    public ComplexityAnalyzer(ModContainer modContainer) {
        LOGGER.info("=== {} v{} ===", MOD_NAME, modContainer.getModInfo().getVersion());
        modContainer.registerConfig(ModConfig.Type.COMMON, ComplexityConfig.SPEC);
    }
}