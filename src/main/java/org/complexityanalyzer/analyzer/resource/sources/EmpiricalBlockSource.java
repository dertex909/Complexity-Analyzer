package org.complexityanalyzer.analyzer.resource.sources;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.providers.BlockPropertyProvider;
import org.complexityanalyzer.geoscan.GeoDatabase;

import java.util.Optional;

public class EmpiricalBlockSource implements IResourceSource {

    private final BlockPropertyProvider propertyProvider;
    private final GeoDatabase geoDatabase;

    public EmpiricalBlockSource(BlockPropertyProvider propertyProvider, GeoDatabase geoDatabase) {
        this.propertyProvider = propertyProvider;
        this.geoDatabase = geoDatabase;
    }

    @Override
    public void initialize(Level level) {}

    @Override
    public boolean canProvide(Item item) {
        return item instanceof BlockItem && geoDatabase.isLoaded();
    }

    @Override
    public Optional<BaseResourceData> analyze(Item item) {
        if (!canProvide(item)) {
            return Optional.empty();
        }

        Block block = ((BlockItem) item).getBlock();
        double rarity = geoDatabase.getGlobalRarity(block);

        if (rarity <= 0.0) {
            return Optional.of(new BaseResourceData.Builder(item, this)
                    .sourceType(BaseResourceData.ResourceSourceType.UNOBTAINABLE)
                    .baseFactor(BaseResourceData.ResourceSourceType.UNOBTAINABLE.getBaseMultiplier())
                    .details("Not found in the world after geo-scan.")
                    .build());
        }

        var propertiesOpt = propertyProvider.getProperties(block);
        if (propertiesOpt.isEmpty()) {
            return Optional.empty();
        }
        var properties = propertiesOpt.get();

        double toolMultiplier = properties.getToolMultiplier();
        double hardnessMultiplier = properties.getHardnessMultiplier();
        double rarityFactor = 1.0 + Math.log1p(1.0 / rarity);
        double baseFactor = toolMultiplier * hardnessMultiplier * rarityFactor * BaseResourceData.ResourceSourceType.EMPIRICAL_BLOCK.getBaseMultiplier();

        String details = String.format("Empirical Rarity: %.6f%%, Tool: %s",
                rarity * 100.0, properties.getTierName());

        return Optional.of(new BaseResourceData.Builder(item, this)
                .sourceType(BaseResourceData.ResourceSourceType.EMPIRICAL_BLOCK)
                .baseFactor(baseFactor)
                .details(details)
                .build());
    }

    public boolean isReady() { return this.geoDatabase != null && this.geoDatabase.isLoaded(); }
    @Override
    public int getPriority() { return 100000; }
    @Override
    public String getName() { return "EmpiricalBlockSource"; }
    @Override
    public BaseResourceData.ResourceSourceType getSourceType() { return BaseResourceData.ResourceSourceType.EMPIRICAL_BLOCK; }
}