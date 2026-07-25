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

package org.complexityanalyzer.analyzer;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.cache.MachineRegistryCache;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.GameRegistryManager;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static net.minecraft.core.BlockPos.ZERO;

public class MachineRegistry {

    private static final ClassInfo EMPTY_INFO = new ClassInfo(new Method[0], new Field[0]);

    private final Object2ObjectMap<ResourceLocation, ObjectList<Item>> idMapping = new Object2ObjectOpenHashMap<>();
    private final Reference2ObjectMap<RecipeType<?>, ObjectList<Item>> instanceMapping = new Reference2ObjectOpenHashMap<>();
    private final Object2ObjectMap<Class<?>, ClassInfo> classInfoCache = new Object2ObjectOpenHashMap<>();

    private boolean initialized = false;

    private static ObjectList<StaticFieldRef> findRecipeTypeReferencesASM(Class<?> clazz, ObjectSet<String> visitedClasses) {
        var results = new ObjectArrayList<StaticFieldRef>();
        if (!curClsValid(clazz)) return results;

        String className = clazz.getName();
        if (!visitedClasses.add(className)) return results;

        try {
            String resourceName = clazz.getSimpleName() + ".class";
            try (var is = clazz.getResourceAsStream(resourceName)) {
                if (is != null) {
                    processBytecodeASM(is, results);
                } else {
                    String classPath = "/" + className.replace('.', '/') + ".class";
                    try (InputStream is2 = clazz.getResourceAsStream(classPath)) {
                        if (is2 != null) processBytecodeASM(is2, results);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return results;
    }

    private static void processBytecodeASM(InputStream is, ObjectList<StaticFieldRef> results) throws Exception {
        var cr = new ClassReader(is);
        var cn = new ClassNode();
        cr.accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        // 1. Проверяем статические поля самого класса
        for (var field : cn.fields) {
            if ((field.access & Modifier.STATIC) != 0) {
                var directRt = extractStaticRecipeType(cn.name, field.name);
                if (directRt != null) {
                    var ref = new StaticFieldRef(cn.name, field.name);
                    if (!results.contains(ref)) results.add(ref);
                }
            }
        }

        // 2. Проверяем инструкции обращения к статическим полям в методах
        for (var method : cn.methods) {
            if (method.instructions == null) continue;
            for (var insn : method.instructions) {
                if (insn instanceof FieldInsnNode fieldInsn) {
                    if (isPotentialRecipeTypeDescriptor(fieldInsn.desc)) {
                        var rt = extractStaticRecipeType(fieldInsn.owner, fieldInsn.name);
                        if (rt != null) {
                            var ref = new StaticFieldRef(fieldInsn.owner, fieldInsn.name);
                            if (!results.contains(ref)) results.add(ref);
                        }
                    }
                }
            }
        }
    }

    private static boolean isPotentialRecipeTypeDescriptor(@Nullable String desc) {
        if (desc == null) return false;
        return desc.contains("RecipeType") ||
                desc.contains("Holder") ||
                desc.contains("Supplier") ||
                desc.contains("DeferredHolder") ||
                desc.contains("RegistryObject");
    }

    private static @Nullable RecipeType<?> extractStaticRecipeType(String ownerClass, String fieldName) {
        try {
            var cls = Class.forName(ownerClass.replace('/', '.'));
            var f = cls.getDeclaredField(fieldName);
            f.setAccessible(true);
            if (!Modifier.isStatic(f.getModifiers())) return null;
            var raw = f.get(null);
            return unwrapRecipeType(raw);
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Nullable
    private static RecipeType<?> unwrapRecipeType(@Nullable Object obj) {
        if (obj == null) return null;

        if (obj instanceof RecipeType<?> rt) {
            return rt;
        }

        if (obj instanceof Block ||
                obj instanceof Item ||
                obj instanceof BlockEntity ||
                obj instanceof BlockEntityType<?> ||
                obj instanceof net.minecraft.sounds.SoundEvent ||
                obj instanceof net.minecraft.world.level.material.Fluid ||
                obj instanceof net.minecraft.world.entity.EntityType<?>) {
            return null;
        }

        switch (obj) {
            case Holder<?> holder -> {
                try {
                    if (holder.isBound()) {
                        var val = holder.value();
                        if (val instanceof RecipeType<?> rt) return rt;
                    }
                    var keyOpt = holder.unwrapKey();
                    if (keyOpt.isPresent()) {
                        var key = keyOpt.get();
                        if (key.isFor(Registries.RECIPE_TYPE)) {
                            return BuiltInRegistries.RECIPE_TYPE.get(key.location());
                        }
                    }
                } catch (Throwable ignored) {
                }
                return null;
            }


            // Supplier
            case Supplier<?> supplier -> {
                try {
                    return unwrapRecipeType(supplier.get());
                } catch (Throwable ignored) {
                }
                return null;
            }


            // Optional
            case Optional<?> opt -> {
                return opt.map(MachineRegistry::unwrapRecipeType).orElse(null);
            }
            default -> {
            }
        }

        // Рефлексия по публичным методам без параметров
        try {
            for (var m : obj.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && (RecipeType.class.isAssignableFrom(m.getReturnType()) || Holder.class.isAssignableFrom(m.getReturnType()))) {
                    m.setAccessible(true);
                    var res = m.invoke(obj);
                    var rt = unwrapRecipeType(res);
                    if (rt != null) return rt;
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static boolean isComplexObject(@Nullable Object obj) {
        if (obj == null) return false;
        var c = obj.getClass();
        return curClsValid(c) && !c.isEnum();
    }

    private static void saveDumpToDisk(MinecraftServer server, String content) {
        try {
            var saveDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").resolve("complexityanalyzer");
            Files.createDirectories(saveDir);
            var dumpFile = saveDir.resolve("machine_scan_debug.txt");
            Files.writeString(dumpFile, content, StandardCharsets.UTF_8);
            ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Saved full scan debug file to: {}", dumpFile.toAbsolutePath());
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.error("[MachineRegistry] Failed to write debug dump file: {}", t.getMessage());
        }
    }

    private static String getStackTraceString(Throwable t) {
        var sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString().trim();
    }

    private static String getClassHierarchyString(Class<?> clazz) {
        var sb = new StringBuilder();
        var curr = clazz;
        while (curr != null && curr != Object.class) {
            if (!sb.isEmpty()) sb.append(" -> ");
            sb.append(curr.getName());
            curr = curr.getSuperclass();
        }
        return sb.toString();
    }

    private static String formatValue(@Nullable Object obj) {
        switch (obj) {
            case null -> {
                return "null";
            }
            case RecipeType<?> rt -> {
                var id = GameRegistryManager.getRecipeTypeId(rt);
                return "RecipeType[" + (id != null ? id : rt.toString()) + "]";
            }
            case Holder<?> holder -> {
                if (holder.isBound()) {
                    return "Holder[Value=" + formatValue(holder.value()) + "]";
                }
                var keyOpt = holder.unwrapKey();
                return "Holder[Key=" + keyOpt.map(k -> k.location().toString()).orElse("unbound") + "]";
            }
            case ResourceLocation rl -> {
                return "ResourceLocation[" + rl + "]";
            }
            case Enum<?> en -> {
                return "Enum[" + en.name() + "]";
            }
            case String str -> {
                return "\"" + (str.length() > 60 ? str.substring(0, 57) + "..." : str) + "\"";
            }
            default -> {
            }
        }
        Class<?> c = obj.getClass();
        if (c.isPrimitive() || Number.class.isAssignableFrom(c) || Boolean.class.isAssignableFrom(c) || Character.class.isAssignableFrom(c)) {
            return String.valueOf(obj);
        }
        return c.getName() + "@" + Integer.toHexString(System.identityHashCode(obj));
    }

    @Nullable
    public Item getMachineForRecipe(RecipeType<?> type) {
        var list = getMachinesForRecipe(type);
        return (list != null && !list.isEmpty()) ? list.getFirst() : null;
    }

    private int registerVanilla() {
        register("minecraft:crafting", "minecraft:crafting_table");
        register("minecraft:smelting", "minecraft:furnace");
        register("minecraft:blasting", "minecraft:blast_furnace");
        register("minecraft:smoking", "minecraft:smoker");
        register("minecraft:campfire_cooking", "minecraft:campfire");
        register("minecraft:stonecutting", "minecraft:stonecutter");
        register("minecraft:smithing", "minecraft:smithing_table");
        return 7;
    }

    public void initialize(MinecraftServer server) {
        if (initialized) return;
        int vanilla = registerVanilla();
        ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Registered {} vanilla machines", vanilla);

        boolean cacheEnabled = ComplexityConfig.ENABLE_CACHE.get();
        var cacheFile = cacheEnabled ? MachineRegistryCache.INSTANCE.file(server) : null;
        MachineRegistryCache.Fingerprint fingerprint = null;
        if (cacheFile != null) {
            fingerprint = MachineRegistryCache.INSTANCE.computeFingerprint();
            int restored = MachineRegistryCache.INSTANCE.tryLoad(cacheFile, fingerprint, idMapping);
            if (restored >= 0) {
                for (var entry : idMapping.object2ObjectEntrySet()) {
                    var rt = BuiltInRegistries.RECIPE_TYPE.get(entry.getKey());
                    if (rt != null) {
                        var instList = instanceMapping.computeIfAbsent(rt, k -> new ObjectArrayList<>());
                        for (var item : entry.getValue()) if (!instList.contains(item)) instList.add(item);
                    }
                }
                ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Loaded {} machine mappings from cache (block scan skipped)", restored);
                initialized = true;
                return;
            }
        }

        int dynamic = registerModdedMachines(server);
        ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Total registered {} dynamic modded machines", dynamic);

        if (cacheFile != null) MachineRegistryCache.INSTANCE.save(cacheFile, fingerprint, idMapping);

        initialized = true;
    }

    @Nullable
    public ObjectList<Item> getMachinesForRecipe(RecipeType<?> type) {
        if (!initialized || type == null) return null;

        var result = new ObjectArrayList<Item>();

        var listByInst = instanceMapping.get(type);
        if (listByInst != null) for (var item : listByInst) if (!result.contains(item)) result.add(item);

        var typeId = GameRegistryManager.getRecipeTypeId(type);
        if (typeId != null) {
            var listById = idMapping.get(typeId);
            if (listById != null) for (var item : listById) if (!result.contains(item)) result.add(item);
        }

        return result.isEmpty() ? null : result;
    }

    private int registerModdedMachines(MinecraftServer server) {
        int registeredCount = 0;
        int entityBlocks = 0;
        int errors = 0;

        var blocks = GameRegistryManager.getAllBlocks();
        int totalBlocks = blocks.size();

        var dump = new StringBuilder(1024 * 1024);
        dump.append("=================================================================\n");
        dump.append("MACHINE REGISTRY FULL DEBUG DUMP\n");
        dump.append("Total Blocks to scan: ").append(totalBlocks).append("\n");
        dump.append("=================================================================\n\n");

        for (var block : blocks) {
            var machineItem = block.asItem();
            if (machineItem == Items.AIR) continue;

            var blockId = GameRegistryManager.getBlockId(block);
            boolean isEntityBlock = block instanceof EntityBlock;

            dump.append("\n-----------------------------------------------------------------\n");
            dump.append("BLOCK: ").append(blockId)
                    .append(" | Item: ").append(GameRegistryManager.getItemId(machineItem))
                    .append("\nBlock Class: ").append(block.getClass().getName())
                    .append("\nClass Hierarchy: ").append(getClassHierarchyString(block.getClass())).append("\n");

            try {
                BlockEntity be = null;
                if (isEntityBlock) {
                    entityBlocks++;
                    try {
                        be = ((EntityBlock) block).newBlockEntity(ZERO, block.defaultBlockState());
                        if (be != null) {
                            dump.append("  [BE_CREATE] SUCCESS: Created BlockEntity instance -> ").append(be.getClass().getName()).append("\n");
                            dump.append("  [BE_HIERARCHY] ").append(getClassHierarchyString(be.getClass())).append("\n");
                        } else {
                            dump.append("  [BE_CREATE] NULL: newBlockEntity returned null\n");
                        }
                    } catch (Throwable t) {
                        dump.append("  [BE_CREATE] FAILED: ").append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
                        dump.append("  [BE_CREATE_STACKTRACE]:\n").append(getStackTraceString(t)).append("\n");
                    }
                } else {
                    dump.append("  [NON_BE_BLOCK] Block has no BlockEntity (Stonecutter/Sawmill style)\n");
                }

                var targetClass = be != null ? be.getClass() : block.getClass();
                int blockMatchedCount = 0;

                int asmScanned = scanClassBytecodeASM(targetClass, machineItem, dump);
                blockMatchedCount += asmScanned;

                if (be != null) {
                    int scanned = scanBlockEntityInstance(be, machineItem, dump);
                    blockMatchedCount += scanned;

                    if (scanned == 0 && asmScanned == 0) {
                        dump.append("  [DEEP_SCAN_START] Initial scans found no RecipeType, starting deep object traversal...\n");
                        var rt = findRecipeTypeDeep(be, 0, new ReferenceOpenHashSet<>(), dump);
                        if (rt != null && registerDynamicMachine(rt, machineItem)) {
                            var typeId = GameRegistryManager.getRecipeTypeId(rt);
                            dump.append("  [MATCH:DEEP] >>> MATCH: RecipeType '").append(typeId).append("' -> Item '").append(GameRegistryManager.getItemId(machineItem)).append("' <<<\n");
                            blockMatchedCount++;
                        } else {
                            dump.append("  [DEEP_SCAN_END] Deep traversal completed. No RecipeType found.\n");
                        }
                    }
                } else {
                    int staticScanned = scanStaticFieldsOnly(block.getClass(), machineItem, dump);
                    blockMatchedCount += staticScanned;
                }

                registeredCount += blockMatchedCount;
                if (blockMatchedCount > 0) {
                    dump.append("  [RESULT] SUCCESS: Mapped block ").append(blockId).append(" with ").append(blockMatchedCount).append(" match(es)\n");
                } else {
                    dump.append("  [RESULT] NO_MATCH: No RecipeType mapped for block ").append(blockId).append("\n");
                }

            } catch (Throwable t) {
                errors++;
                dump.append("  [FATAL_BLOCK_ERROR] ").append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
                dump.append("  [FATAL_STACKTRACE]:\n").append(getStackTraceString(t)).append("\n");
            }
        }

        dump.append("\n=================================================================\n");
        dump.append("SCAN SUMMARY:\n");
        dump.append("Total Blocks: ").append(totalBlocks).append("\n");
        dump.append("Entity Blocks: ").append(entityBlocks).append("\n");
        dump.append("Registered Machines: ").append(registeredCount).append("\n");
        dump.append("Errors: ").append(errors).append("\n");
        dump.append("=================================================================\n");

        saveDumpToDisk(server, dump.toString());

        ComplexityAnalyzer.LOGGER.info("[MachineRegistry:SUMMARY] Scan finished! Registered Machines={}, Errors={}. Full debug dumped to disk!", registeredCount, errors);

        return registeredCount;
    }

    private int scanClassBytecodeASM(Class<?> clazz, Item machineItem, StringBuilder dump) {
        if (!curClsValid(clazz)) return 0;
        int count = 0;
        var refs = findRecipeTypeReferencesASM(clazz, new ObjectOpenHashSet<>());

        dump.append("  [ASM_SCAN] Target Class: ").append(clazz.getName()).append(" -> Found ").append(refs.size()).append(" potential static field ref(s)\n");
        for (var ref : refs) {
            dump.append("    [ASM_REF] ").append(ref.ownerClass()).append("#").append(ref.fieldName());
            try {
                var rt = extractStaticRecipeType(ref.ownerClass(), ref.fieldName());
                if (rt != null) {
                    var typeId = GameRegistryManager.getRecipeTypeId(rt);
                    dump.append(" -> Unwrapped RT: ").append(typeId);
                    if (registerDynamicMachine(rt, machineItem)) {
                        dump.append(" [MATCH VIA ASM]");
                        count++;
                    }
                } else {
                    dump.append(" -> Unwrapped RT: null");
                }
            } catch (Throwable t) {
                dump.append(" -> ERROR: ").append(t.getClass().getName()).append(": ").append(t.getMessage());
            }
            dump.append("\n");
        }
        return count;
    }

    private int scanBlockEntityInstance(BlockEntity be, Item machineItem, StringBuilder dump) {
        int count = 0;
        Class<?> beClass = be.getClass();
        var info = classInfo(beClass);

        dump.append("  [BE_METHOD_SCAN] Inspecting zero-arg methods on ").append(beClass.getName()).append(":\n");
        for (var method : info.recipeMethods()) {
            try {
                var raw = method.invoke(be);
                var recipeType = unwrapRecipeType(raw);
                dump.append("    [BE_METHOD] ").append(method.getName()).append("()")
                        .append(" [Returns: ").append(method.getReturnType().getName()).append("] = ")
                        .append(formatValue(raw));
                if (recipeType != null) {
                    var typeId = GameRegistryManager.getRecipeTypeId(recipeType);
                    dump.append(" -> Unwrapped RT: ").append(typeId);
                    if (registerDynamicMachine(recipeType, machineItem)) {
                        dump.append(" [MATCH]");
                        count++;
                    }
                }
                dump.append("\n");
            } catch (Throwable t) {
                dump.append("    [BE_METHOD_ERR] ").append(method.getName()).append("(): ")
                        .append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n")
                        .append("    [BE_METHOD_ERR_STACKTRACE]:\n").append(getStackTraceString(t)).append("\n");
            }
        }

        var currentClass = beClass;
        while (curClsValid(currentClass)) {
            dump.append("  [BE_FIELD_HIERARCHY] Class: ").append(currentClass.getName()).append("\n");
            for (var field : currentClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    var val = field.get(be);
                    var recipeType = unwrapRecipeType(val);
                    dump.append("    [BE_FIELD] ").append(field.getName())
                            .append(" (").append(field.getType().getName()).append(") = ")
                            .append(formatValue(val));
                    if (recipeType != null) {
                        var typeId = GameRegistryManager.getRecipeTypeId(recipeType);
                        dump.append(" -> Unwrapped RT: ").append(typeId);
                        if (registerDynamicMachine(recipeType, machineItem)) {
                            dump.append(" [MATCH]");
                            count++;
                        }
                    }
                    dump.append("\n");
                } catch (Throwable t) {
                    dump.append("    [BE_FIELD_ERR] ").append(field.getName()).append(": ")
                            .append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n")
                            .append("    [BE_FIELD_ERR_STACKTRACE]:\n").append(getStackTraceString(t)).append("\n");
                }
            }
            currentClass = currentClass.getSuperclass();
        }

        return count;
    }

    private int scanStaticFieldsOnly(Class<?> clazz, Item machineItem, StringBuilder dump) {
        int count = 0;
        dump.append("  [STATIC_FIELD_SCAN] Inspecting static fields for class: ").append(clazz.getName()).append("\n");
        var current = clazz;
        while (curClsValid(current)) {
            dump.append("  [STATIC_HIERARCHY] Class: ").append(current.getName()).append("\n");
            for (var field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    var val = field.get(null);
                    var recipeType = unwrapRecipeType(val);
                    dump.append("    [STATIC_FIELD] ").append(field.getName())
                            .append(" (").append(field.getType().getName()).append(") = ")
                            .append(formatValue(val));
                    if (recipeType != null) {
                        var typeId = GameRegistryManager.getRecipeTypeId(recipeType);
                        dump.append(" -> Unwrapped RT: ").append(typeId);
                        if (registerDynamicMachine(recipeType, machineItem)) {
                            dump.append(" [MATCH]");
                            count++;
                        }
                    }
                    dump.append("\n");
                } catch (Throwable t) {
                    dump.append("    [STATIC_FIELD_ERR] ").append(field.getName()).append(": ")
                            .append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n")
                            .append("    [STATIC_FIELD_ERR_STACKTRACE]:\n").append(getStackTraceString(t)).append("\n");
                }
            }
            current = current.getSuperclass();
        }
        return count;
    }

    private ClassInfo classInfo(Class<?> clazz) {
        var info = classInfoCache.get(clazz);
        if (info == null) {
            info = buildClassInfo(clazz);
            classInfoCache.put(clazz, info);
        }
        return info;
    }

    private ClassInfo buildClassInfo(Class<?> clazz) {
        if (!curClsValid(clazz)) return EMPTY_INFO;

        var methods = new ObjectArrayList<Method>();
        for (var method : clazz.getMethods()) {
            if (method.getParameterCount() == 0) {
                var rt = method.getReturnType();
                if (rt != void.class && rt != Void.class && !rt.isPrimitive()) {
                    String name = method.getName();
                    if (RecipeType.class.isAssignableFrom(rt) || Supplier.class.isAssignableFrom(rt) ||
                            Holder.class.isAssignableFrom(rt) || Optional.class.isAssignableFrom(rt) ||
                            name.startsWith("get") || name.startsWith("recipe") || name.startsWith("type")) {
                        try {
                            method.setAccessible(true);
                            methods.add(method);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }

        var fields = new ObjectArrayList<Field>();
        var current = clazz;
        while (curClsValid(current)) {
            for (var field : current.getDeclaredFields()) {
                if (field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    fields.add(field);
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return new ClassInfo(methods.toArray(new Method[0]), fields.toArray(new Field[0]));
    }

    @Nullable
    private RecipeType<?> findRecipeTypeDeep(Object obj, int depth, ReferenceSet<Object> visited, StringBuilder dump) {
        if (obj == null || depth > 5 || !visited.add(obj)) return null;

        String indent = "    " + "  ".repeat(depth);
        Class<?> cls = obj.getClass();

        var directUnwrap = unwrapRecipeType(obj);
        if (directUnwrap != null) {
            dump.append(indent).append("[DEEP_MATCH] Direct unwrap of ").append(cls.getName())
                    .append(" -> RecipeType: ").append(GameRegistryManager.getRecipeTypeId(directUnwrap)).append("\n");
            return directUnwrap;
        }

        if (obj instanceof Iterable<?> coll) {
            int idx = 0;
            for (var item : coll) {
                if (item != null) {
                    dump.append(indent).append("[DEEP_ITER] Traversing Iterable item #").append(idx)
                            .append(" (").append(item.getClass().getName()).append(")\n");
                    var res = findRecipeTypeDeep(item, depth + 1, visited, dump);
                    if (res != null) return res;
                }
                idx++;
            }
            return null;
        }

        if (obj instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                var val = entry.getValue();
                if (val != null) {
                    dump.append(indent).append("[DEEP_MAP] Traversing Map value for key '").append(entry.getKey())
                            .append("' (").append(val.getClass().getName()).append(")\n");
                    var res = findRecipeTypeDeep(val, depth + 1, visited, dump);
                    if (res != null) return res;
                }
            }
            return null;
        }

        var info = classInfo(cls);

        for (var method : info.recipeMethods()) {
            try {
                var val = method.invoke(obj);
                if (val == null) continue;

                var rt = unwrapRecipeType(val);
                dump.append(indent).append("[DEEP_METHOD] ").append(method.getName()).append("() -> ")
                        .append(formatValue(val));
                if (rt != null) {
                    dump.append(" -> Unwrapped RT: ").append(GameRegistryManager.getRecipeTypeId(rt)).append("\n");
                    return rt;
                }
                dump.append("\n");

                if (isComplexObject(val)) {
                    dump.append(indent).append("  -> Recursing into method return '").append(method.getName()).append("' (").append(val.getClass().getName()).append(")\n");
                    var deep = findRecipeTypeDeep(val, depth + 1, visited, dump);
                    if (deep != null) return deep;
                }
            } catch (Throwable t) {
                dump.append(indent).append("[DEEP_METHOD_ERR] ").append(method.getName()).append("(): ")
                        .append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
            }
        }

        for (var field : info.fields()) {
            try {
                var val = field.get(obj);
                if (val == null) continue;

                var rt = unwrapRecipeType(val);
                dump.append(indent).append("[DEEP_FIELD] ").append(field.getName()).append(" (").append(field.getType().getSimpleName())
                        .append(") = ").append(formatValue(val));
                if (rt != null) {
                    dump.append(" -> Unwrapped RT: ").append(GameRegistryManager.getRecipeTypeId(rt)).append("\n");
                    return rt;
                }
                dump.append("\n");

                if (isComplexObject(val)) {
                    dump.append(indent).append("  -> Recursing into field '").append(field.getName()).append("' (").append(val.getClass().getName()).append(")\n");
                    var deep = findRecipeTypeDeep(val, depth + 1, visited, dump);
                    if (deep != null) return deep;
                }
            } catch (Throwable t) {
                dump.append(indent).append("[DEEP_FIELD_ERR] ").append(field.getName()).append(": ")
                        .append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
            }
        }
        return null;
    }

    private static boolean curClsValid(@Nullable Class<?> cls) {
        if (cls == null || cls == Object.class) return false;
        String name = cls.getName();
        return !name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("net.minecraft.");
    }

    private boolean registerDynamicMachine(RecipeType<?> recipeType, Item item) {
        if (item == Items.AIR) return false;

        var instList = instanceMapping.computeIfAbsent(recipeType, k -> new ObjectArrayList<>());
        if (!instList.contains(item)) instList.add(item);

        var typeId = GameRegistryManager.getRecipeTypeId(recipeType);
        if (typeId != null) {
            var list = idMapping.computeIfAbsent(typeId, k -> new ObjectArrayList<>());
            if (!list.contains(item)) {
                list.add(item);
                ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Mapped recipe type '{}' -> Machine item '{}'", typeId, GameRegistryManager.getItemId(item));
                return true;
            }
        }
        return false;
    }

    private void register(String recipeTypeId, String itemId) {
        var typeRL = ResourceLocation.parse(recipeTypeId);
        var itemRL = ResourceLocation.parse(itemId);
        var item = GameRegistryManager.getItem(itemRL);

        if (item == null || item == Items.AIR) {
            ComplexityAnalyzer.LOGGER.warn("[MachineRegistry] Failed to register machine: {} -> {} (item not found)", recipeTypeId, itemId);
            return;
        }

        var list = idMapping.computeIfAbsent(typeRL, k -> new ObjectArrayList<>());
        if (!list.contains(item)) list.add(item);

        var rt = BuiltInRegistries.RECIPE_TYPE.get(typeRL);
        if (rt != null) {
            var instList = instanceMapping.computeIfAbsent(rt, k -> new ObjectArrayList<>());
            if (!instList.contains(item)) instList.add(item);
        }

        ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Mapped vanilla machine: '{}' -> '{}'", recipeTypeId, itemId);
    }

    private record StaticFieldRef(String ownerClass, String fieldName) {
    }

    private record ClassInfo(Method[] recipeMethods, Field[] fields) {
    }
}