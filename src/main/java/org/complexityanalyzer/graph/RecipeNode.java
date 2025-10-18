package org.complexityanalyzer.graph;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;
import org.complexityanalyzer.data.RecipeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RecipeNode {
    private final Item resultItem;
    private final int resultCount;
    private final List<IngredientSlot> ingredients;
    private final RecipeType<?> recipeType;
    private final double recipeMultiplier;
    private final int priority;
    private final RecipeCategory category;


    private RecipeNode(Builder builder) {
        this.resultItem = builder.resultItem;
        this.resultCount = Math.max(1, builder.resultCount);
        this.ingredients = new ArrayList<>(builder.ingredients);
        this.recipeType = builder.recipeType;
        this.recipeMultiplier = builder.recipeMultiplier;
        this.category = builder.category;
        this.priority = builder.priority != 0 ? builder.priority : category.getPriority();
    }

    public RecipeCategory getCategory() {
        return category;
    }

    public Item getResultItem() {
        return resultItem;
    }

    public int getResultCount() {
        return resultCount;
    }

    public List<IngredientSlot> getIngredients() {
        return Collections.unmodifiableList(ingredients);
    }

    public RecipeType<?> getRecipeType() {
        return recipeType;
    }

    public double getRecipeMultiplier() {
        return recipeMultiplier;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isBaseRecipe() {
        return ingredients.isEmpty();
    }

    public int getTotalIngredientCount() {
        return ingredients.stream()
                .mapToInt(IngredientSlot::getCount)
                .sum();
    }

    public int getIngredientSlotCount() {
        return ingredients.size();
    }

    @Override
    public String toString() {
        return String.format("RecipeNode{result=%s x%d, ingredients=%d, type=%s}",
                resultItem, resultCount, ingredients.size(), RecipeInfo.getTypeDisplayName(recipeType));
    }

    public static class Builder {
        private final Item resultItem;
        private int resultCount = 1;
        private RecipeCategory category = RecipeCategory.PRIMARY;
        final List<IngredientSlot> ingredients = new ArrayList<>();
        RecipeType<?> recipeType = RecipeType.CRAFTING;
        private double recipeMultiplier = 1.0;
        private int priority = 0;

        public Builder(Item resultItem) {
            this.resultItem = resultItem;
        }

        public Builder category(RecipeCategory category) {
            this.category = category;
            return this;
        }

        public Builder resultCount(int count) {
            this.resultCount = count;
            return this;
        }

        public Builder addIngredient(IngredientSlot slot) {
            this.ingredients.add(slot);
            return this;
        }

        public Builder addIngredient(Item item, int count) {
            this.ingredients.add(new IngredientSlot(item, count));
            return this;
        }

        public void addIngredient(List<Item> variants, int count) {
            this.ingredients.add(new IngredientSlot(variants, count));
        }

        public Builder ingredients(List<IngredientSlot> ingredients) {
            this.ingredients.clear();
            this.ingredients.addAll(ingredients);
            return this;
        }

        public Builder recipeType(RecipeType<?> type) {
            this.recipeType = type;
            this.recipeMultiplier = RecipeInfo.getMultiplierForType(type);
            return this;
        }

        public Builder recipeMultiplier(double multiplier) {
            this.recipeMultiplier = multiplier;
            return this;
        }

        public void priority(int priority) {
            this.priority = priority;
        }

        public RecipeNode build() {
            return new RecipeNode(this);
        }
    }

    public static RecipeNode empty(Item item) {
        return new Builder(item)
                .resultCount(1)
                .build();
    }
}