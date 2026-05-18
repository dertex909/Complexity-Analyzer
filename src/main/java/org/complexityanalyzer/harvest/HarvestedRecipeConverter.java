package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeNode;

public final class HarvestedRecipeConverter {

    private HarvestedRecipeConverter() {
    }

    public static RecipeNode convert(FastHarvester.HarvestedItems harvested, Level level) {
        if (harvested == null || harvested.isEmpty()) return null;

        ItemStack declaredResult = declaredRecipeResult(harvested.root(), level);
        var ingredients = harvested.ingredients();
        var stacks = harvested.items();
        var fluids = harvested.fluids();

        ItemStack output = selectOutput(declaredResult, stacks);
        if (output.isEmpty()) return null;

        var builder = new RecipeNode.Builder(output.getItem())
                .category(RecipeCategory.PRIMARY)
                .resultCount(output.getCount())
                .rawRecipe(harvested.root());

        if (harvested.root() instanceof Recipe<?> recipe) builder.recipeType(recipe.getType());

        for (Ingredient ingredient : ingredients) appendIngredient(builder, ingredient);
        for (ItemStack stack : stacks) if (!sameStackIdentity(stack, output)) appendStackAsIngredient(builder, stack);
        if (!fluids.isEmpty()) builder.fluidOutputs(fluids);

        return builder.build();
    }

    private static ItemStack declaredRecipeResult(Object root, Level level) {
        if (root instanceof Recipe<?> recipe && level != null) {
            try {
                return recipe.getResultItem(level.registryAccess()).copy();
            } catch (Throwable ignored) {
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack selectOutput(ItemStack declaredResult, ObjectList<ItemStack> stacks) {
        if (!declaredResult.isEmpty()) return declaredResult;
        ItemStack best = ItemStack.EMPTY;
        int bestScore = Integer.MAX_VALUE;
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            int score = stack.getCount() <= 0 ? Integer.MAX_VALUE : stack.getCount();
            if (best.isEmpty() || score < bestScore) {
                best = stack;
                bestScore = score;
            }
        }
        return best.copy();
    }

    private static void appendIngredient(RecipeNode.Builder builder, Ingredient ingredient) {
        var variants = new ObjectArrayList<Item>();
        ItemStack[] stacks = ingredient.getItems();
        int limit = ComplexityConfig.MAX_INGREDIENT_VARIANTS.get();
        for (int i = 0; i < Math.min(stacks.length, limit); i++) {
            ItemStack stack = stacks[i];
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (!variants.contains(item)) variants.add(item);
        }
        if (!variants.isEmpty()) builder.addIngredient(variants, 1);
    }

    private static void appendStackAsIngredient(RecipeNode.Builder builder, ItemStack stack) {
        var variants = new ObjectArrayList<Item>();
        variants.add(stack.getItem());
        builder.addIngredient(variants, Math.max(1, stack.getCount()));
    }

    private static boolean sameStackIdentity(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && a.getItem() == b.getItem() && a.getCount() == b.getCount();
    }
}
