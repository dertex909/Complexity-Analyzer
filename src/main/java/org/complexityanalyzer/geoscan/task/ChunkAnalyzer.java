package org.complexityanalyzer.geoscan.task;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.geoscan.data.ChunkSnapshot;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Анализирует содержимое чанков.
 * Высокопроизводительная реализация с использованием Fastutil.
 */
public class ChunkAnalyzer {

    /**
     * Создаёт снапшот блоков чанка
     */
    public @Nullable ChunkSnapshot createSnapshot(ChunkAccess chunk) {
        Reference2IntOpenHashMap<Block> blockCounts = new Reference2IntOpenHashMap<>();
        LevelChunkSection[] sections = chunk.getSections();

        for (LevelChunkSection section : sections) {
            if (section == null || section.hasOnlyAir()) continue;

            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        Block block = section.getBlockState(x, y, z).getBlock();
                        if (block != Blocks.AIR && block != Blocks.CAVE_AIR && block != Blocks.VOID_AIR) {
                            blockCounts.addTo(block, 1);
                        }
                    }
                }
            }
        }

        if (blockCounts.isEmpty()) return null;

        // Конвертируем в Map<String, Integer>
        Map<String, Integer> finalCounts = new HashMap<>(blockCounts.size());
        for (Reference2IntOpenHashMap.Entry<Block> entry : blockCounts.reference2IntEntrySet()) {
            finalCounts.put(
                    BuiltInRegistries.BLOCK.getKey(entry.getKey()).toString(),
                    entry.getIntValue()
            );
        }

        return new ChunkSnapshot(chunk.getPos().x, chunk.getPos().z, finalCounts);
    }

    /**
     * Определяет доминантный биом чанка
     */
    public @Nullable ResourceLocation getDominantBiome(ChunkAccess chunk) {
        Object2IntOpenHashMap<ResourceLocation> biomeCounts = new Object2IntOpenHashMap<>();
        int minSectionY = chunk.getMinSection();
        LevelChunkSection[] sections = chunk.getSections();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) continue;

            int sectionY = (minSectionY + sectionIndex) * 16;
            int biomeY = (sectionY + 8) >> 2;

            try {
                var biomeHolder = chunk.getNoiseBiome(2, biomeY, 2);
                biomeHolder.unwrapKey().ifPresent(key ->
                        biomeCounts.addTo(key.location(), 1));
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.debug("[Analyzer] Error reading biome at section {}", sectionIndex);
            }
        }

        ResourceLocation dominant = null;
        int max = -1;
        for (Object2IntOpenHashMap.Entry<ResourceLocation> entry : biomeCounts.object2IntEntrySet()) {
            if (entry.getIntValue() > max) {
                max = entry.getIntValue();
                dominant = entry.getKey();
            }
        }
        return dominant;
    }
}