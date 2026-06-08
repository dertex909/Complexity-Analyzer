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
import org.complexityanalyzer.harvest.ItemStackIdentity;

import java.util.Objects;

/**
 * A single normalized recipe in the harvested graph: one way to produce {@link #getResultItem()} from a set of
 * item, fluid and chemical ingredients. This is the analyzer's mod-agnostic representation of every recipe it
 * discovered — vanilla crafting/smelting as well as modded machine recipes — so addons can inspect crafting
 * relationships uniformly without depending on each mod's own recipe classes.
 *
 * <p>Instances are immutable (built via {@link Builder}); the returned ingredient/output lists are unmodifiable.
 * A node with no ingredients is a {@link #isBaseRecipe() base recipe}; a {@link #isPlaceholder() placeholder}
 * stands in for a virtual/synthetic product.
 */
public class RecipeNode {
    /** A chemical/gas ingredient identified by registry id and amount (for mods with chemical systems). */
    public record ChemicalIngredient(ResourceLocation id, int amount) {
    }

    /** A chemical/gas output identified by registry id and amount. */
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
    private volatile int listIndex = -1;

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

    public int getListIndex() {
        return listIndex;
    }

    public void setListIndex(int listIndex) {
        this.listIndex = listIndex;
    }

    /** @return an empty/unprocessable node for the given item (no ingredients, no real recipe). */
    public static RecipeNode empty(Item item) {
        return new Builder(item).category(RecipeCategory.UNPROCESSABLE).build();
    }

    /** Reclassifies this node (used internally during solving). */
    public void setCategory(RecipeCategory category) {
        this.category = category;
    }

    /** @return chemical/gas outputs produced; unmodifiable, may be empty. */
    public ObjectList<ChemicalOutput> getChemicalOutputs() {
        return chemicalOutputs;
    }

    /** @return chemical/gas ingredients consumed; unmodifiable, may be empty. */
    public ObjectList<ChemicalIngredient> getChemicalIngredients() {
        return chemicalIngredients;
    }

    /** @return the item ingredient slots, each holding the accepted item variants and a count; unmodifiable. */
    public ObjectList<IngredientSlot> getIngredients() {
        return ingredients;
    }

    /** @return the fluid ingredient slots; unmodifiable, may be empty. */
    public ObjectList<FluidIngredientSlot> getFluidIngredients() {
        return fluidIngredients;
    }

    /** @return all item outputs of the recipe (the primary result plus any byproducts); unmodifiable. */
    public ObjectList<ItemStack> getItemOutputs() {
        return itemOutputs;
    }

    /** @return fluid outputs of the recipe; unmodifiable, may be empty. */
    public ObjectList<FluidStack> getFluidOutputs() {
        return fluidOutputs;
    }

    /** @return the primary produced item. */
    public Item getResultItem() {
        return resultItem;
    }

    /** @return the synthetic id when this is a placeholder node, otherwise an empty/identifier string. */
    public String getPlaceholderId() {
        return placeholderId;
    }

    /** @return the vanilla/modded recipe type this node was derived from, or {@code null} for synthetic nodes. */
    public RecipeType<?> getRecipeType() {
        return recipeType;
    }

    /** @return the solver-assigned category of this node. */
    public RecipeCategory getCategory() {
        return category;
    }

    /** @return how many of the primary item one craft yields. */
    public int getResultCount() {
        return resultCount;
    }

    /** @return the selection priority among competing recipes for the same item; higher wins. */
    public int getPriority() {
        return priority;
    }

    /** @return the summed count across all item, fluid and chemical ingredient slots. */
    public int getTotalIngredientCount() {
        int total = 0;
        for (var slot : ingredients) total += slot.getCount();
        for (var slot : fluidIngredients) total += slot.getAmount();
        for (var slot : chemicalIngredients) total += slot.amount();
        return total;
    }

    /** @return {@code true} if this recipe has no inputs at all (a leaf/raw producer). */
    public boolean isBaseRecipe() {
        return ingredients.isEmpty() && fluidIngredients.isEmpty() && chemicalIngredients.isEmpty();
    }

