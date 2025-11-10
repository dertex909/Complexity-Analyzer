/*
 * Complexity Analyzer
 * Copyright (C) 2025 dertex909
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

import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.vanilla.IVanillaRecipeFactory;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IIngredientManager;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.compat.jei.mocks.JeiMocks;
import org.jetbrains.annotations.NotNull;

import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.meta.TypeQualifierDefault;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.*;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class MockRecipeRegistration implements IRecipeRegistration {
    private final Map<RecipeType<?>, List<?>> collectedRecipes = new HashMap<>();
    private final Level level;
    private final RecipeManager recipeManager;

    private static IJeiHelpers STUB_HELPERS = null;

    public MockRecipeRegistration(Level level) {
        this.level = level;
        this.recipeManager = level.getRecipeManager();
    }

    public Level getLevel() {
        return level;
    }

    @Override
    public <T> void addRecipes(RecipeType<T> recipeType, List<T> recipes) {
        if (recipes.isEmpty()) {
            return;
        }
        collectedRecipes.put(recipeType, new ArrayList<>(recipes));
    }

    @NotNull
    public Map<RecipeType<?>, List<?>> getCollectedRecipes() {
        return Collections.unmodifiableMap(collectedRecipes);
    }

    @Override
    @NotNull
    public IJeiHelpers getJeiHelpers() {
        if (STUB_HELPERS == null) {
            STUB_HELPERS = new JeiMocks.SmartJeiHelpers(recipeManager);
        }
        return STUB_HELPERS;
    }

    @Override
    @NotNull
    public IIngredientManager getIngredientManager() {
        return getJeiHelpers().getIngredientManager();
    }

    @Override
    @NotNull
    public IVanillaRecipeFactory getVanillaRecipeFactory() {
        return getJeiHelpers().getVanillaRecipeFactory();
    }

    @Override
    public <T> void addIngredientInfo(T ingredient, IIngredientType<T> ingredientType, Component... descriptionComponents) {}

    @Override
    public <T> void addIngredientInfo(List<T> ingredients, IIngredientType<T> ingredientType, Component... descriptionComponents) {}
}

@Retention(RetentionPolicy.RUNTIME)
@TypeQualifierDefault(ElementType.METHOD)
@interface MethodsReturnNonnullByDefault {}