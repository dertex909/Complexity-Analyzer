package org.complexityanalyzer.compat.jei;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeNode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class AdaptiveRecipeConverter {

    private static final Map<Class<?>, RecipeAdapter> LEARNED_ADAPTERS = new ConcurrentHashMap<>();
    private static final int MAX_RECURSION_DEPTH = 5;

    private static final List<String> OUTPUT_KEYWORDS = List.of(
            "output", "result", "product", "produce", "yield", "generate", "reward", "primary", "secondary", "byproduct"
    );
    private static final List<String> INPUT_KEYWORDS = List.of(
            "input", "ingredient", "require", "consume", "use", "need", "supply", "source", "catalyst", "cost"
    );

    public static RecipeNode convertRecipe(net.minecraft.world.item.crafting.Recipe<?> recipe, Level level) {
        List<ItemStack> itemOutputs = extractOutputs(recipe, level);
        List<FluidStack> fluidOutputs = extractFluidOutputs(recipe, level);

        if (itemOutputs.isEmpty()) {
            itemOutputs = extractMekanismItemOutputs(recipe);
        }

        if (itemOutputs.isEmpty() && fluidOutputs.isEmpty()) {
            return null;
        }

        List<List<ItemStack>> itemInputs = extractInputs(recipe, level);
        List<List<FluidStack>> fluidInputs = extractFluidInputs(recipe, level);

        if (itemInputs.isEmpty() && fluidInputs.isEmpty()) {
            return null;
        }

        RecipeType<?> recipeType = recipe.getType();

        RecipeNode.Builder builder;
        Item resultItem;

        if (!itemOutputs.isEmpty()) {
            resultItem = itemOutputs.getFirst().getItem();
            builder = new RecipeNode.Builder(resultItem);
        } else {
            resultItem = Items.BARRIER;
            builder = new RecipeNode.Builder(resultItem).isPlaceholder(true);
            builder.placeholderId(BuiltInRegistries.FLUID.getKey(fluidOutputs.getFirst().getFluid()).toString());
        }

        builder.itemOutputs(itemOutputs)
                .fluidOutputs(fluidOutputs);

        // Шаг 4: Определяем категорию рецепта
        List<Ingredient> ingredients = itemInputs.stream()
                .filter(l -> !l.isEmpty())
                .map(l -> Ingredient.of(l.toArray(new ItemStack[0])))
                .toList();
        RecipeCategory category = org.complexityanalyzer.graph.GraphBuilder.classifyRecipe(recipe, resultItem, ingredients);
        if (category == RecipeCategory.UNPROCESSABLE) return null;

        builder.recipeType(recipeType).category(category);

        // Шаг 5: Добавляем все найденные ингредиенты
        itemInputs.forEach(group -> {
            if (!group.isEmpty()) {
                builder.addIngredient(
                        group.stream().map(ItemStack::getItem).distinct().toList(),
                        group.getFirst().getCount()
                );
            }
        });

        fluidInputs.forEach(group -> {
            if (!group.isEmpty()) {
                builder.addFluidIngredient(
                        group.stream().map(FluidStack::getFluid).distinct().toList(),
                        group.getFirst().getAmount()
                );
            }
        });

        RecipeNode node = builder.build();

        if (node.getIngredients().isEmpty() && node.getFluidIngredients().isEmpty()) {
            return null;
        }

        return node;
    }

    private static <T> List<T> findRecursive(Object obj, int depth, BiFunction<Object, Integer, List<T>> baseCaseEvaluator) {
        if (obj == null || depth > MAX_RECURSION_DEPTH) return Collections.emptyList();

        List<T> baseResult = baseCaseEvaluator.apply(obj, depth);
        if (baseResult != null) return baseResult;

        List<T> results = new ArrayList<>();
        if (obj instanceof Collection<?> coll) {
            for (Object item : coll) results.addAll(findRecursive(item, depth + 1, baseCaseEvaluator));
        } else if (obj.getClass().isArray()) {
            try {
                for (Object item : (Object[]) obj) results.addAll(findRecursive(item, depth + 1, baseCaseEvaluator));
            } catch (ClassCastException ignored) {}
        } else if (obj.getClass().isRecord()) {
            results.addAll(collectFromRecord(obj, depth, (value, d) -> findRecursive(value, d, baseCaseEvaluator)));
        }
        return results;
    }

    public static List<ItemStack> extractOutputs(Object recipe, Level level) {
        Object actualRecipe = unwrapRecipeHolder(recipe);
        RecipeAdapter adapter = getAdapter(actualRecipe, true, ResourceType.ITEM, level);
        if (adapter.itemOutputAccessor == null) return new ArrayList<>();
        try {
            Object result = adapter.itemOutputAccessor.extract(actualRecipe, level);
            return deepFindItemStacks(result, 0);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static List<FluidStack> extractFluidOutputs(Object recipe, Level level) {
        Object actualRecipe = unwrapRecipeHolder(recipe);
        RecipeAdapter adapter = getAdapter(actualRecipe, true, ResourceType.FLUID, level);
        if (adapter.fluidOutputAccessor == null) return new ArrayList<>();
        try {
            Object result = adapter.fluidOutputAccessor.extract(actualRecipe, level);
            return deepFindFluidStacks(result, 0);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static List<List<ItemStack>> extractInputs(Object recipe, Level level) {
        Object actualRecipe = unwrapRecipeHolder(recipe);
        RecipeAdapter adapter = getAdapter(actualRecipe, false, ResourceType.ITEM, level);
        if (adapter.itemInputAccessor == null) return new ArrayList<>();
        try {
            Object result = adapter.itemInputAccessor.extract(actualRecipe, level);
            return deepFindItemStackLists(result, 0);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static List<List<FluidStack>> extractFluidInputs(Object recipe, Level level) {
        Object actualRecipe = unwrapRecipeHolder(recipe);
        RecipeAdapter adapter = getAdapter(actualRecipe, false, ResourceType.FLUID, level);
        if (adapter.fluidInputAccessor == null) return new ArrayList<>();
        try {
            Object result = adapter.fluidInputAccessor.extract(actualRecipe, level);
            return deepFindFluidStackLists(result, 0);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static RecipeAdapter getAdapter(Object recipe, boolean isOutput, ResourceType resourceType, Level level) {
        Class<?> recipeClass = recipe.getClass();
        RecipeAdapter adapter = LEARNED_ADAPTERS.computeIfAbsent(recipeClass,
                clazz -> new RecipeAdapter(null, null, null, null));

        if (isOutput) {
            Accessor existingAccessor = switch (resourceType) {
                case ITEM -> adapter.itemOutputAccessor;
                case FLUID -> adapter.fluidOutputAccessor;
            };

            if (existingAccessor == null) {
                Accessor accessor = learnAccessor(recipe, true, resourceType, level);
                adapter = switch (resourceType) {
                    case ITEM -> new RecipeAdapter(accessor, adapter.fluidOutputAccessor,
                            adapter.itemInputAccessor, adapter.fluidInputAccessor);
                    case FLUID -> new RecipeAdapter(adapter.itemOutputAccessor, accessor,
                            adapter.itemInputAccessor, adapter.fluidInputAccessor);
                };
                LEARNED_ADAPTERS.put(recipeClass, adapter);
            }
        } else {
            Accessor existingAccessor = switch (resourceType) {
                case ITEM -> adapter.itemInputAccessor;
                case FLUID -> adapter.fluidInputAccessor;
            };

            if (existingAccessor == null) {
                Accessor accessor = learnAccessor(recipe, false, resourceType, level);
                adapter = switch (resourceType) {
                    case ITEM -> new RecipeAdapter(adapter.itemOutputAccessor, adapter.fluidOutputAccessor,
                            accessor, adapter.fluidInputAccessor);
                    case FLUID -> new RecipeAdapter(adapter.itemOutputAccessor, adapter.fluidOutputAccessor,
                            adapter.itemInputAccessor, accessor);
                };
                LEARNED_ADAPTERS.put(recipeClass, adapter);
            }
        }
        return adapter;
    }

    enum ResourceType {
        ITEM, FLUID
    }

    private static Object invokeMethod(Method method, Object target, Level level) throws Exception {
        Object[] args = prepareArguments(method, level);
        if (args == null) {
            return null;
        }
        return method.invoke(target, args);
    }

    private static Accessor learnAccessor(Object recipe, boolean isOutput, ResourceType resourceType, Level level) {
        Method method = learnMethod(recipe, isOutput, resourceType, level);
        if (method != null) {
            return new MethodAccessor(method);
        }

        Field field = learnField(recipe, isOutput, resourceType);
        if (field != null) {
            return new FieldAccessor(field);
        }

        return null;
    }

    private static RecipeType<?> extractRecipeTypeFromAccessors(Object actual) {
        RecipeAdapter adapter = LEARNED_ADAPTERS.get(actual.getClass());
        if (adapter != null) {
            RecipeType<?> type = tryResolveType(adapter.itemOutputAccessor, actual);
            if (type != null) return type;

            type = tryResolveType(adapter.fluidOutputAccessor, actual);
            if (type != null) return type;

            type = tryResolveType(adapter.itemInputAccessor, actual);
            if (type != null) return type;

            return tryResolveType(adapter.fluidInputAccessor, actual);
        }
        return null;
    }

    private static RecipeType<?> tryResolveType(Accessor accessor, Object recipe) {
        if (accessor == null) {
            return null;
        }
        try {
            Object value = accessor.extract(recipe, null);
            if (value == null) {
                return null;
            }
            RecipeType<?> fromMethod = findRecipeTypeViaGetter(value);
            if (fromMethod != null) {
                return fromMethod;
            }
            return extractRecipeTypeFromFields(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static RecipeType<?> findRecipeTypeViaGetter(Object value) {
        String[] candidates = { "getRecipeType", "recipeType", "getType", "type" };
        for (String candidate : candidates) {
            try {
                Method method = findAnyMethod(value.getClass(), candidate);
                if (method == null || method.getParameterCount() != 0) continue;
                method.setAccessible(true);
                Object result = method.invoke(value);
                RecipeType<?> type = coerceRecipeType(result);
                if (type != null) {
                    return type;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static Object unwrapRecipeHolder(Object obj) {
        if (obj instanceof net.minecraft.world.item.crafting.RecipeHolder<?> holder) {
            return holder.value();
        }
        return obj;
    }

    private static String[] getCandidateMethods(boolean isOutput, ResourceType resourceType) {
        if (isOutput) {
            return new String[]{
                    "getLeftGasOutput", "getRightGasOutput", "getLeftOutput", "getRightOutput", "getLeftGas", "getRightGas",
                    "getGasOutput", "getGasOutputs", "getChemicalOutput", "getChemicalOutputs", "gasOutput", "gasOutputs",
                    "chemicalOutput", "chemicalOutputs", "getInfusionOutput", "getInfusionOutputs", "getPigmentOutput", "getPigmentOutputs",
                    "getSlurryOutput", "getSlurryOutputs", "fetchChemicalOutput", "retrieveChemicalOutput", "outputGas", "outputChemical",
                    "outputsGas", "outputsChemical", "resultChemical", "producedGas", "producedChemical",
                    "getFluidOutput", "getFluidOutputs", "outputFluid", "outputFluids", "getOutputFluids", "getFluidResult",
                    "getFluidResults", "fetchFluidOutput", "retrieveFluidOutput", "fluidOutput", "fluidOutputs", "producedFluid",
                    "producedFluids", "resultFluid", "outputsFluid", "getOutputFluid", "getOutputFluids", "outputFluid", "outputFluids",
                    "getResultItem", "getResultItems", "getItemOutput",
                    "getItemOutputs", "itemOutput", "itemOutputs", "stackOutput", "stackOutputs", "getStack", "getStacks",
                    "outputItem", "outputItems", "resultStack", "resultStacks", "producedItem", "producedItems",
                    "getOutputStack", "getOutput", "getOutputs", "getResult", "getResults", "getProduct", "getProducts",
                    "output", "outputs", "result", "results", "product", "products", "fetchOutput", "fetchOutputs",
                    "retrieveOutput", "retrieveOutputs", "produce", "produces", "produced", "getProduce", "create", "creates",
                    "getCreate", "make", "makes", "getMake", "yield", "getYield", "generate", "generated", "getGenerate",
                    "getProcessingOutput", "getRecipeOutput", "getMainOutput", "getSecondaryOutput", "getBonusOutput",
                    "getPrimaryOutput", "getByproduct", "getByproducts", "getResultDefinition", "getOutputDefinition",
                    "getOutputData", "getResultData", "getOutputSlot", "getOutputSlots", "getOutputContainer", "getOutputContents",
                    "getOutputChemical", "getOutputChemicals", "getOutputGas", "getOutputGases"
            };
        }

        return switch (resourceType) {
            case ITEM -> new String[]{
                    "getInputItem", "getInputItems", "getItemInput", "getItemInputs",
                    "inputItem", "inputItems", "itemInput", "itemInputs", "stackInput", "stackInputs",
                    "getInputStack", "getInputStacks", "ingredientItem", "ingredientItems",
                    "getIngredientItem", "getIngredientItems", "getIngredientStack",
                    "getInput", "getInputs", "getIngredient", "getIngredients", "input", "inputs",
                    "ingredient", "ingredients", "getInputSolid", "inputSolid", "solidInput"
            };
            case FLUID -> new String[]{
                    "getFluidInput", "getFluidInputs", "getInputFluid", "getInputFluids",
                    "inputFluid", "inputFluids", "fluidInput", "fluidInputs",
                    "getFluidIngredient", "getFluidIngredients", "fetchFluidInput",
                    "retrieveFluidInput", "ingredientFluid", "ingredientFluids",
                    "getLeftGasInput", "getRightGasInput", "getGasInput", "getGasInputs",
                    "getChemicalInput", "getChemicalInputs", "getInputGas", "getInputGases",
                    "gasInput", "gasInputs", "chemicalInput", "chemicalInputs",
                    "getInputChemical", "inputChemical", "getInfusionInput", "getInfusionInputs",
                    "getPigmentInput", "getPigmentInputs", "getSlurryInput", "getSlurryInputs",
                    "fetchChemicalInput", "retrieveChemicalInput", "inputGas", "inputsGas",
                    "inputsChemical", "ingredientGas", "ingredientChemical"
            };
        };
    }

    private static String[] getWildcards(boolean isOutput, ResourceType resourceType) {
        if (isOutput) {
            return new String[]{"gas", "chemical", "infusion", "pigment", "slurry", "fluid", "item", "stack", "output", "result", "produce", "craft", "create", "yield", "generate"};
        }

        return switch (resourceType) {
            case ITEM -> new String[]{"item", "stack", "input", "ingredient", "solid"};
            case FLUID -> new String[]{"fluid", "liquid", "input", "ingredient", "gas", "chemical", "infusion", "pigment", "slurry", "input", "ingredient"};
        };
    }

    private static String[] getCandidateFields(boolean isOutput, ResourceType resourceType) {
        if (isOutput) {
            return new String[]{
                    "leftGasOutput", "rightGasOutput", "leftOutput", "rightOutput", "gasOutput", "chemicalOutput",
                    "infusionOutput", "pigmentOutput", "slurryOutput", "fluidOutput", "result", "results", "output", "outputs",
                    "product", "products", "mainOutput", "secondaryOutput", "bonusOutput", "primaryOutput", "byproduct", "outputDefinition"
            };
        }

        return switch (resourceType) {
            case ITEM -> new String[]{
                    "inputs", "input", "ingredient", "ingredients", "itemInput", "itemInputs",
                    "stackInput", "solidInput", "inputDefinition"
            };
            case FLUID -> new String[]{
                    "fluidInput", "fluidInputs", "inputFluid", "inputFluids", "liquidInput",
                    "leftGasInput", "rightGasInput", "gasInput", "chemicalInput",
                    "infusionInput", "pigmentInput", "slurryInput", "gasInputs", "chemicalInputs"
            };
        };
    }

    private static Method learnMethod(Object recipe, boolean isOutput, ResourceType resourceType, Level level) {
        String[] candidateMethods = getCandidateMethods(isOutput, resourceType);

        // Фаза 1: Точное совпадение имени
        for (String methodName : candidateMethods) {
            Method method = findAndValidateMethod(recipe, methodName, isOutput, resourceType, level);
            if (method != null) return method;
        }

        // Фаза 2: Wildcard поиск
        String[] wildcards = getWildcards(isOutput, resourceType);

        for (String wildcard : wildcards) {
            for (Method method : recipe.getClass().getMethods()) {
                if (method.getParameterCount() > 1 || method.getName().equals("getClass") || method.getName().equals("toString")) continue;
                if (Arrays.stream(candidateMethods).anyMatch(m -> m.equals(method.getName()))) continue;
                if (method.getName().toLowerCase().contains(wildcard)) {
                    Method validated = findAndValidateMethod(recipe, method.getName(), isOutput, resourceType, level);
                    if (validated != null) return validated;
                }
            }
        }
        return null;
    }

    private static Field learnField(Object recipe, boolean isOutput, ResourceType resourceType) {
        String[] candidateNames = getCandidateFields(isOutput, resourceType);

        for (String name : candidateNames) {
            Field field = findAnyField(recipe.getClass(), name);
            if (field == null) continue;
            if (validateField(field, recipe, isOutput, resourceType)) {
                return field;
            }
        }

        List<String> keywords = isOutput ? OUTPUT_KEYWORDS : INPUT_KEYWORDS;
        for (Field field : getAllFields(recipe.getClass())) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            String lower = field.getName().toLowerCase(Locale.ROOT);
            if (keywords.stream().noneMatch(lower::contains)) continue;
            if (validateField(field, recipe, isOutput, resourceType)) {
                return field;
            }
        }

        return null;
    }

    private static Method findAndValidateMethod(Object recipe, String methodName, boolean isOutput, ResourceType resourceType, Level level) {
        try {
            Method method = findAnyMethod(recipe.getClass(), methodName);
            if (method == null) return null;
            method.setAccessible(true);
            if (validateMethod(method, recipe, isOutput, resourceType, level)) return method;
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean validateMethod(Method method, Object recipe, boolean isOutput, ResourceType resourceType, Level level) {
        try {
            Object result = invokeMethod(method, recipe, level);
            return validateResult(result, isOutput, resourceType);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean validateField(Field field, Object recipe, boolean isOutput, ResourceType resourceType) {
        try {
            field.setAccessible(true);
            Object value = field.get(recipe);
            return validateResult(value, isOutput, resourceType);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean validateResult(Object result, boolean isOutput, ResourceType resourceType) {
        Object resolved = resolveValue(result);
        if (resolved == null) {
            return false;
        }
        if (resolved != result) {
            return validateResult(resolved, isOutput, resourceType);
        }

        if (isOutput) {
            return resourceType == ResourceType.ITEM ?
                    !deepFindItemStacks(resolved, 0).isEmpty() :
                    !deepFindFluidStacks(resolved, 0).isEmpty();
        } else {
            return resourceType == ResourceType.ITEM ?
                    !deepFindItemStackLists(resolved, 0).isEmpty() :
                    !deepFindFluidStackLists(resolved, 0).isEmpty();
        }
    }

    private static List<ItemStack> deepFindItemStacks(Object obj, int depth) {
        if (obj == null || depth > MAX_RECURSION_DEPTH) return new ArrayList<>();
        if (obj instanceof ItemStack stack) return stack.isEmpty() ? new ArrayList<>() : List.of(stack);
        if (obj instanceof Ingredient ingredient) {
            return Arrays.stream(ingredient.getItems()).filter(s -> !s.isEmpty()).toList();
        }
        String className = obj.getClass().getName();
        if (className.contains("ItemStackIngredient")) {
            try {
                Method representations = obj.getClass().getMethod("getRepresentations");
                Object result = representations.invoke(obj);
                List<ItemStack> stacks = extractItemStacks(result, depth + 1);
                if (!stacks.isEmpty()) {
                    return stacks;
                }
            } catch (Exception ignored) {}
        }
        if (className.contains("ItemStackOutput") || className.contains("OutputIngredient")) {
            List<ItemStack> stacks = extractStacksFromProvider(obj, depth,
                    "getOutput", "getOutputs", "getRepresentations", "getDefinition", "getItem", "getResult");
            if (!stacks.isEmpty()) {
                return stacks;
            }
        }
        if (obj.getClass().isRecord()) {
            List<ItemStack> recordStacks = collectFromRecord(obj, depth, AdaptiveRecipeConverter::deepFindItemStacks);
            if (!recordStacks.isEmpty()) {
                return recordStacks;
            }
        }
        if (obj instanceof Collection<?> coll) {
            List<ItemStack> result = new ArrayList<>();
            for (Object item : coll) result.addAll(deepFindItemStacks(item, depth + 1));
            return result;
        }
        if (obj.getClass().isArray()) {
            List<ItemStack> result = new ArrayList<>();
            for (Object item : (Object[]) obj) result.addAll(deepFindItemStacks(item, depth + 1));
            return result;
        }
        for (Method method : obj.getClass().getMethods()) {
            if (method.getParameterCount() != 0) continue;
            String name = method.getName();
            if (!(name.startsWith("get") || name.startsWith("as") || name.startsWith("to")
                    || name.toLowerCase(Locale.ROOT).contains("output")
                    || name.toLowerCase(Locale.ROOT).contains("result"))) continue;
            if (name.equals("getClass") || name.equals("toString")) continue;
            try {
                method.setAccessible(true);
                List<ItemStack> found = deepFindItemStacks(method.invoke(obj), depth + 1);
                if (!found.isEmpty()) return found;
            } catch (Exception ignored) {}
        }
        return new ArrayList<>();
    }

    private static List<FluidStack> deepFindFluidStacks(Object obj, int depth) {
        return findRecursive(obj, depth, (o, d) -> {
            if (o instanceof FluidStack stack && !stack.isEmpty()) {
                return List.of(stack);
            }
            return null;
        });
    }

    private static List<List<ItemStack>> deepFindItemStackLists(Object obj, int depth) {
        if (obj == null || depth > MAX_RECURSION_DEPTH) return new ArrayList<>();
        if (obj instanceof Ingredient ingredient) {
            List<ItemStack> stacks = Arrays.stream(ingredient.getItems()).filter(s -> !s.isEmpty()).toList();
            return stacks.isEmpty() ? new ArrayList<>() : List.of(stacks);
        }
        String className = obj.getClass().getName();
        if (className.contains("ItemStackIngredient")) {
            try {
                Method representations = obj.getClass().getMethod("getRepresentations");
                Object result = representations.invoke(obj);
                List<ItemStack> stacks = extractItemStacks(result, depth + 1);
                if (!stacks.isEmpty()) {
                    return List.of(stacks);
                }
            } catch (Exception ignored) {}
        }
        if (className.contains("ItemStackOutput") || className.contains("OutputIngredient")) {
            List<ItemStack> stacks = extractStacksFromProvider(obj, depth,
                    "getOutput", "getOutputs", "getRepresentations", "getDefinition", "getItem", "getResult");
            if (!stacks.isEmpty()) {
                return List.of(stacks);
            }
        }
        if (obj.getClass().isRecord()) {
            List<List<ItemStack>> recordStacks = collectFromRecord(obj, depth, AdaptiveRecipeConverter::deepFindItemStackLists);
            if (!recordStacks.isEmpty()) {
                return recordStacks;
            }
            List<ItemStack> flatStacks = collectFromRecord(obj, depth, AdaptiveRecipeConverter::deepFindItemStacks);
            if (!flatStacks.isEmpty()) {
                return List.of(flatStacks);
            }
        }
        if (obj instanceof Collection<?> coll && !coll.isEmpty()) {
            Object first = coll.iterator().next();
            if (first instanceof Ingredient) {
                List<List<ItemStack>> result = new ArrayList<>();
                for (Object item : coll) {
                    if (item instanceof Ingredient ing) {
                        List<ItemStack> stacks = Arrays.stream(ing.getItems()).filter(s -> !s.isEmpty()).toList();
                        if (!stacks.isEmpty()) result.add(stacks);
                    }
                }
                return result;
            }
            if (first instanceof ItemStack) {
                List<ItemStack> stacks = coll.stream().map(i -> (ItemStack) i).filter(s -> !s.isEmpty()).toList();
                return stacks.isEmpty() ? new ArrayList<>() : List.of(stacks);
            }
            if (first instanceof Collection) {
                List<List<ItemStack>> result = new ArrayList<>();
                for (Object inner : coll) result.addAll(deepFindItemStackLists(inner, depth + 1));
                return result;
            }
            List<ItemStack> extracted = new ArrayList<>();
            for (Object item : coll) extracted.addAll(deepFindItemStacks(item, depth + 1));
            if (!extracted.isEmpty()) return List.of(extracted);
        }
        for (Method method : obj.getClass().getMethods()) {
            if (method.getParameterCount() != 0) continue;
            String name = method.getName();
            if (!(name.startsWith("get") || name.startsWith("as") || name.toLowerCase(Locale.ROOT).contains("output") || name.toLowerCase(Locale.ROOT).contains("result"))) continue;
            if (name.equals("getClass")) continue;
            try {
                method.setAccessible(true);
                List<List<ItemStack>> found = deepFindItemStackLists(method.invoke(obj), depth + 1);
                if (!found.isEmpty()) return found;
            } catch (Exception ignored) {}
        }
        return new ArrayList<>();
    }

    private static List<List<FluidStack>> deepFindFluidStackLists(Object obj, int depth) {
        if (obj == null || depth > MAX_RECURSION_DEPTH) return new ArrayList<>();

        String className = obj.getClass().getName();

        // Mekanism FluidStackIngredient
        if (className.contains("FluidStackIngredient")) {
            try {
                Object result = obj.getClass().getMethod("getRepresentations").invoke(obj);
                if (result instanceof List<?> list) {
                    // ✅ БЕЗ ПРОВЕРКИ isActualFluid - берём ВСЁ!
                    List<FluidStack> stacks = list.stream()
                            .filter(i -> i instanceof FluidStack && !((FluidStack) i).isEmpty())
                            .map(i -> (FluidStack) i)
                            .toList();
                    return stacks.isEmpty() ? new ArrayList<>() : List.of(stacks);
                }
            } catch (Exception ignored) {}
        }

        if (obj instanceof Collection<?> coll && !coll.isEmpty()) {
            Object first = coll.iterator().next();

            if (first instanceof FluidStack) {
                // ✅ БЕЗ ПРОВЕРКИ - берём всё!
                List<FluidStack> stacks = coll.stream()
                        .map(i -> (FluidStack) i)
                        .filter(s -> !s.isEmpty())
                        .toList();
                return stacks.isEmpty() ? new ArrayList<>() : List.of(stacks);
            }

            if (first instanceof Collection) {
                List<List<FluidStack>> result = new ArrayList<>();
                for (Object inner : coll) {
                    result.addAll(deepFindFluidStackLists(inner, depth + 1));
                }
                return result;
            }

            List<FluidStack> extracted = new ArrayList<>();
            for (Object item : coll) {
                extracted.addAll(deepFindFluidStacks(item, depth + 1));
            }
            if (!extracted.isEmpty()) return List.of(extracted);
        }

        if (obj.getClass().isRecord()) {
            List<List<FluidStack>> recordLists = collectFromRecord(obj, depth, AdaptiveRecipeConverter::deepFindFluidStackLists);
            if (!recordLists.isEmpty()) {
                return recordLists;
            }
            List<FluidStack> flatStacks = collectFromRecord(obj, depth, AdaptiveRecipeConverter::deepFindFluidStacks);
            if (!flatStacks.isEmpty()) {
                return List.of(flatStacks);
            }
        }

        for (Method method : obj.getClass().getMethods()) {
            if (method.getParameterCount() != 0 || !method.getName().startsWith("get") && !method.getName().startsWith("as")) continue;
            if (method.getName().equals("getClass")) continue;
            try {
                method.setAccessible(true);
                List<List<FluidStack>> found = deepFindFluidStackLists(method.invoke(obj), depth + 1);
                if (!found.isEmpty()) return found;
            } catch (Exception ignored) {}
        }

        return new ArrayList<>();
    }

    private static <T> List<T> collectFromRecord(Object obj, int depth, BiFunction<Object, Integer, List<T>> extractor) {
        if (!obj.getClass().isRecord()) return Collections.emptyList();
        List<T> results = new ArrayList<>();
        try {
            for (RecordComponent component : obj.getClass().getRecordComponents()) {
                Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                Object value = accessor.invoke(obj);
                List<T> nested = extractor.apply(value, depth + 1);
                if (!nested.isEmpty()) {
                    results.addAll(nested);
                }
            }
        } catch (Exception ignored) {}
        return results;
    }

    private static List<ItemStack> extractStacksFromProvider(Object provider, int depth, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                Method method = findAnyMethod(provider.getClass(), methodName);
                if (method == null) continue;
                method.setAccessible(true);
                Object result = method.invoke(provider);
                List<ItemStack> stacks = extractItemStacks(result, depth + 1);
                if (!stacks.isEmpty()) {
                    return stacks;
                }
            } catch (Exception ignored) {}
        }
        return Collections.emptyList();
    }

    public static RecipeType<?> extractRecipeType(Object recipe) {
        if (recipe == null) return null;
        Object actual = unwrapRecipeHolder(recipe);

        if (actual instanceof net.minecraft.world.item.crafting.Recipe<?> vanillaRecipe) {
            return vanillaRecipe.getType();
        }

        String[] candidates = {
                "getRecipeType", "recipeType", "getType", "type", "getRecipe", "recipe",
                "getJeiRecipeType", "jeiRecipeType", "getViewerType", "viewerType"
        };

        for (String methodName : candidates) {
            try {
                Method method = findAnyMethod(actual.getClass(), methodName);
                if (method == null || method.getParameterCount() != 0) continue;
                method.setAccessible(true);
                Object value = method.invoke(actual);
                RecipeType<?> type = coerceRecipeType(value);
                if (type != null) {
                    return type;
                }
            } catch (Exception ignored) {}
        }

        RecipeType<?> fromFields = extractRecipeTypeFromFields(actual);
        if (fromFields != null) {
            return fromFields;
        }

        return extractRecipeTypeFromAccessors(actual);
    }

    private static RecipeType<?> extractRecipeTypeFromFields(Object actual) {
        for (Field field : getAllFields(actual.getClass())) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            String lower = field.getName().toLowerCase(Locale.ROOT);
            if (!(lower.contains("recipetype") || lower.equals("type") || lower.contains("viewer"))) continue;
            try {
                field.setAccessible(true);
                Object value = resolveValue(field.get(actual));
                RecipeType<?> type = coerceRecipeType(value);
                if (type != null) {
                    return type;
                }
            } catch (Exception ignored) {}
        }

        if (actual.getClass().isRecord()) {
            for (RecordComponent component : actual.getClass().getRecordComponents()) {
                String lower = component.getName().toLowerCase(Locale.ROOT);
                if (!(lower.contains("recipetype") || lower.equals("type") || lower.contains("viewer"))) continue;
                try {
                    Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    Object value = resolveValue(accessor.invoke(actual));
                    RecipeType<?> type = coerceRecipeType(value);
                    if (type != null) {
                        return type;
                    }
                } catch (Exception ignored) {}
            }
        }

        return null;
    }

    private static RecipeType<?> coerceRecipeType(Object value) {
        if (value instanceof RecipeType<?> recipeType) {
            return recipeType;
        }
        if (value instanceof Optional<?> optional && optional.isPresent()) {
            return coerceRecipeType(optional.get());
        }
        if (value instanceof ResourceLocation rl) {
            return BuiltInRegistries.RECIPE_TYPE.get(rl);
        }
        if (value instanceof String str) {
            try {
                ResourceLocation rl = ResourceLocation.parse(str);
                return BuiltInRegistries.RECIPE_TYPE.get(rl);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static List<ItemStack> extractItemStacks(Object obj, int depth) {
        if (obj == null || depth > MAX_RECURSION_DEPTH) return Collections.emptyList();
        switch (obj) {
            case ItemStack stack -> {
                return stack.isEmpty() ? Collections.emptyList() : List.of(stack);
            }
            case ItemStack[] array -> {
                return Arrays.stream(array)
                        .filter(item -> item != null && !item.isEmpty())
                        .toList();
            }
            case Collection<?> coll -> {
                List<ItemStack> stacks = new ArrayList<>();
                for (Object element : coll) {
                    List<ItemStack> nested = extractItemStacks(element, depth + 1);
                    if (!nested.isEmpty()) stacks.addAll(nested);
                }
                // Mekanism recipe outputs can be wrapped in a MekanismRecipeOutput object
                if (obj.getClass().getName().contains("MekanismRecipeOutput")) {
                    try {
                        Method getStacks = obj.getClass().getMethod("getStacks");
                        Object result = getStacks.invoke(obj);
                        if (result instanceof ItemStack[] stacksArray) {
                            stacks.addAll(Arrays.stream(stacksArray).filter(s -> !s.isEmpty()).toList());
                        }
                    } catch (Exception ignored) {
                    }
                }
                return stacks;
            }
            default -> {
            }
        }
        if (obj.getClass().isRecord()) {
            List<ItemStack> recordStacks = collectFromRecord(obj, depth, AdaptiveRecipeConverter::deepFindItemStacks);
            if (!recordStacks.isEmpty()) {
                return recordStacks;
            }
        }
        return deepFindItemStacks(obj, depth + 1);
    }


    private static List<ItemStack> extractMekanismItemOutputs(Object recipe) {
        if (recipe == null) return Collections.emptyList();
        String className = recipe.getClass().getName();
        if (!className.startsWith("mekanism")) {
            return Collections.emptyList();
        }

        // Try common Mekanism recipe output definitions (getOutputDefinition -> ItemStackOutput)
        try {
            Method outputDefinition = findAnyMethod(recipe.getClass(), "getOutputDefinition");
            if (outputDefinition != null) {
                outputDefinition.setAccessible(true);
                Object definition = outputDefinition.invoke(recipe);
                List<ItemStack> stacks = extractItemStacks(definition, 0);
                if (!stacks.isEmpty()) {
                    return stacks;
                }
                stacks = extractStacksFromProvider(definition, 0,
                        "getPrimaryOutput", "getItemOutput", "getOutput", "getResult", "getOutputs");
                if (!stacks.isEmpty()) {
                    return stacks;
                }
            }
        } catch (Exception ignored) {}

        // Fallback: call direct output accessors on the recipe instance itself
        List<ItemStack> direct = extractStacksFromProvider(recipe, 0,
                "getPrimaryOutput", "getItemOutput", "getOutput", "getResult", "getOutputs",
                "primaryOutput", "itemOutput", "output", "result");
        if (!direct.isEmpty()) {
            return direct;
        }

        return Collections.emptyList();
    }




    private static Method findAnyMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    private static Object resolveValue(Object value) {
        if (value instanceof Optional<?> optional) {
            return optional.orElse(null);
        }
        if (value instanceof Supplier<?> supplier) {
            try {
                return supplier.get();
            } catch (Exception ignored) {
                return null;
            }
        }
        return value;
    }

    private static Object[] prepareArguments(Method method, Level level) {
        int paramCount = method.getParameterCount();
        if (paramCount == 0) {
            return new Object[0];
        }

        Object[] args = new Object[paramCount];
        Object registryAccess = level != null ? level.registryAccess() : null;

        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> paramType = parameterTypes[i];

            if (paramType.isPrimitive()) {
                args[i] = primitiveDefault(paramType);
                if (args[i] == null) {
                    return null;
                }
                continue;
            }

            if (paramType.isInstance(level)) {
                args[i] = level;
                continue;
            }
            if (level != null && paramType.isAssignableFrom(level.getClass())) {
                args[i] = level;
                continue;
            }

            if (registryAccess != null && (paramType.isInstance(registryAccess) || paramType.isAssignableFrom(registryAccess.getClass())
                    || paramType.getName().contains("RegistryAccess") || paramType.getName().contains("HolderLookup"))) {
                args[i] = registryAccess;
                continue;
            }

            args[i] = null;
        }

        return args;
    }

    private static Object primitiveDefault(Class<?> primitive) {
        if (primitive == boolean.class) return false;
        if (primitive == byte.class) return (byte) 0;
        if (primitive == short.class) return (short) 0;
        if (primitive == int.class) return 0;
        if (primitive == long.class) return 0L;
        if (primitive == float.class) return 0F;
        if (primitive == double.class) return 0D;
        if (primitive == char.class) return (char) 0;
        return null;
    }

    private static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            Field[] declared = current.getDeclaredFields();
            Collections.addAll(fields, declared);
            current = current.getSuperclass();
        }
        return fields;
    }

    private static Field findAnyField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equals(name)) {
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    interface Accessor {
        Object extract(Object recipe, Level level) throws Exception;
    }

    static class MethodAccessor implements Accessor {
        private final Method method;

        MethodAccessor(Method method) {
            this.method = method;
        }

        @Override
        public Object extract(Object recipe, Level level) throws Exception {
            return invokeMethod(method, recipe, level);
        }
    }

    static class FieldAccessor implements Accessor {
        private final Field field;

        FieldAccessor(Field field) {
            this.field = field;
        }

        @Override
        public Object extract(Object recipe, Level level) throws Exception {
            field.setAccessible(true);
            return field.get(recipe);
        }
    }

    static class RecipeAdapter {
        final Accessor itemOutputAccessor;
        final Accessor fluidOutputAccessor;
        final Accessor itemInputAccessor;
        final Accessor fluidInputAccessor;

        RecipeAdapter(Accessor itemOutputAccessor, Accessor fluidOutputAccessor,
                      Accessor itemInputAccessor, Accessor fluidInputAccessor) {
            this.itemOutputAccessor = itemOutputAccessor;
            this.fluidOutputAccessor = fluidOutputAccessor;
            this.itemInputAccessor = itemInputAccessor;
            this.fluidInputAccessor = fluidInputAccessor;
        }
    }
}