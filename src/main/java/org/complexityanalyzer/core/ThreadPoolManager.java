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

package org.complexityanalyzer.core;

import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.config.ComplexityConfig;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ThreadPoolManager implements AutoCloseable {

    private static final int QUEUE_CAPACITY = 10_000;
    private static final long KEEP_ALIVE_SECONDS = 60L;
    private static final long GRACEFUL_TIMEOUT_MS = 500L;
    private static final long FORCE_KILL_TIMEOUT_MS = 1500L;
    private static final long QUEUE_OFFER_TIMEOUT_SECONDS = 10L;

    private static final Object LIFECYCLE_LOCK = new Object();
    private static final AtomicReference<ThreadPoolManager> INSTANCE = new AtomicReference<>();
    private static final PoolStats EMPTY_STATS = new PoolStats(0, 0, 0, 0, 0, 0);

    static {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                var current = INSTANCE.get();
                if (current != null) current.shutdown();
            }, "Complexity-JVM-Shutdown-Hook"));
        } catch (Exception ignored) {
        }
    }

    private final int parallelism;
    private final ThreadPoolExecutor computePool;
    private final ForkJoinPool forkJoinPool;
    private final AtomicInteger computeThreadCounter = new AtomicInteger(0);
    private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);

    private ThreadPoolManager() {
        this.parallelism = Math.max(1, ComplexityConfig.getMaxThreads());
        ComplexityAnalyzer.LOGGER.info("Initializing ThreadPoolManager with parallelism = {}", parallelism);

        var pluginClassLoader = getClass().getClassLoader();

        this.computePool = new ThreadPoolExecutor(
                parallelism,
                parallelism,
                KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    var thread = new ComplexityComputeThread(this, r, "Complexity-Compute-" + computeThreadCounter.incrementAndGet(), pluginClassLoader);
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                },
                new BlockingTimeoutPolicy()
        );

        this.computePool.allowCoreThreadTimeOut(true);

        this.forkJoinPool = new ForkJoinPool(
                parallelism,
                pool -> {
                    var thread = new ComplexityForkJoinThread(this, pool, pluginClassLoader);
                    thread.setDaemon(true);
                    return thread;
                },
                (t, e) -> ComplexityAnalyzer.LOGGER.error(
                        "Uncaught exception in ForkJoinPool thread {}", t.getName(), e),
                true
        );
    }

    public static ThreadPoolManager getInstance() {
        var current = INSTANCE.get();
        if (current != null && current.isRunning()) return current;

        synchronized (LIFECYCLE_LOCK) {
            current = INSTANCE.get();
            if (current == null || !current.isRunning()) {
                current = new ThreadPoolManager();
                INSTANCE.set(current);
            }
            return current;
        }
    }

    public static void reinitialize() {
        ThreadPoolManager oldManager;
        var freshManager = new ThreadPoolManager();

        synchronized (LIFECYCLE_LOCK) {
            oldManager = INSTANCE.getAndSet(freshManager);
            ComplexityConfig.resetThreadCache();
        }

        if (oldManager != null) {
            var asyncShutdown = new Thread(() -> {
                try {
                    oldManager.shutdown();
                } finally {
                    Thread.currentThread().setContextClassLoader(null);
                }
            }, "Complexity-Async-Shutdown");
            asyncShutdown.setDaemon(true);
            asyncShutdown.start();
        }

        ComplexityAnalyzer.LOGGER.info("ThreadPoolManager reinitialized with {} threads", freshManager.parallelism);
    }

    public static boolean isComplexityThread() {
        return Thread.currentThread() instanceof ComplexityThread;
    }

    public boolean isRunning() {
        return state.get() == State.RUNNING;
    }

    @NotNull
    public ExecutorService getComputePool() {
        ensureRunning();
        return computePool;
    }

    @NotNull
    public ForkJoinPool getForkJoinPool() {
        ensureRunning();
        return forkJoinPool;
    }

    public void invokeParallel(@NotNull Runnable action) {
        Objects.requireNonNull(action, "Action cannot be null");
        ensureRunning();
        try {
            forkJoinPool.invoke(ForkJoinTask.adapt(action));
        } catch (RejectedExecutionException | CancellationException e) {
            throw new RejectedExecutionException("Parallel execution rejected or cancelled - pool is shut down", e);
        }
    }

    public int getParallelism() {
        return parallelism;
    }

    private void ensureRunning() {
        if (state.get() != State.RUNNING) throw new RejectedExecutionException(
                "ThreadPoolManager instance is shut down (state=" + state.get() +
                        "). If reinitialized, call ThreadPoolManager.getInstance() to acquire the active manager.");
    }

    private boolean isWorkerOfThisManager() {
        var current = Thread.currentThread();
        if (current instanceof ComplexityComputeThread cct) return cct.owner == this;
        if (current instanceof ComplexityForkJoinThread cfjt) return cfjt.owner == this;
        return false;
    }

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        if (!state.compareAndSet(State.RUNNING, State.SHUTTING_DOWN)) return;

        long startTime = System.currentTimeMillis();
        ComplexityAnalyzer.LOGGER.debug("Shutting down ThreadPoolManager...");
        boolean wasInterrupted = false;
        int totalCancelledTasks = 0;

        computePool.shutdown();
        forkJoinPool.shutdown();

        if (isWorkerOfThisManager()) {
            ComplexityAnalyzer.LOGGER.debug("Self-shutdown detected from owner worker thread. Bypassing graceful wait.");
            totalCancelledTasks += computePool.shutdownNow().size();
            totalCancelledTasks += forkJoinPool.shutdownNow().size();
        } else {
            var computeResult = awaitPoolTermination(computePool, "ComputePool", GRACEFUL_TIMEOUT_MS);
            wasInterrupted |= computeResult.interrupted();
            totalCancelledTasks += computeResult.cancelledCount();

            var fjResult = awaitPoolTermination(forkJoinPool, "ForkJoinPool", GRACEFUL_TIMEOUT_MS);
            wasInterrupted |= fjResult.interrupted();
            totalCancelledTasks += fjResult.cancelledCount();

            if (!computeResult.terminated()) {
                ComplexityAnalyzer.LOGGER.warn("ComputePool did not terminate gracefully. Forcing shutdownNow...");
                totalCancelledTasks += computePool.shutdownNow().size();
            }

            if (!fjResult.terminated()) {
                ComplexityAnalyzer.LOGGER.warn("ForkJoinPool did not terminate gracefully. Forcing shutdownNow...");
                totalCancelledTasks += forkJoinPool.shutdownNow().size();
            }

            if (!computeResult.terminated()) {
                wasInterrupted |= awaitPoolTermination(computePool, "ComputePool (Force)", FORCE_KILL_TIMEOUT_MS).interrupted();
            }
            if (!fjResult.terminated()) {
                wasInterrupted |= awaitPoolTermination(forkJoinPool, "ForkJoinPool (Force)", FORCE_KILL_TIMEOUT_MS).interrupted();
            }
        }

        if (totalCancelledTasks > 0) {
            ComplexityAnalyzer.LOGGER.info("ThreadPoolManager: Total {} pending tasks cancelled during shutdown", totalCancelledTasks);
        }

        state.set(State.SHUTDOWN);

        INSTANCE.compareAndSet(this, null);

        logRemainingThreads();

        if (wasInterrupted) Thread.currentThread().interrupt();
        long totalTime = System.currentTimeMillis() - startTime;
        ComplexityAnalyzer.LOGGER.debug("ThreadPoolManager shutdown complete in {}ms.", totalTime);
    }

    private TerminationResult awaitPoolTermination(ExecutorService pool, String poolName, long timeoutMs) {
        if (pool.isTerminated()) return new TerminationResult(true, false, 0);
        try {
            boolean terminated = pool.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
            if (terminated) ComplexityAnalyzer.LOGGER.debug("{} terminated successfully.", poolName);
            return new TerminationResult(terminated, false, 0);
        } catch (InterruptedException e) {
            ComplexityAnalyzer.LOGGER.warn("Interrupted while waiting for {} termination", poolName);
            int cancelled = pool.shutdownNow().size();
            return new TerminationResult(pool.isTerminated(), true, cancelled);
        }
    }

    private void logRemainingThreads() {
        int computeActive = computePool.isTerminated() ? 0 : computePool.getActiveCount();
        int fjActive = forkJoinPool.isTerminated() ? 0 : forkJoinPool.getActiveThreadCount();
        int total = computeActive + fjActive;

        if (total > 0) ComplexityAnalyzer.LOGGER.warn(
                "{} worker threads still active after shutdown (Compute: {}, ForkJoin: {}) — will terminate with JVM", total, computeActive, fjActive);
    }

    @NotNull
    public PoolStats getStats() {
        if (state.get() != State.RUNNING) return EMPTY_STATS;

        return new PoolStats(
                parallelism,
                computePool.getActiveCount(),
                computePool.getCompletedTaskCount(),
                computePool.getQueue().size(),
                forkJoinPool.getActiveThreadCount(),
                forkJoinPool.getStealCount()
        );
    }

    public enum State {
        RUNNING,
        SHUTTING_DOWN,
        SHUTDOWN
    }

    public interface ComplexityThread {
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

    private record TerminationResult(boolean terminated, boolean interrupted, int cancelledCount) {
    }

    private static final class BlockingTimeoutPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) throw new RejectedExecutionException(
                    "Task " + r + " rejected from " + executor + " (pool is shut down)");

            if (isComplexityThread()) {
                if (!executor.getQueue().offer(r)) r.run();
                return;
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(QUEUE_OFFER_TIMEOUT_SECONDS);
            try {
                while (System.nanoTime() < deadline) {
                    if (executor.isShutdown()) throw new RejectedExecutionException(
                            "Task " + r + " rejected: pool was shut down during offer");
                    if (executor.getQueue().offer(r, 250, TimeUnit.MILLISECONDS)) {
                        if (executor.isShutdown() && executor.getQueue().remove(r)) {
                            throw new RejectedExecutionException("Task " + r + " rejected: pool was shut down after offer");
                        }
                        return;
                    }
                }
                throw new RejectedExecutionException(
                        "Task " + r + " rejected: queue offer timed out after " + QUEUE_OFFER_TIMEOUT_SECONDS + "s");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("Task " + r + " interrupted while waiting for queue space", e);
            }
        }
    }

    private static final class ComplexityComputeThread extends Thread implements ComplexityThread {
        final ThreadPoolManager owner;

        ComplexityComputeThread(ThreadPoolManager owner, Runnable target, String name, ClassLoader classLoader) {
            super(target, name);
            this.owner = owner;
            if (classLoader != null) setContextClassLoader(classLoader);
        }

        @Override
        public void run() {
            try {
                super.run();
            } finally {
                setContextClassLoader(null);
            }
        }
    }

    private static final class ComplexityForkJoinThread extends ForkJoinWorkerThread implements ComplexityThread {
        final ThreadPoolManager owner;

        ComplexityForkJoinThread(ThreadPoolManager owner, ForkJoinPool pool, ClassLoader classLoader) {
            super(pool);
            this.owner = owner;
            if (classLoader != null) setContextClassLoader(classLoader);
        }

        @Override
        protected void onStart() {
            super.onStart();
            setName("Complexity-ForkJoin-" + getPoolIndex());
        }

        @Override
        protected void onTermination(Throwable cause) {
            try {
                super.onTermination(cause);
            } finally {
                setContextClassLoader(null);
            }
        }
    }
}