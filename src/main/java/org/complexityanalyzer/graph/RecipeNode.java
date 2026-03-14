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

package org.complexityanalyzer.graph;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.compat.jei.AdaptiveRecipeConverter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class RecipeNode {
    private final List<IngredientSlot> ingredients;
    private final List<FluidIngredientSlot> fluidIngredients;
    private final List<ChemicalIngredient> chemicalIngredients;
    private final List<AdaptiveRecipeConverter.ChemicalOutput> chemicalOutputs;
    private final List<ItemStack> itemOutputs;
    private final List<FluidStack> fluidOutputs;
    private final Item resultItem;
    private final String placeholderId;
    private final RecipeType<?> recipeType;
    private final int resultCount;
    private final int priority;
    private final double recipeMultiplier;
    private final boolean isPlaceholder;
    private final transient Object rawRecipeRef;
    private RecipeCategory category;

    private RecipeNode(Builder builder) {
        this.ingredients = Collections.unmodifiableList(builder.ingredients);
        this.fluidIngredients = Collections.unmodifiableList(builder.fluidIngredients);
        this.chemicalIngredients = Collections.unmodifiableList(builder.chemicalIngredients);
        this.itemOutputs = Collections.unmodifiableList(builder.itemOutputs);
        this.fluidOutputs = Collections.unmodifiableList(builder.fluidOutputs);
        this.chemicalOutputs = Collections.unmodifiableList(builder.chemicalOutputs);
        this.recipeMultiplier = builder.recipeMultiplier;
        this.resultItem = builder.resultItem;
        this.resultCount = builder.resultCount;
        this.recipeType = builder.recipeType;
        this.category = builder.category;
        this.priority = builder.priority;
        this.isPlaceholder = builder.isPlaceholder;
        this.placeholderId = builder.placeholderId;
        this.rawRecipeRef = builder.rawRecipeRef;
    }

    public static RecipeNode empty(Item item) {
        return new Builder(item).category(RecipeCategory.UNPROCESSABLE).build();
    }

    public void setCategory(RecipeCategory category) {
        this.category = category;
    }

    public List<AdaptiveRecipeConverter.ChemicalOutput> getChemicalOutputs() {
        return chemicalOutputs;
    }

    public List<ChemicalIngredient> getChemicalIngredients() {
        return chemicalIngredients;
    }

    public List<IngredientSlot> getIngredients() {
        return ingredients;
    }

    public List<FluidIngredientSlot> getFluidIngredients() {
        return fluidIngredients;
    }

    public List<ItemStack> getItemOutputs() {
        return itemOutputs;
    }

    public List<FluidStack> getFluidOutputs() {
        return fluidOutputs;
    }

    public Object getRawRecipeRef() {
        return rawRecipeRef;
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
        return ingredients.stream().mapToInt(IngredientSlot::getCount).sum();
    }

    public int getIngredientSlotCount() {
        return ingredients.size();
    }

    public int getFluidIngredientSlotCount() {
        return fluidIngredients.size();
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
        return resultItem.equals(that.resultItem) &&
                recipeType.equals(that.recipeType) && ingredients.equals(that.ingredients);
    }

    @Override
    public int hashCode() {
        return Objects.hash(resultItem, recipeType, ingredients);
    }

    @Override
    public String toString() {
        return "RecipeNode{" +
                "result=" + BuiltInRegistries.ITEM.getKey(resultItem) +
                ", type=" + recipeType +
                '}';
    }

    public int getTotalFluidAmount() {
        return fluidOutputs.stream()
                .mapToInt(FluidStack::getAmount)
                .sum();
    }

    public static class Builder {
        private final Item resultItem;
        private final List<ChemicalIngredient> chemicalIngredients = new ArrayList<>();
        private final List<IngredientSlot> ingredients = new ArrayList<>();
        private final List<FluidIngredientSlot> fluidIngredients = new ArrayList<>();
        private List<ItemStack> itemOutputs = new ArrayList<>();
        private List<FluidStack> fluidOutputs = new ArrayList<>();
        private List<AdaptiveRecipeConverter.ChemicalOutput> chemicalOutputs = new ArrayList<>();
        private RecipeType<?> recipeType;
        private RecipeCategory category = RecipeCategory.PRIMARY;
        private double recipeMultiplier = 1.0;
        private int priority = 0;
        private int resultCount = 1;
        private boolean isPlaceholder = false;
        private String placeholderId = "";
        private Object rawRecipeRef;

        public Builder(Item resultItem) {
            this.resultItem = resultItem;
        }

        public void addIngredient(List<Item> variants, int count) {
            this.ingredients.add(new IngredientSlot(variants, count));
        }

        public void addFluidIngredient(List<Fluid> variants, int amount) {
            this.fluidIngredients.add(new FluidIngredientSlot(variants, amount));
        }

        public Builder chemicalOutputs(List<AdaptiveRecipeConverter.ChemicalOutput> outputs) {
            this.chemicalOutputs = outputs != null ? outputs : new ArrayList<>();
            return this;
        }

        public void addChemicalIngredient(ResourceLocation chemicalId, int amount) {
            this.chemicalIngredients.add(new ChemicalIngredient(chemicalId, amount));
        }

        public Builder itemOutputs(List<ItemStack> outputs) {
            this.itemOutputs = outputs;
            return this;
        }

        public Builder fluidOutputs(List<FluidStack> outputs) {
            this.fluidOutputs = outputs;
            return this;
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

        public Builder recipeMultiplier(double multiplier) {
            this.recipeMultiplier = multiplier;
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

        public Builder rawRecipe(Object raw) {
            this.rawRecipeRef = raw;
            return this;
        }

        public RecipeNode build() {
            if (this.resultCount == 0 && !itemOutputs.isEmpty()) {
                this.resultCount = itemOutputs.stream()
                        .filter(s -> s.getItem().equals(resultItem))
                        .mapToInt(ItemStack::getCount)
                        .sum();
            }
            if (this.resultCount == 0) this.resultCount = 1;
            return new RecipeNode(this);
        }
    }
}

record ChemicalIngredient(ResourceLocation id, int amount) {
}