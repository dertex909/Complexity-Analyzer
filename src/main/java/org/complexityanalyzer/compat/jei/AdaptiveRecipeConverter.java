package org.complexityanalyzer.compat.jei;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.graph.Chemical;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeNode;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

public class AdaptiveRecipeConverter {

    private static final Map<Class<?>, RecipeAdapter> LEARNED_ADAPTERS = new ConcurrentHashMap<>();
    private static final int MAX_RECURSION_DEPTH = 5;
    private static final boolean VERBOSE_DEBUG = false;

    public static RecipeNode convertRecipe(net.minecraft.world.item.crafting.Recipe<?> recipe, Level level) {
        // Шаг 1: Извлекаем все возможные выходы.
        List<ItemStack> itemOutputs = extractOutputs(recipe, level);
        List<FluidStack> fluidOutputs = extractFluidOutputs(recipe, level);
        List<ChemicalStack> chemicalOutputs = extractChemicalOutputs(recipe, level);

        if (itemOutputs.isEmpty()) {
            itemOutputs = extractMekanismItemOutputs(recipe);
        }

        if (itemOutputs.isEmpty() && (!fluidOutputs.isEmpty() || !chemicalOutputs.isEmpty())) {
            String recipeClassName = recipe.getClass().getName();
            if (recipeClassName.startsWith("mekanism")) {
                ComplexityAnalyzer.LOGGER.debug("[JEI] Mekanism recipe {} has no item outputs (fluids: {}, chemicals: {})",
                        recipeClassName, fluidOutputs.size(), chemicalOutputs.size());
            }
        }

        if (itemOutputs.isEmpty() && fluidOutputs.isEmpty() && chemicalOutputs.isEmpty()) {
            if (VERBOSE_DEBUG) ComplexityAnalyzer.LOGGER.debug("Recipe has no outputs, skipping recipe of type: {}", recipe.getType());
            return null;
        }

        // Шаг 2: Извлекаем все возможные входы.
        List<List<ItemStack>> itemInputs = extractInputs(recipe, level);
        List<List<FluidStack>> fluidInputs = extractFluidInputs(recipe, level);
        List<List<ChemicalStack>> chemicalInputs = extractChemicalInputs(recipe, level);

        if (itemInputs.isEmpty() && fluidInputs.isEmpty() && chemicalInputs.isEmpty()) {
            if (VERBOSE_DEBUG) ComplexityAnalyzer.LOGGER.debug("Recipe has no inputs, skipping recipe of type: {}", recipe.getType());
            return null;
        }

        // Шаг 3: Создаем строитель узла RecipeNode.
        RecipeNode.Builder builder;
        Item resultItem;

        if (!itemOutputs.isEmpty()) {
            resultItem = itemOutputs.getFirst().getItem();
            builder = new RecipeNode.Builder(resultItem);
        } else {
            resultItem = Items.BARRIER;
            builder = new RecipeNode.Builder(resultItem).isPlaceholder(true);
            if (!chemicalOutputs.isEmpty()) builder.placeholderId(chemicalOutputs.getFirst().getChemical().getFullId());
            else if (!fluidOutputs.isEmpty()) builder.placeholderId(BuiltInRegistries.FLUID.getKey(fluidOutputs.getFirst().getFluid()).toString());
        }

        builder.itemOutputs(itemOutputs).fluidOutputs(fluidOutputs).chemicalOutputs(chemicalOutputs);

        // Шаг 4: Определяем категорию рецепта.
        List<Ingredient> ingredients = itemInputs.stream().filter(l -> !l.isEmpty()).map(l -> Ingredient.of(l.toArray(new ItemStack[0]))).toList();
        RecipeCategory category = org.complexityanalyzer.graph.GraphBuilder.classifyRecipe(recipe, resultItem, ingredients);
        if (category == RecipeCategory.UNPROCESSABLE) return null;

        builder.recipeType(recipe.getType()).category(category);

        // Шаг 5: Добавляем все найденные ингредиенты.
        itemInputs.forEach(group -> { if (!group.isEmpty()) builder.addIngredient(group.stream().map(ItemStack::getItem).distinct().toList(), group.getFirst().getCount()); });
        fluidInputs.forEach(group -> { if (!group.isEmpty()) builder.addFluidIngredient(group.stream().map(FluidStack::getFluid).distinct().toList(), group.getFirst().getAmount()); });
        chemicalInputs.forEach(group -> { if (!group.isEmpty()) builder.addChemicalIngredient(group.stream().map(ChemicalStack::getChemical).distinct().toList(), group.getFirst().getAmount()); });

        RecipeNode node = builder.build();

        if (node.getIngredients().isEmpty() && node.getFluidIngredients().isEmpty() && node.getChemicalIngredients().isEmpty()) return null;

        if (VERBOSE_DEBUG && (node.hasFluidIngredients() || node.hasChemicalIngredients())) {
            String name = node.isPlaceholder() ? node.getPlaceholderId() : BuiltInRegistries.ITEM.getKey(resultItem).toString();
            ComplexityAnalyzer.LOGGER.debug("Converted recipe for {}: {} items, {} fluids, {} chemicals.", name, node.getIngredientSlotCount(), node.getFluidIngredientSlotCount(), node.getChemicalIngredientSlotCount());
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
        }
        return results;
    }

