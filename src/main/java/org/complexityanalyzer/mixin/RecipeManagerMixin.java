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

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.item.crafting.*;
import org.complexityanalyzer.harvest.engine.DynamicRecipeHarvester;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;

@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {
    @WrapOperation(method = "getRecipesFor", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/crafting/RecipeManager;byType(Lnet/minecraft/world/item/crafting/RecipeType;)Ljava/util/Collection;"))
    private <I extends RecipeInput, T extends Recipe<I>> Collection<RecipeHolder<T>> complexity$filterKnownDuringProbe(RecipeManager instance, RecipeType<T> recipeType, Operation<Collection<RecipeHolder<T>>> original) {
        var raw = original.call(instance, recipeType);
        var probe = DynamicRecipeHarvester.getActiveProbe();
        if (probe == null || raw.isEmpty()) return raw;
        return probe.filterKnown(recipeType, raw);
    }
}