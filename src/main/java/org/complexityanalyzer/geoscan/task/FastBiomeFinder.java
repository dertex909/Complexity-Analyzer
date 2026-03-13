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

    private record CachedSamplers(ServerLevel level, BiomeSource biomeSource, Climate.Sampler sampler) {
    }

    private static CachedSamplers getSamplers(ServerLevel level) {
        CachedSamplers cached = SAMPLER_CACHE.get();
        if (cached != null && cached.level == level) return cached;
        BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        CachedSamplers newCache = new CachedSamplers(level, biomeSource, sampler);
        SAMPLER_CACHE.set(newCache);
        return newCache;
    }

    public static BlockPos findBiome(
            ServerLevel level,
            Predicate<Holder<Biome>> biomePredicate,
            BlockPos origin,
            int maxRadius
    ) {
        CachedSamplers samplers = getSamplers(level);
        int searchY = level.getSeaLevel();
        int coarseStep = Math.max(256, maxRadius / 25);
        BlockPos coarseMatch = gridSearch(samplers, biomePredicate, origin.getX(), origin.getZ(),
                searchY, maxRadius, coarseStep);
        if (coarseMatch == null) {
            coarseMatch = fastRandomSearch(samplers, biomePredicate, origin, maxRadius, searchY);
            if (coarseMatch == null) return null;
        }
        return refinePosition(samplers, biomePredicate, coarseMatch, searchY);
    }

    private static BlockPos gridSearch(CachedSamplers samplers, Predicate<Holder<Biome>> predicate,
                                       int centerX, int centerZ, int y, int maxRadius, int step) {
        if (checkBiomeFast(samplers, predicate, centerX, y, centerZ)) return new BlockPos(centerX, y, centerZ);

        for (int distance = step; distance <= maxRadius; distance += step) {
            for (int offset = -distance; offset <= distance; offset += step) {
                if (checkBiomeFast(samplers, predicate, centerX + offset, y, centerZ - distance)) {
                    return new BlockPos(centerX + offset, y, centerZ - distance);
                }
                if (checkBiomeFast(samplers, predicate, centerX + offset, y, centerZ + distance)) {
                    return new BlockPos(centerX + offset, y, centerZ + distance);
                }
            }
            for (int offset = -distance + step; offset < distance; offset += step) {
                if (checkBiomeFast(samplers, predicate, centerX - distance, y, centerZ + offset)) {
                    return new BlockPos(centerX - distance, y, centerZ + offset);
                }
                if (checkBiomeFast(samplers, predicate, centerX + distance, y, centerZ + offset)) {
                    return new BlockPos(centerX + distance, y, centerZ + offset);
                }
            }
        }

        return null;
    }

    private static BlockPos fastRandomSearch(CachedSamplers samplers, Predicate<Holder<Biome>> predicate,
                                             BlockPos origin, int maxRadius, int y
    ) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int i = 0; i < 500; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = Math.sqrt(random.nextDouble()) * maxRadius;
            int x = origin.getX() + (int) (Math.cos(angle) * distance);
            int z = origin.getZ() + (int) (Math.sin(angle) * distance);
            if (checkBiomeFast(samplers, predicate, x, y, z)) return new BlockPos(x, y, z);
        }

        return null;
    }

    private static BlockPos refinePosition(CachedSamplers samplers, Predicate<Holder<Biome>> predicate,
                                           BlockPos rough, int defaultY
    ) {
        int bestX = rough.getX();
        int bestZ = rough.getZ();
        int bestY = defaultY;

        for (int y = 64; y <= 200; y += 32) {
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

    private static boolean checkBiomeFast(CachedSamplers samplers, Predicate<Holder<Biome>> predicate,
                                          int x, int y, int z
    ) {
        try {
            Holder<Biome> biome = samplers.biomeSource.getNoiseBiome(x >> 2, y >> 2, z >> 2, samplers.sampler);
            return predicate.test(biome);
        } catch (Exception e) {
            return false;
        }
    }
}