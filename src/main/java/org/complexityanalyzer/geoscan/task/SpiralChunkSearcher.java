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

            if (dz == 0) {
                segmentLength++;
            }
        }

        currentX += dx;
        currentZ += dz;
        stepInSegment++;

        return nextPos;
    }
}