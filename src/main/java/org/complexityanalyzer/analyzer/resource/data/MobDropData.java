package org.complexityanalyzer.analyzer.resource.data;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;

public record MobDropData(Item item, EntityType<?> sourceMob, double averageYield) {}