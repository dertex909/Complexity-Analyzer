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
import net.minecraft.world.level.Level;
import org.complexityanalyzer.harvest.engine.DirectRecipeLookup;
import org.complexityanalyzer.harvest.engine.DynamicRecipeHarvester;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.List;

import static org.complexityanalyzer.harvest.inspector.StandardRecipeMethods.GET_RECIPES_FOR;

@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin implements DirectRecipeLookup {
    @Unique
    private static volatile MethodHandle complexity$directGetRecipesFor;

    @Unique
    private static MethodHandle complexity$getDirectHandle() throws Throwable {
        var mh = complexity$directGetRecipesFor;
        if (mh != null) return mh;
        var mt = MethodType.methodType(List.class, RecipeType.class, RecipeInput.class, Level.class);
        mh = MethodHandles.lookup().findSpecial(RecipeManager.class, GET_RECIPES_FOR, mt, RecipeManager.class);
        complexity$directGetRecipesFor = mh;
        return mh;
    }

    @WrapOperation(
            method = "getRecipesFor",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/crafting/RecipeManager;byType(Lnet/minecraft/world/item/crafting/RecipeType;)Ljava/util/Collection;")
    )
    private <I extends RecipeInput, T extends Recipe<I>> Collection<RecipeHolder<T>> complexity$filterKnownDuringProbe(RecipeManager instance, RecipeType<T> recipeType, Operation<Collection<RecipeHolder<T>>> original) {
        var raw = original.call(instance, recipeType);
        var probe = DynamicRecipeHarvester.getActiveProbe();
        if (probe == null || raw.isEmpty()) return raw;
        return probe.filterKnown(recipeType, raw);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<RecipeHolder<?>> complexity$getRecipesForDirect(RecipeType<?> type, RecipeInput input, Level level) {
        try {
            return (List<RecipeHolder<?>>) complexity$getDirectHandle().invokeExact(this, type, input, level);
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) throw re;
            if (t instanceof Error e) throw e;
            throw new RuntimeException(t);
        }
    }
}