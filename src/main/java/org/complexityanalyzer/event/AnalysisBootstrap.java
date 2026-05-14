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

package org.complexityanalyzer.event;

import net.minecraft.server.MinecraftServer;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import org.complexityanalyzer.command.WebCommand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.compat.jei.AdaptiveRecipeConverter;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.export.cabin.io.CabinBackgroundService;

@EventBusSubscriber(modid = ComplexityAnalyzer.MODID)
public class AnalysisBootstrap {

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        GameRegistryManager.initialize();
        AnalysisEngine engine = AnalysisEngine.getInstance();

        Thread.ofVirtual().start(() -> {
            try (Scanner s = new Scanner(URI.create("https://checkip.amazonaws.com").toURL().openStream(), StandardCharsets.UTF_8).useDelimiter("\\A")) {
                String ip = s.next().trim();
                WebCommand.setPublicIp(ip);
                ComplexityAnalyzer.LOGGER.info("[Network] Public IP detected: {}", ip);
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.warn("[Network] Failed to detect public IP: {}", e.getMessage());
            }
        });

        ComplexityAnalyzer.LOGGER.info("Server started, initializing Complexity Analyzer...");
        engine.initializeAsync(server.overworld(), () -> {
            ComplexityAnalyzer.LOGGER.info("✅ Analysis engine initialization complete.");
            try {
                String modVersion = ModList.get().getModContainerById(ComplexityAnalyzer.MODID)
                        .map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
                CabinBackgroundService.getInstance().regenerateAsync(server, engine, modVersion);
            } catch (Throwable t) {
                ComplexityAnalyzer.LOGGER.error("[Cabin] Failed to schedule auto-regenerate", t);
            }
        });
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ComplexityAnalyzer.LOGGER.info("Server stopping, shutting down Complexity Analyzer...");
        try {
            CabinBackgroundService.getInstance().clear();
        } catch (Throwable ignored) {
        }
        AnalysisEngine.getInstance().shutdownCompletely();
        ComplexityAnalyzer.LOGGER.debug("Cleaning up AdaptiveRecipeConverter resources...");
        AdaptiveRecipeConverter.clearCaches();
    }

    public static AnalysisEngine getEngine() {
        return AnalysisEngine.getInstance();
    }
}