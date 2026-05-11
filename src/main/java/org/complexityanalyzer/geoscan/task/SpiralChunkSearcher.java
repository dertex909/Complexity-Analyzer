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

package org.complexityanalyzer.geoscan.task;

import net.minecraft.world.level.ChunkPos;

public class SpiralChunkSearcher {

    private int currentX;
    private int currentZ;
    private int dx;
    private int dz;
    private int segmentLength;
    private int stepInSegment;

    public SpiralChunkSearcher() {
    }

    public void startAt(int startChunkX, int startChunkZ) {
        this.currentX = startChunkX;
        this.currentZ = startChunkZ;
        this.dx = 1;
        this.dz = 0;
        this.segmentLength = 1;
        this.stepInSegment = 0;
    }

    public ChunkPos next() {
        ChunkPos nextPos = new ChunkPos(currentX, currentZ);
        if (stepInSegment >= segmentLength) {
            stepInSegment = 0;
            int oldDx = dx;
            dx = -dz;
            dz = oldDx;
            if (dz == 0) segmentLength++;
        }

        currentX += dx;
        currentZ += dz;
        stepInSegment++;
        return nextPos;
    }
}