/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
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

package org.complexityanalyzer.compat.jei;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.runtime.IIngredientManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.compat.jei.mocks.JeiMocks;
import org.jetbrains.annotations.NotNull;

import java.util.List;


public class MockRecipeCatalystRegistration implements IRecipeCatalystRegistration {
    private final Object2ObjectMap<ResourceLocation, ObjectList<ItemStack>> catalysts = new Object2ObjectOpenHashMap<>();
    private static final IJeiHelpers EMPTY_JEI_HELPERS = new JeiMocks.EmptyJeiHelpers();

    public Object2ObjectMap<ResourceLocation, ObjectList<ItemStack>> getCatalysts() {
        return catalysts;
    }

    @Override
    public <T> void addRecipeCatalyst(@NotNull IIngredientType<T> ingredientType, @NotNull T ingredient, RecipeType<?> @NotNull ... recipeTypes) {
        if (ingredient instanceof ItemStack stack && !stack.isEmpty()) {
            for (RecipeType<?> recipeType : recipeTypes) {
                ResourceLocation uid = recipeType.getUid();
                catalysts.computeIfAbsent(uid, k -> new ObjectArrayList<>()).add(stack.copy());
                ComplexityAnalyzer.LOGGER.debug("JEI Catalyst Intercept: {} -> {}",
                        uid, BuiltInRegistries.ITEM.getKey(stack.getItem()));
            }
        }
    }

    @Override
    public void addRecipeCatalysts(@NotNull RecipeType<?> recipeType, ItemLike @NotNull ... ingredients) {
    }

    @Override
    public <T> void addRecipeCatalysts(@NotNull RecipeType<?> recipeType, @NotNull IIngredientType<T> ingredientType, @NotNull List<T> ingredients) {
    }

    @Override
    @NotNull
    public IIngredientManager getIngredientManager() {
        return EMPTY_JEI_HELPERS.getIngredientManager();
    }

    @Override
    @NotNull
    public IJeiHelpers getJeiHelpers() {
        return EMPTY_JEI_HELPERS;
    }
}