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

package org.complexityanalyzer.harvest.inspector;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;

public final class MethodRefUtils {
    private MethodRefUtils() {
    }

    private static String extract(Object methodRef) {
        try {
            var writeReplace = methodRef.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            var lambda = (SerializedLambda) writeReplace.invoke(methodRef);
            return lambda.getImplMethodName();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to extract method name from lambda reference", e);
        }
    }

    public static <T, R> String getMethodName(Class<T> ignored, MethodRef<T, R> methodRef) {
        return extract(methodRef);
    }

    public static <T, P1, R> String getMethodName1(Class<T> ignored, MethodRef1<T, P1, R> methodRef) {
        return extract(methodRef);
    }

    public static String getRecipeResultItemName(RecipeGetResultItemRef methodRef) {
        return extract(methodRef);
    }

    public static String getRecipeAssembleName(RecipeAssembleRef methodRef) {
        return extract(methodRef);
    }

    @FunctionalInterface
    public interface MethodRef<T, R> extends Serializable {
        R ignored(T instance);
    }

    @FunctionalInterface
    public interface MethodRef1<T, P1, R> extends Serializable {
        R ignored(T instance, P1 p1);
    }

    @FunctionalInterface
    public interface RecipeGetResultItemRef extends Serializable {
        ItemStack ignored(Recipe<?> instance, HolderLookup.Provider provider);
    }

    @FunctionalInterface
    public interface RecipeAssembleRef extends Serializable {
        ItemStack ignored(Recipe<RecipeInput> instance, RecipeInput input, HolderLookup.Provider provider);
    }
}