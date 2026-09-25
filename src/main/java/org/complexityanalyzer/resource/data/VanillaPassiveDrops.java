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

package org.complexityanalyzer.resource.data;

import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

public enum VanillaPassiveDrops {

    SHEEP_WOOL(EntityType.SHEEP, Items.WHITE_WOOL, 1040.0 / 2.0, "Shearing sheep after eating grass (avg 2 wool per shear, 1040 ticks cycle)", Items.SHEARS, 1.0 / (238.0 * 2.0)),

    COW_MILK(EntityType.COW, Items.MILK_BUCKET, 20.0, "Milking adult cow with Bucket (no cooldown)", Items.BUCKET, 1.0),

    MOOSHROOM_STEW(EntityType.MOOSHROOM, Items.MUSHROOM_STEW, 20.0, "Milking Mooshroom with Bowl (no cooldown)", Items.BOWL, 1.0),

    GOAT_HORN(EntityType.GOAT, Items.GOAT_HORN, 900.0, "Goat rams a solid block (breaks 1 of 2 horns, 600-1200 tick ram cooldown)"),

    SNIFFER_EGG(EntityType.SNIFFER, Items.SNIFFER_EGG, 6000.0, "Breeding Sniffers with Torchflower Seeds (drops egg item)", Items.TORCHFLOWER_SEEDS, 2.0),

    CHICKEN_EGG(EntityType.CHICKEN, Items.EGG, 6000 + (6000 / 2.0), "Lays egg periodically (every 5-10 min, avg 7.5 min)"),

    HONEYCOMB(EntityType.BEE, Items.HONEYCOMB, 5333.3 / 3.0, "Harvesting full Beehive with Shears (yields 3 honeycombs)", Items.SHEARS, 1.0 / (238.0 * 3.0)),
    HONEY_BOTTLE(EntityType.BEE, Items.HONEY_BOTTLE, 5333.3, "Harvesting full Beehive with Glass Bottle", Items.GLASS_BOTTLE, 1.0),

    ARMADILLO_SCUTE_PASSIVE(EntityType.ARMADILLO, Items.ARMADILLO_SCUTE, (20 * 60 * 5) + ((20 * 60 * 5) / 2.0), "Periodic scute shed (every 5-10 min, avg 7.5 min)"),
    ARMADILLO_SCUTE_BRUSH(EntityType.ARMADILLO, Items.ARMADILLO_SCUTE, 20.0, "Brushing with Brush (costs 16 brush durability)", Items.BRUSH, 16.0 / 64.0),

    TURTLE_SCUTE(EntityType.TURTLE, Items.TURTLE_SCUTE, 24000.0, "Baby turtle grows into an adult (20 min)"),
    TURTLE_EGG(EntityType.TURTLE, Items.TURTLE_EGG, 6000.0 / 2.5, "Breeding with Seagrass and laying on sand (avg 2.5 eggs per breed)", Items.SEAGRASS, 2.0 / 2.5),

    SNOW_GOLEM_PUMPKIN(EntityType.SNOW_GOLEM, Items.CARVED_PUMPKIN, 20.0, "Shearing pumpkin head from Snow Golem", Items.SHEARS, 1.0 / 238.0),
    SNOW_GOLEM_SNOWBALL(EntityType.SNOW_GOLEM, Items.SNOWBALL, 10.0, "Mining infinite snow trail under Snow Golem with Shovel", Items.IRON_SHOVEL, 1.0 / 250.0),

    OCHRE_FROGLIGHT(EntityType.FROG, Items.OCHRE_FROGLIGHT, 200.0, "Temperate frog eats small Magma Cube"),
    VERDANT_FROGLIGHT(EntityType.FROG, Items.VERDANT_FROGLIGHT, 200.0, "Cold frog eats small Magma Cube"),
    PEARLESCENT_FROGLIGHT(EntityType.FROG, Items.PEARLESCENT_FROGLIGHT, 200.0, "Warm frog eats small Magma Cube"),
    FROGSPAWN(EntityType.FROG, Items.FROGSPAWN, 6000.0, "Breeding with Slimeballs, laid on water surface", Items.SLIME_BALL, 2.0);

    private final EntityType<?> entity;
    private final Item drop;
    private final double avgTicks;
    private final Reference2DoubleMap<Item> requiredItems;
    private final String description;

    VanillaPassiveDrops(EntityType<?> entity, Item drop, double avgTicks, String description) {
        this(entity, drop, avgTicks, description, null, 0.0);
    }

    VanillaPassiveDrops(EntityType<?> entity, Item drop, double avgTicks, String description,
                        @Nullable Item requiredTool, double toolCost) {
        this.entity = entity;
        this.drop = drop;
        this.avgTicks = avgTicks;
        this.description = description;

        if (requiredTool != null && toolCost > 0.0) {
            var map = new Reference2DoubleOpenHashMap<Item>(1);
            map.put(requiredTool, toolCost);
            this.requiredItems = Reference2DoubleMaps.unmodifiable(map);
        } else {
            this.requiredItems = Reference2DoubleMaps.emptyMap();
        }
    }

    public EntityType<?> getEntity() {
        return entity;
    }

    public Item getDrop() {
        return drop;
    }

    public double getAvgTicks() {
        return avgTicks;
    }

    public Reference2DoubleMap<Item> getRequiredItems() {
        return requiredItems;
    }

    public String getDescription() {
        return description;
    }
}