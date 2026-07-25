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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
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

    private final Object2ObjectMap<ResourceLocation, ObjectList<Item>> idMapping = new Object2ObjectOpenHashMap<>();
    private final Reference2ObjectMap<RecipeType<?>, ObjectList<Item>> instanceMapping = new Reference2ObjectOpenHashMap<>();
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
            int restored = MachineRegistryCache.INSTANCE.tryLoad(cacheFile, fingerprint, idMapping);
            if (restored >= 0) {
                for (var entry : idMapping.object2ObjectEntrySet()) {
                    RecipeType<?> rt = BuiltInRegistries.RECIPE_TYPE.get(entry.getKey());
                    if (rt != null) {
                        var instList = instanceMapping.computeIfAbsent(rt, k -> new ObjectArrayList<>());
                        for (Item item : entry.getValue()) {
                            if (!instList.contains(item)) instList.add(item);
                        }
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

        ObjectList<Item> result = new ObjectArrayList<>();

        var listByInst = instanceMapping.get(type);
        if (listByInst != null) {
            for (Item item : listByInst) {
                if (!result.contains(item)) result.add(item);
            }
        }

        var typeId = GameRegistryManager.getRecipeTypeId(type);
        if (typeId != null) {
            var listById = idMapping.get(typeId);
            if (listById != null) {
                for (Item item : listById) {
                    if (!result.contains(item)) result.add(item);
                }
            }
        }

        return result.isEmpty() ? null : result;
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
            boolean isEntityBlock = block instanceof EntityBlock;

            dump.append("\n-----------------------------------------------------------------\n");
            dump.append("BLOCK: ").append(blockId).append(" | Class: ").append(block.getClass().getName()).append("\n");

            try {
                BlockEntity be = null;
                if (isEntityBlock) {
                    entityBlocks++;
                    try {
                        be = ((EntityBlock) block).newBlockEntity(ZERO, block.defaultBlockState());
                        dump.append("  [BE_CREATE] SUCCESS: Created BlockEntity instance -> ").append(be.getClass().getName()).append("\n");
                    } catch (Throwable t) {
                        dump.append("  [BE_CREATE] FAILED: ").append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
                    }
                } else {
                    dump.append("  [NON_BE_BLOCK] Block has no BlockEntity (Stonecutter/Sawmill style)\n");
                }

                Class<?> targetClass = be != null ? be.getClass() : block.getClass();
                int asmScanned = scanClassBytecodeASM(targetClass, machineItem, dump);
                registeredCount += asmScanned;

                if (be != null) {
                    int scanned = scanBlockEntityInstance(be, machineItem, dump);
                    if (scanned == 0 && asmScanned == 0) {
                        var rt = findRecipeTypeDeep(be, 0, new ReferenceOpenHashSet<>());
                        if (rt != null && registerDynamicMachine(rt, machineItem)) {
                            var typeId = GameRegistryManager.getRecipeTypeId(rt);
                            dump.append("  [MATCH:DEEP] >>> MATCH: RecipeType '").append(typeId).append("' -> Item '").append(GameRegistryManager.getItemId(machineItem)).append("' <<<\n");
                            registeredCount++;
                        }
                    } else {
                        registeredCount += scanned;
                    }
                } else {
                    int staticScanned = scanStaticFieldsOnly(block.getClass(), machineItem, dump);
                    registeredCount += staticScanned;
                }
            } catch (Throwable t) {
                errors++;
                dump.append("  [FATAL_BLOCK_ERROR] ").append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
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
        var refs = findRecipeTypeReferencesASM(clazz, new ObjectOpenHashSet<>(), 0);

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

    private record StaticFieldRef(String ownerClass, String fieldName) {}

    private static ObjectList<StaticFieldRef> findRecipeTypeReferencesASM(Class<?> clazz, ObjectSet<String> visitedClasses, int depth) {
        var results = new ObjectArrayList<StaticFieldRef>();
        if (!curClsValid(clazz) || depth > 2) return results;

        String className = clazz.getName();
        if (!visitedClasses.add(className)) return results;

        try {
            String resourceName = clazz.getSimpleName() + ".class";
            try (InputStream is = clazz.getResourceAsStream(resourceName)) {
                if (is != null) {
                    processBytecodeASM(is, results, visitedClasses, depth, depth == 0);
                } else {
                    String classPath = "/" + className.replace('.', '/') + ".class";
                    try (InputStream is2 = clazz.getResourceAsStream(classPath)) {
                        if (is2 != null) processBytecodeASM(is2, results, visitedClasses, depth, depth == 0);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return results;
    }

    private static void processBytecodeASM(InputStream is, ObjectList<StaticFieldRef> results, ObjectSet<String> visitedClasses, int depth, boolean isTargetClass) throws Exception {
        ClassReader cr = new ClassReader(is);
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        if (isTargetClass) {
            for (FieldNode field : cn.fields) {
                RecipeType<?> directRt = extractStaticRecipeType(cn.name, field.name);
                if (directRt != null) {
                    results.add(new StaticFieldRef(cn.name, field.name));
                }
            }
        }

        ObjectList<String> referencedClasses = new ObjectArrayList<>();

        for (MethodNode method : cn.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode fieldInsn) {
                    RecipeType<?> rt = extractStaticRecipeType(fieldInsn.owner, fieldInsn.name);
                    if (rt != null) {
                        var ref = new StaticFieldRef(fieldInsn.owner, fieldInsn.name);
                        if (!results.contains(ref)) results.add(ref);
                    }
                } else if (insn instanceof TypeInsnNode typeInsn) {
                    if (typeInsn.desc != null) {
                        String name = typeInsn.desc.replace('/', '.');
                        if (isRelevantClassByName(name)) referencedClasses.add(name);
                    }
                } else if (insn instanceof MethodInsnNode methodInsn) {
                    if (methodInsn.owner != null) {
                        String name = methodInsn.owner.replace('/', '.');
                        if (isRelevantClassByName(name)) referencedClasses.add(name);
                    }
                }
            }
        }

        if (depth < 1) {
            for (String refClsName : referencedClasses) {
                try {
                    Class<?> refCls = Class.forName(refClsName);
                    if (isRelevantClassType(refCls)) {
                        var deepRefs = findRecipeTypeReferencesASM(refCls, visitedClasses, depth + 1);
                        for (var ref : deepRefs) {
                            if (!results.contains(ref)) results.add(ref);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean isRelevantClassByName(String className) {
        return !className.startsWith("java.") && !className.startsWith("javax.") && !className.startsWith("net.minecraft.");
    }

    private static boolean isRelevantClassType(Class<?> cls) {
        if (!curClsValid(cls)) return false;
        return AbstractContainerMenu.class.isAssignableFrom(cls)
                || MenuProvider.class.isAssignableFrom(cls)
                || BlockEntity.class.isAssignableFrom(cls)
                || Block.class.isAssignableFrom(cls);
    }

    private static @Nullable RecipeType<?> extractStaticRecipeType(String ownerClass, String fieldName) {
        try {
            Class<?> cls = Class.forName(ownerClass.replace('/', '.'));
            Field f = cls.getDeclaredField(fieldName);
            f.setAccessible(true);
            if (!Modifier.isStatic(f.getModifiers())) return null;
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
        switch (obj) {
            case null -> {
                return null;
            }
            case RecipeType<?> rt -> {
                return rt;
            }
            case Holder<?> holder -> {
                try {
                    if (holder.isBound()) {
                        Object val = holder.value();
                        if (val instanceof RecipeType<?> rt) return rt;
                    }
                    var keyOpt = holder.unwrapKey();
                    if (keyOpt.isPresent()) {
                        ResourceLocation loc = keyOpt.get().location();
                        RecipeType<?> rt = BuiltInRegistries.RECIPE_TYPE.get(loc);
                        if (rt != null) return rt;
                    }
                } catch (Throwable ignored) {
                }
            }
            default -> {
            }
        }

        if (obj instanceof ResourceLocation rl) {
            try {
                return BuiltInRegistries.RECIPE_TYPE.get(rl);
            } catch (Throwable ignored) {
            }
        }

        if (obj instanceof Supplier<?> supplier) {
            try {
                return unwrapRecipeType(supplier.get());
            } catch (Throwable ignored) {
            }
        }

        if (obj instanceof Optional<?> opt) {
            if (opt.isPresent()) return unwrapRecipeType(opt.get());
        }

        try {
            for (Method m : obj.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && (RecipeType.class.isAssignableFrom(m.getReturnType()) || Holder.class.isAssignableFrom(m.getReturnType()))) {
                    m.setAccessible(true);
                    Object res = m.invoke(obj);
                    RecipeType<?> rt = unwrapRecipeType(res);
                    if (rt != null) return rt;
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private ClassInfo classInfo(Class<?> clazz) {
        ClassInfo info = classInfoCache.get(clazz);
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
                Class<?> rt = method.getReturnType();
                if (rt != void.class && rt != Void.class && !rt.isPrimitive()) {
                    if (RecipeType.class.isAssignableFrom(rt) || Supplier.class.isAssignableFrom(rt) ||
                            Holder.class.isAssignableFrom(rt) || Optional.class.isAssignableFrom(rt)) {
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
    private RecipeType<?> findRecipeTypeDeep(Object obj, int depth, ReferenceSet<Object> visited) {
        if (obj == null || depth > 5 || !visited.add(obj)) return null;

        RecipeType<?> directUnwrap = unwrapRecipeType(obj);
        if (directUnwrap != null) return directUnwrap;

        if (obj instanceof Iterable<?> coll) {
            for (Object item : coll) {
                RecipeType<?> res = findRecipeTypeDeep(item, depth + 1, visited);
                if (res != null) return res;
            }
            return null;
        }

        if (obj instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                RecipeType<?> res = findRecipeTypeDeep(item, depth + 1, visited);
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

                if (isComplexObject(val)) {
                    RecipeType<?> deep = findRecipeTypeDeep(val, depth + 1, visited);
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

                if (isComplexObject(val)) {
                    RecipeType<?> deep = findRecipeTypeDeep(val, depth + 1, visited);
                    if (deep != null) return deep;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean isComplexObject(@Nullable Object obj) {
        if (obj == null) return false;
        Class<?> c = obj.getClass();
        String name = c.getName();
        return !c.isPrimitive() && !c.isEnum() && !name.startsWith("java.") && !name.startsWith("javax.");
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

        var instList = instanceMapping.computeIfAbsent(recipeType, k -> new ObjectArrayList<>());
        if (!instList.contains(item)) {
            instList.add(item);
        }

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

        RecipeType<?> rt = BuiltInRegistries.RECIPE_TYPE.get(typeRL);
        if (rt != null) {
            var instList = instanceMapping.computeIfAbsent(rt, k -> new ObjectArrayList<>());
            if (!instList.contains(item)) instList.add(item);
        }

        ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Mapped vanilla machine: '{}' -> '{}'", recipeTypeId, itemId);
    }

    private record ClassInfo(Method[] recipeMethods, Field[] fields) {
    }
}