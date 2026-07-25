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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.cache.MachineRegistryCache;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.GameRegistryManager;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static net.minecraft.core.BlockPos.ZERO;

public class MachineRegistry {

    private static final ClassInfo EMPTY_INFO = new ClassInfo(new Method[0], new Field[0]);
    private final Object2ObjectMap<ResourceLocation, ObjectList<Item>> mapping = new Object2ObjectOpenHashMap<>();
    private final Object2ObjectMap<Class<?>, ClassInfo> classInfoCache = new Object2ObjectOpenHashMap<>();
    private boolean initialized = false;

    public void initialize(MinecraftServer server) {
        if (initialized) return;
        int vanilla = registerVanilla();
        ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Registered {} vanilla machines", vanilla);

        boolean cacheEnabled = ComplexityConfig.ENABLE_CACHE.get();
        var cacheFile = cacheEnabled ? MachineRegistryCache.INSTANCE.file(server) : null;
        MachineRegistryCache.Fingerprint fingerprint = null;
        if (cacheFile != null) {
            fingerprint = MachineRegistryCache.INSTANCE.computeFingerprint();
            int restored = MachineRegistryCache.INSTANCE.tryLoad(cacheFile, fingerprint, mapping);
            if (restored >= 0) {
                ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Loaded {} machine mappings from cache (block scan skipped)", restored);
                initialized = true;
                return;
            }
        }

        int dynamic = registerModdedMachines(server);
        ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Total registered {} dynamic modded machines", dynamic);

        if (cacheFile != null) MachineRegistryCache.INSTANCE.save(cacheFile, fingerprint, mapping);

        initialized = true;
    }