    public static List<ItemStack> extractOutputs(Object recipe, Level level) {
        Object actualRecipe = unwrapRecipeHolder(recipe);
        RecipeAdapter adapter = getAdapter(actualRecipe, true, level);
        if (adapter.outputMethod == null) return new ArrayList<>();
        try {
            Object result = invokeMethod(adapter.outputMethod, actualRecipe, level);
            return deepFindItemStacks(result, 0);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static List<FluidStack> extractFluidOutputs(Object recipe, Level level) {
        Object actualRecipe = unwrapRecipeHolder(recipe);
        RecipeAdapter adapter = getAdapter(actualRecipe, true, level);
        if (adapter.outputMethod == null) return new ArrayList<>();
        try {
            Object result = invokeMethod(adapter.outputMethod, actualRecipe, level);
            return deepFindFluidStacks(result, 0);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static List<List<ItemStack>> extractInputs(Object recipe, Level level) {
        Object actualRecipe = unwrapRecipeHolder(recipe);
        RecipeAdapter adapter = getAdapter(actualRecipe, false, level);
        if (adapter.inputMethod == null) return new ArrayList<>();
        try {
            Object result = invokeMethod(adapter.inputMethod, actualRecipe, level);
            return deepFindItemStackLists(result, 0);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static List<List<FluidStack>> extractFluidInputs(Object recipe, Level level) {
        Object actualRecipe = unwrapRecipeHolder(recipe);
        RecipeAdapter adapter = getAdapter(actualRecipe, false, level);
        if (adapter.inputMethod == null) return new ArrayList<>();
        try {
            Object result = invokeMethod(adapter.inputMethod, actualRecipe, level);
            return deepFindFluidStackLists(result, 0);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static List<ChemicalStack> extractChemicalOutputs(Object recipe, Level level) {
        Object actualRecipe = unwrapRecipeHolder(recipe);
        RecipeAdapter adapter = getAdapter(actualRecipe, true, level);
        if (adapter.outputMethod == null) return new ArrayList<>();
        try {
            Object result = invokeMethod(adapter.outputMethod, actualRecipe, level);
            return deepFindChemicalStacks(result, 0);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static List<List<ChemicalStack>> extractChemicalInputs(Object recipe, Level level) {
        Object actualRecipe = unwrapRecipeHolder(recipe);
        RecipeAdapter adapter = getAdapter(actualRecipe, false, level);
        if (adapter.inputMethod == null) return new ArrayList<>();
        try {
            Object result = invokeMethod(adapter.inputMethod, actualRecipe, level);
            return deepFindChemicalStackLists(result, 0);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static RecipeAdapter getAdapter(Object recipe, boolean isOutput, Level level) {
        Class<?> recipeClass = recipe.getClass();
        RecipeAdapter adapter = LEARNED_ADAPTERS.computeIfAbsent(recipeClass, clazz -> new RecipeAdapter(null, null));

        if (isOutput && adapter.outputMethod == null) {
            Method method = learnMethod(recipe, true, level);
            adapter = new RecipeAdapter(method, adapter.inputMethod);
            LEARNED_ADAPTERS.put(recipeClass, adapter);
        } else if (!isOutput && adapter.inputMethod == null) {
            Method method = learnMethod(recipe, false, level);
            adapter = new RecipeAdapter(adapter.outputMethod, method);
            LEARNED_ADAPTERS.put(recipeClass, adapter);
        }
        return adapter;
    }

    private static Object invokeMethod(Method method, Object target, Level level) throws Exception {
        if (method.getParameterCount() == 0) {
            return method.invoke(target);
        } else if (method.getParameterCount() == 1) {
            Class<?> paramType = method.getParameterTypes()[0];
            if (paramType.getName().contains("RegistryAccess")) {
                return method.invoke(target, level.registryAccess());
            }
            return method.invoke(target, (Object) null);
        }
        return null;
    }

    private static Object unwrapRecipeHolder(Object obj) {
        if (obj instanceof net.minecraft.world.item.crafting.RecipeHolder<?> holder) {
            return holder.value();
        }
        return obj;
    }

    private static Method learnMethod(Object recipe, boolean isOutput, Level level) {
        String[] candidateMethods = isOutput
                ? new String[]{
                "getLeftGasOutput", "getRightGasOutput", "getLeftOutput", "getRightOutput", "getLeftGas", "getRightGas",
                "getGasOutput", "getGasOutputs", "getChemicalOutput", "getChemicalOutputs", "gasOutput", "gasOutputs",
                "chemicalOutput", "chemicalOutputs", "getInfusionOutput", "getInfusionOutputs", "getPigmentOutput", "getPigmentOutputs",
                "getSlurryOutput", "getSlurryOutputs", "fetchChemicalOutput", "retrieveChemicalOutput", "outputGas", "outputChemical",
                "outputsGas", "outputsChemical", "resultChemical", "producedGas", "producedChemical",
                "getFluidOutput", "getFluidOutputs", "outputFluid", "outputFluids", "getOutputFluids", "getFluidResult",
                "getFluidResults", "fetchFluidOutput", "retrieveFluidOutput", "fluidOutput", "fluidOutputs", "producedFluid",
                "producedFluids", "resultFluid", "outputsFluid", "getResultItem", "getResultItems", "getItemOutput",
                "getItemOutputs", "itemOutput", "itemOutputs", "stackOutput", "stackOutputs", "getStack", "getStacks",
                "outputItem", "outputItems", "resultStack", "resultStacks", "producedItem", "producedItems",
                "getOutputStack", "getOutput", "getOutputs", "getResult", "getResults", "getProduct", "getProducts",
                "output", "outputs", "result", "results", "product", "products", "fetchOutput", "fetchOutputs",
                "retrieveOutput", "retrieveOutputs", "produce", "produces", "produced", "getProduce", "create", "creates",
                "getCreate", "make", "makes", "getMake", "yield", "getYield", "generate", "generated", "getGenerate",
                "getProcessingOutput", "getRecipeOutput", "getMainOutput", "getSecondaryOutput", "getBonusOutput",
                "getPrimaryOutput", "getByproduct", "getByproducts", "getResultDefinition", "getOutputDefinition",
                "getOutputData", "getResultData", "getOutputSlot", "getOutputSlots", "getOutputContainer", "getOutputContents"
        }
                : new String[]{
                "getLeftGasInput", "getRightGasInput", "getGasInput", "getGasInputs", "getChemicalInput", "getChemicalInputs",
                "gasInput", "gasInputs", "chemicalInput", "chemicalInputs", "getInfusionInput", "getInfusionInputs",
                "getPigmentInput", "getPigmentInputs", "getSlurryInput", "getSlurryInputs", "fetchChemicalInput", "retrieveChemicalInput",
                "inputGas", "inputChemical", "inputsGas", "inputsChemical", "ingredientGas", "ingredientChemical",
                "getFluidInput", "getFluidInputs", "inputFluid", "inputFluids", "getInputFluids", "getFluidIngredient",
                "getFluidIngredients", "fetchFluidInput", "retrieveFluidInput", "fluidInput", "fluidInputs", "ingredientFluid",
                "ingredientFluids", "inputsFluid", "getInputItem", "getInputItems", "getItemInput", "getItemInputs",
                "itemInput", "itemInputs", "stackInput", "stackInputs", "getInputStack", "getInputStacks", "inputItem",
                "inputItems", "ingredientItem", "ingredientItems", "getIngredientItem", "getIngredientItems", "getIngredientStack",
                "getInput", "getInputs", "getIngredient", "getIngredients", "input", "inputs", "ingredient", "ingredients",
                "fetchInput", "fetchInputs", "retrieveInput", "retrieveInputs", "consume", "consumes", "consumed", "getConsume",
                "require", "requires", "required", "getRequire", "need", "needs", "needed", "getNeed", "use", "uses",
                "used", "getUse", "supply", "supplies", "getSupply", "getSupplies", "source", "sources", "getSource",
                "getSources", "getProcessingInput", "getRecipeInput", "getMainInput", "getSecondaryInput", "getCatalystInput",
                "getInputDefinition", "getIngredientDefinition", "getInputData", "getIngredientData", "getInputSlot",
                "getInputSlots", "getIngredientSlot", "getIngredientSlots", "getInputContainer", "getInputContents",
                "getInputPair"
        };

        // Фаза 1: Точное совпадение имени
        for (String methodName : candidateMethods) {
            Method method = findAndValidateMethod(recipe, methodName, isOutput, level);
            if (method != null) return method;
        }

        // Фаза 2: Wildcard поиск
        String[] wildcards = isOutput
                ? new String[]{"gas", "chemical", "infusion", "pigment", "slurry", "fluid", "item", "stack", "output", "result", "produce", "craft", "create", "yield", "generate"}
                : new String[]{"gas", "chemical", "infusion", "pigment", "slurry", "fluid", "item", "stack", "input", "ingredient", "require", "consume", "use", "need", "supply", "source"};

        for (String wildcard : wildcards) {
            for (Method method : recipe.getClass().getMethods()) {
                if (method.getParameterCount() > 1 || method.getName().equals("getClass") || method.getName().equals("toString")) continue;
                if (Arrays.stream(candidateMethods).anyMatch(m -> m.equals(method.getName()))) continue; // Already checked
                if (method.getName().toLowerCase().contains(wildcard)) {
                    Method validated = findAndValidateMethod(recipe, method.getName(), isOutput, level);
                    if (validated != null) return validated;
                }
            }
        }
        return null;
    }

    private static Method findAndValidateMethod(Object recipe, String methodName, boolean isOutput, Level level) {
        try {
            Method method = findAnyMethod(recipe.getClass(), methodName);
            if (method == null) return null;
            method.setAccessible(true);
            Object result = invokeMethod(method, recipe, level);
            if (result == null) return null;

            if (isOutput) {
                if (!deepFindChemicalStacks(result, 0).isEmpty() || !deepFindFluidStacks(result, 0).isEmpty() || !deepFindItemStacks(result, 0).isEmpty()) {
                    return method;
                }
            } else {
                if (!deepFindChemicalStackLists(result, 0).isEmpty() || !deepFindFluidStackLists(result, 0).isEmpty() || !deepFindItemStackLists(result, 0).isEmpty()) {
                    return method;
                }
            }
        } catch (Exception ignored) {}
        return null;
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
            if (o instanceof FluidStack stack && !stack.isEmpty() && IngredientTypeDetector.isActualFluid(stack)) return List.of(stack);
            if (IngredientTypeDetector.isChemicalIngredient(o)) return Collections.emptyList();
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
        if (className.contains("FluidStackIngredient")) {
            try {
                Object result = obj.getClass().getMethod("getRepresentations").invoke(obj);
                if (result instanceof List<?> list) {
                    List<FluidStack> stacks = list.stream().filter(i -> i instanceof FluidStack && !((FluidStack) i).isEmpty() && IngredientTypeDetector.isActualFluid(i)).map(i -> (FluidStack) i).toList();
                    return stacks.isEmpty() ? new ArrayList<>() : List.of(stacks);
                }
            } catch (Exception ignored) {}
        }
        if (obj instanceof Collection<?> coll && !coll.isEmpty()) {
            Object first = coll.iterator().next();
            if (first instanceof FluidStack) {
                List<FluidStack> stacks = coll.stream().map(i -> (FluidStack) i).filter(s -> !s.isEmpty()).toList();
                return stacks.isEmpty() ? new ArrayList<>() : List.of(stacks);
            }
            if (first instanceof Collection) {
                List<List<FluidStack>> result = new ArrayList<>();
                for (Object inner : coll) result.addAll(deepFindFluidStackLists(inner, depth + 1));
                return result;
            }
            List<FluidStack> extracted = new ArrayList<>();
            for (Object item : coll) extracted.addAll(deepFindFluidStacks(item, depth + 1));
            if (!extracted.isEmpty()) return List.of(extracted);
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

    private static List<ItemStack> extractItemStacks(Object obj, int depth) {
        if (obj == null || depth > MAX_RECURSION_DEPTH) return Collections.emptyList();
        if (obj instanceof ItemStack stack) {
            return stack.isEmpty() ? Collections.emptyList() : List.of(stack);
        }
        if (obj instanceof ItemStack[] array) {
            return Arrays.stream(array)
                    .filter(item -> item != null && !item.isEmpty())
                    .toList();
        }
        if (obj instanceof Collection<?> coll) {
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
                } catch (Exception ignored) {}
            }
            return stacks;
        }
        return deepFindItemStacks(obj, depth + 1);
    }

    private static List<ChemicalStack> deepFindChemicalStacks(Object obj, int depth) {
        return findRecursive(obj, depth, (o, d) -> {
            if (o instanceof FluidStack fluidStack && !IngredientTypeDetector.isActualFluid(fluidStack)) {
                ChemicalStack converted = ChemicalStack.fromFluidStack(fluidStack);
                return converted != null && !converted.isEmpty() ? List.of(converted) : Collections.emptyList();
            }
            if (IngredientTypeDetector.isChemicalIngredient(o)) {
                ChemicalStack stack = ChemicalStack.fromObject(o);
                return stack != null && !stack.isEmpty() ? List.of(stack) : Collections.emptyList();
            }
            return null;
        });
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

    private static List<List<ChemicalStack>> deepFindChemicalStackLists(Object obj, int depth) {
        if (obj == null || depth > MAX_RECURSION_DEPTH) return new ArrayList<>();
        String className = obj.getClass().getName();
        if (className.contains("ChemicalStackIngredient")) {
            try {
                Object result = obj.getClass().getMethod("getRepresentations").invoke(obj);
                if (result instanceof List<?> list) {
                    List<ChemicalStack> stacks = new ArrayList<>();
                    for (Object item : list) {
                        ChemicalStack stack = ChemicalStack.fromObject(item);
                        if (stack != null && !stack.isEmpty()) stacks.add(stack);
                    }
                    return stacks.isEmpty() ? new ArrayList<>() : List.of(stacks);
                }
            } catch (Exception ignored) {}
        }
        if (obj instanceof Collection<?> coll && !coll.isEmpty()) {
            Object first = coll.iterator().next();
            String firstClassName = first.getClass().getName();
            if (firstClassName.contains("ChemicalStack") || firstClassName.contains("GasStack")) {
                List<ChemicalStack> stacks = new ArrayList<>();
                for (Object item : coll) {
                    ChemicalStack stack = ChemicalStack.fromObject(item);
                    if (stack != null && !stack.isEmpty()) stacks.add(stack);
                }
                return stacks.isEmpty() ? new ArrayList<>() : List.of(stacks);
            }
            if (first instanceof Collection) {
                List<List<ChemicalStack>> result = new ArrayList<>();
                for (Object inner : coll) result.addAll(deepFindChemicalStackLists(inner, depth + 1));
                return result;
            }
            List<ChemicalStack> extracted = new ArrayList<>();
            for (Object item : coll) extracted.addAll(deepFindChemicalStacks(item, depth + 1));
            if (!extracted.isEmpty()) return List.of(extracted);
        }
        for (Method method : obj.getClass().getMethods()) {
            if (method.getParameterCount() != 0 || !method.getName().startsWith("get") && !method.getName().startsWith("as")) continue;
            if (method.getName().equals("getClass")) continue;
            try {
                method.setAccessible(true);
                List<List<ChemicalStack>> found = deepFindChemicalStackLists(method.invoke(obj), depth + 1);
                if (!found.isEmpty()) return found;
            } catch (Exception ignored) {}
        }
        return new ArrayList<>();
    }

    public static class ChemicalStack {
        private final Chemical chemical;
        private final long amount;

        private ChemicalStack(Chemical chemical, long amount) {
            this.chemical = chemical;
            this.amount = amount;
        }

        public static ChemicalStack fromObject(Object obj) {
            if (obj == null) return null;
            Chemical chemical = Chemical.fromObject(obj);
            if (chemical == null) return null;
            try {
                Method getAmount = obj.getClass().getMethod("getAmount");
                long amount = ((Number) getAmount.invoke(obj)).longValue();
                return new ChemicalStack(chemical, amount);
            } catch (Exception e) {
                return new ChemicalStack(chemical, 0);
            }
        }

        public static ChemicalStack fromFluidStack(FluidStack fluidStack) {
            if (fluidStack == null) return null;
            Chemical chemical = Chemical.fromObject(fluidStack);
            if (chemical == null) chemical = Chemical.fromFluid(fluidStack.getFluid());
            return chemical == null ? null : new ChemicalStack(chemical, fluidStack.getAmount());
        }

        public Chemical getChemical() { return chemical; }
        public long getAmount() { return amount; }
        public boolean isEmpty() { return amount <= 0; }
        @Override public String toString() { return String.format("%s x%d", chemical, amount); }
    }

    private static Method findAnyMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    static class RecipeAdapter {
        final Method outputMethod;
        final Method inputMethod;
        RecipeAdapter(Method outputMethod, Method inputMethod) { this.outputMethod = outputMethod; this.inputMethod = inputMethod; }
    }
}