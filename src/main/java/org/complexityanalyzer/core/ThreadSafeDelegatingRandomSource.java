package org.complexityanalyzer.core;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.jetbrains.annotations.NotNull;

public class ThreadSafeDelegatingRandomSource implements RandomSource {
    private final RandomSource original;
    private final ThreadLocal<RandomSource> threadLocalRandom;

    public ThreadSafeDelegatingRandomSource(RandomSource original) {
        this.original = original;
        this.threadLocalRandom = ThreadLocal.withInitial(RandomSource::createNewThreadLocalInstance);
    }

    private RandomSource getDelegate() {
        if (Thread.currentThread().getName().startsWith("Complexity-")) return this.threadLocalRandom.get();
        return this.original;
    }

    @Override
    public @NotNull RandomSource fork() {
        return getDelegate().fork();
    }

    @Override
    public @NotNull PositionalRandomFactory forkPositional() {
        return getDelegate().forkPositional();
    }

    @Override
    public void setSeed(long seed) {
        getDelegate().setSeed(seed);
    }

    @Override
    public int nextInt() {
        return getDelegate().nextInt();
    }

    @Override
    public int nextInt(int bound) {
        return getDelegate().nextInt(bound);
    }

    @Override
    public long nextLong() {
        return getDelegate().nextLong();
    }

    @Override
    public boolean nextBoolean() {
        return getDelegate().nextBoolean();
    }

    @Override
    public float nextFloat() {
        return getDelegate().nextFloat();
    }

    @Override
    public double nextDouble() {
        return getDelegate().nextDouble();
    }

    @Override
    public double nextGaussian() {
        return getDelegate().nextGaussian();
    }
}