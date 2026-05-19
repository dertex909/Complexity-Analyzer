package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeNode;

public final class HarvestedRecipeConverter {

    private HarvestedRecipeConverter() {
    }

    public static RecipeNode convert(HarvestedItems harvested, Level level) {
        if (harvested == null || harvested.isEmpty()) return null;

        ItemStack declaredResult = declaredRecipeResult(harvested.root(), level);
        var inputIngredients = harvested.inputIngredients();
        var inputStacks = harvested.inputItems();
        var outputStacks = harvested.outputItems();
        var inputFluids = harvested.inputFluids();
        var outputFluids = harvested.outputFluids();

        ItemStack output;
        boolean isPlaceholder = false;
        String placeholderId = "";

        if (declaredResult.isEmpty() && outputStacks.isEmpty() && !outputFluids.isEmpty()) {
            output = new ItemStack(Items.AIR);
            isPlaceholder = true;
            placeholderId = GameRegistryManager.getFluidId(outputFluids.getFirst().getFluid()).toString();
        } else {
            output = selectOutput(declaredResult, outputStacks.isEmpty() ? inputStacks : outputStacks);
        }

        if (output.isEmpty() && !isPlaceholder && !inputIngredients.isEmpty()) {
            ItemStack[] items = inputIngredients.getFirst().getItems();
            if (items.length > 0) {
                output = items[0].copy();
                output.setCount(1);
            }
        }

        if (output.isEmpty() && !isPlaceholder) return null;

        var builder = new RecipeNode.Builder(output.getItem())
                .category(RecipeCategory.PRIMARY)
                .resultCount(output.getCount())
                .rawRecipe(harvested.root());

        if (isPlaceholder) {
            builder.isPlaceholder(true);
            builder.placeholderId(placeholderId);
        }

        if (harvested.root() instanceof Recipe<?> recipe) builder.recipeType(recipe.getType());

        for (Ingredient ingredient : inputIngredients) appendIngredient(builder, ingredient);
        for (ItemStack stack : inputStacks) {
            if (!sameStackIdentity(stack, output)) appendStackAsIngredient(builder, stack);
        }

        if (!inputFluids.isEmpty()) {
            var seenFluids = new Reference2DoubleOpenHashMap<Fluid>();
            for (FluidStack fluid : inputFluids) {
                Fluid f = normalizeFluid(fluid.getFluid());
                if (f == Fluids.EMPTY) continue;
                double amt = fluid.getAmount();
                double existing = seenFluids.getDouble(f);
                if (amt > existing) seenFluids.put(f, amt);
            }
            for (var entry : seenFluids.reference2DoubleEntrySet()) {
                var variants = new ObjectArrayList<Fluid>();
                variants.add(entry.getKey());
                builder.addFluidIngredient(variants, (int) entry.getDoubleValue());
            }
        }

        if (!outputStacks.isEmpty()) builder.itemOutputs(outputStacks);

        if (!outputFluids.isEmpty()) {
            var mergedOutputs = new Reference2DoubleOpenHashMap<Fluid>();
            for (FluidStack fluid : outputFluids) {
                Fluid f = normalizeFluid(fluid.getFluid());
                if (f == Fluids.EMPTY) continue;
                double amt = fluid.getAmount();
                double existing = mergedOutputs.getDouble(f);
                if (amt > existing) mergedOutputs.put(f, amt);
            }
            var deduplicatedOutputs = new ObjectArrayList<FluidStack>();
            for (var entry : mergedOutputs.reference2DoubleEntrySet()) {
                deduplicatedOutputs.add(new FluidStack(entry.getKey(), (int) entry.getDoubleValue()));
            }
            builder.fluidOutputs(deduplicatedOutputs);
        }

        return builder.build();
    }

    private static Fluid normalizeFluid(Fluid fluid) {
        var id = GameRegistryManager.getFluidId(fluid);
        var fluidName = id.toString();
        if (fluidName.contains("flowing_")) {
            var staticName = fluidName.replace("flowing_", "");
            try {
                var staticFluid = GameRegistryManager.getFluid(ResourceLocation.parse(staticName));
                if (staticFluid != null) return staticFluid;
            } catch (Throwable ignored) {
            }
        }
        return fluid;
    }

    private static ItemStack declaredRecipeResult(Object root, Level level) {
        if (root instanceof Recipe<?> recipe && level != null) try {
            return recipe.getResultItem(level.registryAccess()).copy();
        } catch (Throwable ignored) {
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