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

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.level.block.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

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
}