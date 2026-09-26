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

package org.complexityanalyzer.resource.sources.hardcoded;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.api.IHardcodedSourceRegistry;
import org.complexityanalyzer.resource.data.BaseResourceData;

import static org.complexityanalyzer.resource.data.BaseResourceData.ResourceSourceType.*;

public final class ManualOverridesModule {

    private ManualOverridesModule() {
    }

    public static void register(Level ignored, IHardcodedSourceRegistry registry) {
        rule(registry, Items.ELYTRA, 1000.0, SPECIAL_LOOT, "End Ship item frame treasure");
        rule(registry, Items.OMINOUS_TRIAL_KEY, 200.0, SPECIAL_LOOT, "Ominous Vault drop", Items.TRIAL_KEY, 1.0);

        rule(registry, Items.BUNDLE, 1.0, CRAFTING, "Bundle crafting", Items.STRING, 2.0, Items.RABBIT_HIDE, 1.0);
        rule(registry, Items.WRITTEN_BOOK, 1.0, CRAFTING, "Signing a book", Items.WRITABLE_BOOK, 1.0);
        rule(registry, Items.FIREWORK_STAR, 1.0, CRAFTING, "Basic firework star", Items.GUNPOWDER, 1.0, Items.YELLOW_DYE, 1.0);

        rule(registry, Items.DRAGON_BREATH, 100.0, SPECIAL_ACTION, "Collecting dragon breath with bottle", Items.GLASS_BOTTLE, 1.0);
        rule(registry, Items.LINGERING_POTION, 1.0, CRAFTING, "Lingering potion brewing", Items.DRAGON_BREATH, 1.0, Items.SPLASH_POTION, 1.0);

        rule(registry, Items.CARVED_PUMPKIN, 1.0, BLOCK_TRANSFORMATION, "Carving pumpkin with shears", Items.PUMPKIN, 1.0, Items.SHEARS, 0.01);
        rule(registry, Items.PUMPKIN_SEEDS, 0.25, BLOCK_TRANSFORMATION, "Seeds from carving pumpkin", Items.PUMPKIN, 0.25);
    }

    private static void rule(IHardcodedSourceRegistry registry, Item result, double baseCost, BaseResourceData.ResourceSourceType type, String details) {
        var data = new BaseResourceData.Builder(result).sourceType(type).baseFactor(baseCost).details(details).build();
        registry.register(result, data);
    }

    private static void rule(IHardcodedSourceRegistry registry, Item result, double baseCost, BaseResourceData.ResourceSourceType type, String details, Item in1, double count) {
        var data = new BaseResourceData.Builder(result).sourceType(type).baseFactor(baseCost).details(details).addSourceItem(in1, count).build();
        registry.register(result, data);
    }

    private static void rule(IHardcodedSourceRegistry registry, Item result, double baseCost, BaseResourceData.ResourceSourceType type, String details, Item in1, double count, Item in2, double count1) {
        var data = new BaseResourceData.Builder(result).sourceType(type).baseFactor(baseCost).details(details).addSourceItem(in1, count).addSourceItem(in2, count1).build();
        registry.register(result, data);
    }
}