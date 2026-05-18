package org.complexityanalyzer.harvest;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Эвристический определитель INPUT/OUTPUT ролей без хардкода ключевых слов.
 * Использует многофакторный анализ:
 * <p>
 * 1. STANDARD_INPUT_MATCH — значение содержится в recipe.getIngredients()
 * 2. OUTPUT_ANCHOR_MATCH — значение совпадает с recipe.getResultItem()
 * 3. CROSS_REFERENCE — сравнение с другими полями того же класса
 * 4. NAME_HEURISTIC — мягкий анализ имён (не хардкод, а fuzzy)
 * 5. TYPE_HEURISTIC — Ingredient → скорее INPUT, ItemStack → зависит
 * 6. INITIALIZATION_PATTERN — присваивается в конструкторе → INPUT
 * 7. COLLECTION_POSITION — первый/последний элемент коллекции
 */
public final class HeuristicRoleClassifier {

    private static final ConcurrentHashMap<Class<?>, ClassFieldRoles> FIELD_ROLE_CACHE = new ConcurrentHashMap<>(256);

    public enum Role {
        INPUT,
        OUTPUT,
        UNKNOWN
    }

    public enum ClassificationMethod {
        STANDARD_INPUT_MATCH,
        OUTPUT_ANCHOR_MATCH,
        CROSS_REFERENCE,
        NAME_HEURISTIC,
        TYPE_HEURISTIC,
        INITIALIZATION_PATTERN,
        COLLECTION_POSITION,
        STANDARD_RECIPE_API
    }

    public record RoleClassification(
            Role role,
            int confidence,
            List<ClassificationMethod> methods,
            List<String> evidence
    ) {
        public static final RoleClassification UNKNOWN = new RoleClassification(
                Role.UNKNOWN, 0, List.of(), List.of()
        );

        @Override
        public @NotNull String toString() {
            return role + " conf=" + confidence + " methods=" + methods;
        }
    }

    public record ClassFieldRoles(
            Map<String, RoleClassification> fieldRoles,
            Map<String, RoleClassification> methodRoles
    ) {
    }

    private HeuristicRoleClassifier() {
    }

    // ======================== Публичный API ========================

