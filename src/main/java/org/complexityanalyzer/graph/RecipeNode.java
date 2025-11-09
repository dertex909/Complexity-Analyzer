package org.complexityanalyzer.graph;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.compat.jei.AdaptiveRecipeConverter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class RecipeNode {

    private final Item resultItem;
    private final int resultCount;
    private final RecipeType<?> recipeType;
    private RecipeCategory category;
    private final double recipeMultiplier;
    private final int priority;

    private final List<IngredientSlot> ingredients;
    private final List<FluidIngredientSlot> fluidIngredients;
    private final List<ChemicalIngredient> chemicalIngredients; // ✅ ДОБАВЛЕНО

    private final List<AdaptiveRecipeConverter.ChemicalOutput> chemicalOutputs;

    private final List<ItemStack> itemOutputs;
    private final List<FluidStack> fluidOutputs;

    private final boolean isPlaceholder;
    private final String placeholderId;

    private transient Object rawRecipeRef;

    private RecipeNode(Builder builder) {
        this.resultItem = builder.resultItem;
        this.resultCount = builder.resultCount;
        this.recipeType = builder.recipeType;
        this.category = builder.category;
        this.recipeMultiplier = builder.recipeMultiplier;
        this.priority = builder.priority;

        this.ingredients = Collections.unmodifiableList(builder.ingredients);
        this.fluidIngredients = Collections.unmodifiableList(builder.fluidIngredients);
        this.chemicalIngredients = Collections.unmodifiableList(builder.chemicalIngredients); // ✅ ДОБАВЛЕНО

        this.itemOutputs = Collections.unmodifiableList(builder.itemOutputs);
        this.fluidOutputs = Collections.unmodifiableList(builder.fluidOutputs);

        this.isPlaceholder = builder.isPlaceholder;
        this.placeholderId = builder.placeholderId;

        this.rawRecipeRef = builder.rawRecipeRef;
        this.chemicalOutputs = Collections.unmodifiableList(builder.chemicalOutputs);
    }

    public static RecipeNode empty(Item item) {
        return new Builder(item).category(RecipeCategory.UNPROCESSABLE).build();
    }

    // Геттеры
    public void setCategory(RecipeCategory category) { this.category = category; }
    public List<AdaptiveRecipeConverter.ChemicalOutput> getChemicalOutputs() { return chemicalOutputs; }
    public List<ChemicalIngredient> getChemicalIngredients() { return chemicalIngredients; } // ✅ ДОБАВЛЕНО
    public Object getRawRecipeRef() { return rawRecipeRef; }
    public Item getResultItem() { return resultItem; }
    public RecipeType<?> getRecipeType() { return recipeType; }
    public RecipeCategory getCategory() { return category; }
    public double getRecipeMultiplier() { return recipeMultiplier; }
    public int getResultCount() { return resultCount; }
    public int getPriority() { return priority; }
    public int getTotalIngredientCount() {
        return ingredients.stream().mapToInt(IngredientSlot::getCount).sum();
    }
    public boolean isBaseRecipe() {
        return ingredients.isEmpty() && fluidIngredients.isEmpty() && chemicalIngredients.isEmpty(); // ✅ ИСПРАВЛЕНО
    }
    public List<IngredientSlot> getIngredients() { return ingredients; }
    public List<FluidIngredientSlot> getFluidIngredients() { return fluidIngredients; }
    public int getIngredientSlotCount() { return ingredients.size(); }
    public int getFluidIngredientSlotCount() { return fluidIngredients.size(); }
    public boolean hasFluidIngredients() { return !fluidIngredients.isEmpty(); }
    public List<ItemStack> getItemOutputs() { return itemOutputs; }
    public List<FluidStack> getFluidOutputs() { return fluidOutputs; }
    public boolean isPlaceholder() { return isPlaceholder; }
    public String getPlaceholderId() { return placeholderId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RecipeNode that = (RecipeNode) o;
        return resultItem.equals(that.resultItem) &&
                recipeType.equals(that.recipeType) &&
                ingredients.equals(that.ingredients);
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

    // Builder
    public static class Builder {
        private final Item resultItem;
        private RecipeType<?> recipeType;
        private RecipeCategory category = RecipeCategory.PRIMARY;
        private double recipeMultiplier = 1.0;

        private int priority = 0;
        private int resultCount = 1;

        private List<ChemicalIngredient> chemicalIngredients = new ArrayList<>();

        private final List<IngredientSlot> ingredients = new ArrayList<>();
        private final List<FluidIngredientSlot> fluidIngredients = new ArrayList<>();
        private List<ItemStack> itemOutputs = new ArrayList<>();
        private List<FluidStack> fluidOutputs = new ArrayList<>();
        private List<AdaptiveRecipeConverter.ChemicalOutput> chemicalOutputs = new ArrayList<>();
        private boolean isPlaceholder = false;

        private String placeholderId = "";
        private Object rawRecipeRef;

        public Builder(Item resultItem) {
            this.resultItem = resultItem;
        }

        public Builder addChemicalIngredient(ResourceLocation chemicalId, int amount) {
            this.chemicalIngredients.add(new ChemicalIngredient(chemicalId, amount));
            return this;
        }

        public Builder resultCount(int count) { this.resultCount = count; return this; }
        public Builder recipeType(RecipeType<?> recipeType) { this.recipeType = recipeType; return this; }
        public Builder category(RecipeCategory category) { this.category = category; return this; }
        public Builder recipeMultiplier(double multiplier) { this.recipeMultiplier = multiplier; return this; }
        public Builder priority(int priority) { this.priority = priority; return this; }
        public Builder isPlaceholder(boolean placeholder) { this.isPlaceholder = placeholder; return this; }
        public Builder placeholderId(String id) { this.placeholderId = id; return this; }
        public Builder rawRecipe(Object raw) { this.rawRecipeRef = raw; return this; }
        public Builder addIngredient(List<Item> variants, int count) {
            this.ingredients.add(new IngredientSlot(variants, count));
            return this;
        }
        public Builder addFluidIngredient(List<net.minecraft.world.level.material.Fluid> variants, int amount) {
            this.fluidIngredients.add(new FluidIngredientSlot(variants, amount));
            return this;
        }
        public Builder itemOutputs(List<ItemStack> outputs) {
            this.itemOutputs = outputs;
            return this;
        }
        public Builder fluidOutputs(List<FluidStack> outputs) {
            this.fluidOutputs = outputs;
            return this;
        }
        public Builder chemicalOutputs(List<AdaptiveRecipeConverter.ChemicalOutput> outputs) {
            this.chemicalOutputs = outputs != null ? outputs : new ArrayList<>();
            return this;
        }

        public RecipeNode build() {
            if (this.resultCount == 0 && !itemOutputs.isEmpty()) {
                this.resultCount = itemOutputs.stream()
                        .filter(s -> s.getItem().equals(resultItem))
                        .mapToInt(ItemStack::getCount)
                        .sum();
            }
            if (this.resultCount == 0) {
                this.resultCount = 1;
            }
            return new RecipeNode(this);
        }
    }
}

// ✅ ДОБАВЬТЕ ЭТО В КОНЕЦ ФАЙЛА:
record ChemicalIngredient(ResourceLocation id, int amount) {}