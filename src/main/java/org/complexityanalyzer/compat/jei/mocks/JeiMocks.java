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

package org.complexityanalyzer.compat.jei.mocks;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import mezz.jei.api.gui.builder.IClickableIngredientFactory;
import mezz.jei.api.helpers.*;
import mezz.jei.api.ingredients.*;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.vanilla.IJeiAnvilRecipe;
import mezz.jei.api.recipe.vanilla.IJeiBrewingRecipe;
import mezz.jei.api.recipe.vanilla.IJeiGrindstoneRecipe;
import mezz.jei.api.recipe.vanilla.IJeiShapedRecipeBuilder;
import mezz.jei.api.recipe.vanilla.IVanillaRecipeFactory;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IIngredientVisibility;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.RecipeManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@SuppressWarnings(value = {"removal", "deprecation", "unused"})
public class JeiMocks {

    public static class EmptyJeiHelpers implements IJeiHelpers {
        private static final EmptyIngredientManager INGREDIENT_MANAGER = new EmptyIngredientManager();
        private static final EmptyVanillaRecipeFactory VANILLA_RECIPE_FACTORY = new EmptyVanillaRecipeFactory();

        @Override
        public @NotNull IGuiHelper getGuiHelper() {
            return null;
        }

        @Override
        public @NotNull IStackHelper getStackHelper() {
            return null;
        }

        @Override
        public @NotNull IModIdHelper getModIdHelper() {
            return null;
        }

        @Override
        public @NotNull IFocusFactory getFocusFactory() {
            return null;
        }

        @Override
        public @NotNull IColorHelper getColorHelper() {
            return null;
        }

        @Override
        public @NotNull IPlatformFluidHelper<?> getPlatformFluidHelper() {
            return null;
        }

        @Override
        public <T> @NotNull Optional<RecipeType<T>> getRecipeType(@NotNull ResourceLocation uid, @NotNull Class<? extends T> recipeClass) {
            return Optional.empty();
        }

        @Override
        public @NotNull Optional<RecipeType<?>> getRecipeType(@NotNull ResourceLocation uid) {
            return Optional.empty();
        }

        @Override
        public @NotNull Stream<RecipeType<?>> getAllRecipeTypes() {
            return Stream.empty();
        }

        @Override
        public @NotNull IIngredientManager getIngredientManager() {
            return INGREDIENT_MANAGER;
        }

        @Override
        public @NotNull ICodecHelper getCodecHelper() {
            return null;
        }

        @Override
        public @NotNull IVanillaRecipeFactory getVanillaRecipeFactory() {
            return VANILLA_RECIPE_FACTORY;
        }

        @Override
        public @NotNull IIngredientVisibility getIngredientVisibility() {
            return null;
        }
    }

    public static class SmartJeiHelpers extends EmptyJeiHelpers {
        private final SmartIngredientManager ingredientManager;
        private final SmartVanillaRecipeFactory vanillaRecipeFactory;

        public SmartJeiHelpers(RecipeManager recipeManager) {
            this.ingredientManager = new SmartIngredientManager(recipeManager);
            this.vanillaRecipeFactory = new SmartVanillaRecipeFactory(recipeManager);
        }

        @Override
        public @NotNull IIngredientManager getIngredientManager() {
            return this.ingredientManager;
        }

        @Override
        public @NotNull IVanillaRecipeFactory getVanillaRecipeFactory() {
            return this.vanillaRecipeFactory;
        }
    }

    private static class EmptyIngredientManager implements IIngredientManager {
        @Override
        public <V> @NotNull Collection<V> getAllIngredients(@NotNull IIngredientType<V> ingredientType) {
            return ObjectLists.emptyList();
        }

        @Override
        public <V> @NotNull Collection<ITypedIngredient<V>> getAllTypedIngredients(@NotNull IIngredientType<V> ingredientType) {
            return ObjectLists.emptyList();
        }

        @Override
        public <V> @NotNull IIngredientHelper<V> getIngredientHelper(@NotNull V ingredient) {
            return null;
        }

        @Override
        public <V> @NotNull IIngredientHelper<V> getIngredientHelper(@NotNull IIngredientType<V> ingredientType) {
            return null;
        }

        @Override
        public <V> @NotNull IIngredientRenderer<V> getIngredientRenderer(@NotNull V ingredient) {
            return null;
        }

        @Override
        public <V> @NotNull IIngredientRenderer<V> getIngredientRenderer(@NotNull IIngredientType<V> ingredientType) {
            return null;
        }

        @Override
        public <V> @NotNull Codec<V> getIngredientCodec(@NotNull IIngredientType<V> ingredientType) {
            return null;
        }

        @Override
        public @NotNull Collection<IIngredientType<?>> getRegisteredIngredientTypes() {
            return ObjectLists.emptyList();
        }

        @Override
        public @NotNull Optional<IIngredientType<?>> getIngredientTypeForUid(@NotNull String ingredientTypeUid) {
            return Optional.empty();
        }

        @Override
        public <V> void addIngredientsAtRuntime(@NotNull IIngredientType<V> ingredientType, @NotNull Collection<V> ingredients) {
        }

        @Override
        public <V> void removeIngredientsAtRuntime(@NotNull IIngredientType<V> ingredientType, @NotNull Collection<V> ingredients) {
        }

        @Nullable
        @Override
        public <V> IIngredientType<V> getIngredientType(@NotNull V ingredient) {
            return null;
        }

        @Override
        public <V> @NotNull Optional<IIngredientType<V>> getIngredientTypeChecked(@NotNull V ingredient) {
            return Optional.empty();
        }

