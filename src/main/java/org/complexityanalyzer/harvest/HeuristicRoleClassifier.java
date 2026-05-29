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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;

public final class HeuristicRoleClassifier {

    public enum Role {INPUT, OUTPUT, UNKNOWN}

    public enum ClassificationMethod {
        STANDARD_INPUT_MATCH, OUTPUT_ANCHOR_MATCH, CROSS_REFERENCE,
        TYPE_HEURISTIC, INITIALIZATION_PATTERN, STANDARD_RECIPE_API
    }

    public record RoleClassification(
            Role role,
            int confidence,
            ObjectList<ClassificationMethod> methods,
            ObjectList<String> evidence
    ) {
        public static final RoleClassification UNKNOWN = new RoleClassification(
                Role.UNKNOWN, 0, ObjectLists.emptyList(), ObjectLists.emptyList()
        );
    }

    private HeuristicRoleClassifier() {
    }

    public static RoleClassification classify(Object recipe, Object raw, String accessorName, String accessorType,
                                              Item anchorItem, Set<Ingredient> standardInputs) {
        if (raw == null) return RoleClassification.UNKNOWN;
        var methods = new ObjectArrayList<ClassificationMethod>();
        var evidence = new ObjectArrayList<String>();
        int confidence = 0;

        if (recipe instanceof Recipe<?> && "method".equals(accessorType)) {
            if ("getResultItem".equals(accessorName) || "getResult".equals(accessorName) || "getOutput".equals(accessorName)) {
                return new RoleClassification(Role.OUTPUT, 100,
                        ObjectLists.singleton(ClassificationMethod.STANDARD_RECIPE_API),
                        ObjectLists.singleton("Standard output method")
                );
            }
            if ("getIngredients".equals(accessorName) || "getInputs".equals(accessorName)) {
                return new RoleClassification(Role.INPUT, 100,
                        ObjectLists.singleton(ClassificationMethod.STANDARD_RECIPE_API),
                        ObjectLists.singleton("Standard input method")
                );
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

        var role = confidence >= 60 ? (methods.contains(ClassificationMethod.OUTPUT_ANCHOR_MATCH) ? Role.OUTPUT :
                                       methods.contains(ClassificationMethod.STANDARD_INPUT_MATCH) ? Role.INPUT :
                                       Role.UNKNOWN) : Role.UNKNOWN;
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

    public static RoleClassification classifyMethod(java.lang.reflect.Method method) {
        String name = method.getName();
        if ("getResultItem".equals(name) || "getResult".equals(name) || "getOutput".equals(name))
            return new RoleClassification(Role.OUTPUT, 90,
                    ObjectLists.singleton(ClassificationMethod.STANDARD_RECIPE_API),
                    ObjectLists.singleton("Standard output")
            );
        if ("getIngredients".equals(name) || "getInputs".equals(name))
            return new RoleClassification(Role.INPUT, 90,
                    ObjectLists.singleton(ClassificationMethod.STANDARD_RECIPE_API),
                    ObjectLists.singleton("Standard input")
            );
        return RoleClassification.UNKNOWN;
    }
}