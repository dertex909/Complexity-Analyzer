/*
 * Complexity Analyzer
 * Copyright (C) 2025-2026 dertex909
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.harvest.inspector;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class HeuristicRoleClassifier {

    private static final ObjectList<ClassificationMethod> STANDARD_API_METHOD_LIST = ObjectLists.singleton(ClassificationMethod.STANDARD_RECIPE_API);
    private static final ObjectList<String> EVIDENCE_OUTPUT = ObjectLists.singleton("Standard output method");
    private static final ObjectList<String> EVIDENCE_INPUT = ObjectLists.singleton("Standard input method");

    private static final RoleClassification STD_OUTPUT_100 = new RoleClassification(Role.OUTPUT, 100, STANDARD_API_METHOD_LIST, EVIDENCE_OUTPUT);
    private static final RoleClassification STD_INPUT_100 = new RoleClassification(Role.INPUT, 100, STANDARD_API_METHOD_LIST, EVIDENCE_INPUT);
    private static final RoleClassification STD_OUTPUT_90 = new RoleClassification(Role.OUTPUT, 90, STANDARD_API_METHOD_LIST, EVIDENCE_OUTPUT);
    private static final RoleClassification STD_INPUT_90 = new RoleClassification(Role.INPUT, 90, STANDARD_API_METHOD_LIST, EVIDENCE_INPUT);

    private HeuristicRoleClassifier() {
    }

    private static RoleClassification classifyStandardRecipeMethod(String methodName, int confidence) {
        if (StandardRecipeMethods.GET_RESULT_ITEM.equals(methodName)) {
            return confidence == 100 ? STD_OUTPUT_100 : (confidence == 90 ? STD_OUTPUT_90 : new RoleClassification(Role.OUTPUT, confidence, STANDARD_API_METHOD_LIST, EVIDENCE_OUTPUT));
        }
        if (StandardRecipeMethods.GET_INGREDIENTS.equals(methodName)) {
            return confidence == 100 ? STD_INPUT_100 : (confidence == 90 ? STD_INPUT_90 : new RoleClassification(Role.INPUT, confidence, STANDARD_API_METHOD_LIST, EVIDENCE_INPUT));
        }
        return null;
    }

    public static RoleClassification classify(Object recipe, Object raw, String accessorName, String accessorType,
                                              Item anchorItem, ReferenceSet<Ingredient> standardInputs) {
        if (raw == null) return RoleClassification.UNKNOWN;

        if (recipe instanceof Recipe<?> && "method".equals(accessorType)) {
            var standardClassification = classifyStandardRecipeMethod(accessorName, 100);
            if (standardClassification != null) return standardClassification;
        }

        ObjectArrayList<ClassificationMethod> methods = null;
        ObjectArrayList<String> evidence = null;
        int confidence = 0;

        if (!standardInputs.isEmpty() && raw instanceof Ingredient ing && standardInputs.contains(ing)) {
            methods = new ObjectArrayList<>();
            evidence = new ObjectArrayList<>();
            methods.add(ClassificationMethod.STANDARD_INPUT_MATCH);
            confidence += 80;
            evidence.add("Matches standard ingredient");
        }

        if (anchorItem != null && raw instanceof ItemStack stack && !stack.isEmpty() && stack.getItem() == anchorItem) {
            methods = new ObjectArrayList<>();
            evidence = new ObjectArrayList<>();
            methods.add(ClassificationMethod.OUTPUT_ANCHOR_MATCH);
            confidence += 80;
            evidence.add("Matches anchor output");
        }

        boolean matchesOutput = methods != null && methods.contains(ClassificationMethod.OUTPUT_ANCHOR_MATCH);
        if (raw instanceof Ingredient && !matchesOutput) {
            if (methods == null) {
                methods = new ObjectArrayList<>();
                evidence = new ObjectArrayList<>();
            }
            methods.add(ClassificationMethod.TYPE_HEURISTIC);
            confidence += 20;
            evidence.add("Type is Ingredient");
        }

        if (methods == null) return RoleClassification.UNKNOWN;
        var role = confidence >= 60 ? (methods.contains(ClassificationMethod.OUTPUT_ANCHOR_MATCH) ? Role.OUTPUT :
                methods.contains(ClassificationMethod.STANDARD_INPUT_MATCH) ? Role.INPUT : Role.UNKNOWN) : Role.UNKNOWN;
        return new RoleClassification(role, Math.min(100, confidence), methods, evidence);
    }

    public static RoleClassification classifyField(Field field) {
        var methods = new ObjectArrayList<ClassificationMethod>();
        var evidence = new ObjectArrayList<String>();
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

    public static RoleClassification classifyMethod(Method method) {
        var result = classifyStandardRecipeMethod(method.getName(), 90);
        return result != null ? result : RoleClassification.UNKNOWN;
    }

    public enum Role {INPUT, OUTPUT, UNKNOWN}

    public enum ClassificationMethod {
        STANDARD_INPUT_MATCH, OUTPUT_ANCHOR_MATCH, CROSS_REFERENCE,
        TYPE_HEURISTIC, INITIALIZATION_PATTERN, STANDARD_RECIPE_API
    }

    public record RoleClassification(Role role, int confidence, ObjectList<ClassificationMethod> methods,
                                     ObjectList<String> evidence) {
        public static final RoleClassification UNKNOWN =
                new RoleClassification(Role.UNKNOWN, 0, ObjectLists.emptyList(), ObjectLists.emptyList());
    }
}