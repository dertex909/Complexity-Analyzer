package org.complexityanalyzer.geoscan.data;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicLong;

public class BiomeDataMapper {
    public void prepareForSave(BiomeScanData data) {
        data.serializableBlockCounts.clear();
        data.getInternalBlockCounts().forEach((block, count) -> {
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
            if (!key.equals(BuiltInRegistries.BLOCK.getDefaultKey())) {
                data.serializableBlockCounts.put(key.toString(), count.get());
            }
        });

        if (data.getInternalScannedChunksSet() != null) {
            data.scannedChunks = new ArrayList<>(data.getInternalScannedChunksSet());
        } else {
            data.scannedChunks = new ArrayList<>();
        }
    }

    public void afterLoad(BiomeScanData data) {
        data.getInternalBlockCounts().clear();
        if (data.serializableBlockCounts != null) {
            data.serializableBlockCounts.forEach((key, count) -> {
                Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(key));
                if (block != Blocks.AIR) {
                    data.getInternalBlockCounts().put(block, new AtomicLong(count));
                }
            });
        }

        if (data.scannedChunks != null) {
            data.setInternalScannedChunksSet(new HashSet<>(data.scannedChunks));
        } else {
            data.setInternalScannedChunksSet(new HashSet<>());
        }
    }
}