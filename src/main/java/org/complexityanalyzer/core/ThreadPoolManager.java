/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
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

package org.complexityanalyzer.core;

import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.config.ComplexityConfig;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class ThreadPoolManager {

    private static final int QUEUE_CAPACITY = 10000;
    private static final long GRACEFUL_TIMEOUT_MS = 500;
    private static final long FORCE_KILL_TIMEOUT_MS = 2000;

    private static volatile ThreadPoolManager instance;
    private static final Object LOCK = new Object();

    private volatile ExecutorService computePool;
    private volatile ForkJoinPool forkJoinPool;
    private volatile int parallelism;

    private final AtomicInteger computeThreadCounter = new AtomicInteger(0);
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final AtomicBoolean isInitializing = new AtomicBoolean(false);
    private static final AtomicBoolean JVM_SHUTTING_DOWN = new AtomicBoolean(false);

    static {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> JVM_SHUTTING_DOWN.set(true), "Complexity-Shutdown-Flag-Setter"));
        } catch (Exception ignored) {
        }
    }

    private volatile Thread shutdownWatchdog;

    private ThreadPoolManager() {
        initialize();
    }

    public static ThreadPoolManager getInstance() {
        ThreadPoolManager localInstance = instance;
        if (localInstance == null || localInstance.isShutdown.get()) synchronized (LOCK) {
            localInstance = instance;
            if (localInstance == null || localInstance.isShutdown.get()) {
                instance = localInstance = new ThreadPoolManager();
            }
        }
        return localInstance;
    }

    public static void reinitialize() {
        synchronized (LOCK) {
            ThreadPoolManager existing = instance;

            if (existing != null) {
                if (!existing.isShutdown.get()) existing.shutdown();
                existing.isShutdown.set(false);
                existing.isInitializing.set(false);
                existing.computeThreadCounter.set(0);
                ComplexityConfig.resetThreadCache();
                existing.initialize();
                ComplexityAnalyzer.LOGGER.info("ThreadPoolManager reinitialized with {} threads", existing.parallelism);
            } else {
                instance = new ThreadPoolManager();
            }
        }
    }

    private void initialize() {
        if (!isInitializing.compareAndSet(false, true)) {
            synchronized (LOCK) {
                if (!isShutdown.get() && computePool != null && !computePool.isShutdown()) return;
            }
            return;
        }

        try {
            synchronized (LOCK) {
                if (isShutdown.get()) {
                    ComplexityAnalyzer.LOGGER.warn("Attempted to initialize during shutdown - aborting");
                    return;
                }

                this.parallelism = ComplexityConfig.getMaxThreads();

                ComplexityAnalyzer.LOGGER.info("Initializing ThreadPoolManager with {} threads", parallelism);

                this.computePool = new ThreadPoolExecutor(
                        parallelism,
                        parallelism,
                        60L, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                        r -> {
                            Thread t = new Thread(r, "Complexity-Compute-" + computeThreadCounter.incrementAndGet());
                            t.setDaemon(true);
                            t.setPriority(Thread.MIN_PRIORITY);
                            return t;
                        },
                        new ThreadPoolExecutor.AbortPolicy()
                );

                this.forkJoinPool = new ForkJoinPool(
                        parallelism,
                        pool -> {
                            ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                            thread.setName("Complexity-ForkJoin-" + thread.getPoolIndex());
                            thread.setDaemon(true);
                            return thread;
                        },
                        (t, e) -> ComplexityAnalyzer.LOGGER.error(
                                "Uncaught exception in ForkJoinPool thread {}", t.getName(), e),
                        true
                );

                Thread oldWatchdog = shutdownWatchdog;
                if (oldWatchdog != null && oldWatchdog.isAlive()) oldWatchdog.interrupt();

                shutdownWatchdog = new Thread(() -> {
                    while (!isShutdown.get()) {
                        LockSupport.parkNanos(500_000_000L);
                        if (Thread.currentThread().isInterrupted()) return;

                        if (JVM_SHUTTING_DOWN.get()) {
                            ComplexityAnalyzer.LOGGER.info("[Watchdog] JVM shutdown detected — force killing all threads");
                            forceShutdown();
                            return;
                        }
                    }
                }, "Complexity-Watchdog");

                shutdownWatchdog.setDaemon(true);
                shutdownWatchdog.start();
            }
        } finally {
            isInitializing.set(false);
        }
    }


    private void forceShutdown() {
        if (!isShutdown.compareAndSet(false, true)) return;

        ExecutorService compute = computePool;
        ForkJoinPool fj = forkJoinPool;

        if (compute != null && !compute.isShutdown()) {
            int droppedCount = compute.shutdownNow().size();
            if (droppedCount > 0) {
                ComplexityAnalyzer.LOGGER.debug("[Watchdog] ComputePool: dropped {} pending tasks", droppedCount);
            }
        }

        if (fj != null && !fj.isShutdown()) {
            int droppedCount = fj.shutdownNow().size();
            if (droppedCount > 0) {
                ComplexityAnalyzer.LOGGER.debug("[Watchdog] ForkJoinPool: dropped {} pending tasks", droppedCount);
            }
        }

        computePool = null;
        forkJoinPool = null;

        ComplexityAnalyzer.LOGGER.info("[Watchdog] All pools force-killed");
    }

    public ExecutorService getComputePool() {
        ensureNotShutdown();
        ExecutorService pool = computePool;
        if (pool == null || pool.isShutdown()) synchronized (LOCK) {
            pool = computePool;
            if (pool == null || pool.isShutdown()) {
                initialize();
                pool = computePool;
            }
        }
        return pool;
    }

    public ForkJoinPool getForkJoinPool() {
        ensureNotShutdown();
        ForkJoinPool pool = forkJoinPool;
        if (pool == null || pool.isShutdown()) synchronized (LOCK) {
            pool = forkJoinPool;
            if (pool == null || pool.isShutdown()) {
                initialize();
                pool = forkJoinPool;
            }
        }
        return pool;
    }

    public int getParallelism() {
        return parallelism;
    }

    private void ensureNotShutdown() {
        if (isShutdown.get()) throw new RejectedExecutionException("ThreadPoolManager is shut down");
    }

    public void shutdown() {
        if (!isShutdown.compareAndSet(false, true)) return;

        synchronized (LOCK) {
            long shutdownStartTime = System.currentTimeMillis();
            ComplexityAnalyzer.LOGGER.info("Shutting down ThreadPoolManager...");

            Thread watchdog = shutdownWatchdog;
            if (watchdog != null) {
                watchdog.interrupt();
                shutdownWatchdog = null;
            }

            ExecutorService compute = computePool;
            ForkJoinPool fj = forkJoinPool;

            if (compute != null && !compute.isShutdown()) compute.shutdown();
            if (fj != null && !fj.isShutdown()) fj.shutdown();

            boolean computeTerminated = awaitTermination(compute, "ComputePool", GRACEFUL_TIMEOUT_MS);
            boolean fjTerminated = awaitTermination(fj, "ForkJoinPool", GRACEFUL_TIMEOUT_MS);

            if (!computeTerminated) {
                ComplexityAnalyzer.LOGGER.warn("ComputePool did not terminate gracefully, forcing...");
                int droppedCount = compute.shutdownNow().size();
                if (droppedCount > 0)
                    ComplexityAnalyzer.LOGGER.info("ComputePool: dropped {} pending tasks", droppedCount);
            }

            if (!fjTerminated) {
                ComplexityAnalyzer.LOGGER.warn("ForkJoinPool did not terminate gracefully, forcing...");
                int droppedCount = fj.shutdownNow().size();
                if (droppedCount > 0)
                    ComplexityAnalyzer.LOGGER.info("ForkJoinPool: dropped {} pending tasks", droppedCount);
            }

            long elapsed = System.currentTimeMillis() - shutdownStartTime;
            long remainingTime = FORCE_KILL_TIMEOUT_MS - elapsed;

            if (remainingTime > 0) {
                if (!computeTerminated) awaitTermination(compute, "ComputePool", remainingTime / 2);
                if (!fjTerminated) {
                    elapsed = System.currentTimeMillis() - shutdownStartTime;
                    remainingTime = FORCE_KILL_TIMEOUT_MS - elapsed;
                    if (remainingTime > 0) fjTerminated = awaitTermination(fj, "ForkJoinPool", remainingTime);
                }
            }

            if (!fjTerminated) {
                elapsed = System.currentTimeMillis() - shutdownStartTime;
                ComplexityAnalyzer.LOGGER.warn("Pools did not terminate within {}ms - force killing threads", elapsed);
                forceInterruptAllWorkers();
            }

            computePool = null;
            forkJoinPool = null;

            logRemainingThreads(compute, fj);

            long totalTime = System.currentTimeMillis() - shutdownStartTime;
            ComplexityAnalyzer.LOGGER.info("ThreadPoolManager shutdown complete in {}ms.", totalTime);
        }
    }

    private boolean awaitTermination(ExecutorService pool, String name, long timeoutMs) {
        if (pool == null || pool.isTerminated()) return true;
        if (timeoutMs <= 0) return pool.isTerminated();

        try {
            boolean terminated = pool.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
            if (terminated) ComplexityAnalyzer.LOGGER.debug("{} terminated successfully", name);
            return terminated;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ComplexityAnalyzer.LOGGER.debug("Interrupted while waiting for {} termination", name);
            return pool.isTerminated();
        }
    }

    private void forceInterruptAllWorkers() {
        Thread[] threads = new Thread[Thread.activeCount() * 2];
        int count = Thread.enumerate(threads);

        int interrupted = 0;
        for (int i = 0; i < count; i++) {
            Thread t = threads[i];
            if (t != null && t.isAlive()) {
                String name = t.getName();
                if (name.startsWith("Complexity-Compute-") || name.startsWith("Complexity-ForkJoin-")) {
                    if (!t.isInterrupted()) {
                        t.interrupt();
                        interrupted++;
                        ComplexityAnalyzer.LOGGER.debug("Force interrupted thread: {}", name);
                    }
                }
            }
        }

        if (interrupted > 0) {
            ComplexityAnalyzer.LOGGER.info("Force interrupted {} worker threads", interrupted);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void logRemainingThreads(ExecutorService compute, ForkJoinPool fj) {
        int computeActive = compute instanceof ThreadPoolExecutor tpe && !tpe.isTerminated() ? tpe.getActiveCount() : 0;
        int fjActive = fj != null && !fj.isTerminated() ? fj.getActiveThreadCount() : 0;
        int total = computeActive + fjActive;

        if (total > 0) {
            String details = computeActive > 0 && fjActive > 0
                    ? String.format(" (Compute: %d, ForkJoin: %d)", computeActive, fjActive)
                    : computeActive > 0 ? " (ComputePool)" : " (ForkJoinPool)";
            ComplexityAnalyzer.LOGGER.warn("{} daemon threads{} still running — will terminate with JVM", total, details);
        }
    }

    private static final PoolStats EMPTY_STATS = new PoolStats(0, 0, 0, 0, 0, 0);

    public PoolStats getStats() {
        ExecutorService compute = computePool;
        ForkJoinPool fj = forkJoinPool;

        if (compute == null || fj == null || isShutdown.get()) return EMPTY_STATS;

        if (compute instanceof ThreadPoolExecutor tpe) return new PoolStats(
                parallelism,
                tpe.getActiveCount(),
                tpe.getCompletedTaskCount(),
                tpe.getQueue().size(),
                fj.getActiveThreadCount(),
                fj.getStealCount()
        );

        return new PoolStats(parallelism, 0, 0, 0, fj.getActiveThreadCount(), fj.getStealCount());
    }

    public record PoolStats(
            int parallelism,
            int activeThreads,
            long completedTasks,
            int queuedTasks,
            int forkJoinActive,
            long forkJoinSteals
    ) {
        @Override
        public @NotNull String toString() {
            return String.format("PoolStats{parallelism=%d, active=%d, completed=%d, queued=%d, fjActive=%d, fjSteals=%d}",
                    parallelism, activeThreads, completedTasks, queuedTasks, forkJoinActive, forkJoinSteals);
        }
    }
}