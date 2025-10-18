package org.complexityanalyzer.analyzer.resource;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;

import java.util.Optional;

public interface IResourceSource {

    void initialize(Level level);

    boolean canProvide(Item item);

    Optional<BaseResourceData> analyze(Item item);

    BaseResourceData.ResourceSourceType getSourceType();

    default int getPriority() {
        return 0;
    }

    String getName();
}