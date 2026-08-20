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
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.neoforged.neoforge.attachment.AttachmentHolder;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;

public final class StandardRecipeMethods {

    public static final String GET_RESULT_ITEM = extract((RecipeGetResultItemRef) Recipe::getResultItem);
    public static final String GET_INGREDIENTS = extract((RecipeGetIngredientsRef) Recipe::getIngredients);
    public static final String GET_TOAST_SYMBOL = extract((RecipeGetToastSymbolRef) Recipe::getToastSymbol);
    public static final String ASSEMBLE = extract((RecipeAssembleRef) Recipe::assemble);
    public static final String SERIALIZE_ATTACHMENTS = extract((AttachmentHolderSerializeRef) AttachmentHolder::serializeAttachments);

    private StandardRecipeMethods() {
    }

    private static String extract(Serializable methodRef) {
        try {
            var writeReplace = methodRef.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            var lambda = (SerializedLambda) writeReplace.invoke(methodRef);
            return lambda.getImplMethodName();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to extract method name from lambda reference", e);
        }
    }

    @FunctionalInterface
    private interface RecipeGetResultItemRef extends Serializable {
        ItemStack ignored(Recipe<?> instance, HolderLookup.Provider provider);
    }

    @FunctionalInterface
    private interface RecipeGetIngredientsRef extends Serializable {
        NonNullList<Ingredient> ignored(Recipe<?> instance);
    }

    @FunctionalInterface
    private interface RecipeGetToastSymbolRef extends Serializable {
        ItemStack ignored(Recipe<?> instance);
    }

    @FunctionalInterface
    private interface RecipeAssembleRef extends Serializable {
        ItemStack ignored(Recipe<RecipeInput> instance, RecipeInput input, HolderLookup.Provider provider);
    }

    @FunctionalInterface
    private interface AttachmentHolderSerializeRef extends Serializable {
        CompoundTag ignored(AttachmentHolder instance, HolderLookup.Provider provider);
    }
}