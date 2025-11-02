package org.complexityanalyzer.analyzer.resource;

import net.minecraft.world.item.Item;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;

import java.util.List;

public interface IMultiSourceProvider {
    List<BaseResourceData> findAllSources(Item item);
}