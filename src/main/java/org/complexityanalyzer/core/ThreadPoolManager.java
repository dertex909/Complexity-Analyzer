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

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadPoolManager {

    private static final int PARALLELISM = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    private static volatile ThreadPoolManager instance;
    private static final Object LOCK = new Object();

    private volatile ExecutorService computePool;
    private volatile ForkJoinPool forkJoinPool;
    private volatile ScheduledExecutorService scheduledPool;

    private final AtomicInteger computeThreadCounter = new AtomicInteger(0);
    private final AtomicInteger scheduledThreadCounter = new AtomicInteger(0);

    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final AtomicBoolean isInitializing = new AtomicBoolean(false);

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
                ComplexityAnalyzer.LOGGER.info("Initializing ThreadPoolManager with {} threads", PARALLELISM);

                this.computePool = new ThreadPoolExecutor(
                        PARALLELISM,
                        PARALLELISM,
                        60L, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(10000),
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

                this.scheduledPool = Executors.newScheduledThreadPool(
                        Math.max(1, PARALLELISM / 2),
                        r -> {
                            Thread t = new Thread(r, "Complexity-Scheduled-" + scheduledThreadCounter.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        }
                );

                isShutdown.set(false);
            }
        } finally {
            isInitializing.set(false);
        }
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

    public ScheduledExecutorService getScheduledPool() {
        ensureNotShutdown();
        ScheduledExecutorService pool = scheduledPool;
        if (pool == null || pool.isShutdown()) {
            synchronized (LOCK) {
                pool = scheduledPool;
                if (pool == null || pool.isShutdown()) {
                    initialize();
                    pool = scheduledPool;
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

    public <T> Future<T> submit(Callable<T> task) {
        if (task == null) return null;
        return getComputePool().submit(task);
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

    public boolean isShutdownState() {
        return isShutdown.get();
    }

    public void shutdown() {
        if (!isShutdown.compareAndSet(false, true)) return;

        synchronized (LOCK) {
            ComplexityAnalyzer.LOGGER.info("Shutting down ThreadPoolManager...");

            shutdownPool(computePool, "ComputePool");
            shutdownPool(forkJoinPool, "ForkJoinPool");
            shutdownPool(scheduledPool, "ScheduledPool");

            computePool = null;
            forkJoinPool = null;
            scheduledPool = null;

            ComplexityAnalyzer.LOGGER.info("ThreadPoolManager shutdown complete.");
        }
    }

    private void shutdownPool(ExecutorService pool, String name) {
        if (pool == null || pool.isShutdown()) return;

        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                ComplexityAnalyzer.LOGGER.warn("{} did not terminate gracefully, forcing...", name);
                pool.shutdownNow();
                if (!pool.awaitTermination(2, TimeUnit.SECONDS))
                    ComplexityAnalyzer.LOGGER.error("{} did not terminate!", name);
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
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