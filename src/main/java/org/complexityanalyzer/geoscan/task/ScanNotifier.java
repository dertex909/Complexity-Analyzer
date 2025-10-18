package org.complexityanalyzer.geoscan.task;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.jetbrains.annotations.Nullable;

public class ScanNotifier {

    private final MinecraftServer server;
    private long lastLogTime = 0;
    private static final long LOG_INTERVAL_MS = 15_000;

    public ScanNotifier(MinecraftServer server) {
        this.server = server;
    }

    public void broadcastInfo(String message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal("§e[CA] §f" + message), false);
    }

    public void broadcastWarning(String message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal("§e[CA] §6WARNING! §f" + message), false);
    }

    public void broadcastSevere(String message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal("§c[CA] §l" + message), false);
    }

    public void broadcastSuccess(String message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal("§a[CA] §f" + message), false);
    }

    public void sendSuccess(@Nullable CommandSourceStack source, String message) {
        Component component = Component.literal("§a[CA] §f" + message);
        if (source != null) {
            source.sendSuccess(() -> component, false);
        } else {
            logInfo(message);
        }
    }

    public void sendFailure(@Nullable CommandSourceStack source, String message) {
        Component component = Component.literal("§c[CA] Error: " + message);
        if (source != null) {
            source.sendFailure(component);
        } else {
            logError(message);
        }
    }

    public void logInfo(String message) {
        ComplexityAnalyzer.LOGGER.info(message);
    }

    public void logWarn(String message) {
        ComplexityAnalyzer.LOGGER.warn(message);
    }

    public void logError(String message) {
        ComplexityAnalyzer.LOGGER.error(message);
    }

    public void logError(String message, Throwable throwable) {
        ComplexityAnalyzer.LOGGER.error(message, throwable);
    }

    public void logProgress(boolean shouldLog, String message) {
        if (!shouldLog) return;

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastLogTime > LOG_INTERVAL_MS) {
            lastLogTime = currentTime;
            logInfo(message);
        }
    }

    public void notifyScanCountdown(int secondsLeft) {
        if (secondsLeft > 0 && (secondsLeft <= 10 || secondsLeft % 15 == 0)) {
            broadcastInfo(String.format("World analysis will start in %d seconds...", secondsLeft));
        } else if (secondsLeft == 0) {
            broadcastSevere("Scan started! High server load may occur!");
        }
    }

    public void notifyScanStarting(int chunksPerBiome, String initiator) {
        logInfo("Countdown finished. Starting scan requested by " + initiator + " for " + chunksPerBiome + " chunks per biome.");
    }

    public void notifyScanPreparationComplete(int totalBiomes) {
        broadcastInfo("Preliminary analysis complete. Background scanning begins.");
        logInfo("Geo-scan preparation complete. Found " + totalBiomes + " reachable biomes to scan/update.");
    }

    public void notifyDatabaseIsUpToDate() {
        broadcastSuccess("Database is already up to date.");
    }

    public void notifyReconnaissanceFinished(boolean wasStopped) {
        if (wasStopped) {
            broadcastInfo("Reconnaissance scan stopped. Progress saved.");
        } else {
            broadcastSuccess("Reconnaissance complete! Starting final data refinement in background...");
        }
    }

    public void notifyRefinementFinished() {
        broadcastSuccess("Geo-scan complete! Database is fully optimized.");
        logInfo("Global refinement complete. GeoDatabase is now fully operational.");
    }
}