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

    public static BlockPos findBiome(ServerLevel level, Predicate<Holder<Biome>> biomePredicate, BlockPos origin, int maxRadius) {
        var samplers = new Samplers(level.getChunkSource().getGenerator().getBiomeSource(), level.getChunkSource().randomState().sampler());

        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        int totalHeight = maxY - minY;
        int coarseYStep = Math.max(24, totalHeight / 32);

        var match = checkColumn(samplers, biomePredicate, origin.getX(), origin.getZ(), minY, maxY, coarseYStep);
        if (match != null) return refineToCenter(samplers, biomePredicate, match, minY, maxY);

        if ((origin.getX() != 0 || origin.getZ() != 0) && Math.hypot(origin.getX(), origin.getZ()) <= maxRadius) {
            match = checkColumn(samplers, biomePredicate, 0, 0, minY, maxY, coarseYStep);
            if (match != null) return refineToCenter(samplers, biomePredicate, match, minY, maxY);
        }

        int coarseStep = Math.clamp(maxRadius / 40, 64, 192);
        var coarseMatch = gridSearch(samplers, biomePredicate, origin.getX(), origin.getZ(), minY, maxY, coarseYStep, maxRadius, coarseStep);

        if (coarseMatch == null) {
            coarseMatch = fastRandomSearch(samplers, biomePredicate, origin, maxRadius, minY, maxY, coarseYStep);
            if (coarseMatch == null) return null;
        }

        return refineToCenter(samplers, biomePredicate, coarseMatch, minY, maxY);
    }

    private static BlockPos gridSearch(Samplers samplers, Predicate<Holder<Biome>> predicate, int centerX, int centerZ,
                                       int minY, int maxY, int yStep, int maxRadius, int step) {
        for (int distance = step; distance <= maxRadius; distance += step) {
            for (int offset = -distance; offset <= distance; offset += step) {
                var match = checkColumn(samplers, predicate, centerX + offset, centerZ - distance, minY, maxY, yStep);
                if (match != null) return match;
                match = checkColumn(samplers, predicate, centerX + offset, centerZ + distance, minY, maxY, yStep);
                if (match != null) return match;
            }

            for (int offset = -distance + step; offset < distance; offset += step) {
                var match = checkColumn(samplers, predicate, centerX - distance, centerZ + offset, minY, maxY, yStep);
                if (match != null) return match;
                match = checkColumn(samplers, predicate, centerX + distance, centerZ + offset, minY, maxY, yStep);
                if (match != null) return match;
            }
        }

        return null;
    }

    private static BlockPos fastRandomSearch(Samplers samplers, Predicate<Holder<Biome>> predicate,
                                             BlockPos origin, int maxRadius, int minY, int maxY, int yStep) {
        var random = ThreadLocalRandom.current();

        for (int i = 0; i < 2500; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distanceFactor = (i < 1000) ? random.nextDouble() : Math.sqrt(random.nextDouble());
            double distance = distanceFactor * maxRadius;
            int x = origin.getX() + (int) (Math.cos(angle) * distance);
            int z = origin.getZ() + (int) (Math.sin(angle) * distance);
            var match = checkColumn(samplers, predicate, x, z, minY, maxY, yStep);
            if (match != null) return match;
        }

        return null;
    }

    private static BlockPos refineToCenter(Samplers samplers, Predicate<Holder<Biome>> predicate, BlockPos rough,
                                           int minY, int maxY) {
        int x = rough.getX();
        int y = rough.getY();
        int z = rough.getZ();

        int minX = findBoundary(samplers, predicate, x, y, z, -1, 0);
        int maxX = findBoundary(samplers, predicate, x, y, z, 1, 0);
        int centerX = (minX + maxX) / 2;

        int minZ = findBoundary(samplers, predicate, centerX, y, z, 0, -1);
        int maxZ = findBoundary(samplers, predicate, centerX, y, z, 0, 1);
        int centerZ = (minZ + maxZ) / 2;

        int bottomY = findVerticalBoundary(samplers, predicate, centerX, centerZ, y, -1, minY);
        int topY = findVerticalBoundary(samplers, predicate, centerX, centerZ, y, 1, maxY);
        int centerY = (bottomY + topY) / 2;

        return (checkBiomeFast(samplers, predicate, centerX, centerY, centerZ)) ? new BlockPos(centerX, centerY, centerZ) : rough;
    }

    private static int findBoundary(Samplers samplers, Predicate<Holder<Biome>> predicate, int startX, int y,
                                    int startZ, int dx, int dz) {
        int currX = startX;
        int currZ = startZ;
        int step = 16;
        int limit = 1500;

        for (int i = 0; i < limit; i += step) {
            currX += dx * step;
            currZ += dz * step;
            if (!checkBiomeFast(samplers, predicate, currX, y, currZ)) {
                int low = 0;
                int high = step;
                while (high - low > 4) {
                    int mid = (low + high) / 2;
                    int testX = currX - dx * (step - mid);
                    int testZ = currZ - dz * (step - mid);
                    if (checkBiomeFast(samplers, predicate, testX, y, testZ)) {
                        low = mid;
                    } else {
                        high = mid;
                    }
                }
                return (dx != 0) ? (currX - dx * (step - low)) : (currZ - dz * (step - low));
            }
        }
        return (dx != 0) ? currX : currZ;
    }

    private static int findVerticalBoundary(Samplers samplers, Predicate<Holder<Biome>> predicate, int x, int z,
                                            int startY, int dir, int clampY) {
        int y = startY;
        while ((dir > 0 ? y < clampY : y > clampY)) {
            int nextY = y + dir * 4;
            if (!checkBiomeFast(samplers, predicate, x, nextY, z)) return y;
            y = nextY;
        }
        return y;
    }

    private static BlockPos checkColumn(Samplers samplers, Predicate<Holder<Biome>> predicate,
                                        int x, int z, int minY, int maxY, int yStep) {
        for (int y = minY; y < maxY; y += yStep) {
            if (checkBiomeFast(samplers, predicate, x, y, z)) return new BlockPos(x, y, z);
        }
        return null;
    }

    private static boolean checkBiomeFast(Samplers samplers, Predicate<Holder<Biome>> predicate, int x, int y, int z) {
        try {
            return predicate.test(samplers.biomeSource.getNoiseBiome(x >> 2, y >> 2, z >> 2, samplers.sampler));
        } catch (Exception e) {
            return false;
        }
    }

    private record Samplers(BiomeSource biomeSource, Climate.Sampler sampler) {
    }
}