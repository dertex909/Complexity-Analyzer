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

package org.complexityanalyzer.graph;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.core.GameRegistryManager;

import java.util.Objects;

public class RecipeNode {
    public record ChemicalIngredient(ResourceLocation id, int amount) {
    }

    public record ChemicalOutput(ResourceLocation id, long amount) {
    }

    private final ObjectList<IngredientSlot> ingredients;
    private final ObjectList<FluidIngredientSlot> fluidIngredients;
    private final ObjectList<ChemicalIngredient> chemicalIngredients;
    private final ObjectList<ChemicalOutput> chemicalOutputs;
    private final ObjectList<ItemStack> itemOutputs;
    private final ObjectList<FluidStack> fluidOutputs;
    private final Item resultItem;
    private final String placeholderId;
    private final RecipeType<?> recipeType;
    private final int resultCount;
    private final int priority;
    private final double recipeMultiplier;
    private final boolean isPlaceholder;
    private RecipeCategory category;

    private RecipeNode(Builder builder) {
        this.ingredients = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.ingredients));
        this.fluidIngredients = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.fluidIngredients));
        this.chemicalIngredients = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.chemicalIngredients));
        this.itemOutputs = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.itemOutputs));
        this.fluidOutputs = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.fluidOutputs));
        this.chemicalOutputs = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.chemicalOutputs));
        this.recipeMultiplier = builder.recipeMultiplier;
        this.resultItem = builder.resultItem;
        this.resultCount = builder.resultCount;
        this.recipeType = builder.recipeType;
        this.category = builder.category;
        this.priority = builder.priority;
        this.isPlaceholder = builder.isPlaceholder;
        this.placeholderId = builder.placeholderId;
    }

    public static RecipeNode empty(Item item) {
        return new Builder(item).category(RecipeCategory.UNPROCESSABLE).build();
    }

    public void setCategory(RecipeCategory category) {
        this.category = category;
    }

    public ObjectList<ChemicalOutput> getChemicalOutputs() {
        return chemicalOutputs;
    }

    public ObjectList<ChemicalIngredient> getChemicalIngredients() {
        return chemicalIngredients;
    }

    public ObjectList<IngredientSlot> getIngredients() {
        return ingredients;
    }

    public ObjectList<FluidIngredientSlot> getFluidIngredients() {
        return fluidIngredients;
    }

    public ObjectList<ItemStack> getItemOutputs() {
        return itemOutputs;
    }

    public ObjectList<FluidStack> getFluidOutputs() {
        return fluidOutputs;
    }

    public Item getResultItem() {
        return resultItem;
    }

    public String getPlaceholderId() {
        return placeholderId;
    }

    public RecipeType<?> getRecipeType() {
        return recipeType;
    }

    public RecipeCategory getCategory() {
        return category;
    }

    public int getResultCount() {
        return resultCount;
    }

    public int getPriority() {
        return priority;
    }

    public int getTotalIngredientCount() {
        int total = 0;
        for (var slot : ingredients) total += slot.getCount();
        return total;
    }

    public boolean isBaseRecipe() {
        return ingredients.isEmpty() && fluidIngredients.isEmpty() && chemicalIngredients.isEmpty();
    }

    public boolean hasFluidIngredients() {
        return !fluidIngredients.isEmpty();
    }

    public boolean isPlaceholder() {
        return isPlaceholder;
    }

    public double getRecipeMultiplier() {
        return recipeMultiplier;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RecipeNode that = (RecipeNode) o;
        if (!resultItem.equals(that.resultItem)) return false;
        if (!Objects.equals(recipeType, that.recipeType)) return false;
        return ingredients.equals(that.ingredients);
    }

    @Override
    public int hashCode() {
        int result = resultItem.hashCode();
        result = 31 * result + (recipeType != null ? recipeType.hashCode() : 0);
        result = 31 * result + ingredients.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "RecipeNode{" + "result=" + GameRegistryManager.getItemId(resultItem) + ", type=" + recipeType + '}';
    }

    public static class Builder {
        private final Item resultItem;
        private final ObjectList<ChemicalIngredient> chemicalIngredients = new ObjectArrayList<>();
        private final ObjectList<IngredientSlot> ingredients = new ObjectArrayList<>();
        private final ObjectList<FluidIngredientSlot> fluidIngredients = new ObjectArrayList<>();
        private ObjectList<ItemStack> itemOutputs = new ObjectArrayList<>();
        private ObjectList<FluidStack> fluidOutputs = new ObjectArrayList<>();
        private final ObjectList<ChemicalOutput> chemicalOutputs = new ObjectArrayList<>();
        private RecipeType<?> recipeType;
        private RecipeCategory category = RecipeCategory.PRIMARY;
        private final double recipeMultiplier = 1.0;
        private int priority = 0;
        private int resultCount = 1;
        private boolean isPlaceholder = false;
        private String placeholderId = "";

        public Builder(Item resultItem) {
            this.resultItem = resultItem;
        }

        public void addIngredient(ObjectList<Item> variants, int count) {
            this.ingredients.add(new IngredientSlot(variants, count));
        }

        public void addFluidIngredient(ObjectList<Fluid> variants, int amount) {
            this.fluidIngredients.add(new FluidIngredientSlot(variants, amount));
        }

        public void itemOutputs(ObjectList<ItemStack> outputs) {
            this.itemOutputs = outputs;
        }

        public void fluidOutputs(ObjectList<FluidStack> outputs) {
            this.fluidOutputs = outputs;
        }

        public Builder resultCount(int count) {
            this.resultCount = count;
            return this;
        }

        public Builder recipeType(RecipeType<?> recipeType) {
            this.recipeType = recipeType;
            return this;
        }

        public Builder category(RecipeCategory category) {
            this.category = category;
            return this;
        }

        public void priority(int priority) {
            this.priority = priority;
        }

        public Builder isPlaceholder(boolean placeholder) {
            this.isPlaceholder = placeholder;
            return this;
        }

        public void placeholderId(String id) {
            this.placeholderId = id;
        }

        public Builder rawRecipe() {
            return this;
        }

        public RecipeNode build() {
            if (this.resultCount == 0 && !itemOutputs.isEmpty()) {
                int sum = 0;
                for (var s : itemOutputs) if (s.getItem().equals(resultItem)) sum += s.getCount();
                this.resultCount = sum;
            }
            if (this.resultCount == 0) this.resultCount = 1;
            return new RecipeNode(this);
        }
    }
}