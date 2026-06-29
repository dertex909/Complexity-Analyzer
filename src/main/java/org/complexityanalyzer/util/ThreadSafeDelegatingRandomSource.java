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

package org.complexityanalyzer.util;

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