    /** @return {@code true} if the recipe consumes at least one fluid. */
    public boolean hasFluidIngredients() {
        return !fluidIngredients.isEmpty();
    }

    /** @return {@code true} if this is a synthetic placeholder node rather than a real recipe. */
    public boolean isPlaceholder() {
        return isPlaceholder;
    }

    /** @return a cost scaling factor applied to this recipe (e.g. for output-count or efficiency normalization). */
    public double getRecipeMultiplier() {
        return recipeMultiplier;
    }

    private static boolean equalItemStackLists(ObjectList<ItemStack> list1, ObjectList<ItemStack> list2) {
        if (list1 == list2) return true;
        if (list1 == null || list2 == null) return false;
        if (list1.size() != list2.size()) return false;
        for (int i = 0; i < list1.size(); i++) {
            var s1 = list1.get(i);
            var s2 = list2.get(i);
            if (s1 == s2) continue;
            if (s1 == null || s2 == null) return false;
            if (!ItemStackIdentity.sameItemDataAndCount(s1, s2)) return false;
        }
        return true;
    }

    private static boolean equalFluidStackLists(ObjectList<FluidStack> list1, ObjectList<FluidStack> list2) {
        if (list1 == list2) return true;
        if (list1 == null || list2 == null) return false;
        if (list1.size() != list2.size()) return false;
        for (int i = 0; i < list1.size(); i++) {
            var s1 = list1.get(i);
            var s2 = list2.get(i);
            if (s1 == s2) continue;
            if (s1 == null || s2 == null) return false;
            if (s1.getFluid() != s2.getFluid() || s1.getAmount() != s2.getAmount()) return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        var that = (RecipeNode) o;
        if (resultCount != that.resultCount) return false;
        if (isPlaceholder != that.isPlaceholder) return false;
        if (!resultItem.equals(that.resultItem)) return false;
        if (!Objects.equals(recipeType, that.recipeType)) return false;
        if (!Objects.equals(placeholderId, that.placeholderId)) return false;
        if (!ingredients.equals(that.ingredients)) return false;
        if (!fluidIngredients.equals(that.fluidIngredients)) return false;
        if (!chemicalIngredients.equals(that.chemicalIngredients)) return false;
        if (!chemicalOutputs.equals(that.chemicalOutputs)) return false;
        if (!equalItemStackLists(itemOutputs, that.itemOutputs)) return false;
        return equalFluidStackLists(fluidOutputs, that.fluidOutputs);
    }

    @Override
    public int hashCode() {
        int result = resultItem.hashCode();
        result = 31 * result + (recipeType != null ? recipeType.hashCode() : 0);
        result = 31 * result + resultCount;
        result = 31 * result + (isPlaceholder ? 1 : 0);
        result = 31 * result + (placeholderId != null ? placeholderId.hashCode() : 0);
        result = 31 * result + ingredients.hashCode();
        result = 31 * result + fluidIngredients.hashCode();
        result = 31 * result + chemicalIngredients.hashCode();
        result = 31 * result + chemicalOutputs.hashCode();

        int outputsHash = 1;
        for (var stack : itemOutputs) {
            int stackHash = ItemStackIdentity.hashItemDataAndCount(stack);
            outputsHash = 31 * outputsHash + stackHash;
        }
        result = 31 * result + outputsHash;

        int fluidOutputsHash = 1;
        for (var stack : fluidOutputs) {
            int stackHash = (stack == null || stack.isEmpty()) ? 0 : (stack.getFluid().hashCode() * 31 + stack.getAmount());
            fluidOutputsHash = 31 * fluidOutputsHash + stackHash;
        }
        result = 31 * result + fluidOutputsHash;

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

        public void addIngredient(ObjectList<ItemStack> variants, int count) {
            this.ingredients.add(new IngredientSlot(variants, count));
        }

        public void addChemicalIngredient(ChemicalIngredient ingredient) {
            this.chemicalIngredients.add(ingredient);
        }

        public void addChemicalOutput(ChemicalOutput output) {
            this.chemicalOutputs.add(output);
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