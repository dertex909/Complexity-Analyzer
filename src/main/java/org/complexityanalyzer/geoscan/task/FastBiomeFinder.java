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

package org.complexityanalyzer.geoscan.task;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public class FastBiomeFinder {

    private static final ThreadLocal<CachedSamplers> SAMPLER_CACHE = new ThreadLocal<>();

    private static CachedSamplers getSamplers(ServerLevel level) {
        var cached = SAMPLER_CACHE.get();
        if (cached != null && cached.level == level) return cached;
        var biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        var sampler = level.getChunkSource().randomState().sampler();
        var newCache = new CachedSamplers(level, biomeSource, sampler);
        SAMPLER_CACHE.set(newCache);
        return newCache;
    }

    public static BlockPos findBiome(ServerLevel level, Predicate<Holder<Biome>> biomePredicate,
                                     BlockPos origin, int maxRadius) {
        var samplers = getSamplers(level);
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        int totalHeight = maxY - minY;
        int coarseYStep = Math.max(32, totalHeight / 32);
        int coarseStep = Math.max(256, maxRadius / 25);
        var coarseMatch = gridSearch(samplers, biomePredicate, origin.getX(), origin.getZ(), minY, maxY, coarseYStep, maxRadius, coarseStep);

        if (coarseMatch == null) {
            coarseMatch = fastRandomSearch(samplers, biomePredicate, origin, maxRadius, minY, maxY, coarseYStep);
            if (coarseMatch == null) return null;
        }

        return refinePosition(samplers, biomePredicate, coarseMatch, minY, maxY, coarseYStep);
    }

    private static BlockPos gridSearch(CachedSamplers samplers, Predicate<Holder<Biome>> predicate,
                                       int centerX, int centerZ, int minY, int maxY, int yStep,
                                       int maxRadius, int step) {
        var match = checkColumn(samplers, predicate, centerX, centerZ, minY, maxY, yStep);
        if (match != null) return match;

        for (int distance = step; distance <= maxRadius; distance += step) {
            for (int offset = -distance; offset <= distance; offset += step) {
                match = checkColumn(samplers, predicate, centerX + offset, centerZ - distance, minY, maxY, yStep);
                if (match != null) return match;
                match = checkColumn(samplers, predicate, centerX + offset, centerZ + distance, minY, maxY, yStep);
                if (match != null) return match;
            }

            for (int offset = -distance + step; offset < distance; offset += step) {
                match = checkColumn(samplers, predicate, centerX - distance, centerZ + offset, minY, maxY, yStep);
                if (match != null) return match;
                match = checkColumn(samplers, predicate, centerX + distance, centerZ + offset, minY, maxY, yStep);
                if (match != null) return match;
            }
        }

        return null;
    }

    private static BlockPos fastRandomSearch(CachedSamplers samplers, Predicate<Holder<Biome>> predicate,
                                             BlockPos origin, int maxRadius, int minY, int maxY, int yStep) {
        var random = ThreadLocalRandom.current();

        for (int i = 0; i < 500; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = Math.sqrt(random.nextDouble()) * maxRadius;
            int x = origin.getX() + (int) (Math.cos(angle) * distance);
            int z = origin.getZ() + (int) (Math.sin(angle) * distance);
            var match = checkColumn(samplers, predicate, x, z, minY, maxY, yStep);
            if (match != null) return match;
        }

        return null;
    }

    private static BlockPos refinePosition(CachedSamplers samplers, Predicate<Holder<Biome>> predicate,
                                           BlockPos rough, int minY, int maxY, int coarseYStep) {
        int bestX = rough.getX();
        int bestZ = rough.getZ();
        int bestY = rough.getY();

        int startY = Math.max(minY, rough.getY() - coarseYStep);
        int endY = Math.min(maxY, rough.getY() + coarseYStep);

        for (int y = startY; y < endY; y += 4) {
            if (checkBiomeFast(samplers, predicate, bestX, y, bestZ)) {
                bestY = y;
                break;
            }
        }

        for (int step = 32; step >= 16; step /= 2) {
            for (int dx = -step; dx <= step; dx += step) {
                for (int dz = -step; dz <= step; dz += step) {
                    if (dx == 0 && dz == 0) continue;
                    int testX = bestX + dx;
                    int testZ = bestZ + dz;
                    if (checkBiomeFast(samplers, predicate, testX, bestY, testZ)) {
                        bestX = testX;
                        bestZ = testZ;
                    }
                }
            }
        }

        return new BlockPos(bestX, bestY, bestZ);
    }

    private static BlockPos checkColumn(CachedSamplers samplers, Predicate<Holder<Biome>> predicate,
                                        int x, int z, int minY, int maxY, int yStep) {
        for (int y = minY; y < maxY; y += yStep) {
            if (checkBiomeFast(samplers, predicate, x, y, z)) return new BlockPos(x, y, z);
        }
        return null;
    }

    private static boolean checkBiomeFast(CachedSamplers samplers, Predicate<Holder<Biome>> predicate, int x, int y, int z) {
        try {
            var biome = samplers.biomeSource.getNoiseBiome(x >> 2, y >> 2, z >> 2, samplers.sampler);
            return predicate.test(biome);
        } catch (Exception e) {
            return false;
        }
    }

    private record CachedSamplers(ServerLevel level, BiomeSource biomeSource, Climate.Sampler sampler) {
    }
}