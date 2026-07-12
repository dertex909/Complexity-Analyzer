/*
 * Complexity Analyzer
 * Copyright (C) 2025-2026 dertex909
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.analyzer.resource.sources;

import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.config.ComplexityConfig;
import org.jetbrains.annotations.Nullable;

public class PassiveProductionSource implements IResourceSource {

    private final Reference2ObjectMap<Item, ProductionInfo> productionMap = new Reference2ObjectOpenHashMap<>();

    @Override
    public void initialize(Level level) {
        productionMap.put(Items.EGG, new ProductionInfo(EntityType.CHICKEN, 9000.0, "Passive (lays egg)"));
        productionMap.put(Items.ARMADILLO_SCUTE, new ProductionInfo(EntityType.ARMADILLO, 9000.0, "Brushing / Passive"));
        productionMap.put(Items.TURTLE_SCUTE, new ProductionInfo(EntityType.TURTLE, 24000.0, "Grows Up"));
        productionMap.put(Items.TADPOLE_BUCKET, new ProductionInfo(EntityType.FROG, 1200.0, "Hatches from Frogspawn"));
        productionMap.put(Items.WHITE_WOOL, new ProductionInfo(EntityType.SHEEP, 2400.0, "Shearing"));
        productionMap.put(Items.MILK_BUCKET, new ProductionInfo(EntityType.COW, 20.0, "Milking"));
        productionMap.put(Items.MUSHROOM_STEW, new ProductionInfo(EntityType.MOOSHROOM, 20.0, "Milking with Bowl"));
        productionMap.put(Items.GOAT_HORN, new ProductionInfo(EntityType.GOAT, 6300.0, "Rams a block"));
        productionMap.put(Items.OCHRE_FROGLIGHT, new ProductionInfo(EntityType.FROG, 200.0, "Eats Magma Cube"));
        productionMap.put(Items.VERDANT_FROGLIGHT, new ProductionInfo(EntityType.FROG, 200.0, "Eats Magma Cube"));
        productionMap.put(Items.PEARLESCENT_FROGLIGHT, new ProductionInfo(EntityType.FROG, 200.0, "Eats Magma Cube"));
        productionMap.put(Items.CHORUS_FRUIT, new ProductionInfo(EntityType.SHULKER, 6000.0, "Grows on Chorus Plant"));
        productionMap.put(Items.PITCHER_POD, new ProductionInfo(EntityType.SNIFFER, 4800.0, "Dug up by Sniffer"));
        productionMap.put(Items.TORCHFLOWER_SEEDS, new ProductionInfo(EntityType.SNIFFER, 4800.0, "Dug up by Sniffer"));
        productionMap.put(Items.BEETROOT, new ProductionInfo(null, 4800.0, "Farming"));
        productionMap.put(Items.FROGSPAWN, new ProductionInfo(EntityType.FROG, 12000.0, "Breeding"));
        productionMap.put(Items.TURTLE_EGG, new ProductionInfo(EntityType.TURTLE, 12000.0, "Breeding"));
        productionMap.put(Items.PITCHER_PLANT, new ProductionInfo(null, 24000.0, "Grows from Pod"));
    }

    @Override
    public boolean canProvide(Item item) {
        return productionMap.containsKey(item);
    }

    @Override
    @Nullable
    public BaseResourceData analyze(Item item) {
        if (!canProvide(item)) return null;
        var info = productionMap.get(item);
        var complexity = (info.ticksPerItem() * ComplexityConfig.TIME_COST_MULTIPLIER.get()) + ComplexityConfig.BASE_ACTION_COST.get();
        var sourceName = (info.sourceType() != null) ? info.sourceType().getDescription().getString() : "the environment";
        var details = String.format("From %s (Avg. ~%d ticks, Method: %s)", sourceName, (int) info.ticksPerItem(), info.method());

        return new BaseResourceData.Builder(item, this)
                .sourceType(BaseResourceData.ResourceSourceType.FARMING)
                .baseFactor(complexity)
                .sourceSpecifier(sourceName)
                .details(details)
                .build();
    }

    @Override
    public BaseResourceData.ResourceSourceType getSourceType() {
        return BaseResourceData.ResourceSourceType.FARMING;
    }

    @Override
    public int getPriority() {
        return 30;
    }

    @Override
    public String getName() {
        return "PassiveProductionSource";
    }

    private record ProductionInfo(EntityType<?> sourceType, double ticksPerItem, String method) {
    }
}