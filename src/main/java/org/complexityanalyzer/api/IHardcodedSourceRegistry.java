package org.complexityanalyzer.api;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;

import java.util.Map;

public interface IHardcodedSourceRegistry {

    void registerTransformation(Item result, Item input, Map<Item, Double> toolWear,
                                double baseCost, String description);

    void registerComplexSource(Item result, Map<Item, Double> ingredients,
                               double baseCost, BaseResourceData.ResourceSourceType type,
                               String description);

    void registerOverride(Item result, Map<Item, Double> ingredients,
                          double baseCost, String description);

    void registerUnobtainable(Item item, String reason);

    boolean isRegistered(Item item);
}