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
import org.complexityanalyzer.ComplexityAnalyzer;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public class FastBiomeFinder {

    public static BlockPos findBiome(
            ServerLevel level,
            Predicate<Holder<Biome>> biomePredicate,
            BlockPos origin,
            int maxRadius
    ) {
        long startTime = System.currentTimeMillis();

        BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();

        int searchY = level.getSeaLevel();
        int initialStep = maxRadius > 10000 ? 128 : 64;

        ComplexityAnalyzer.LOGGER.debug("[FastBiomeFinder] Starting search from [{}, {}, {}], radius={}, step={}",
                origin.getX(), searchY, origin.getZ(), maxRadius, initialStep);

        BlockPos roughMatch = spiralSearch(
                biomeSource,
                sampler,
                biomePredicate,
                origin.getX(),
                origin.getZ(),
                searchY,
                maxRadius,
                initialStep,
                "ROUGH"
        );

        if (roughMatch == null) {
            long elapsed = System.currentTimeMillis() - startTime;
            ComplexityAnalyzer.LOGGER.debug("[FastBiomeFinder] NOT FOUND (rough search failed) in {}ms", elapsed);
            return null;
        }

        ComplexityAnalyzer.LOGGER.debug("[FastBiomeFinder] Rough match found at [{}, {}, {}]",
                roughMatch.getX(), roughMatch.getY(), roughMatch.getZ());

        BlockPos withCorrectY = findOptimalY(biomeSource, sampler, biomePredicate, roughMatch);

        ComplexityAnalyzer.LOGGER.debug("[FastBiomeFinder] Optimal Y found at [{}, {}, {}]",
                withCorrectY.getX(), withCorrectY.getY(), withCorrectY.getZ());

        BlockPos preciseMatch = spiralSearch(
                biomeSource,
                sampler,
                biomePredicate,
                withCorrectY.getX(),
                withCorrectY.getZ(),
                withCorrectY.getY(),
                initialStep * 2,
                16,
                "PRECISE"
        );

        BlockPos result = preciseMatch != null ? preciseMatch : withCorrectY;
        long elapsed = System.currentTimeMillis() - startTime;

        ComplexityAnalyzer.LOGGER.debug("[FastBiomeFinder] FOUND at [{}, {}, {}] in {}ms",
                result.getX(), result.getY(), result.getZ(), elapsed);

        return result;
    }

    private static BlockPos findOptimalY(
            BiomeSource biomeSource,
            Climate.Sampler sampler,
            Predicate<Holder<Biome>> predicate,
            BlockPos pos
    ) {
        for (int y = 64; y <= 256; y += 16) {
            if (checkBiome(biomeSource, sampler, predicate, pos.getX(), y, pos.getZ())) {
                return new BlockPos(pos.getX(), y, pos.getZ());
            }
        }

        for (int y = 48; y >= -64; y -= 16) {
            if (checkBiome(biomeSource, sampler, predicate, pos.getX(), y, pos.getZ())) {
                return new BlockPos(pos.getX(), y, pos.getZ());
            }
        }

        return pos;
    }

    private static BlockPos spiralSearch(
            BiomeSource biomeSource,
            Climate.Sampler sampler,
            Predicate<Holder<Biome>> predicate,
            int centerX,
            int centerZ,
            int y,
            int maxRadius,
            int step,
            String phaseName
    ) {
        int checksCount = 0;

        if (checkBiome(biomeSource, sampler, predicate, centerX, y, centerZ)) {
            ComplexityAnalyzer.LOGGER.debug("[FastBiomeFinder] {} - Found at CENTER [{}, {}, {}] (1 check)",
                    phaseName, centerX, y, centerZ);
            return new BlockPos(centerX, y, centerZ);
        }
        checksCount++;

        int x = 0;
        int z = 0;
        int dx = step;
        int dz = 0;
        int segmentLength = 1;
        int segmentPassed = 0;

        while (Math.abs(x) <= maxRadius || Math.abs(z) <= maxRadius) {
            x += dx;
            z += dz;
            segmentPassed++;
            checksCount++;

            int worldX = centerX + x;
            int worldZ = centerZ + z;

            if (checkBiome(biomeSource, sampler, predicate, worldX, y, worldZ)) {
                ComplexityAnalyzer.LOGGER.debug("[FastBiomeFinder] {} - Found at [{}, {}, {}] after {} checks",
                        phaseName, worldX, y, worldZ, checksCount);
                return new BlockPos(worldX, y, worldZ);
            }

            if (segmentPassed >= segmentLength) {
                segmentPassed = 0;
                int temp = dx;
                dx = -dz;
                dz = temp;
                if (dz == 0) segmentLength++;
            }

            if (Math.abs(x) > maxRadius && Math.abs(z) > maxRadius) break;
        }

        ComplexityAnalyzer.LOGGER.debug("[FastBiomeFinder] {} - NOT FOUND after {} checks (max radius reached)",
                phaseName, checksCount);
        return null;
    }

    private static boolean checkBiome(
            BiomeSource biomeSource,
            Climate.Sampler sampler,
            Predicate<Holder<Biome>> predicate,
            int x,
            int y,
            int z
    ) {
        try {
            int biomeX = x >> 2;
            int biomeY = y >> 2;
            int biomeZ = z >> 2;

            Holder<Biome> biome = biomeSource.getNoiseBiome(biomeX, biomeY, biomeZ, sampler);
            return predicate.test(biome);
        } catch (Exception e) {
            return false;
        }
    }

    public static BlockPos randomSearch(
            ServerLevel level,
            Predicate<Holder<Biome>> biomePredicate,
            BlockPos origin,
            int maxRadius,
            int attempts
    ) {
        ComplexityAnalyzer.LOGGER.debug("[FastBiomeFinder] RANDOM - Starting {} random attempts in radius {}",
                attempts, maxRadius);

        BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        int searchY = level.getSeaLevel();

        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int i = 0; i < attempts; i++) {
            int x = origin.getX() + random.nextInt(-maxRadius, maxRadius);
            int z = origin.getZ() + random.nextInt(-maxRadius, maxRadius);

            if (checkBiome(biomeSource, sampler, biomePredicate, x, searchY, z)) {
                ComplexityAnalyzer.LOGGER.debug("[FastBiomeFinder] RANDOM - Found at [{}, {}, {}] after {} attempts",
                        x, searchY, z, i + 1);
                return new BlockPos(x, searchY, z);
            }
        }

        ComplexityAnalyzer.LOGGER.debug("[FastBiomeFinder] RANDOM - NOT FOUND after {} attempts", attempts);
        return null;
    }
}