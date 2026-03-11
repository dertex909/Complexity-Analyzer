package org.complexityanalyzer.geoscan.scan;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.geoscan.GeoDatabase;
import org.complexityanalyzer.geoscan.config.ScanConfig.ScanProfile;
import org.complexityanalyzer.geoscan.data.BiomeScanData;
import org.complexityanalyzer.geoscan.data.ScanMetadata;
import org.complexityanalyzer.geoscan.task.ScanNotifier;
import org.complexityanalyzer.geoscan.task.ScanTask;
import org.complexityanalyzer.geoscan.task.WorldScanner;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Координирует подготовку и запуск сканирования.
 * Отвечает за создание сессий и подготовку задач.
 */
public class ScanCoordinator {

    private final MinecraftServer server;
    private final GeoDatabase database;
    private final WorldScanner worldScanner;
    private final ScanNotifier notifier;

    private final AtomicLong sessionIdGenerator = new AtomicLong(0);

    private volatile ScanSession currentSession;

    public ScanCoordinator(
            MinecraftServer server,
            GeoDatabase database,
            WorldScanner worldScanner,
            ScanNotifier notifier
    ) {
        this.server = server;
        this.database = database;
        this.worldScanner = worldScanner;
        this.notifier = notifier;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Создание сессии
    // ══════════════════════════════════════════════════════════════════════════

    public ScanSession createSession(int chunksPerBiome, ScanProfile profile) {
        // Инвалидируем предыдущую сессию
        invalidateCurrentSession();

        long sessionId = sessionIdGenerator.incrementAndGet();
        ScanSession session = new ScanSession(sessionId, profile, chunksPerBiome);
        this.currentSession = session;

        worldScanner.clearStopRequest();
        worldScanner.configureForScan(chunksPerBiome);

        ComplexityAnalyzer.LOGGER.info("Created scan session {} with profile {} for {} chunks/biome",
                sessionId, profile.name(), chunksPerBiome);

        return session;
    }

    public void invalidateCurrentSession() {
        ScanSession session = currentSession;
        if (session != null) {
            session.invalidate();
            ComplexityAnalyzer.LOGGER.debug("Invalidated session {}", session.getSessionId());
        }
    }

    public ScanSession getCurrentSession() {
        return currentSession;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Подготовка задач
    // ══════════════════════════════════════════════════════════════════════════

    public List<ScanTask> prepareTasks(ScanSession session) {
        if (!session.isValid()) return Collections.emptyList();

        database.loadAll();
        List<ScanTask> tasks = new ArrayList<>();
        int chunksPerBiome = session.getChunksPerBiome();

        ComplexityAnalyzer.LOGGER.debug("[Prepare] Building scan tasks for {} chunks/biome", chunksPerBiome);

        for (ServerLevel level : server.getAllLevels()) {
            if (!session.isValid()) break;

            ResourceKey<Level> dimension = level.dimension();
            ComplexityAnalyzer.LOGGER.info("Scanning dimension: {}", dimension.location());

            Set<ResourceKey<Biome>> biomes = getBiomesForDimension(level);
            ComplexityAnalyzer.LOGGER.debug("Found {} biomes in {}", biomes.size(), dimension.location());

            for (ResourceKey<Biome> biomeKey : biomes) {
                if (!session.isValid()) break;

                int existingChunks = getExistingChunkCount(dimension.location(), biomeKey.location());
                int chunksNeeded = chunksPerBiome - existingChunks;

                if (chunksNeeded > 0) {
                    tasks.add(new ScanTask(dimension, biomeKey, chunksNeeded));
                }
            }
        }

        ComplexityAnalyzer.LOGGER.debug("[Prepare] Created {} scan tasks", tasks.size());
        tasks.sort(Comparator.naturalOrder());
        return tasks;
    }

    private Set<ResourceKey<Biome>> getBiomesForDimension(ServerLevel level) {
        Set<ResourceKey<Biome>> biomes = new HashSet<>();
        var biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        biomeSource.possibleBiomes().forEach(holder ->
                holder.unwrapKey().ifPresent(biomes::add));
        return biomes;
    }

    private int getExistingChunkCount(ResourceLocation dimension, ResourceLocation biome) {
        int finalChunks = database.getBiomeData(dimension, biome)
                .map(BiomeScanData::getChunksScanned)
                .orElse(0);
        int reconChunks = database.countReconChunks(dimension, biome);
        return Math.max(finalChunks, reconChunks);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Инициализация сессии
    // ══════════════════════════════════════════════════════════════════════════

    public boolean initializeSession(ScanSession session, List<ScanTask> tasks) {
        if (!session.isValid() || tasks.isEmpty()) {
            return false;
        }

        database.setScanPhase(ScanMetadata.ScanPhase.RECONNAISSANCE);

        session.addTasks(tasks);
        session.setTotalTasks(tasks.size());

        int totalChunks = 0;
        for (ScanTask task : tasks) {
            int needed = task.chunksToFind();
            totalChunks += needed;
            session.setBiomeNeed(task.dimension().location(), task.biome().location(), needed);
        }

        session.setTotalChunksNeeded(totalChunks);

        Set<Long> existing = database.loadAllReconChunkCoordinates();
        session.loadAttemptedChunks(existing);

        notifier.logInfo(String.format("Loaded %d already scanned chunk coordinates.", existing.size()));
        notifier.notifyScanPreparationComplete(tasks.size());

        return true;
    }

    public void finishReconnaissance(ScanSession session) {
        session.setPhase(ScanMetadata.ScanPhase.IDLE);
        session.clear();
        worldScanner.clearStopRequest();
        notifier.notifyReconnaissanceFinished(false);
    }

    public void stopScan() {
        invalidateCurrentSession();
        worldScanner.requestStop();

        ScanSession session = currentSession;
        if (session != null) {
            session.setPhase(ScanMetadata.ScanPhase.IDLE);
            session.clear();
        }
    }

    public void shutdown() {
        invalidateCurrentSession();
        worldScanner.shutdown();
    }
}