package org.complexityanalyzer.analyzer.resource.providers;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.block.Block;
import org.complexityanalyzer.ComplexityAnalyzer;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class BlockPropertyProvider {

    private final Map<Block, BlockProperties> propertiesCache = new HashMap<>();
    private boolean initialized = false;

    public void initialize() {
        if (initialized) return;
        ComplexityAnalyzer.LOGGER.info("Initializing BlockPropertyProvider...");

        int analyzed = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            try {
                BlockProperties props = analyzeBlock(block);
                propertiesCache.put(block, props);
                analyzed++;
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.warn("Failed to analyze block {}: {}", block, e.getMessage());
            }
        }

        initialized = true;
        ComplexityAnalyzer.LOGGER.info("BlockPropertyProvider initialized, analyzed {} blocks.", analyzed);
    }

    private BlockProperties analyzeBlock(Block block) {
        float hardness = block.defaultDestroyTime();
        Tier requiredTier = determineRequiredTier(block, hardness);
        boolean canHarvestByHand = hardness >= 0 && requiredTier == Tiers.WOOD;

        float explosionResistance;
        try {
            explosionResistance = block.defaultBlockState().getExplosionResistance(null, null, null);
        } catch (NullPointerException e) {
            explosionResistance = 0.0f;
            ComplexityAnalyzer.LOGGER.warn("Could not determine explosion resistance for block '{}'. It may not handle null world/pos contexts correctly. Defaulting to 0.", BuiltInRegistries.BLOCK.getKey(block));
        }

        return new BlockProperties(hardness, requiredTier, canHarvestByHand, explosionResistance);
    }

    private Tier determineRequiredTier(Block block, float hardness) {
        String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
        if (hardness < 0) return Tiers.NETHERITE;
        if (blockId.contains("obsidian")) return Tiers.DIAMOND;
        if (blockId.contains("ancient_debris") || blockId.contains("netherite")) return Tiers.DIAMOND;
        if (blockId.contains("diamond_ore")) return Tiers.IRON;
        if ((blockId.contains("gold_ore") || blockId.contains("redstone_ore"))) return Tiers.IRON;
        if ((blockId.contains("iron_ore") || blockId.contains("copper_ore") || blockId.contains("lapis_ore"))) return Tiers.STONE;
        if (blockId.contains("stone") || blockId.contains("deepslate")) return Tiers.WOOD;
        if (hardness > 30.0f) return Tiers.DIAMOND;
        if (hardness > 5.0f) return Tiers.IRON;
        if (hardness > 2.0f) return Tiers.STONE;
        return Tiers.WOOD;
    }

    public Optional<BlockProperties> getProperties(Block block) {
        return Optional.ofNullable(propertiesCache.get(block));
    }

    public Optional<BlockProperties> getProperties(Item item) {
        if (item instanceof net.minecraft.world.item.BlockItem blockItem) {
            return getProperties(blockItem.getBlock());
        }
        return Optional.empty();
    }

    public boolean isInitialized() {
        return initialized;
    }

    public record BlockProperties(
            float hardness,
            Tier requiredTier,
            boolean canHarvestByHand,
            float explosionResistance
    ) {
        public double getHardnessMultiplier() {
            if (hardness < 0) return 10.0;
            if (hardness == 0) return 0.1;
            if (hardness < 1.0f) return 0.5;
            if (hardness < 3.0f) return 1.0;
            if (hardness < 10.0f) return 1.5;
            if (hardness < 30.0f) return 2.0;
            return 3.0;
        }

        public double getToolMultiplier() {
            if (requiredTier == Tiers.WOOD) return 1.0;
            if (requiredTier == Tiers.STONE) return 1.5;
            if (requiredTier == Tiers.IRON) return 2.5;
            if (requiredTier == Tiers.GOLD) return 2.0;
            if (requiredTier == Tiers.DIAMOND) return 4.0;
            if (requiredTier == Tiers.NETHERITE) return 5.0;
            return 1.0;
        }

        public String getTierName() {
            if (requiredTier == Tiers.WOOD) return "WOOD";
            if (requiredTier == Tiers.STONE) return "STONE";
            if (requiredTier == Tiers.IRON) return "IRON";
            if (requiredTier == Tiers.GOLD) return "GOLD";
            if (requiredTier == Tiers.DIAMOND) return "DIAMOND";
            if (requiredTier == Tiers.NETHERITE) return "NETHERITE";
            return "HAND";
        }
    }
}