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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.resource.sources;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.resource.IMultiSourceProvider;
import org.complexityanalyzer.resource.IResourceSource;
import org.complexityanalyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.resource.data.VanillaPassiveDrops;
import org.jetbrains.annotations.Nullable;

public class PassiveProductionSource implements IResourceSource, IMultiSourceProvider {

    private final Reference2ObjectMap<Item, ObjectList<VanillaPassiveDrops>> dropMap = new Reference2ObjectOpenHashMap<>();

    @Override
    public void initialize(Level level) {
        dropMap.clear();
        for (var entry : VanillaPassiveDrops.values()) {
            dropMap.computeIfAbsent(entry.getDrop(), k -> new ObjectArrayList<>(1)).add(entry);
        }
    }

    @Override
    public boolean canProvide(Item item) {
        return dropMap.containsKey(item);
    }

    @Override
    @Nullable
    public BaseResourceData analyze(Item item) {
        var list = dropMap.get(item);
        if (list == null || list.isEmpty()) return null;
        var best = list.getFirst();
        return buildResourceData(best);
    }

    @Override
    public ObjectList<BaseResourceData> findAllSources(Item item) {
        var list = dropMap.get(item);
        if (list == null || list.isEmpty()) return ObjectLists.emptyList();
        var result = new ObjectArrayList<BaseResourceData>(list.size());
        for (var entry : list) result.add(buildResourceData(entry));
        return result;
    }

    private BaseResourceData buildResourceData(VanillaPassiveDrops entry) {
        double timeCost = entry.getAvgTicks() * ComplexityConfig.TIME_COST_MULTIPLIER.get();
        double complexity = timeCost + ComplexityConfig.BASE_ACTION_COST.get();

        String sourceName = entry.getEntity().getDescription().getString();
        String details = "From %s (Avg. ~%d ticks, %s)".formatted(
                sourceName,
                (long) entry.getAvgTicks(),
                entry.getDescription()
        );

        var builder = new BaseResourceData.Builder(entry.getDrop(), this)
                .sourceType(BaseResourceData.ResourceSourceType.FARMING)
                .baseFactor(complexity)
                .sourceSpecifier(sourceName)
                .details(details);

        if (!entry.getRequiredItems().isEmpty()) builder.sourceItems(entry.getRequiredItems());

        return builder.build();
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
}