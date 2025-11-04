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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.meta.TypeQualifierDefault;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class MockRecipeRegistration implements IRecipeRegistration {
    private final Map<RecipeType<?>, List<?>> collectedRecipes = new HashMap<>();
    private final Level level;
    private final RecipeManager recipeManager;

    private static IJeiHelpers STUB_HELPERS = null;
    private static IVanillaRecipeFactory STUB_RECIPE_FACTORY = null;

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
            STUB_HELPERS = createProxyWithRecipeManager(IJeiHelpers.class);
        }
        return STUB_HELPERS;
    }

    @Override
    @NotNull
    public IIngredientManager getIngredientManager() {
        try {
            Class<?> jeiInternalClass = Class.forName("mezz.jei.common.Internal");
            Method getIngredientManager = jeiInternalClass.getMethod("getIngredientManager");
            Object manager = getIngredientManager.invoke(null);

            if (manager instanceof IIngredientManager) {
                return (IIngredientManager) manager;
            }
        } catch (Exception ignored) {}

        return createProxyWithRecipeManager(IIngredientManager.class);
    }

    @Override
    @NotNull
    public IVanillaRecipeFactory getVanillaRecipeFactory() {
        if (STUB_RECIPE_FACTORY == null) {
            STUB_RECIPE_FACTORY = createProxyWithRecipeManager(IVanillaRecipeFactory.class);
        }
        return STUB_RECIPE_FACTORY;
    }

    @Override
    public <T> void addIngredientInfo(T ingredient, IIngredientType<T> ingredientType, Component... descriptionComponents) {}

    @Override
    public <T> void addIngredientInfo(List<T> ingredients, IIngredientType<T> ingredientType, Component... descriptionComponents) {}

    @SuppressWarnings("unchecked")
    private <T> T createProxyWithRecipeManager(Class<T> interfaceClass) {
        return (T) Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class<?>[] { interfaceClass },
                new StubInvocationHandlerWithRecipeManager(recipeManager)
        );
    }

    private static class StubInvocationHandlerWithRecipeManager implements InvocationHandler {
        private final RecipeManager recipeManager;

        public StubInvocationHandlerWithRecipeManager(RecipeManager recipeManager) {
            this.recipeManager = recipeManager;
        }

        @Override
        @Nullable
        public Object invoke(Object proxy, Method method, @Nullable Object[] args) {
            String methodName = method.getName();

            if (methodName.equals("getRecipeManager")) {
                return recipeManager;
            }

            if (methodName.equals("getAllRecipesFor") && args.length == 1) {
                try {
                    Method getAllRecipesForMethod = RecipeManager.class.getMethod(
                            "getAllRecipesFor",
                            net.minecraft.world.item.crafting.RecipeType.class
                    );
                    return getAllRecipesForMethod.invoke(recipeManager, args[0]);
                } catch (Exception ignored) {}
            }

            switch (methodName) {
                case "toString":
                    return "MockJeiStub@" + Integer.toHexString(System.identityHashCode(proxy));
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return args.length == 1 && proxy == args[0];
            }

            Class<?> returnType = method.getReturnType();

            if (returnType == void.class) return null;
            if (returnType == boolean.class) return false;
            if (returnType.isPrimitive()) return 0;
            if (Collection.class.isAssignableFrom(returnType)) return Collections.emptyList();
            if (Optional.class.isAssignableFrom(returnType)) return Optional.empty();

            return null;
        }
    }
}

@Retention(RetentionPolicy.RUNTIME)
@TypeQualifierDefault(ElementType.METHOD)
@interface MethodsReturnNonnullByDefault {}