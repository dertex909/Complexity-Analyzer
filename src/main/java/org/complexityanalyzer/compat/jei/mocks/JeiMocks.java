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
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.RecipeManager;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
@SuppressWarnings(value = {"removal", "deprecation", "unused"})
public class JeiMocks {

    public static class EmptyJeiHelpers implements IJeiHelpers {
        private static final EmptyIngredientManager INGREDIENT_MANAGER = new EmptyIngredientManager();
        private static final EmptyVanillaRecipeFactory VANILLA_RECIPE_FACTORY = new EmptyVanillaRecipeFactory();

        @Override
        public IGuiHelper getGuiHelper() {
            return null;
        }

        @Override
        public IStackHelper getStackHelper() {
            return null;
        }

        @Override
        public IModIdHelper getModIdHelper() {
            return null;
        }

        @Override
        public IFocusFactory getFocusFactory() {
            return null;
        }

        @Override
        public IColorHelper getColorHelper() {
            return null;
        }

        @Override
        public IPlatformFluidHelper<?> getPlatformFluidHelper() {
            return null;
        }

        @Override
        public <T> Optional<RecipeType<T>> getRecipeType(ResourceLocation uid, Class<? extends T> recipeClass) {
            return Optional.empty();
        }

        @Override
        public Optional<RecipeType<?>> getRecipeType(ResourceLocation uid) {
            return Optional.empty();
        }

        @Override
        public Stream<RecipeType<?>> getAllRecipeTypes() {
            return Stream.empty();
        }

        @Override
        public IIngredientManager getIngredientManager() {
            return INGREDIENT_MANAGER;
        }

        @Override
        public ICodecHelper getCodecHelper() {
            return null;
        }

        @Override
        public IVanillaRecipeFactory getVanillaRecipeFactory() {
            return VANILLA_RECIPE_FACTORY;
        }

        @Override
        public IIngredientVisibility getIngredientVisibility() {
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
        public IIngredientManager getIngredientManager() {
            return this.ingredientManager;
        }

        @Override
        public IVanillaRecipeFactory getVanillaRecipeFactory() {
            return this.vanillaRecipeFactory;
        }
    }

    private static class EmptyIngredientManager implements IIngredientManager {
        @Override
        public <V> Collection<V> getAllIngredients(IIngredientType<V> ingredientType) {
            return ObjectLists.emptyList();
        }

        @Override
        public <V> Collection<ITypedIngredient<V>> getAllTypedIngredients(IIngredientType<V> ingredientType) {
            return ObjectLists.emptyList();
        }

        @Override
        public <V> IIngredientHelper<V> getIngredientHelper(V ingredient) {
            return null;
        }

        @Override
        public <V> IIngredientHelper<V> getIngredientHelper(IIngredientType<V> ingredientType) {
            return null;
        }

        @Override
        public <V> IIngredientRenderer<V> getIngredientRenderer(V ingredient) {
            return null;
        }

        @Override
        public <V> IIngredientRenderer<V> getIngredientRenderer(IIngredientType<V> ingredientType) {
            return null;
        }

        @Override
        public <V> Codec<V> getIngredientCodec(IIngredientType<V> ingredientType) {
            return null;
        }

        @Override
        public Collection<IIngredientType<?>> getRegisteredIngredientTypes() {
            return ObjectLists.emptyList();
        }

        @Override
        public Optional<IIngredientType<?>> getIngredientTypeForUid(String ingredientTypeUid) {
            return Optional.empty();
        }

        @Override
        public <V> void addIngredientsAtRuntime(IIngredientType<V> ingredientType, Collection<V> ingredients) {
        }

        @Override
        public <V> void removeIngredientsAtRuntime(IIngredientType<V> ingredientType, Collection<V> ingredients) {
        }

        @Nullable
        @Override
        public <V> IIngredientType<V> getIngredientType(V ingredient) {
            return null;
        }

