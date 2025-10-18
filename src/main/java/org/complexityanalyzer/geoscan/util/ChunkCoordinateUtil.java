package org.complexityanalyzer.geoscan.util;

public final class ChunkCoordinateUtil {

    private ChunkCoordinateUtil() {
    }

    public static long pack(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    public static int unpackZ(long packed) {
        return (int) packed;
    }
}