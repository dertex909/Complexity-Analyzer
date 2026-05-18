package org.complexityanalyzer.harvest;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

public final class HeuristicRoleClassifier {

    public enum Role {INPUT, OUTPUT, UNKNOWN}

    public enum ClassificationMethod {
        STANDARD_INPUT_MATCH, OUTPUT_ANCHOR_MATCH, CROSS_REFERENCE,
        TYPE_HEURISTIC, INITIALIZATION_PATTERN, STANDARD_RECIPE_API
    }

    public record RoleClassification(Role role, int confidence, List<ClassificationMethod> methods,
                                     List<String> evidence) {
        public static final RoleClassification UNKNOWN = new RoleClassification(Role.UNKNOWN, 0, List.of(), List.of());
    }

    private HeuristicRoleClassifier() {
    }

    public static RoleClassification classify(Object recipe, Object raw, String accessorName, String accessorType,
                                              Item anchorItem, Set<Ingredient> standardInputs) {
        if (raw == null) return RoleClassification.UNKNOWN;
        var methods = new ArrayList<ClassificationMethod>();
        var evidence = new ArrayList<String>();
        int confidence = 0;

        if (recipe instanceof Recipe<?> && "method".equals(accessorType)) {
            if ("getResultItem".equals(accessorName) || "getResult".equals(accessorName) || "getOutput".equals(accessorName)) {
                return new RoleClassification(Role.OUTPUT, 100, List.of(ClassificationMethod.STANDARD_RECIPE_API), List.of("Standard output method"));
            }
            if ("getIngredients".equals(accessorName) || "getInputs".equals(accessorName)) {
                return new RoleClassification(Role.INPUT, 100, List.of(ClassificationMethod.STANDARD_RECIPE_API), List.of("Standard input method"));
            }
        }

        if (!standardInputs.isEmpty() && raw instanceof Ingredient ing && standardInputs.contains(ing)) {
            methods.add(ClassificationMethod.STANDARD_INPUT_MATCH);
            confidence += 80;
            evidence.add("Matches standard ingredient");
        }

        if (anchorItem != null && raw instanceof ItemStack stack && !stack.isEmpty() && stack.getItem() == anchorItem) {
            methods.add(ClassificationMethod.OUTPUT_ANCHOR_MATCH);
            confidence += 80;
            evidence.add("Matches anchor output");
        }

        if (raw instanceof Ingredient && !methods.contains(ClassificationMethod.OUTPUT_ANCHOR_MATCH)) {
            methods.add(ClassificationMethod.TYPE_HEURISTIC);
            confidence += 20;
            evidence.add("Type is Ingredient");
        }

        Role role = confidence >= 60 ? (methods.contains(ClassificationMethod.OUTPUT_ANCHOR_MATCH) ? Role.OUTPUT :
                                        methods.contains(ClassificationMethod.STANDARD_INPUT_MATCH) ? Role.INPUT : Role.UNKNOWN) : Role.UNKNOWN;
        return new RoleClassification(role, Math.min(100, confidence), methods, evidence);
    }

    public static RoleClassification classifyField(Field field) {
        var methods = new ArrayList<ClassificationMethod>();
        var evidence = new ArrayList<String>();
        int confidence = 0;
        if (Ingredient.class.isAssignableFrom(field.getType())) {
            methods.add(ClassificationMethod.TYPE_HEURISTIC);
            confidence += 25;
        }
        if (Modifier.isFinal(field.getModifiers())) {
            methods.add(ClassificationMethod.INITIALIZATION_PATTERN);
            confidence += 15;
        }
        return new RoleClassification(Role.UNKNOWN, confidence, methods, evidence);
    }

    public static RoleClassification classifyMethod(java.lang.reflect.Method method) {
        String name = method.getName();
        if ("getResultItem".equals(name) || "getResult".equals(name) || "getOutput".equals(name))
            return new RoleClassification(Role.OUTPUT, 90, List.of(ClassificationMethod.STANDARD_RECIPE_API), List.of("Standard output"));
        if ("getIngredients".equals(name) || "getInputs".equals(name))
            return new RoleClassification(Role.INPUT, 90, List.of(ClassificationMethod.STANDARD_RECIPE_API), List.of("Standard input"));
        return RoleClassification.UNKNOWN;
    }
}