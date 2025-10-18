package org.complexityanalyzer.geoscan.data;

import java.util.Map;

public record ChunkSnapshot(
        int chunkX,
        int chunkZ,
        Map<String, Integer> blockCounts
) {}