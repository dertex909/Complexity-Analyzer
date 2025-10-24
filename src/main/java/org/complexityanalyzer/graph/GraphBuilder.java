package org.complexityanalyzer.graph;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.config.ComplexityConfig;

import java.util.List;
import java.util.stream.Stream;

public class GraphBuilder {
    private static final TagKey<Item> STORAGE_BLOCKS_TAG = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:storage_blocks"));
    private static final TagKey<Item> INGOTS_TAG = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:ingots"));
    private static final TagKey<Item> NUGGETS_TAG = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:nuggets"));
    private static final TagKey<Item> GEMS_TAG = TagKey.create(Registries.ITEM, ResourceLocation.parse("c:gems"));

    public static RecipeGraph buildFromWorld(Level level) {
        ComplexityAnalyzer.LOGGER.info("Building recipe graph with advanced classification...");
        RecipeGraph graph = new RecipeGraph();
        RecipeManager recipeManager = level.getRecipeManager();
        int processedCount = 0;
        int skippedCount = 0;
        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            try {
                RecipeNode node = buildNode(holder.value(), level);
                if (node != null) {
                    graph.addRecipe(node);
                    processedCount++;
                } else {
                    skippedCount++;
                }
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.warn("Failed to process recipe {}: {}", holder.id(), e.getMessage());
                skippedCount++;
            }
        }
        ComplexityAnalyzer.LOGGER.info("Recipe graph built: {} recipes processed, {} skipped", processedCount, skippedCount);
        return graph;
    }

    private static RecipeNode buildSmithingNode(
            net.minecraft.world.item.crafting.SmithingTransformRecipe recipe,
            Item resultItem,
            int resultCount
    ) {
        RecipeNode.Builder builder = new RecipeNode.Builder(resultItem)
                .resultCount(resultCount)
                .recipeType(RecipeType.SMITHING)
                .category(RecipeCategory.PRIMARY);

        try {
            java.lang.reflect.Field templateField = net.minecraft.world.item.crafting.SmithingTransformRecipe.class.getDeclaredField("template");
            java.lang.reflect.Field baseField = net.minecraft.world.item.crafting.SmithingTransformRecipe.class.getDeclaredField("base");
            java.lang.reflect.Field additionField = net.minecraft.world.item.crafting.SmithingTransformRecipe.class.getDeclaredField("addition");

            templateField.setAccessible(true);
            baseField.setAccessible(true);
            additionField.setAccessible(true);

            Ingredient template = (Ingredient) templateField.get(recipe);
            Ingredient base = (Ingredient) baseField.get(recipe);
            Ingredient addition = (Ingredient) additionField.get(recipe);

            if (!template.isEmpty()) {
                List<Item> templateVariants = Stream.of(template.getItems())
                        .map(ItemStack::getItem)
                        .distinct()
                        .toList();
                if (!templateVariants.isEmpty()) {
                    builder.addIngredient(templateVariants, 1);
                }
            }

            if (!base.isEmpty()) {
                List<Item> baseVariants = Stream.of(base.getItems())
                        .map(ItemStack::getItem)
                        .distinct()
                        .toList();
                if (!baseVariants.isEmpty()) {
                    builder.addIngredient(baseVariants, 1);
                }
            }

            if (!addition.isEmpty()) {
                List<Item> additionVariants = Stream.of(addition.getItems())
                        .map(ItemStack::getItem)
                        .distinct()
                        .toList();
                if (!additionVariants.isEmpty()) {
                    builder.addIngredient(additionVariants, 1);
                }
            }

            builder.priority(2100);

        } catch (NoSuchFieldException | IllegalAccessException e) {
            ComplexityAnalyzer.LOGGER.error("Failed to process SmithingTransformRecipe for {}: {}",
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(resultItem),
                    e.getMessage());
            return null;
        }

        return builder.build();
    }

    private static RecipeNode buildNode(Recipe<?> recipe, Level level) {
        ItemStack resultStack = recipe.getResultItem(level.registryAccess());
        if (resultStack.isEmpty()) return null;

        Item resultItem = resultStack.getItem();
        List<Ingredient> ingredients = recipe.getIngredients();

        if (recipe instanceof net.minecraft.world.item.crafting.SmithingTransformRecipe smithing) {
            return buildSmithingNode(smithing, resultItem, resultStack.getCount());
        }

        if (ingredients.isEmpty()) {
            ComplexityAnalyzer.LOGGER.debug("Skipping recipe with no ingredients: {} for {}",
                    recipe.getClass().getSimpleName(),
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(resultItem));
            return null;
        }

        RecipeCategory category = classifyRecipe(recipe, resultItem, ingredients);
        if (category == RecipeCategory.UNPROCESSABLE) return null;

        RecipeNode.Builder builder = new RecipeNode.Builder(resultItem)
                .resultCount(resultStack.getCount())
                .recipeType(recipe.getType())
                .category(category);

        if (category == RecipeCategory.PRIMARY && (recipe.getType() == RecipeType.SMELTING || recipe.getType() == RecipeType.BLASTING)) {
            builder.priority(2000);
        }

        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            List<Item> itemVariants = Stream.of(ingredient.getItems())
                    .limit(ComplexityConfig.MAX_INGREDIENT_VARIANTS.get())
                    .map(ItemStack::getItem).distinct().toList();
            if (!itemVariants.isEmpty()) builder.addIngredient(itemVariants, 1);
        }
        return builder.build();
    }

    private static RecipeCategory classifyRecipe(Recipe<?> recipe, Item resultItem, List<Ingredient> ingredients) {
        if (isUnprocessable(recipe, resultItem, ingredients)) return RecipeCategory.UNPROCESSABLE;
        if (isRecyclingRecipe(recipe, ingredients)) return RecipeCategory.RECYCLING;

        ItemStack resultStack = new ItemStack(resultItem);

        if (ingredients.size() == 1) {
            ItemStack ingredientStack = ingredients.getFirst().getItems().length > 0 ? ingredients.getFirst().getItems()[0] : ItemStack.EMPTY;
            if (!ingredientStack.isEmpty()) {
                if (ingredientStack.is(STORAGE_BLOCKS_TAG) && (resultStack.is(INGOTS_TAG) || resultStack.is(GEMS_TAG))) {
                    return RecipeCategory.STORAGE_DECOMPRESSION;
                }
                if (ingredientStack.is(INGOTS_TAG) && resultStack.is(NUGGETS_TAG)) {
                    return RecipeCategory.STORAGE_DECOMPRESSION;
                }
            }
        }

        if (areAllIngredientsOfTag(ingredients, NUGGETS_TAG) && resultStack.is(INGOTS_TAG)) {
            return RecipeCategory.STORAGE_COMPRESSION;
        }
        if (areAllIngredientsOfTag(ingredients, INGOTS_TAG) && resultStack.is(STORAGE_BLOCKS_TAG)) {
            return RecipeCategory.STORAGE_COMPRESSION;
        }
        if (areAllIngredientsOfTag(ingredients, GEMS_TAG) && resultStack.is(STORAGE_BLOCKS_TAG)) {
            return RecipeCategory.STORAGE_COMPRESSION;
        }

        return RecipeCategory.PRIMARY;
    }

    private static boolean areAllIngredientsOfTag(List<Ingredient> ingredients, TagKey<Item> tag) {
        if (ingredients.isEmpty()) return false;
        return ingredients.stream()
                .flatMap(ing -> Stream.of(ing.getItems()))
                .allMatch(stack -> !stack.isEmpty() && stack.is(tag));
    }

    private static boolean isUnprocessable(Recipe<?> recipe, Item resultItem, List<Ingredient> ingredients) {
        if (new ItemStack(resultItem).isDamageableItem()) {
            return ingredients.stream().flatMap(ing -> Stream.of(ing.getItems())).anyMatch(stack -> stack.getItem() == resultItem);
        }
        return recipe instanceof TippedArrowRecipe || recipe instanceof MapCloningRecipe || recipe instanceof ArmorDyeRecipe || recipe instanceof BannerDuplicateRecipe;
    }

    private static boolean isRecyclingRecipe(Recipe<?> recipe, List<Ingredient> ingredients) {
        if (ingredients.size() != 1) return false;
        boolean ingredientIsDamageable = Stream.of(ingredients.getFirst().getItems()).anyMatch(ItemStack::isDamageableItem);
        return ingredientIsDamageable && (recipe.getType() == RecipeType.SMELTING || recipe.getType() == RecipeType.BLASTING);
    }
}