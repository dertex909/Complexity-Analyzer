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
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.cache.MachineRegistryCache;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.GameRegistryManager;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static net.minecraft.core.BlockPos.ZERO;
import static net.minecraft.core.registries.Registries.RECIPE_TYPE;
import static net.minecraft.world.item.Items.AIR;

public class MachineRegistry {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private static final MethodInfo[] EMPTY_METHOD_INFOS = new MethodInfo[0];
    private static final FieldInfo[] EMPTY_FIELD_INFOS = new FieldInfo[0];
    private static final ClassInfo EMPTY_INFO = new ClassInfo(EMPTY_METHOD_INFOS, EMPTY_FIELD_INFOS, EMPTY_FIELD_INFOS);

    private final Object2ObjectMap<ResourceLocation, ObjectList<Item>> idMapping = new Object2ObjectOpenHashMap<>();
    private final Reference2ObjectMap<RecipeType<?>, ObjectList<Item>> instanceMapping = new Reference2ObjectOpenHashMap<>();
    private final Object2ObjectMap<Class<?>, ClassInfo> classInfoCache = new Object2ObjectOpenHashMap<>();

    private boolean initialized = false;

    private static void findRecipeTypeReferencesASM(Class<?> clazz, ObjectSet<String> visitedClasses, ObjectList<StaticFieldRef> outRefs) {
        outRefs.clear();
        if (!curClsValid(clazz)) return;

        String className = clazz.getName();
        if (!visitedClasses.add(className)) return;

        try (var is = getClassInputStream(clazz, className)) {
            if (is != null) processBytecodeASM(is, outRefs);
        } catch (Throwable ignored) {
        }
    }

    private static @Nullable InputStream getClassInputStream(Class<?> clazz, String className) {
        String classPath = className.replace('.', '/') + ".class";
        var is = clazz.getResourceAsStream("/" + classPath);
        if (is != null) return is;
        var cl = clazz.getClassLoader();
        return cl != null ? cl.getResourceAsStream(classPath) : null;
    }

    private static void processBytecodeASM(InputStream is, ObjectList<StaticFieldRef> results) throws Exception {
        var cr = new ClassReader(is);
        var cn = new ClassNode();
        cr.accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        for (var field : cn.fields) {
            if ((field.access & Modifier.STATIC) != 0) {
                var directRt = extractStaticRecipeType(cn.name, field.name);
                if (directRt != null) {
                    var ref = new StaticFieldRef(cn.name, field.name);
                    if (!results.contains(ref)) results.add(ref);
                }
            }
        }

        for (var method : cn.methods) {
            if (method.instructions == null) continue;
            for (var insn : method.instructions) {
                if (insn instanceof FieldInsnNode fieldInsn) if (isPotentialRecipeTypeDescriptor(fieldInsn.desc)) {
                    var rt = extractStaticRecipeType(fieldInsn.owner, fieldInsn.name);
                    if (rt != null) {
                        var ref = new StaticFieldRef(fieldInsn.owner, fieldInsn.name);
                        if (!results.contains(ref)) results.add(ref);
                    }
                }
            }
        }
    }

    private static boolean isPotentialRecipeTypeDescriptor(@Nullable String desc) {
        if (desc == null) return false;
        return desc.contains("RecipeType") || desc.contains("Holder") || desc.contains("Supplier")
                || desc.contains("DeferredHolder") || desc.contains("RegistryObject");
    }