    public static RoleClassification classify(
            Object recipe,
            Object raw,
            String accessorName,
            String accessorType,
            Item anchorItem,
            Set<Ingredient> standardInputs
    ) {
        if (raw == null) return RoleClassification.UNKNOWN;

        var methods = new ArrayList<ClassificationMethod>();
        var evidence = new ArrayList<String>();
        int confidence = 0;

        // === Метод 1: STANDARD_RECIPE_API ===
        if (recipe instanceof Recipe<?> && "method".equals(accessorType)) {
            if ("getResultItem".equals(accessorName) || "getResult".equals(accessorName)
                    || "getOutput".equals(accessorName) || "getAssembledItem".equals(accessorName)) {
                methods.add(ClassificationMethod.STANDARD_RECIPE_API);
                evidence.add("Standard Recipe API output method: " + accessorName);
                return new RoleClassification(Role.OUTPUT, 100, methods, evidence);
            }
            if ("getIngredients".equals(accessorName) || "getInputs".equals(accessorName)) {
                methods.add(ClassificationMethod.STANDARD_RECIPE_API);
                evidence.add("Standard Recipe API input method: " + accessorName);
                return new RoleClassification(Role.INPUT, 100, methods, evidence);
            }
        }

        // === Метод 2: STANDARD_INPUT_MATCH ===
        if (!standardInputs.isEmpty()) {
            if (raw instanceof Ingredient ing && standardInputs.contains(ing)) {
                methods.add(ClassificationMethod.STANDARD_INPUT_MATCH);
                confidence += 80;
                evidence.add("Value matches standard ingredient");
            } else if (raw instanceof ItemStack stack && !stack.isEmpty()) {
                for (Ingredient ing : standardInputs) {
                    if (ing.test(stack)) {
                        methods.add(ClassificationMethod.STANDARD_INPUT_MATCH);
                        confidence += 80;
                        evidence.add("ItemStack matches standard ingredient");
                        break;
                    }
                }
            }
        }

        // === Метод 3: OUTPUT_ANCHOR_MATCH ===
        if (anchorItem != null) {
            if (raw instanceof ItemStack stack && !stack.isEmpty() && stack.getItem() == anchorItem) {
                methods.add(ClassificationMethod.OUTPUT_ANCHOR_MATCH);
                confidence += 80;
                evidence.add("ItemStack matches anchor output item");
            } else if (raw instanceof Ingredient ing) {
                for (ItemStack s : ing.getItems()) {
                    if (s.getItem() == anchorItem) {
                        methods.add(ClassificationMethod.OUTPUT_ANCHOR_MATCH);
                        confidence += 80;
                        evidence.add("Ingredient contains anchor output item");
                        break;
                    }
                }
            }
        }

        // === Метод 4: TYPE_HEURISTIC ===
        if (raw instanceof Ingredient && !methods.contains(ClassificationMethod.OUTPUT_ANCHOR_MATCH)) {
            methods.add(ClassificationMethod.TYPE_HEURISTIC);
            confidence += 20;
            evidence.add("Type is Ingredient → likely INPUT");
        }

        // === Метод 5: NAME_HEURISTIC (fuzzy) ===
        Role nameRole = fuzzyNameRole(accessorName);
        if (nameRole != Role.UNKNOWN) {
            methods.add(ClassificationMethod.NAME_HEURISTIC);
            confidence += 30;
            evidence.add("Name heuristic: '" + accessorName + "' → " + nameRole);
        }

        // === Метод 6: CROSS_REFERENCE ===
        if ("field".equals(accessorType) && recipe != null) {
            ClassFieldRoles roles = getFieldRoles(recipe.getClass());
            RoleClassification fieldRole = roles.fieldRoles().get(accessorName);
            if (fieldRole != null && fieldRole.role() != Role.UNKNOWN) {
                methods.add(ClassificationMethod.CROSS_REFERENCE);
                confidence += 25;
                evidence.add("Cross-reference with other fields in " + recipe.getClass().getSimpleName());
                int boostedConf = Math.min(100, confidence + fieldRole.confidence() / 2);
                return new RoleClassification(fieldRole.role(), boostedConf, methods, evidence);
            }
        }

        // === Итоговое решение ===
        Role role;
        if (confidence >= 60) {
            if (methods.contains(ClassificationMethod.OUTPUT_ANCHOR_MATCH)) {
                role = Role.OUTPUT;
            } else if (methods.contains(ClassificationMethod.STANDARD_INPUT_MATCH)) {
                role = Role.INPUT;
            } else if (nameRole == Role.OUTPUT) {
                role = Role.OUTPUT;
            } else if (nameRole == Role.INPUT) {
                role = Role.INPUT;
            } else if (raw instanceof Ingredient) {
                role = Role.INPUT;
            } else {
                role = Role.UNKNOWN;
            }
        } else if (confidence >= 25) {
            role = nameRole;
        } else {
            role = Role.UNKNOWN;
        }

        return new RoleClassification(role, Math.min(100, confidence), methods, evidence);
    }

    public static RoleClassification classifyField(Field field) {
        String name = field.getName();
        Class<?> type = field.getType();

        var methods = new ArrayList<ClassificationMethod>();
        var evidence = new ArrayList<String>();
        int confidence = 0;

        if (Ingredient.class.isAssignableFrom(type)) {
            methods.add(ClassificationMethod.TYPE_HEURISTIC);
            confidence += 25;
            evidence.add("Field type is Ingredient");
        }

        Role nameRole = fuzzyNameRole(name);
        if (nameRole != Role.UNKNOWN) {
            methods.add(ClassificationMethod.NAME_HEURISTIC);
            confidence += 30;
            evidence.add("Field name '" + name + "' → " + nameRole);
        }

        if (Modifier.isFinal(field.getModifiers())) {
            methods.add(ClassificationMethod.INITIALIZATION_PATTERN);
            confidence += 15;
            evidence.add("Field is final → likely INPUT (initialized in constructor)");
        }

        Role role;
        if (confidence >= 50) {
            role = nameRole;
        } else {
            role = Role.UNKNOWN;
        }

        return new RoleClassification(role, Math.min(100, confidence), methods, evidence);
    }