        @Override
        public <V> Optional<IIngredientType<V>> getIngredientTypeChecked(V ingredient) {
            return Optional.empty();
        }

        @Override
        public <B, I> Optional<IIngredientTypeWithSubtypes<B, I>> getIngredientTypeWithSubtypesFromBase(B baseIngredient) {
            return Optional.empty();
        }

        @Override
        public <V> Optional<IIngredientType<V>> getIngredientTypeChecked(Class<? extends V> ingredientClass) {
            return Optional.empty();
        }

        @Override
        public <V> Optional<ITypedIngredient<V>> createTypedIngredient(IIngredientType<V> ingredientType, V ingredient, boolean normalize) {
            return Optional.empty();
        }

        @Override
        public <V> ITypedIngredient<V> normalizeTypedIngredient(ITypedIngredient<V> typedIngredient) {
            return typedIngredient;
        }

        @Override
        public IClickableIngredientFactory getClickableIngredientFactory() {
            return null;
        }

        @Override
        public Collection<String> getIngredientAliases(ITypedIngredient<?> ingredient) {
            return ObjectLists.emptyList();
        }

        @Override
        public void registerIngredientListener(IIngredientListener listener) {
        }

        @Override
        public <V> Optional<V> getIngredientByUid(IIngredientType<V> ingredientType, String ingredientUuid) {
            return Optional.empty();
        }

        @Override
        public <V> Optional<ITypedIngredient<V>> getTypedIngredientByUid(IIngredientType<V> ingredientType, String ingredientUuid) {
            return Optional.empty();
        }

        @Override
        public <V> Optional<IClickableIngredient<V>> createClickableIngredient(
                IIngredientType<V> ingredientType,
                V ingredient,
                net.minecraft.client.renderer.Rect2i area,
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
        public IJeiAnvilRecipe createAnvilRecipe(ItemStack leftInput, List<ItemStack> rightInputs,
                                                 List<ItemStack> outputs, ResourceLocation uid) {
            return null;
        }

        @Override
        public IJeiAnvilRecipe createAnvilRecipe(List<ItemStack> leftInputs, List<ItemStack> rightInputs,
                                                 List<ItemStack> outputs, ResourceLocation uid) {
            return null;
        }

        @Override
        public IJeiGrindstoneRecipe createGrindstoneRecipe(List<ItemStack> topInputs, List<ItemStack> bottomInputs,
                                                           List<ItemStack> outputs, int minXp, int maxXp,
                                                           ResourceLocation uid) {
            return null;
        }

        @Override
        public IJeiBrewingRecipe createBrewingRecipe(List<ItemStack> ingredients, ItemStack potionInput,
                                                     ItemStack potionOutput, ResourceLocation uid) {
            return null;
        }

        @Override
        public IJeiBrewingRecipe createBrewingRecipe(List<ItemStack> ingredients, List<ItemStack> potionInputs,
                                                     ItemStack potionOutput, ResourceLocation uid) {
            return null;
        }

        @Override
        public IJeiShapedRecipeBuilder createShapedRecipeBuilder(CraftingBookCategory category,
                                                                 List<ItemStack> results) {
            return null;
        }

        @Override
        public IJeiAnvilRecipe createAnvilRecipe(ItemStack leftInput, List<ItemStack> rightInputs,
                                                 List<ItemStack> outputs) {
            return null;
        }

        @Override
        public IJeiAnvilRecipe createAnvilRecipe(List<ItemStack> leftInputs, List<ItemStack> rightInputs,
                                                 List<ItemStack> outputs) {
            return null;
        }

        @Override
        public IJeiBrewingRecipe createBrewingRecipe(List<ItemStack> ingredients, ItemStack potionInput,
                                                     ItemStack potionOutput) {
            return null;
        }

        @Override
        public IJeiBrewingRecipe createBrewingRecipe(List<ItemStack> ingredients, List<ItemStack> potionInputs,
                                                     ItemStack potionOutput) {
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