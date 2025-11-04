/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014-2015 mezz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package mezz.jei.api.recipe;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

public final class RecipeType<T> {
	public static <T> RecipeType<T> create(String nameSpace, String path, Class<? extends T> recipeClass) {
		ResourceLocation uid = ResourceLocation.fromNamespaceAndPath(nameSpace, path);
		return new RecipeType<>(uid, recipeClass);
	}

	public static <R extends Recipe<?>> RecipeType<RecipeHolder<R>> createFromVanilla(net.minecraft.world.item.crafting.RecipeType<R> vanillaRecipeType) {
		ResourceLocation uid = BuiltInRegistries.RECIPE_TYPE.getKey(vanillaRecipeType);
		if (uid == null) {
			throw new IllegalArgumentException("Vanilla Recipe Type must be registered before using it here. %s".formatted(vanillaRecipeType));
		}
		return createRecipeHolderType(uid);
	}

	public static <R extends Recipe<?>> RecipeType<RecipeHolder<R>> createRecipeHolderType(ResourceLocation uid) {
		@SuppressWarnings({"unchecked", "RedundantCast"})
		Class<? extends RecipeHolder<R>> holderClass = (Class<? extends RecipeHolder<R>>) (Object) RecipeHolder.class;
		return new RecipeType<>(uid, holderClass);
	}

    private final ResourceLocation uid;
	private final Class<? extends T> recipeClass;

	@SuppressWarnings("ConstantValue")
	public RecipeType(ResourceLocation uid, Class<? extends T> recipeClass) {
		if (uid == null) {
			throw new NullPointerException("uid must not be null.");
		}
		if (recipeClass == null) {
			throw new NullPointerException("recipeClass must not be null.");
		}
		this.uid = uid;
		this.recipeClass = recipeClass;
	}

	public ResourceLocation getUid() {
		return uid;
	}

	public Class<? extends T> getRecipeClass() {
		return recipeClass;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == this) {
			return true;
		}
		if (!(obj instanceof RecipeType<?> other)) {
			return false;
		}
		return this.recipeClass == other.recipeClass &&
			this.uid.equals(other.uid);
	}

	@Override
	public int hashCode() {
		return 31 * uid.hashCode() + recipeClass.hashCode();
	}

	@Override
	public String toString() {
		return "RecipeType[" +
			"uid=" + uid + ", " +
			"recipeClass=" + recipeClass + ']';
	}

}
