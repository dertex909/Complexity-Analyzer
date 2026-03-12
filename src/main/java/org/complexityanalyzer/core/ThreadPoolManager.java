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
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class ThreadPoolManager {

    private static final int PARALLELISM = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    private static final int QUEUE_CAPACITY = 10000;
    private static final long SHUTDOWN_TIMEOUT_MS = 500;

    private static volatile ThreadPoolManager instance;
    private static final Object LOCK = new Object();

    private volatile ExecutorService computePool;
    private volatile ForkJoinPool forkJoinPool;

    private final AtomicInteger computeThreadCounter = new AtomicInteger(0);
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final AtomicBoolean isInitializing = new AtomicBoolean(false);

    private volatile Thread shutdownWatchdog;

    private ThreadPoolManager() {
        initialize();
    }

    public static ThreadPoolManager getInstance() {
        ThreadPoolManager localInstance = instance;
        if (localInstance == null) {
            synchronized (LOCK) {
                localInstance = instance;
                if (localInstance == null) instance = localInstance = new ThreadPoolManager();
            }
        }
        return localInstance;
    }

    private void initialize() {
        if (!isInitializing.compareAndSet(false, true)) synchronized (LOCK) {
            if (!isShutdown.get() && computePool != null && !computePool.isShutdown()) return;
        }

        try {
            synchronized (LOCK) {
                ComplexityAnalyzer.LOGGER.info("Initializing ThreadPoolManager with {} threads (leaving 1 for server)", PARALLELISM);

                this.computePool = new ThreadPoolExecutor(
                        PARALLELISM,
                        PARALLELISM,
                        60L, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                        r -> {
                            Thread t = new Thread(r, "Complexity-Compute-" + computeThreadCounter.incrementAndGet());
                            t.setDaemon(true);
                            t.setPriority(Thread.NORM_PRIORITY - 1);
                            return t;
                        },
                        new ThreadPoolExecutor.CallerRunsPolicy()
                );

                this.forkJoinPool = new ForkJoinPool(
                        PARALLELISM,
                        pool -> {
                            ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                            thread.setName("Complexity-ForkJoin-" + thread.getPoolIndex());
                            thread.setDaemon(true);
                            return thread;
                        },
                        (t, e) -> ComplexityAnalyzer.LOGGER.error("Uncaught exception in ForkJoinPool thread {}", t.getName(), e),
                        true
                );

                isShutdown.set(false);

                Thread oldWatchdog = shutdownWatchdog;
                if (oldWatchdog != null && oldWatchdog.isAlive()) oldWatchdog.interrupt();

                shutdownWatchdog = new Thread(() -> {
                    while (!isShutdown.get()) {
                        LockSupport.parkNanos(100_000_000_0L);

                        if (isJvmShuttingDown()) {
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

    private boolean isJvmShuttingDown() {
        try {
            Thread hook = new Thread(() -> {
            });
            Runtime.getRuntime().addShutdownHook(hook);
            Runtime.getRuntime().removeShutdownHook(hook);
            return false;
        } catch (IllegalStateException e) {
            return true;
        }
    }

    private void forceShutdown() {
        isShutdown.set(true);

        ExecutorService compute = computePool;
        ForkJoinPool fj = forkJoinPool;

        if (compute != null && !compute.isShutdown()) {
            List<Runnable> dropped = compute.shutdownNow();
            if (!dropped.isEmpty()) {
                ComplexityAnalyzer.LOGGER.debug("[Watchdog] ComputePool: dropped {} pending tasks", dropped.size());
            }
        }

        if (fj != null && !fj.isShutdown()) {
            List<Runnable> dropped = fj.shutdownNow();
            if (!dropped.isEmpty()) {
                ComplexityAnalyzer.LOGGER.debug("[Watchdog] ForkJoinPool: dropped {} pending tasks", dropped.size());
            }
        }

        computePool = null;
        forkJoinPool = null;

        ComplexityAnalyzer.LOGGER.info("[Watchdog] All pools force-killed");
    }

    public ExecutorService getComputePool() {
        ensureNotShutdown();
        ExecutorService pool = computePool;
        if (pool == null || pool.isShutdown()) {
            synchronized (LOCK) {
                pool = computePool;
                if (pool == null || pool.isShutdown()) {
                    initialize();
                    pool = computePool;
                }
            }
        }
        return pool;
    }

    public ForkJoinPool getForkJoinPool() {
        ensureNotShutdown();
        ForkJoinPool pool = forkJoinPool;
        if (pool == null || pool.isShutdown()) {
            synchronized (LOCK) {
                pool = forkJoinPool;
                if (pool == null || pool.isShutdown()) {
                    initialize();
                    pool = forkJoinPool;
                }
            }
        }
        return pool;
    }

    public int getParallelism() {
        return PARALLELISM;
    }

    public void submit(Runnable task) {
        if (task == null) return;
        getComputePool().submit(task);
    }

    private void ensureNotShutdown() {
        if (isShutdown.get()) {
            synchronized (LOCK) {
                if (isShutdown.get()) {
                    ComplexityAnalyzer.LOGGER.info("ThreadPoolManager was shutdown, reinitializing...");
                    initialize();
                }
            }
        }
    }

    public void shutdown() {
        if (!isShutdown.compareAndSet(false, true)) return;

        synchronized (LOCK) {
            ComplexityAnalyzer.LOGGER.info("Shutting down ThreadPoolManager...");

            Thread watchdog = shutdownWatchdog;
            if (watchdog != null) watchdog.interrupt();

            int computeCancelled = shutdownPoolImmediate(computePool, "ComputePool");
            int forkJoinCancelled = shutdownPoolImmediate(forkJoinPool, "ForkJoinPool");

            int totalCancelled = computeCancelled + forkJoinCancelled;
            if (totalCancelled > 0) {
                ComplexityAnalyzer.LOGGER.info("Cancelled {} pending tasks across all pools.", totalCancelled);
            }

            awaitTerminationBriefly();
            logRemainingThreads();

            computePool = null;
            forkJoinPool = null;

            ComplexityAnalyzer.LOGGER.info("ThreadPoolManager shutdown complete.");
        }
    }

    private int shutdownPoolImmediate(ExecutorService pool, String name) {
        if (pool == null || pool.isShutdown()) return 0;
        List<Runnable> cancelled = pool.shutdownNow();
        int count = cancelled.size();
        if (count > 0) ComplexityAnalyzer.LOGGER.debug("{}: cancelled {} pending tasks", name, count);
        return count;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void awaitTerminationBriefly() {
        long deadline = System.currentTimeMillis() + SHUTDOWN_TIMEOUT_MS;

        try {
            ExecutorService compute = computePool;
            ForkJoinPool fj = forkJoinPool;

            if (compute != null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining > 0) compute.awaitTermination(remaining, TimeUnit.MILLISECONDS);
            }

            if (fj != null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining > 0) fj.awaitTermination(remaining, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ComplexityAnalyzer.LOGGER.debug("Interrupted while waiting for pool termination");
        }
    }

    private void logRemainingThreads() {
        int remaining = 0;

        ExecutorService compute = computePool;
        if (compute instanceof ThreadPoolExecutor tpe && !tpe.isTerminated()) {
            int active = tpe.getActiveCount();
            if (active > 0) {
                remaining += active;
                ComplexityAnalyzer.LOGGER.debug("ComputePool: {} threads still active (will die with JVM)", active);
            }
        }

        ForkJoinPool fj = forkJoinPool;
        if (fj != null && !fj.isTerminated()) {
            int active = fj.getActiveThreadCount();
            if (active > 0) {
                remaining += active;
                ComplexityAnalyzer.LOGGER.debug("ForkJoinPool: {} threads still active (will die with JVM)", active);
            }
        }

        if (remaining > 0) {
            ComplexityAnalyzer.LOGGER.info("{} daemon threads still running — they will terminate when JVM exits.", remaining);
        }
    }

    public PoolStats getStats() {
        ExecutorService compute = computePool;
        ForkJoinPool fj = forkJoinPool;

        if (compute == null || fj == null || isShutdown.get()) {
            return new PoolStats(PARALLELISM, 0, 0, 0, 0, 0);
        }

        if (compute instanceof ThreadPoolExecutor tpe) {
            return new PoolStats(
                    PARALLELISM,
                    tpe.getActiveCount(),
                    tpe.getCompletedTaskCount(),
                    tpe.getQueue().size(),
                    fj.getActiveThreadCount(),
                    fj.getStealCount()
            );
        }

        return new PoolStats(PARALLELISM, 0, 0, 0, fj.getActiveThreadCount(), fj.getStealCount());
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
            return String.format(
                    "PoolStats{parallelism=%d, active=%d, completed=%d, queued=%d, fjActive=%d, fjSteals=%d}",
                    parallelism, activeThreads, completedTasks, queuedTasks, forkJoinActive, forkJoinSteals
            );
        }
    }
}