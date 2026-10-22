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

package org.complexityanalyzer.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.loot.predicates.CompositeLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.LootModifier;
import net.neoforged.neoforge.common.loot.LootTableIdCondition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

public interface Accessors {

    @Mixin(ConcretePowderBlock.class)
    interface ConcretePowderBlockAccessor {
        @Accessor("concrete")
        Block getConcrete();
    }

    @Mixin(CoralBlock.class)
    interface CoralBlockAccessor {
        @Accessor("deadBlock")
        Block getDeadBlock();
    }

    @Mixin(CoralFanBlock.class)
    interface CoralFanBlockAccessor {
        @Accessor("deadBlock")
        Block getDeadBlock();
    }

    @Mixin(CoralPlantBlock.class)
    interface CoralPlantBlockAccessor {
        @Accessor("deadBlock")
        Block getDeadBlock();
    }

    @Mixin(CoralWallFanBlock.class)
    interface CoralWallFanBlockAccessor {
        @Accessor("deadBlock")
        Block getDeadBlock();
    }

    @Mixin(MobBucketItem.class)
    interface MobBucketItemAccessor {
        @Accessor("type")
        EntityType<?> getType();
    }

    @Mixin(LootTableIdCondition.class)
    interface LootTableIdConditionAccessor {
        @Accessor("targetLootTableId")
        ResourceLocation getTargetLootTableId();
    }

    @Mixin(CompositeLootItemCondition.class)
    interface CompositeLootItemConditionAccessor {
        @Accessor("terms")
        List<LootItemCondition> getTerms();
    }

    @Mixin(LootModifier.class)
    interface LootModifierAccessor {
        @Accessor("conditions")
        LootItemCondition[] getConditions();
    }
}