    private static @Nullable RecipeType<?> extractStaticRecipeType(String ownerClass, String fieldName) {
        try {
            var cls = Class.forName(ownerClass.replace('/', '.'));
            var f = cls.getDeclaredField(fieldName);
            f.setAccessible(true);
            if (!Modifier.isStatic(f.getModifiers())) return null;
            var mh = LOOKUP.unreflectGetter(f);
            var raw = mh.invoke();
            return unwrapRecipeType(raw);
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Nullable
    private static RecipeType<?> unwrapRecipeType(@Nullable Object obj) {
        if (obj == null) return null;
        if (obj instanceof RecipeType<?> rt) return rt;

        if (obj instanceof Block || obj instanceof Item || obj instanceof BlockEntity || obj instanceof BlockEntityType<?>
                || obj instanceof SoundEvent || obj instanceof Fluid || obj instanceof EntityType<?>) return null;

        switch (obj) {
            case Holder<?> holder -> {
                try {
                    if (holder.isBound()) {
                        var val = holder.value();
                        var rt = unwrapRecipeType(val);
                        if (rt != null) return rt;
                    }
                    var keyOpt = holder.unwrapKey();
                    if (keyOpt.isPresent()) {
                        var key = keyOpt.get();
                        if (key.isFor(RECIPE_TYPE)) return BuiltInRegistries.RECIPE_TYPE.get(key.location());
                    }
                } catch (Throwable ignored) {
                }
                return null;
            }

            case Supplier<?> supplier -> {
                try {
                    return unwrapRecipeType(supplier.get());
                } catch (Throwable ignored) {
                }
                return null;
            }

            case Optional<?> opt -> {
                return opt.map(MachineRegistry::unwrapRecipeType).orElse(null);
            }
            default -> {
            }
        }

        try {
            for (var m : obj.getClass().getMethods()) {
                if (m.getParameterCount() == 0) {
                    var rt = m.getReturnType();
                    if (RecipeType.class.isAssignableFrom(rt) || Holder.class.isAssignableFrom(rt)) {
                        m.setAccessible(true);
                        var res = m.invoke(obj);
                        if (res != null && res != obj) {
                            var type = unwrapRecipeType(res);
                            if (type != null) return type;
                        }
                    }
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
        return instanceMapping.get(type);
    }

    private int registerModdedMachines(MinecraftServer server) {
        int registeredCount = 0;
        int entityBlocks = 0;
        int errors = 0;

        var blocks = GameRegistryManager.getAllBlocks();
        int totalBlocks = blocks.size();

        var logger = new MachineRegistryDebugLogger(server, totalBlocks);

        var uniqueTargetClasses = new ReferenceOpenHashSet<Class<?>>();
        for (var block : blocks) {
            if (block.asItem() == AIR) continue;
            if (block instanceof EntityBlock entityBlock) {
                try {
                    var be = entityBlock.newBlockEntity(ZERO, block.defaultBlockState());
                    uniqueTargetClasses.add(Objects.requireNonNullElse(be, block).getClass());
                } catch (Throwable ignored) {
                    uniqueTargetClasses.add(block.getClass());
                }
            } else {
                uniqueTargetClasses.add(block.getClass());
            }
        }

        var classAsmResults = new Object2ObjectOpenHashMap<Class<?>, ObjectList<StaticFieldRef>>();
        var scannedAsmClasses = new ObjectOpenHashSet<String>();
        var asmRefBuffer = new ObjectArrayList<StaticFieldRef>();

        for (var clazz : uniqueTargetClasses) {
            if (!curClsValid(clazz)) continue;
            findRecipeTypeReferencesASM(clazz, scannedAsmClasses, asmRefBuffer);
            classAsmResults.put(clazz, new ObjectArrayList<>(asmRefBuffer));
        }

        var classStaticResults = new Object2ObjectOpenHashMap<Class<?>, ObjectList<RecipeType<?>>>();
        var deepScanVisitedBuffer = new ReferenceOpenHashSet<>();

        for (var block : blocks) {
            var machineItem = block.asItem();
            if (machineItem == AIR) continue;

            var blockId = GameRegistryManager.getBlockId(block);
            boolean isEntityBlock = block instanceof EntityBlock;

            logger.logBlockHeader(blockId, machineItem, block.getClass());

            try {
                BlockEntity be = null;
                if (isEntityBlock) {
                    entityBlocks++;
                    try {
                        be = ((EntityBlock) block).newBlockEntity(ZERO, block.defaultBlockState());
                        if (be != null) {
                            logger.logBeCreated(be);
                        } else {
                            logger.logBeCreateNull();
                        }
                    } catch (Throwable t) {
                        logger.logBeCreateFailed(t);
                    }
                } else {
                    logger.logNonBeBlock();
                }

                var targetClass = be != null ? be.getClass() : block.getClass();
                int blockMatchedCount = 0;

                int asmScanned = scanClassBytecodeASM(targetClass, machineItem, classAsmResults, logger);
                blockMatchedCount += asmScanned;

                if (be != null) {
                    int scanned = scanBlockEntityInstance(be, machineItem, logger);
                    blockMatchedCount += scanned;

                    if (scanned == 0 && asmScanned == 0) {
                        logger.logDeepScanStart();
                        deepScanVisitedBuffer.clear();
                        var rt = findRecipeTypeDeep(be, 0, deepScanVisitedBuffer, logger);
                        boolean matched = rt != null && registerDynamicMachine(rt, machineItem);
                        logger.logDeepResult(matched ? rt : null, machineItem);
                        if (matched) blockMatchedCount++;
                    }
                } else {
                    int staticScanned = scanStaticFieldsOnly(block.getClass(), machineItem, classStaticResults, logger);
                    blockMatchedCount += staticScanned;
                }

                registeredCount += blockMatchedCount;
                logger.logBlockResult(blockId, blockMatchedCount);

            } catch (Throwable t) {
                errors++;
                logger.logFatalBlockError(t);
            }
        }

        logger.finishAndSave(totalBlocks, entityBlocks, registeredCount, errors);
        ComplexityAnalyzer.LOGGER.info("[MachineRegistry:SUMMARY] Scan finished! Registered Machines={}, Errors={}.", registeredCount, errors);

        return registeredCount;
    }

    private int scanClassBytecodeASM(Class<?> clazz, Item machineItem, Object2ObjectOpenHashMap<Class<?>, ObjectList<StaticFieldRef>> classAsmResults, MachineRegistryDebugLogger logger) {
        if (!curClsValid(clazz)) return 0;

        var refs = classAsmResults.get(clazz);
        if (refs == null) return 0;

        int count = 0;
        logger.logAsmScanStart(clazz, refs.size());
        for (var ref : refs) {
            try {
                var rt = extractStaticRecipeType(ref.ownerClass(), ref.fieldName());
                boolean matched = false;
                if (rt != null && registerDynamicMachine(rt, machineItem)) {
                    matched = true;
                    count++;
                }
                logger.logAsmRef(ref.ownerClass(), ref.fieldName(), rt, matched, null);
            } catch (Throwable t) {
                logger.logAsmRef(ref.ownerClass(), ref.fieldName(), null, false, t);
            }
        }
        return count;
    }

    private int scanBlockEntityInstance(BlockEntity be, Item machineItem, MachineRegistryDebugLogger logger) {
        int count = 0;
        var beClass = be.getClass();
        var info = classInfo(beClass);

        logger.logBeMethodScanStart(beClass);
        for (var mInfo : info.recipeMethods()) {
            try {
                var raw = mInfo.handle().invoke(be);
                var recipeType = unwrapRecipeType(raw);
                boolean matched = false;
                if (recipeType != null && registerDynamicMachine(recipeType, machineItem)) {
                    matched = true;
                    count++;
                }
                logger.logBeMethod(mInfo.method(), raw, recipeType, matched, null);
            } catch (Throwable t) {
                logger.logBeMethod(mInfo.method(), null, null, false, t);
            }
        }

        logger.logBeHierarchyClass(beClass);
        for (var fInfo : info.instanceFields()) {
            try {
                var val = fInfo.handle().invoke(be);
                var recipeType = unwrapRecipeType(val);
                boolean matched = false;
                if (recipeType != null && registerDynamicMachine(recipeType, machineItem)) {
                    matched = true;
                    count++;
                }
                logger.logBeField(fInfo.field(), val, recipeType, matched, null);
            } catch (Throwable t) {
                logger.logBeField(fInfo.field(), null, null, false, t);
            }
        }

        return count;
    }

    private int scanStaticFieldsOnly(Class<?> clazz, Item machineItem, Object2ObjectOpenHashMap<Class<?>, ObjectList<RecipeType<?>>> classStaticResults, MachineRegistryDebugLogger logger) {
        var recipeTypes = classStaticResults.get(clazz);
        if (recipeTypes == null) {
            recipeTypes = new ObjectArrayList<>();
            logger.logStaticScanStart(clazz);
            var info = classInfo(clazz);
            for (var fInfo : info.staticFields()) {
                try {
                    var val = fInfo.handle().invoke();
                    var recipeType = unwrapRecipeType(val);
                    if (recipeType != null && !recipeTypes.contains(recipeType)) recipeTypes.add(recipeType);
                } catch (Throwable ignored) {
                }
            }
            classStaticResults.put(clazz, recipeTypes);
        }

        int count = 0;
        for (var rt : recipeTypes) if (registerDynamicMachine(rt, machineItem)) count++;
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

    private static boolean isCandidateMethodName(String name) {
        if (name.length() < 3) return false;
        char c0 = name.charAt(0);
        if (c0 == 'g') return name.startsWith("get");
        if (c0 == 'r') return name.startsWith("recipe");
        if (c0 == 't') return name.startsWith("type");
        return false;
    }

    private ClassInfo buildClassInfo(Class<?> clazz) {
        if (!curClsValid(clazz)) return EMPTY_INFO;

        var methods = new ObjectArrayList<MethodInfo>();
        for (var method : clazz.getMethods()) {
            if (method.getParameterCount() == 0) {
                var rt = method.getReturnType();
                if (rt != void.class && rt != Void.class && !rt.isPrimitive()) {
                    boolean isValidType = RecipeType.class.isAssignableFrom(rt) || Supplier.class.isAssignableFrom(rt)
                            || Holder.class.isAssignableFrom(rt) || Optional.class.isAssignableFrom(rt);

                    if (isValidType || isCandidateMethodName(method.getName())) try {
                        method.setAccessible(true);
                        var mh = LOOKUP.unreflect(method);
                        methods.add(new MethodInfo(mh, method));
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        var instanceFields = new ObjectArrayList<FieldInfo>();
        var staticFields = new ObjectArrayList<FieldInfo>();
        var current = clazz;

        while (curClsValid(current)) {
            for (var field : current.getDeclaredFields()) {
                if (field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    var mh = LOOKUP.unreflectGetter(field);
                    if (Modifier.isStatic(field.getModifiers())) {
                        staticFields.add(new FieldInfo(mh, field));
                    } else {
                        instanceFields.add(new FieldInfo(mh, field));
                    }
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }

        var methodArray = methods.isEmpty() ? EMPTY_METHOD_INFOS : methods.toArray(new MethodInfo[0]);
        var instFieldArray = instanceFields.isEmpty() ? EMPTY_FIELD_INFOS : instanceFields.toArray(new FieldInfo[0]);
        var staticFieldArray = staticFields.isEmpty() ? EMPTY_FIELD_INFOS : staticFields.toArray(new FieldInfo[0]);

        return new ClassInfo(methodArray, instFieldArray, staticFieldArray);
    }

    @Nullable
    private RecipeType<?> findRecipeTypeDeep(Object obj, int depth, ReferenceSet<Object> visited, MachineRegistryDebugLogger logger) {
        if (obj == null || depth > 5 || !visited.add(obj)) return null;

        var directUnwrap = unwrapRecipeType(obj);
        if (directUnwrap != null) {
            logger.logDeepMatch(obj.getClass(), directUnwrap, depth);
            return directUnwrap;
        }

        if (obj instanceof Iterable<?> coll) {
            int idx = 0;
            for (var item : coll) {
                if (item != null) {
                    logger.logDeepIter(item.getClass(), idx, depth);
                    var res = findRecipeTypeDeep(item, depth + 1, visited, logger);
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
                    logger.logDeepMap(entry.getKey(), val.getClass(), depth);
                    var res = findRecipeTypeDeep(val, depth + 1, visited, logger);
                    if (res != null) return res;
                }
            }
            return null;
        }

        var info = classInfo(obj.getClass());

        for (var mInfo : info.recipeMethods()) {
            try {
                var val = mInfo.handle().invoke(obj);
                if (val == null || val == obj) continue;

                var rt = unwrapRecipeType(val);
                boolean isComplex = isComplexObject(val);
                logger.logDeepMethod(mInfo.method(), val, rt, null, isComplex, depth);

                if (rt != null) return rt;

                if (isComplex) {
                    var deep = findRecipeTypeDeep(val, depth + 1, visited, logger);
                    if (deep != null) return deep;
                }
            } catch (Throwable t) {
                logger.logDeepMethod(mInfo.method(), null, null, t, false, depth);
            }
        }

        for (var fInfo : info.instanceFields()) {
            try {
                var val = fInfo.handle().invoke(obj);
                if (val == null || val == obj) continue;

                var rt = unwrapRecipeType(val);
                boolean isComplex = isComplexObject(val);
                logger.logDeepField(fInfo.field(), val, rt, null, isComplex, depth);

                if (rt != null) return rt;

                if (isComplex) {
                    var deep = findRecipeTypeDeep(val, depth + 1, visited, logger);
                    if (deep != null) return deep;
                }
            } catch (Throwable t) {
                logger.logDeepField(fInfo.field(), null, null, t, false, depth);
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
        if (item == AIR) return false;

        boolean added = false;
        var instList = instanceMapping.computeIfAbsent(recipeType, k -> new ObjectArrayList<>());
        if (!instList.contains(item)) {
            instList.add(item);
            added = true;
        }

        var typeId = GameRegistryManager.getRecipeTypeId(recipeType);
        if (typeId != null) {
            var list = idMapping.computeIfAbsent(typeId, k -> new ObjectArrayList<>());
            if (!list.contains(item)) {
                list.add(item);
                added = true;
                ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Mapped recipe type '{}' -> Machine item '{}'", typeId, GameRegistryManager.getItemId(item));
            }
        }
        return added;
    }

    private void register(String recipeTypeId, String itemId) {
        var typeRL = ResourceLocation.parse(recipeTypeId);
        var itemRL = ResourceLocation.parse(itemId);
        var item = GameRegistryManager.getItem(itemRL);

        if (item == null || item == AIR) {
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

    private record MethodInfo(MethodHandle handle, Method method) {
    }

    private record FieldInfo(MethodHandle handle, Field field) {
    }

    private record ClassInfo(MethodInfo[] recipeMethods, FieldInfo[] instanceFields, FieldInfo[] staticFields) {
    }
}