/*
 * Complexity Analyzer
 * Copyright (C) 2025 dertex909
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

package org.complexityanalyzer.compat.jei;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.MachineRegistry;
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
    private static MachineRegistry machineRegistry = null;

    private static final int MAX_RECURSION_DEPTH = 5;

    public static void setMachineRegistry(MachineRegistry registry) {
        machineRegistry = registry;
    }

    private static final List<String> OUTPUT_KEYWORDS = List.of(
            "output", "result", "product", "produce", "yield", "generate", "reward", "primary", "secondary", "byproduct"
    );
    private static final List<String> INPUT_KEYWORDS = List.of(
            "input", "ingredient", "require", "consume", "use", "need", "supply", "source", "catalyst", "cost"
    );

    public static List<ChemicalOutput> extractChemicalInputs(Object recipe) {
        try {
            Object actualRecipe = unwrapRecipeHolder(recipe);

             
            if (actualRecipe.getClass().isRecord()) {
                return extractChemicalInputsFromRecord(actualRecipe);
            }
             

             
            for (String methodName : Arrays.asList("getChemicalInput", "getChemicalInputs",
                    "getGasInput", "getGasInputs", "getLeftGasInput", "getRightGasInput",
                    "getLeftInput", "getRightInput", "getInput")) {
                try {
                    Method m = actualRecipe.getClass().getMethod(methodName);
                    Object result = m.invoke(actualRecipe);

                    if (result != null) {
                        List<ChemicalOutput> found = deepFindChemicalStacks(result);
                        if (!found.isEmpty()) return found;
                    }
                } catch (Exception ignored) {}
            }

        } catch (Exception ignored) {}

        return Collections.emptyList();
    }

     
    private static List<ChemicalOutput> extractChemicalInputsFromRecord(Object record) {
        List<ChemicalOutput> results = new ArrayList<>();

        try {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                String name = component.getName();

                 
                if (name.contains("input") || name.contains("Input") ||
                        name.equals("superHeatedCoolant") ||
                        name.contains("ingredient") || name.contains("source")) {

                    Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    Object value = accessor.invoke(record);

                    if (value != null) {
                         
                        ChemicalOutput extracted = tryExtractChemical(value);
                        if (extracted != null) {
                            results.add(extracted);
                        } else {
                             
                            results.addAll(deepFindChemicalStacks(value));
                        }
                    }
                }
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.debug("Failed to extract inputs from record: {}", e.getMessage());
        }

        return results;
    }

    public static List<ChemicalOutput> extractChemicalOutputs(Object recipe, Level level) {
        try {
            Object actualRecipe = unwrapRecipeHolder(recipe);

            if (actualRecipe.getClass().isRecord()) {
                return extractChemicalOutputsFromRecord(actualRecipe);
            }

            RecipeAdapter adapter = getAdapter(actualRecipe, true, ResourceType.CHEMICAL, level);

            if (adapter.chemicalOutputAccessor == null) {
                return new ArrayList<>();
            }

            Object result = adapter.chemicalOutputAccessor.extract(actualRecipe, level);

            return deepFindChemicalStacks(result);

        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.debug("Failed to extract chemical outputs: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

     
    private static List<ChemicalOutput> extractChemicalOutputsFromRecord(Object record) {
        List<ChemicalOutput> results = new ArrayList<>();

        try {
             
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                String name = component.getName();

                 
                if (name.contains("output") || name.contains("Output") ||
                        name.equals("steam") || name.equals("cooledCoolant") ||
                        name.contains("product") || name.contains("result")) {

                    Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    Object value = accessor.invoke(record);

                    if (value != null) {
                         
                        ChemicalOutput extracted = tryExtractChemical(value);
                        if (extracted != null) {
                            results.add(extracted);
                        } else {
                            List<ChemicalOutput> deep = deepFindChemicalStacks(value);
                            if (!deep.isEmpty()) {
                                results.addAll(deep);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.error("Failed to extract from record: ", e);
        }

        return results;
    }

    private static List<ChemicalOutput> deepFindChemicalStacks(Object obj) {
        Set<Object> visited = new HashSet<>();
        return deepFindChemicalStacksRecursive(obj, visited, 0);
    }

    private static List<ChemicalOutput> deepFindChemicalStacksRecursive(Object obj, Set<Object> visited, int depth) {
        if (obj == null || depth > 5) {
            return new ArrayList<>();
        }

        if (!visited.add(obj)) {
            return new ArrayList<>();
        }

        List<ChemicalOutput> results = new ArrayList<>();

        if (!obj.getClass().isRecord()) {
            ChemicalOutput directOutput = tryExtractChemical(obj);
            if (directOutput != null) {
                results.add(directOutput);
            }
        }

        if (obj instanceof Collection<?> coll) {
            for (Object item : coll) {
                results.addAll(deepFindChemicalStacksRecursive(item, visited, depth + 1));
            }
        }

         
        else if (obj.getClass().isArray()) {
            try {
                Object[] array = (Object[]) obj;
                for (Object item : array) {
                    results.addAll(deepFindChemicalStacksRecursive(item, visited, depth + 1));
                }
            } catch (ClassCastException ignored) {}
        }

         
        else if (obj.getClass().isRecord()) {
            try {
                for (RecordComponent component : obj.getClass().getRecordComponents()) {
                    Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    Object value = accessor.invoke(obj);

                     
                    results.addAll(deepFindChemicalStacksRecursive(value, visited, depth + 1));
                }
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.debug("Failed to process record: {}", e.getMessage());
            }
        }

         
        else if (!isPrimitive(obj)) {
             
            for (Method method : obj.getClass().getMethods()) {
                if (method.getParameterCount() != 0) continue;

                String name = method.getName();

                 
                if (!name.contains("Output") && !name.contains("output") &&
                        !name.contains("Chemical") && !name.contains("chemical") &&
                        !name.contains("Definition") && !name.contains("definition")) {
                    continue;
                }

                 
                if (name.equals("getClass") || name.equals("toString") ||
                        name.equals("hashCode") || name.equals("getName")) {
                    continue;
                }

                try {
                    method.setAccessible(true);
                    Object result = method.invoke(obj);

                    if (result != null && result != obj) {
                        results.addAll(deepFindChemicalStacksRecursive(result, visited, depth + 1));
                    }
                } catch (Exception ignored) {}
            }
        }

        return results;
    }
     
    private static boolean isPrimitive(Object obj) {
        return obj instanceof String || obj instanceof Number ||
                obj instanceof Boolean || obj instanceof Character ||
                obj.getClass().isPrimitive();
    }

    private static ChemicalOutput tryExtractChemical(Object obj) {
        if (obj == null) return null;

        String className = obj.getClass().getName();

         
        if (className.contains("ChemicalStackIngredient") ||
                (className.contains("Ingredient") && className.contains("Chemical"))) {

             
            try {
                Method getRepresentations = obj.getClass().getMethod("getRepresentations");
                Object result = getRepresentations.invoke(obj);

                if (result instanceof List<?> list && !list.isEmpty()) {
                     
                    Object firstStack = list.getFirst();
                    return tryExtractChemical(firstStack);  
                }
            } catch (Exception e) {
                ComplexityAnalyzer.LOGGER.debug("Failed to extract from ChemicalStackIngredient: {}", e.getMessage());
            }

            return null;
        }
         

         
        if (!className.contains("Chemical") && !className.contains("Gas") &&
                !className.contains("Slurry") && !className.contains("Infusion") &&
                !className.contains("Pigment")) {
            return null;
        }

        try {
            long amount = 1000;

             
            for (String methodName : Arrays.asList("getAmount", "amount")) {
                try {
                    Method m = obj.getClass().getMethod(methodName);
                    Object result = m.invoke(obj);
                    if (result instanceof Number) {
                        amount = ((Number) result).longValue();
                        break;
                    }
                } catch (Exception ignored) {}
            }

             
            ResourceLocation chemicalId = null;

             
            try {
                Method getTypeRegistryName = obj.getClass().getMethod("getTypeRegistryName");
                Object result = getTypeRegistryName.invoke(obj);
                if (result instanceof ResourceLocation) {
                    chemicalId = (ResourceLocation) result;
                } else if (result != null) {
                     
                    String idStr = result.toString();
                    chemicalId = ResourceLocation.parse(idStr);
                }
            } catch (Exception ignored) {}

             
            if (chemicalId == null) {
                try {
                    Method getChemical = obj.getClass().getMethod("getChemical");
                    Object chemical = getChemical.invoke(obj);
                    if (chemical != null) {
                         
                        try {
                            Method getRegistryName = chemical.getClass().getMethod("getRegistryName");
                            Object regName = getRegistryName.invoke(chemical);
                            if (regName instanceof ResourceLocation) {
                                chemicalId = (ResourceLocation) regName;
                            } else if (regName != null) {
                                chemicalId = ResourceLocation.parse(regName.toString());
                            }
                        } catch (Exception ignored) {
                             
                            String chemStr = chemical.toString();
                            if (chemStr.contains(":")) {
                                chemicalId = parseResourceLocation(chemStr);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (chemicalId != null) {
                return new ChemicalOutput(chemicalId, amount);
            }
             

        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.debug("Failed to extract chemical: {}", e.getMessage());
        }

        return null;
    }

     
    private static ResourceLocation parseResourceLocation(String str) {
        if (str == null || !str.contains(":")) return null;

         
        String cleanId = str.replaceAll(".*?([a-z0-9_]+:[a-z0-9_/]+).*", "$1");

        if (!cleanId.contains(":")) return null;

        try {
            return ResourceLocation.parse(cleanId);
        } catch (Exception e) {
            return null;
        }
    }

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

        List<Ingredient> ingredients = itemInputs.stream()
                .filter(l -> !l.isEmpty())
                .map(l -> Ingredient.of(l.toArray(new ItemStack[0])))
                .toList();
        RecipeCategory category = org.complexityanalyzer.graph.GraphBuilder.classifyRecipe(recipe, resultItem, ingredients);
        if (category == RecipeCategory.UNPROCESSABLE) return null;

        builder.recipeType(recipeType).category(category);

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

        RecipeNode node = builder
                .rawRecipe(recipe)
                .build();

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
        try {
            Object actualRecipe = unwrapRecipeHolder(recipe);

            RecipeAdapter adapter = getAdapter(actualRecipe, true, ResourceType.FLUID, level);

            if (adapter.fluidOutputAccessor == null) {
                return new ArrayList<>();
            }

            Object result = adapter.fluidOutputAccessor.extract(actualRecipe, level);

            return deepFindFluidStacks(result, 0);

        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static List<List<ItemStack>> extractItemInputsFromRecord(Object record) {
        List<List<ItemStack>> results = new ArrayList<>();

        try {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                String name = component.getName();

                 
                if ((name.contains("input") || name.contains("Input") ||
                        name.contains("ingredient")) &&
                        !name.toLowerCase().contains("chemical") &&
                        !name.toLowerCase().contains("gas") &&
                        !name.toLowerCase().contains("fluid")) {

                    Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    Object value = accessor.invoke(record);

                    if (value != null) {
                        List<List<ItemStack>> extracted = deepFindItemStackLists(value, 0);
                        if (!extracted.isEmpty()) {
                            results.addAll(extracted);
                        }
                    }
                }
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.debug("Failed to extract item inputs from record: {}", e.getMessage());
        }

        return results;
    }

    private static List<List<FluidStack>> extractFluidInputsFromRecord(Object record) {
        List<List<FluidStack>> results = new ArrayList<>();

        try {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                String name = component.getName();

                 
                if ((name.contains("water") || name.contains("fluid") ||
                        name.contains("Fluid") || name.contains("liquid")) &&
                        !name.toLowerCase().contains("output")) {

                    Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    Object value = accessor.invoke(record);

                    if (value != null) {
                        List<List<FluidStack>> extracted = deepFindFluidStackLists(value, 0);
                        if (!extracted.isEmpty()) {
                            results.addAll(extracted);
                        }
                    }
                }
            }
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.debug("Failed to extract fluid inputs from record: {}", e.getMessage());
        }

        return results;
    }

    public static List<List<ItemStack>> extractInputs(Object recipe, Level level) {
        Object actualRecipe = unwrapRecipeHolder(recipe);

         
        if (actualRecipe.getClass().isRecord()) {
            return extractItemInputsFromRecord(actualRecipe);
        }
         

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

         
        if (actualRecipe.getClass().isRecord()) {
            return extractFluidInputsFromRecord(actualRecipe);
        }
         

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
                clazz -> new RecipeAdapter(null, null, null, null, null, null));

        if (isOutput) {
            Accessor existingAccessor = switch (resourceType) {
                case ITEM -> adapter.itemOutputAccessor;
                case FLUID -> adapter.fluidOutputAccessor;
                case CHEMICAL -> adapter.chemicalOutputAccessor;
            };

            if (existingAccessor == null) {
                Accessor accessor = learnAccessor(recipe, true, resourceType, level);
                adapter = switch (resourceType) {
                    case ITEM -> new RecipeAdapter(accessor, adapter.fluidOutputAccessor,
                            adapter.chemicalOutputAccessor, adapter.itemInputAccessor,
                            adapter.fluidInputAccessor, adapter.chemicalInputAccessor);
                    case FLUID -> new RecipeAdapter(adapter.itemOutputAccessor, accessor,
                            adapter.chemicalOutputAccessor, adapter.itemInputAccessor,
                            adapter.fluidInputAccessor, adapter.chemicalInputAccessor);
                    case CHEMICAL -> new RecipeAdapter(adapter.itemOutputAccessor,
                            adapter.fluidOutputAccessor, accessor, adapter.itemInputAccessor,
                            adapter.fluidInputAccessor, adapter.chemicalInputAccessor);
                };
                LEARNED_ADAPTERS.put(recipeClass, adapter);
            }
        } else {
            Accessor existingAccessor = switch (resourceType) {
                case ITEM -> adapter.itemInputAccessor;
                case FLUID -> adapter.fluidInputAccessor;
                case CHEMICAL -> adapter.chemicalInputAccessor;
            };

            if (existingAccessor == null) {
                Accessor accessor = learnAccessor(recipe, false, resourceType, level);
                adapter = switch (resourceType) {
                    case ITEM -> new RecipeAdapter(adapter.itemOutputAccessor, adapter.fluidOutputAccessor,
                            adapter.chemicalOutputAccessor, accessor, adapter.fluidInputAccessor, adapter.chemicalInputAccessor);
                    case FLUID -> new RecipeAdapter(adapter.itemOutputAccessor, adapter.fluidOutputAccessor,
                            adapter.chemicalOutputAccessor, adapter.itemInputAccessor, accessor, adapter.chemicalInputAccessor);
                    case CHEMICAL -> new RecipeAdapter(adapter.itemOutputAccessor, adapter.fluidOutputAccessor,
                            adapter.chemicalOutputAccessor, adapter.itemInputAccessor, adapter.fluidInputAccessor, accessor);
                };
                LEARNED_ADAPTERS.put(recipeClass, adapter);
            }
        }
        return adapter;
    }

    enum ResourceType {
        ITEM, FLUID, CHEMICAL
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
            return switch (resourceType) {
                case ITEM -> new String[]{
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
                case FLUID -> new String[]{
                        "getFluidOutput", "getFluidOutputs", "outputFluid", "outputFluids", "getOutputFluids", "getFluidResult",
                        "getFluidResults", "fetchFluidOutput", "retrieveFluidOutput", "fluidOutput", "fluidOutputs", "producedFluid",
                        "producedFluids", "resultFluid", "outputsFluid", "getOutputFluid", "getOutputFluids", "outputFluid", "outputFluids"
                };
                case CHEMICAL -> new String[]{
                        "getOutput", "getOutputDefinition", "getChemicalOutput", "getChemicalOutputs",
                        "getGasOutput", "getGasOutputs", "chemicalOutput", "gasOutput",
                        "getLeftGasOutput", "getRightGasOutput", "getLeftOutput", "getRightOutput"
                };
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
                    "retrieveFluidInput", "ingredientFluid", "ingredientFluids"
            };
            case CHEMICAL -> new String[]{
                    "getInput", "getInputDefinition", "getChemicalInput", "getChemicalInputs",
                    "getGasInput", "getGasInputs", "chemicalInput", "gasInput",
                    "getLeftGasInput", "getRightGasInput"
            };
        };
    }

    private static String[] getWildcards(boolean isOutput, ResourceType resourceType) {
        if (isOutput) {
            return switch (resourceType) {
                case ITEM -> new String[]{"item", "stack", "output", "result", "produce", "craft", "create", "yield", "generate"};
                case FLUID -> new String[]{"fluid", "liquid", "output", "result"};
                case CHEMICAL -> new String[]{"gas", "chemical", "output", "result"};
            };
        }

        return switch (resourceType) {
            case ITEM -> new String[]{"item", "stack", "input", "ingredient", "solid"};
            case FLUID -> new String[]{"fluid", "liquid", "input", "ingredient"};
            case CHEMICAL -> new String[]{"gas", "chemical", "input", "ingredient"};
        };
    }

    private static String[] getCandidateFields(boolean isOutput, ResourceType resourceType) {
        if (isOutput) {
            return switch (resourceType) {
                case ITEM -> new String[]{
                        "result", "results", "output", "outputs",
                        "product", "products", "mainOutput", "secondaryOutput", "bonusOutput", "primaryOutput", "byproduct", "outputDefinition"
                };
                case FLUID -> new String[]{
                        "fluidOutput", "fluidOutputs", "outputFluid", "outputFluids", "liquidOutput"
                };
                case CHEMICAL -> new String[]{
                        "gasOutput", "chemicalOutput", "output", "outputDefinition",
                        "leftGasOutput", "rightGasOutput"
                };
            };
        }

        return switch (resourceType) {
            case ITEM -> new String[]{
                    "inputs", "input", "ingredient", "ingredients", "itemInput", "itemInputs",
                    "stackInput", "solidInput", "inputDefinition"
            };
            case FLUID -> new String[]{
                    "fluidInput", "fluidInputs", "inputFluid", "inputFluids", "liquidInput"
            };
            case CHEMICAL -> new String[]{
                    "gasInput", "chemicalInput", "input", "inputDefinition",
                    "leftGasInput", "rightGasInput"
            };
        };
    }

    private static Method learnMethod(Object recipe, boolean isOutput, ResourceType resourceType, Level level) {
        String[] candidateMethods = getCandidateMethods(isOutput, resourceType);

         
        for (String methodName : candidateMethods) {
            Method method = findAndValidateMethod(recipe, methodName, isOutput, resourceType, level);
            if (method != null) return method;
        }

         
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
        if (obj == null || depth > MAX_RECURSION_DEPTH) return new ArrayList<>();

         
        if (obj instanceof FluidStack stack && !stack.isEmpty()) {
            return List.of(stack);
        }

         
        String className = obj.getClass().getName();
        if (className.contains("Chemical") || className.contains("Gas") || className.contains("Slurry")
                || className.contains("Infusion") || className.contains("Pigment")) {

            FluidStack converted = tryConvertToFluid(obj, depth);
            if (converted != null && !converted.isEmpty()) {
                return List.of(converted);
            }
        }

         
        return findRecursive(obj, depth, (o, d) -> {
            if (o instanceof FluidStack stack && !stack.isEmpty()) {
                return List.of(stack);
            }
            FluidStack converted = tryConvertToFluid(o, d);
            if (converted != null && !converted.isEmpty()) {
                return List.of(converted);
            }
            return null;
        });
    }

    private static FluidStack tryConvertToFluid(Object obj, int depth) {
        if (obj == null || depth > MAX_RECURSION_DEPTH) return null;

        try {
            String resourceId = null;
            long amount = 1000;

            String className = obj.getClass().getSimpleName();

            if (className.contains("Slurry") || className.contains("Pigment")) {
                return null;
            }

            for (String methodName : Arrays.asList("getAmount", "amount")) {
                try {
                    Method m = obj.getClass().getMethod(methodName);
                    Object result = m.invoke(obj);
                    if (result instanceof Number) {
                        amount = ((Number) result).longValue();
                        break;
                    }
                } catch (Exception ignored) {}
            }

             
            Object innerObject = obj;
            for (String methodName : Arrays.asList("getChemical", "chemical", "getGas", "gas", "getFluid", "fluid", "getType", "type")) {
                try {
                    Method m = obj.getClass().getMethod(methodName);
                    Object result = m.invoke(obj);
                    if (result != null && result != obj) {
                        innerObject = result;
                        break;
                    }
                } catch (Exception ignored) {}
            }

             
            for (String methodName : Arrays.asList("toString", "getName", "name", "getRegistryName", "registryName", "getId", "id")) {
                try {
                    Method m = innerObject.getClass().getMethod(methodName);
                    Object result = m.invoke(innerObject);
                    if (result instanceof String str && str.contains(":")) {
                        resourceId = str;
                        break;
                    }
                } catch (Exception ignored) {}
            }

            if (resourceId != null) {
                String cleanId = resourceId.replaceAll(".*\\[([^]]+)].*", "$1")
                        .replaceAll(".*\\{([^}]+)}.*", "$1")
                        .replaceAll("^.*?([a-z0-9_]+:[a-z0-9_]+).*$", "$1");



                try {
                    ResourceLocation loc = ResourceLocation.parse(cleanId);
                    net.minecraft.world.level.material.Fluid fluid = BuiltInRegistries.FLUID.get(loc);

                    if (fluid != net.minecraft.world.level.material.Fluids.EMPTY) {
                        return new FluidStack(fluid, (int) Math.min(amount, Integer.MAX_VALUE));
                    }
                } catch (Exception ignored) {}
            }

        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.debug("Failed to convert {} to FluidStack: {}",
                    obj.getClass().getSimpleName(), e.getMessage());
        }

        return null;
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

         
        if (className.contains("FluidStackIngredient")) {
            try {
                Object result = obj.getClass().getMethod("getRepresentations").invoke(obj);
                if (result instanceof List<?> list) {
                     
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

    private static RecipeType<?> extractRecipeTypeFromRecord(Object record) {
        try {
             
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                String name = component.getName();

                if (name.equals("type") || name.equals("recipeType")) {
                    Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    Object value = accessor.invoke(record);

                    RecipeType<?> type = coerceRecipeType(value);
                    if (type != null) return type;
                }
            }

             
            if (machineRegistry != null) {
                for (RecordComponent component : record.getClass().getRecordComponents()) {
                    if (component.getName().equals("id")) {
                        Method accessor = component.getAccessor();
                        accessor.setAccessible(true);
                        Object value = accessor.invoke(record);

                        if (value instanceof ResourceLocation recipeId) {
                            RecipeType<?> type = findRecipeTypeViaRegistry(recipeId);
                            if (type != null) {
                                ComplexityAnalyzer.LOGGER.debug("Found recipe type via MachineRegistry: {} -> {}",
                                        recipeId, BuiltInRegistries.RECIPE_TYPE.getKey(type));
                                return type;
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.debug("Failed to extract recipe type from record: {}", e.getMessage());
        }

        return null;
    }

     
    private static RecipeType<?> findRecipeTypeViaRegistry(ResourceLocation recipeId) {
        if (machineRegistry == null) return null;

        String path = recipeId.getPath();

         
         
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
         

        String[] parts = path.split("/");

        if (parts.length == 0) return null;

        String typeHint = parts[0];  
        String namespace = recipeId.getNamespace();

         
        if (typeHint.isEmpty()) {
            ComplexityAnalyzer.LOGGER.debug("Empty type hint for recipe {}, cannot determine type", recipeId);
            return null;
        }
         

        ComplexityAnalyzer.LOGGER.debug("Looking for recipe type with hint '{}' in namespace '{}'", typeHint, namespace);

         
        List<RecipeType<?>> candidates = new ArrayList<>();

        for (Map.Entry<ResourceKey<RecipeType<?>>, RecipeType<?>> entry :
                BuiltInRegistries.RECIPE_TYPE.entrySet()) {

            ResourceLocation typeId = entry.getKey().location();

             
            if (!typeId.getNamespace().equals(namespace)) continue;

             
            if (!typeId.getPath().contains(typeHint)) continue;

            RecipeType<?> recipeType = entry.getValue();

             
            Optional<Item> machine = machineRegistry.getMachineForRecipe(recipeType);

            if (machine.isPresent()) {
                ComplexityAnalyzer.LOGGER.debug("  Candidate: {} (has machine)", typeId);
                candidates.add(recipeType);
            }
        }

        if (candidates.isEmpty()) {
            ComplexityAnalyzer.LOGGER.debug("No matching recipe type found for {}", recipeId);
            return null;
        }

         
        if (candidates.size() > 1) {
             
            candidates.sort(Comparator.comparingInt(rt -> {
                ResourceLocation typeLocation = BuiltInRegistries.RECIPE_TYPE.getKey(rt);
                return typeLocation != null ? typeLocation.getPath().length() : Integer.MAX_VALUE;
            }));

            RecipeType<?> best = candidates.getFirst();
            ResourceLocation bestLocation = BuiltInRegistries.RECIPE_TYPE.getKey(best);
            ComplexityAnalyzer.LOGGER.debug("Multiple candidates, chose: {}", bestLocation);
            return best;
        }

        RecipeType<?> result = candidates.getFirst();
        ResourceLocation resultLocation = BuiltInRegistries.RECIPE_TYPE.getKey(result);
        ComplexityAnalyzer.LOGGER.debug("Matched recipe type {} for recipe {}", resultLocation, recipeId);
        return result;
    }

    public static RecipeType<?> extractRecipeType(Object recipe) {
        if (recipe == null) return null;
        Object actual = unwrapRecipeHolder(recipe);

        if (actual instanceof net.minecraft.world.item.crafting.Recipe<?> vanillaRecipe) {
            return vanillaRecipe.getType();
        }

         
        if (actual.getClass().isRecord()) {
            return extractRecipeTypeFromRecord(actual);
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

    public record ChemicalOutput(ResourceLocation id, long amount) {}

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
        final Accessor chemicalOutputAccessor;
        final Accessor itemInputAccessor;
        final Accessor fluidInputAccessor;
        final Accessor chemicalInputAccessor;

        RecipeAdapter(Accessor itemOutputAccessor, Accessor fluidOutputAccessor,
                      Accessor chemicalOutputAccessor,
                      Accessor itemInputAccessor, Accessor fluidInputAccessor,
                      Accessor chemicalInputAccessor) {
            this.itemOutputAccessor = itemOutputAccessor;
            this.fluidOutputAccessor = fluidOutputAccessor;
            this.chemicalOutputAccessor = chemicalOutputAccessor;
            this.itemInputAccessor = itemInputAccessor;
            this.fluidInputAccessor = fluidInputAccessor;
            this.chemicalInputAccessor = chemicalInputAccessor;
        }
    }
}