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

package org.complexityanalyzer.harvest.machine;

import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.harvest.debug.MachineRegistryDebugLogger;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.function.BiPredicate;

import static net.minecraft.core.BlockPos.ZERO;
import static net.minecraft.world.item.Items.AIR;

public final class MachineControllerGraphScanner {

    private static final int MAX_GRAPH_DEPTH = 3;

    private MachineControllerGraphScanner() {
    }

    public static int scanAndResolveControllers(BiPredicate<RecipeType<?>, Item> registrar, MachineRegistryDebugLogger logger) {
        long start = System.nanoTime();
        logger.logControllerScanStart();

        var blocksByNamespace = new Object2ObjectOpenHashMap<String, ObjectList<Block>>();
        for (var block : GameRegistryManager.getAllBlocks()) {
            var blockId = GameRegistryManager.getBlockId(block);
            if (blockId == null) continue;
            blocksByNamespace.computeIfAbsent(blockId.getNamespace(), k -> new ObjectArrayList<>()).add(block);
        }

        int totalResolved = 0;

        for (var entry : blocksByNamespace.object2ObjectEntrySet()) {
            String modId = entry.getKey();
            var modBlocks = entry.getValue();
            int resolvedForMod = processIsolatedMod(modId, modBlocks, registrar, logger);
            totalResolved += resolvedForMod;
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        logger.logControllerScanFinish(totalResolved, elapsedMs);
        return totalResolved;
    }

    private static int processIsolatedMod(
            String modId,
            ObjectList<Block> modBlocks,
            BiPredicate<RecipeType<?>, Item> registrar,
            MachineRegistryDebugLogger logger) {

        var classToPhysicalItem = new Reference2ObjectOpenHashMap<Class<?>, Item>();
        var virtualClasses = new ReferenceOpenHashSet<Class<?>>();
        var modPackages = new ObjectOpenHashSet<String>();

        for (var block : modBlocks) {
            var item = block.asItem();
            var bCls = block.getClass();
            collectClassMetadata(bCls, item, classToPhysicalItem, virtualClasses, modPackages);

            if (block instanceof EntityBlock eb) {
                try {
                    var be = eb.newBlockEntity(ZERO, block.defaultBlockState());
                    if (be != null) {
                        collectClassMetadata(be.getClass(), item, classToPhysicalItem, virtualClasses, modPackages);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        if (virtualClasses.isEmpty()) return 0;

        var allowedRoots = extractModPackageRoots(modPackages);
        var modPrefix = computeLongestCommonPrefix(modPackages);

        logger.logControllerModScope(modId, modPrefix, allowedRoots, virtualClasses.size(), classToPhysicalItem.size());

        var classToDirectRecipes = new Reference2ObjectOpenHashMap<Class<?>, ObjectList<RecipeType<?>>>();
        var localModGraph = new Reference2ObjectOpenHashMap<Class<?>, ReferenceSet<Class<?>>>();

        for (var vCls : virtualClasses) {
            scanModClassBytecode(vCls, modPrefix, allowedRoots, classToDirectRecipes, localModGraph, logger);
        }
        for (var pCls : classToPhysicalItem.keySet()) {
            scanModClassBytecode(pCls, modPrefix, allowedRoots, classToDirectRecipes, localModGraph, logger);
        }

        int resolvedCount = 0;

        for (var recipeEntry : classToDirectRecipes.reference2ObjectEntrySet()) {
            var sourceCls = recipeEntry.getKey();
            var recipes = recipeEntry.getValue();
            if (recipes.isEmpty()) continue;

            var resolvedItem = resolvePhysicalItemBfs(sourceCls, localModGraph, classToPhysicalItem, logger);
            if (resolvedItem != null && resolvedItem != AIR) {
                for (var rt : recipes) {
                    if (registrar.test(rt, resolvedItem)) {
                        resolvedCount++;
                        logger.logControllerResolved(modId, sourceCls, rt, resolvedItem);
                    }
                }
            } else {
                logger.logControllerUnresolved(modId, sourceCls, recipes);
            }
        }

        return resolvedCount;
    }

    private static void collectClassMetadata(
            Class<?> clazz,
            Item item,
            Reference2ObjectMap<Class<?>, Item> classToPhysicalItem,
            ReferenceSet<Class<?>> virtualClasses,
            ObjectSet<String> modPackages) {

        if (item != AIR) {
            classToPhysicalItem.put(clazz, item);
        } else {
            virtualClasses.add(clazz);
        }

        var pkg = clazz.getPackage();
        if (pkg != null && !pkg.getName().isEmpty()) {
            modPackages.add(pkg.getName());
        }
    }

    private static @Nullable Item resolvePhysicalItemBfs(
            Class<?> rootClass,
            Reference2ObjectMap<Class<?>, ReferenceSet<Class<?>>> graph,
            Reference2ObjectMap<Class<?>, Item> classToPhysicalItem,
            MachineRegistryDebugLogger logger) {

        var directItem = classToPhysicalItem.get(rootClass);
        if (directItem != null && directItem != AIR) return directItem;

        var visited = new ReferenceOpenHashSet<Class<?>>();
        var queue = new ArrayDeque<BfsStep>();
        var candidates = new ObjectArrayList<CandidateResult>();

        queue.add(new BfsStep(rootClass, 0, rootClass.getSimpleName()));
        visited.add(rootClass);

        String rootPkg = getPackageName(rootClass);

        while (!queue.isEmpty()) {
            var step = queue.poll();
            var current = step.clazz;
            int depth = step.depth;

            var item = classToPhysicalItem.get(current);
            if (item != null && item != AIR) {
                int score = calculatePackageSimilarityScore(rootPkg, getPackageName(current), depth);
                candidates.add(new CandidateResult(item, current, depth, score, step.path));
            }

            if (depth < MAX_GRAPH_DEPTH) {
                var neighbors = graph.get(current);
                if (neighbors != null) {
                    for (var neighbor : neighbors) {
                        if (visited.add(neighbor)) {
                            queue.add(new BfsStep(neighbor, depth + 1, step.path + " -> " + neighbor.getSimpleName()));
                        }
                    }
                }
            }
        }

        if (candidates.isEmpty()) return null;

        candidates.sort(Comparator.comparingInt(CandidateResult::score).reversed()
                .thenComparingInt(CandidateResult::depth));

        var best = candidates.getFirst();
        logger.logControllerBfsMatch(rootClass, best.targetClass, best.item, best.path, best.depth, best.score);

        return best.item;
    }

    private static int calculatePackageSimilarityScore(String sourcePkg, String targetPkg, int depth) {
        String[] srcParts = sourcePkg.split("\\.");
        String[] tgtParts = targetPkg.split("\\.");
        int commonSegments = 0;
        int minLen = Math.min(srcParts.length, tgtParts.length);

        for (int i = 0; i < minLen; i++) {
            if (srcParts[i].equals(tgtParts[i])) commonSegments++;
            else break;
        }

        return (commonSegments * 100) - (depth * 10);
    }

    private static void scanModClassBytecode(
            Class<?> clazz,
            String modPrefix,
            ObjectSet<String> allowedRoots,
            Reference2ObjectMap<Class<?>, ObjectList<RecipeType<?>>> directRecipes,
            Reference2ObjectMap<Class<?>, ReferenceSet<Class<?>>> graph,
            MachineRegistryDebugLogger logger) {

        var current = clazz;
        var visited = new ObjectOpenHashSet<String>();

        while (isClassInModScope(current.getName(), modPrefix, allowedRoots) && visited.add(current.getName())) {
            try (var is = MachineAsmScanner.getClassInputStream(current, current.getName())) {
                if (is != null) {
                    var cn = new ClassNode();
                    new ClassReader(is).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    var cl = current.getClassLoader();

                    for (var f : cn.fields) {
                        if ((f.access & Modifier.STATIC) != 0 && f.desc.startsWith("L")) {
                            var rt = MachineAsmScanner.extractStaticRecipeType(cl, cn.name, f.name);
                            if (rt != null) {
                                addRecipe(clazz, rt, directRecipes);
                                logger.logControllerDirectRecipe(clazz, rt);
                            }
                        }
                        addClassEdgeFromDesc(cl, f.desc, clazz, modPrefix, allowedRoots, graph);
                    }

                    for (var method : cn.methods) {
                        if (method.instructions == null) continue;
                        for (var insn : method.instructions) {
                            switch (insn) {
                                case FieldInsnNode f -> {
                                    if (f.getOpcode() == Opcodes.GETSTATIC && f.desc.startsWith("L")) {
                                        var rt = MachineAsmScanner.extractStaticRecipeType(cl, f.owner, f.name);
                                        if (rt != null) {
                                            addRecipe(clazz, rt, directRecipes);
                                            logger.logControllerDirectRecipe(clazz, rt);
                                        }
                                    }
                                    addClassEdgeFromInternal(cl, f.owner, clazz, modPrefix, allowedRoots, graph);
                                    addClassEdgeFromDesc(cl, f.desc, clazz, modPrefix, allowedRoots, graph);
                                }
                                case MethodInsnNode m -> {
                                    addClassEdgeFromInternal(cl, m.owner, clazz, modPrefix, allowedRoots, graph);
                                    addClassEdgeFromDesc(cl, m.desc, clazz, modPrefix, allowedRoots, graph);
                                }
                                case TypeInsnNode t ->
                                        addClassEdgeFromInternal(cl, t.desc, clazz, modPrefix, allowedRoots, graph);
                                case LdcInsnNode ldc when ldc.cst instanceof Type t ->
                                        addClassEdgeFromDesc(cl, t.getDescriptor(), clazz, modPrefix, allowedRoots, graph);
                                case InvokeDynamicInsnNode idin -> {
                                    for (var arg : idin.bsmArgs) {
                                        if (arg instanceof Handle h)
                                            addClassEdgeFromInternal(cl, h.getOwner(), clazz, modPrefix, allowedRoots, graph);
                                        else if (arg instanceof Type t)
                                            addClassEdgeFromDesc(cl, t.getDescriptor(), clazz, modPrefix, allowedRoots, graph);
                                    }
                                }
                                default -> {
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            var superCls = current.getSuperclass();
            if (superCls == null || superCls == Object.class) break;
            current = superCls;
        }
    }

    private static void addRecipe(Class<?> clazz, RecipeType<?> rt, Reference2ObjectMap<Class<?>, ObjectList<RecipeType<?>>> directRecipes) {
        var list = directRecipes.computeIfAbsent(clazz, k -> new ObjectArrayList<>());
        if (!list.contains(rt)) list.add(rt);
    }

    private static void addClassEdgeFromInternal(
            ClassLoader cl,
            @Nullable String internalName,
            Class<?> source,
            String modPrefix,
            ObjectSet<String> allowedRoots,
            Reference2ObjectMap<Class<?>, ReferenceSet<Class<?>>> graph) {

        if (internalName == null || internalName.isEmpty() || internalName.startsWith("[")) return;
        String name = internalName.replace('/', '.');

        if (!isClassInModScope(name, modPrefix, allowedRoots)) return;

        try {
            var target = Class.forName(name, false, cl);
            if (target != source && target != Object.class) {
                graph.computeIfAbsent(source, k -> new ReferenceOpenHashSet<>()).add(target);
                graph.computeIfAbsent(target, k -> new ReferenceOpenHashSet<>()).add(source);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void addClassEdgeFromDesc(
            ClassLoader cl,
            @Nullable String desc,
            Class<?> source,
            String modPrefix,
            ObjectSet<String> allowedRoots,
            Reference2ObjectMap<Class<?>, ReferenceSet<Class<?>>> graph) {

        if (desc == null || desc.isEmpty()) return;
        int idx = 0;
        while ((idx = desc.indexOf('L', idx)) != -1) {
            int end = desc.indexOf(';', idx);
            if (end == -1) break;
            addClassEdgeFromInternal(cl, desc.substring(idx + 1, end), source, modPrefix, allowedRoots, graph);
            idx = end + 1;
        }
    }

    private static boolean isClassInModScope(String className, String modPrefix, ObjectSet<String> allowedRoots) {
        if (className.isEmpty()) return false;
        if (!modPrefix.isEmpty() && className.startsWith(modPrefix)) return true;
        for (var root : allowedRoots) {
            if (className.startsWith(root)) return true;
        }
        return false;
    }

    private static String computeLongestCommonPrefix(ObjectSet<String> packages) {
        if (packages.isEmpty()) return "";
        var list = new ObjectArrayList<>(packages);
        String prefix = list.getFirst();

        for (int i = 1; i < list.size(); i++) {
            String current = list.get(i);
            int minLen = Math.min(prefix.length(), current.length());
            int j = 0;
            while (j < minLen && prefix.charAt(j) == current.charAt(j)) j++;
            prefix = prefix.substring(0, j);
            if (prefix.isEmpty()) break;
        }

        int lastDot = prefix.lastIndexOf('.');
        return lastDot != -1 ? prefix.substring(0, lastDot + 1) : "";
    }

    private static ObjectSet<String> extractModPackageRoots(ObjectSet<String> packages) {
        var roots = new ObjectOpenHashSet<String>();
        for (var pkg : packages) {
            String[] parts = pkg.split("\\.");
            int take = Math.min(parts.length, 3);
            if (take > 0) {
                var sb = new StringBuilder();
                for (int i = 0; i < take; i++) sb.append(parts[i]).append('.');
                roots.add(sb.toString());
            }
        }
        return roots;
    }

    private static String getPackageName(Class<?> cls) {
        var pkg = cls.getPackage();
        return pkg != null ? pkg.getName() : "";
    }

    private record BfsStep(Class<?> clazz, int depth, String path) {
    }

    private record CandidateResult(Item item, Class<?> targetClass, int depth, int score, String path) {
    }
}