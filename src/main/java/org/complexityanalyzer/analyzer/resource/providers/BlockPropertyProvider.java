package org.complexityanalyzer.analyzer.resource.providers;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
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
                ComplexityAnalyzer.LOGGER.warn("Failed to analyze block {}: {}", BuiltInRegistries.BLOCK.getKey(block), e.getMessage());
            }
        }

        initialized = true;
        ComplexityAnalyzer.LOGGER.info("BlockPropertyProvider initialized, analyzed {} blocks.", analyzed);
    }

    private BlockProperties analyzeBlock(Block block) {
        float hardness = block.defaultDestroyTime();
        Tier requiredTier = determineRequiredTier(block);
        boolean canHarvestByHand = hardness >= 0 && requiredTier == Tiers.WOOD && !block.defaultBlockState().requiresCorrectToolForDrops();

        float explosionResistance;
        try {
            explosionResistance = block.getExplosionResistance();
        } catch (Exception e) {
            explosionResistance = 0.0f;
        }

        return new BlockProperties(hardness, requiredTier, canHarvestByHand, explosionResistance);
    }

    private Tier determineRequiredTier(Block block) {
        BlockState state = block.defaultBlockState();
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) {
            return Tiers.DIAMOND;
        }
        if (state.is(BlockTags.NEEDS_IRON_TOOL)) {
            return Tiers.IRON;
        }
        if (state.is(BlockTags.NEEDS_STONE_TOOL)) {
            return Tiers.STONE;
        }
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
            return 1.0 + Math.log1p(hardness);
        }

        public double getToolMultiplier() {
            if (requiredTier == Tiers.WOOD) return 1.0;
            if (requiredTier == Tiers.STONE) return 1.5;
            if (requiredTier == Tiers.IRON) return 2.5;
            if (requiredTier == Tiers.DIAMOND) return 4.0;
            if (requiredTier == Tiers.NETHERITE) return 5.0;
            return 1.0;
        }

        public String getTierName() {
            if (canHarvestByHand) return "HAND";
            if (requiredTier == Tiers.WOOD) return "WOOD";
            if (requiredTier == Tiers.STONE) return "STONE";
            if (requiredTier == Tiers.IRON) return "IRON";
            if (requiredTier == Tiers.DIAMOND) return "DIAMOND";
            if (requiredTier == Tiers.NETHERITE) return "NETHERITE";
            return "UNKNOWN";
        }
    }
}