    @Nullable
    public ObjectList<Item> getMachinesForRecipe(RecipeType<?> type) {
        if (!initialized) return null;
        var typeId = GameRegistryManager.getRecipeTypeId(type);
        return (typeId != null) ? mapping.get(typeId) : null;
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

    private int registerModdedMachines(MinecraftServer server) {
        int registeredCount = 0;
        int entityBlocks = 0;
        int errors = 0;

        var blocks = GameRegistryManager.getAllBlocks();
        int totalBlocks = blocks.size();

        StringBuilder dump = new StringBuilder(1024 * 1024);
        dump.append("=================================================================\n");
        dump.append("MACHINE REGISTRY FULL DEBUG DUMP\n");
        dump.append("Total Blocks to scan: ").append(totalBlocks).append("\n");
        dump.append("=================================================================\n\n");

        for (var block : blocks) {
            Item machineItem = block.asItem();
            if (machineItem == Items.AIR) continue;

            ResourceLocation blockId = GameRegistryManager.getBlockId(block);

            if (block instanceof EntityBlock entityBlock) {
                entityBlocks++;
                dump.append("\n-----------------------------------------------------------------\n");
                dump.append("BLOCK: ").append(blockId).append(" | Class: ").append(block.getClass().getName()).append("\n");

                try {
                    BlockEntity be = null;
                    try {
                        be = entityBlock.newBlockEntity(ZERO, block.defaultBlockState());
                        dump.append("  [BE_CREATE] SUCCESS: Created BlockEntity instance -> ").append(be.getClass().getName()).append("\n");
                    } catch (Throwable t) {
                        dump.append("  [BE_CREATE] FAILED: ").append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
                    }

                    // 1. Сканируем через ASM инструкций байткода
                    Class<?> targetClass = be != null ? be.getClass() : block.getClass();
                    int asmScanned = scanClassBytecodeASM(targetClass, machineItem, dump);
                    registeredCount += asmScanned;

                    // 2. Сканируем рефлексией инстанс
                    if (be != null) {
                        int scanned = scanBlockEntityInstance(be, machineItem, dump);
                        if (scanned == 0 && asmScanned == 0) {
                            var rt = findRecipeTypeDeep(be, 0, new ReferenceOpenHashSet<>(), dump);
                            if (rt != null && registerDynamicMachine(rt, machineItem)) {
                                var typeId = GameRegistryManager.getRecipeTypeId(rt);
                                dump.append("  [MATCH:DEEP] >>> MATCH: RecipeType '").append(typeId).append("' -> Item '").append(GameRegistryManager.getItemId(machineItem)).append("' <<<\n");
                                registeredCount++;
                            }
                        } else {
                            registeredCount += scanned;
                        }
                    } else {
                        registeredCount += scanStaticFieldsOnly(block.getClass(), machineItem, dump);
                    }
                } catch (Throwable t) {
                    errors++;
                    dump.append("  [FATAL_BLOCK_ERROR] ").append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
                }
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
        var refs = findRecipeTypeReferencesASM(clazz);

        for (var ref : refs) {
            RecipeType<?> rt = extractStaticRecipeType(ref.ownerClass(), ref.fieldName());
            if (rt != null && registerDynamicMachine(rt, machineItem)) {
                var typeId = GameRegistryManager.getRecipeTypeId(rt);
                dump.append("  [MATCH:ASM] >>> MATCH VIA ASM: Field ").append(ref.ownerClass()).append("->").append(ref.fieldName())
                        .append(" => RecipeType '").append(typeId).append("' -> Item '").append(GameRegistryManager.getItemId(machineItem)).append("' <<<\n");
                count++;
            }
        }
        return count;
    }

    private record StaticFieldRef(String ownerClass, String fieldName) {
    }

    private static ObjectList<StaticFieldRef> findRecipeTypeReferencesASM(Class<?> clazz) {
        var results = new ObjectArrayList<StaticFieldRef>();
        if (!curClsValid(clazz)) return results;

        try {
            String resourceName = clazz.getSimpleName() + ".class";
            try (InputStream is = clazz.getResourceAsStream(resourceName)) {
                if (is != null) {
                    processBytecodeASM(is, results);
                } else {
                    String classPath = "/" + clazz.getName().replace('.', '/') + ".class";
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
        ClassReader cr = new ClassReader(is);
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        for (FieldNode field : cn.fields) {
            if (field.desc != null && (field.desc.contains("RecipeType") || field.desc.contains("Recipe"))) {
                results.add(new StaticFieldRef(cn.name, field.name));
            }
        }

        for (MethodNode method : cn.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode fieldInsn) {
                    if (fieldInsn.desc != null && (fieldInsn.desc.contains("RecipeType") || fieldInsn.desc.contains("Recipe") || fieldInsn.owner.contains("Recipe"))) {
                        var ref = new StaticFieldRef(fieldInsn.owner, fieldInsn.name);
                        if (!results.contains(ref)) results.add(ref);
                    }
                }
            }
        }
    }

    private static @Nullable RecipeType<?> extractStaticRecipeType(String ownerClass, String fieldName) {
        try {
            Class<?> cls = Class.forName(ownerClass.replace('/', '.'));
            Field f = cls.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object raw = f.get(null);
            return unwrapRecipeType(raw);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private int scanBlockEntityInstance(BlockEntity be, Item machineItem, StringBuilder dump) {
        int count = 0;
        Class<?> beClass = be.getClass();
        ClassInfo info = classInfo(beClass);

        for (Method method : info.recipeMethods()) {
            try {
                Object raw = method.invoke(be);
                RecipeType<?> recipeType = unwrapRecipeType(raw);
                if (recipeType != null && registerDynamicMachine(recipeType, machineItem)) {
                    var typeId = GameRegistryManager.getRecipeTypeId(recipeType);
                    dump.append("    [MATCH:METHOD] >>> MAPPED RecipeType '").append(typeId).append("' -> Machine Item '").append(GameRegistryManager.getItemId(machineItem)).append("' <<<\n");
                    count++;
                }
            } catch (Throwable ignored) {
            }
        }

        var currentClass = beClass;
        while (curClsValid(currentClass)) {
            for (Field field : currentClass.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object val = field.get(be);
                    RecipeType<?> recipeType = unwrapRecipeType(val);

                    if (recipeType != null && registerDynamicMachine(recipeType, machineItem)) {
                        var typeId = GameRegistryManager.getRecipeTypeId(recipeType);
                        dump.append("    [MATCH:FIELD] >>> MAPPED RecipeType '").append(typeId).append("' -> Machine Item '").append(GameRegistryManager.getItemId(machineItem)).append("' <<<\n");
                        count++;
                    }
                } catch (Throwable ignored) {
                }
            }
            currentClass = currentClass.getSuperclass();
        }

        return count;
    }

    private int scanStaticFieldsOnly(Class<?> clazz, Item machineItem, StringBuilder dump) {
        int count = 0;
        Class<?> current = clazz;
        while (curClsValid(current)) {
            for (Field field : current.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    if (Modifier.isStatic(field.getModifiers())) {
                        Object val = field.get(null);
                        RecipeType<?> recipeType = unwrapRecipeType(val);
                        if (recipeType != null && registerDynamicMachine(recipeType, machineItem)) {
                            var typeId = GameRegistryManager.getRecipeTypeId(recipeType);
                            dump.append("    [MATCH:STATIC_FIELD] >>> MAPPED RecipeType '").append(typeId).append("' -> Machine Item '").append(GameRegistryManager.getItemId(machineItem)).append("' <<<\n");
                            count++;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return count;
    }

    @Nullable
    private static RecipeType<?> unwrapRecipeType(@Nullable Object obj) {
        if (obj == null) return null;
        if (obj instanceof RecipeType<?> rt) return rt;

        // Разворачиваем обертки (например, Create IRecipeTypeInfo, Supplier, Holder, Optional)
        try {
            for (Method m : obj.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && RecipeType.class.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    Object res = m.invoke(obj);
                    if (res instanceof RecipeType<?> rt) return rt;
                }
            }
        } catch (Throwable ignored) {
        }

        if (obj instanceof Supplier<?> supplier) {
            try {
                Object val = supplier.get();
                if (val instanceof RecipeType<?> rt) return rt;
            } catch (Throwable ignored) {
            }
        }

        if (obj instanceof Optional<?> opt) {
            if (opt.isPresent()) return unwrapRecipeType(opt.get());
        }

        if (obj instanceof Holder<?> holder) {
            try {
                return unwrapRecipeType(holder.value());
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private ClassInfo classInfo(Class<?> clazz) {
        var info = classInfoCache.get(clazz);
        if (info != null) return info;
        info = buildClassInfo(clazz);
        classInfoCache.put(clazz, info);
        return info;
    }

    private ClassInfo buildClassInfo(Class<?> clazz) {
        if (!curClsValid(clazz)) return EMPTY_INFO;

        var methods = new ObjectArrayList<Method>();
        for (var method : clazz.getMethods()) {
            if (method.getParameterCount() == 0) {
                Class<?> rt = method.getReturnType();
                if (rt != void.class && rt != Void.class && !rt.isPrimitive()) {
                    String mName = method.getName().toLowerCase();
                    if (RecipeType.class.isAssignableFrom(rt) || Supplier.class.isAssignableFrom(rt) ||
                            Holder.class.isAssignableFrom(rt) || Optional.class.isAssignableFrom(rt) ||
                            mName.contains("recipe") || mName.contains("type")) {
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

        RecipeType<?> directUnwrap = unwrapRecipeType(obj);
        if (directUnwrap != null) return directUnwrap;

        if (obj instanceof Iterable<?> coll) {
            for (Object item : coll) {
                RecipeType<?> res = findRecipeTypeDeep(item, depth + 1, visited, dump);
                if (res != null) return res;
            }
            return null;
        }

        if (obj instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                RecipeType<?> res = findRecipeTypeDeep(item, depth + 1, visited, dump);
                if (res != null) return res;
            }
            return null;
        }

        var info = classInfo(obj.getClass());

        for (var method : info.recipeMethods()) {
            try {
                Object val = method.invoke(obj);
                RecipeType<?> rt = unwrapRecipeType(val);
                if (rt != null) return rt;

                if (val != null && !isPrimitiveOrJava(val)) {
                    RecipeType<?> deep = findRecipeTypeDeep(val, depth + 1, visited, dump);
                    if (deep != null) return deep;
                }
            } catch (Throwable ignored) {
            }
        }

        for (var field : info.fields()) {
            try {
                Object val = field.get(obj);
                RecipeType<?> rt = unwrapRecipeType(val);
                if (rt != null) return rt;

                if (val != null && !isPrimitiveOrJava(val)) {
                    RecipeType<?> deep = findRecipeTypeDeep(val, depth + 1, visited, dump);
                    if (deep != null) return deep;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean isPrimitiveOrJava(Object obj) {
        if (obj == null) return true;
        Class<?> c = obj.getClass();
        String name = c.getName();
        return c.isPrimitive() || c.isEnum() || name.startsWith("java.") || name.startsWith("javax.");
    }

    private static boolean curClsValid(@Nullable Class<?> cls) {
        if (cls == null || cls == Object.class) return false;
        String name = cls.getName();
        return !name.startsWith("java.") && !name.startsWith("net.minecraft.");
    }

    private static void saveDumpToDisk(MinecraftServer server, String content) {
        try {
            Path saveDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").resolve("complexityanalyzer");
            Files.createDirectories(saveDir);
            Path dumpFile = saveDir.resolve("machine_scan_debug.txt");
            Files.writeString(dumpFile, content, StandardCharsets.UTF_8);
            ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Saved full scan debug file to: {}", dumpFile.toAbsolutePath());
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.error("[MachineRegistry] Failed to write debug dump file: {}", t.getMessage());
        }
    }

    private boolean registerDynamicMachine(RecipeType<?> recipeType, Item item) {
        if (item == Items.AIR) return false;
        var typeId = GameRegistryManager.getRecipeTypeId(recipeType);
        if (typeId == null) return false;

        var list = mapping.computeIfAbsent(typeId, k -> new ObjectArrayList<>());
        if (!list.contains(item)) {
            list.add(item);
            return true;
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

        mapping.computeIfAbsent(typeRL, k -> new ObjectArrayList<>()).add(item);
    }

    private record ClassInfo(Method[] recipeMethods, Field[] fields) {
    }
}