        @Override
        public <B, I> @NotNull Optional<IIngredientTypeWithSubtypes<B, I>> getIngredientTypeWithSubtypesFromBase(@NotNull B baseIngredient) {
            return Optional.empty();
        }

        @Override
        public <V> @NotNull Optional<IIngredientType<V>> getIngredientTypeChecked(@NotNull Class<? extends V> ingredientClass) {
            return Optional.empty();
        }

        @Override
        public <V> @NotNull Optional<ITypedIngredient<V>> createTypedIngredient(@NotNull IIngredientType<V> ingredientType, @NotNull V ingredient, boolean normalize) {
            return Optional.empty();
        }

        @Override
        public <V> @NotNull ITypedIngredient<V> normalizeTypedIngredient(@NotNull ITypedIngredient<V> typedIngredient) {
            return typedIngredient;
        }

        @Override
        public @NotNull IClickableIngredientFactory getClickableIngredientFactory() {
            return null;
        }

        @Override
        public @NotNull Collection<String> getIngredientAliases(@NotNull ITypedIngredient<?> ingredient) {
            return ObjectLists.emptyList();
        }

        @Override
        public void registerIngredientListener(@NotNull IIngredientListener listener) {
        }

        @Override
        public <V> @NotNull Optional<V> getIngredientByUid(@NotNull IIngredientType<V> ingredientType, @NotNull String ingredientUuid) {
            return Optional.empty();
        }

        @Override
        public <V> @NotNull Optional<ITypedIngredient<V>> getTypedIngredientByUid(@NotNull IIngredientType<V> ingredientType, @NotNull String ingredientUuid) {
            return Optional.empty();
        }

        @Override
        public <V> @NotNull Optional<IClickableIngredient<V>> createClickableIngredient(
                @NotNull IIngredientType<V> ingredientType,
                @NotNull V ingredient,
                net.minecraft.client.renderer.@NotNull Rect2i area,
                boolean normalize) {
            return Optional.empty();
        }
    }

    private static class SmartIngredientManager extends EmptyIngredientManager {
        @SuppressWarnings("FieldCanBeLocal")
        private final RecipeManager recipeManager;

        public SmartIngredientManager(RecipeManager recipeManager) {
            super();
            this.recipeManager = recipeManager;
        }
    }

    private static class EmptyVanillaRecipeFactory implements IVanillaRecipeFactory {
        @Override
        public @NotNull IJeiAnvilRecipe createAnvilRecipe(@NotNull ItemStack leftInput, @NotNull List<ItemStack> rightInputs,
                                                          @NotNull List<ItemStack> outputs, @NotNull ResourceLocation uid) {
            return null;
        }

        @Override
        public @NotNull IJeiAnvilRecipe createAnvilRecipe(@NotNull List<ItemStack> leftInputs, @NotNull List<ItemStack> rightInputs,
                                                          @NotNull List<ItemStack> outputs, @NotNull ResourceLocation uid) {
            return null;
        }

        @Override
        public @NotNull IJeiGrindstoneRecipe createGrindstoneRecipe(@NotNull List<ItemStack> topInputs, @NotNull List<ItemStack> bottomInputs,
                                                                    @NotNull List<ItemStack> outputs, int minXp, int maxXp,
                                                                    @NotNull ResourceLocation uid) {
            return null;
        }

        @Override
        public @NotNull IJeiBrewingRecipe createBrewingRecipe(@NotNull List<ItemStack> ingredients, @NotNull ItemStack potionInput,
                                                              @NotNull ItemStack potionOutput, @NotNull ResourceLocation uid) {
            return null;
        }

        @Override
        public @NotNull IJeiBrewingRecipe createBrewingRecipe(@NotNull List<ItemStack> ingredients, @NotNull List<ItemStack> potionInputs,
                                                              @NotNull ItemStack potionOutput, @NotNull ResourceLocation uid) {
            return null;
        }

        @Override
        public @NotNull IJeiShapedRecipeBuilder createShapedRecipeBuilder(@NotNull CraftingBookCategory category,
                                                                          @NotNull List<ItemStack> results) {
            return null;
        }

        @Override
        public @NotNull IJeiAnvilRecipe createAnvilRecipe(@NotNull ItemStack leftInput, @NotNull List<ItemStack> rightInputs,
                                                          @NotNull List<ItemStack> outputs) {
            return null;
        }

        @Override
        public @NotNull IJeiAnvilRecipe createAnvilRecipe(@NotNull List<ItemStack> leftInputs, @NotNull List<ItemStack> rightInputs,
                                                          @NotNull List<ItemStack> outputs) {
            return null;
        }

        @Override
        public @NotNull IJeiBrewingRecipe createBrewingRecipe(@NotNull List<ItemStack> ingredients, @NotNull ItemStack potionInput,
                                                              @NotNull ItemStack potionOutput) {
            return null;
        }

        @Override
        public @NotNull IJeiBrewingRecipe createBrewingRecipe(@NotNull List<ItemStack> ingredients, @NotNull List<ItemStack> potionInputs,
                                                              @NotNull ItemStack potionOutput) {
            return null;
        }
    }

    private static class SmartVanillaRecipeFactory extends EmptyVanillaRecipeFactory {
        @SuppressWarnings("FieldCanBeLocal")
        private final RecipeManager recipeManager;

        public SmartVanillaRecipeFactory(RecipeManager recipeManager) {
            super();
            this.recipeManager = recipeManager;
        }
    }
}