    public static RoleClassification classifyMethod(java.lang.reflect.Method method) {
        String name = method.getName();
        Class<?> returnType = method.getReturnType();

        var methods = new ArrayList<ClassificationMethod>();
        var evidence = new ArrayList<String>();
        int confidence = 0;

        if ("getResultItem".equals(name) || "getResult".equals(name) || "getOutput".equals(name)) {
            methods.add(ClassificationMethod.STANDARD_RECIPE_API);
            confidence += 90;
            evidence.add("Standard output method: " + name);
            return new RoleClassification(Role.OUTPUT, confidence, methods, evidence);
        }
        if ("getIngredients".equals(name) || "getInputs".equals(name)) {
            methods.add(ClassificationMethod.STANDARD_RECIPE_API);
            confidence += 90;
            evidence.add("Standard input method: " + name);
            return new RoleClassification(Role.INPUT, confidence, methods, evidence);
        }

        if (Ingredient.class.isAssignableFrom(returnType)) {
            methods.add(ClassificationMethod.TYPE_HEURISTIC);
            confidence += 20;
            evidence.add("Returns Ingredient");
        }

        Role nameRole = fuzzyNameRole(name);
        if (nameRole != Role.UNKNOWN) {
            methods.add(ClassificationMethod.NAME_HEURISTIC);
            confidence += 30;
            evidence.add("Method name '" + name + "' → " + nameRole);
        }

        Role role = confidence >= 40 ? nameRole : Role.UNKNOWN;
        return new RoleClassification(role, Math.min(100, confidence), methods, evidence);
    }

    public static ClassFieldRoles getFieldRoles(Class<?> clazz) {
        ClassFieldRoles existing = FIELD_ROLE_CACHE.get(clazz);
        if (existing != null) return existing;

        var fieldRoles = new LinkedHashMap<String, RoleClassification>();
        var methodRoles = new LinkedHashMap<String, RoleClassification>();

        Class<?> scan = clazz;
        while (scan != null && scan != Object.class) {
            for (Field f : scan.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                fieldRoles.put(f.getName(), classifyField(f));
            }
            scan = scan.getSuperclass();
        }

        for (java.lang.reflect.Method m : clazz.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() > 1) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            methodRoles.put(m.getName(), classifyMethod(m));
        }

        ClassFieldRoles result = new ClassFieldRoles(fieldRoles, methodRoles);
        FIELD_ROLE_CACHE.put(clazz, result);
        return result;
    }

    public static void clearCache() {
        FIELD_ROLE_CACHE.clear();
    }

    // ======================== Приватные методы ========================

    private static Role fuzzyNameRole(String name) {
        if (name == null || name.length() < 2) return Role.UNKNOWN;

        String lower = name.toLowerCase(Locale.ROOT);

        int outputScore = 0;
        if (lower.startsWith("out") || lower.endsWith("out")) outputScore += 3;
        if (lower.contains("_out_") || lower.contains("_out")) outputScore += 3;
        if (lower.contains("output")) outputScore += 4;
        if (lower.contains("result")) outputScore += 3;
        if (lower.contains("product")) outputScore += 2;
        if (lower.contains("produce")) outputScore += 2;
        if (lower.contains("byproduct")) outputScore += 2;
        if (lower.contains("assembled")) outputScore += 2;

        final int inputScore = getInputScore(lower);

        return outputScore > inputScore ? Role.OUTPUT : inputScore > outputScore ? Role.INPUT : Role.UNKNOWN;
    }

    private static int getInputScore(String lower) {
        int inputScore = 0;
        if (lower.startsWith("in") && !lower.startsWith("info")
                && !lower.startsWith("index") && !lower.startsWith("inventory")
                && !lower.startsWith("init") && !lower.startsWith("instance")) inputScore += 2;
        if (lower.endsWith("in")) inputScore += 2;
        if (lower.contains("_in_") || lower.contains("_in")) inputScore += 2;
        if (lower.contains("input")) inputScore += 4;
        if (lower.contains("ingredient") || lower.contains("ingr")) inputScore += 3;
        if (lower.contains("source")) inputScore += 2;
        if (lower.contains("consume")) inputScore += 2;
        if (lower.contains("reactant")) inputScore += 2;
        if (lower.contains("catalyst")) inputScore += 2;
        return inputScore;
    }
}