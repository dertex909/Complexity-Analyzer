package org.complexityanalyzer.compat.jei;

import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.runtime.IIngredientManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.jetbrains.annotations.NotNull;

import javax.annotation.ParametersAreNonnullByDefault;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ParametersAreNonnullByDefault
public class MockRecipeCatalystRegistration implements IRecipeCatalystRegistration {

    private final Map<ResourceLocation, List<ItemStack>> catalysts = new HashMap<>();

    private static final IIngredientManager EMPTY_INGREDIENT_MANAGER = createProxy(IIngredientManager.class);
    private static final IJeiHelpers EMPTY_JEI_HELPERS = createProxy(IJeiHelpers.class);

    public Map<ResourceLocation, List<ItemStack>> getCatalysts() {
        return catalysts;
    }

    @Override
    public <T> void addRecipeCatalyst(IIngredientType<T> ingredientType, T ingredient, RecipeType<?>... recipeTypes) {
        if (ingredient instanceof ItemStack stack && !stack.isEmpty()) {
            for (RecipeType<?> recipeType : recipeTypes) {
                ResourceLocation uid = recipeType.getUid();
                catalysts.computeIfAbsent(uid, k -> new ArrayList<>()).add(stack.copy());

                ComplexityAnalyzer.LOGGER.debug("JEI Catalyst Intercept: {} -> {}",
                        uid,
                        BuiltInRegistries.ITEM.getKey(stack.getItem()));
            }
        }
    }

    @Override public void addRecipeCatalysts(RecipeType<?> recipeType, ItemLike... ingredients) {}
    @Override public <T> void addRecipeCatalysts(RecipeType<?> recipeType, IIngredientType<T> ingredientType, List<T> ingredients) {}

    @Override @NotNull
    public IIngredientManager getIngredientManager() {return EMPTY_INGREDIENT_MANAGER;}

    @Override @NotNull
    public IJeiHelpers getJeiHelpers() {return EMPTY_JEI_HELPERS;}

    @SuppressWarnings("unchecked")
    private static <T> T createProxy(Class<T> interfaceClass) {
        return (T) Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class<?>[] { interfaceClass },
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    return switch (methodName) {
                        case "toString" -> "MockProxy$" + interfaceClass.getSimpleName();
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> args != null && args.length == 1 && proxy == args[0];
                        default -> null;
                    };
                }
        );
    }
}