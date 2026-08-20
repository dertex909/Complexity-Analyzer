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
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.crafting.RecipeType;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.InputStream;
import java.lang.reflect.Modifier;

public final class MachineAsmScanner {

    private MachineAsmScanner() {
    }

    public static Object2ObjectMap<Class<?>, ObjectList<RecipeType<?>>> precomputeAsmResults(ReferenceSet<Class<?>> classes) {
        var results = new Object2ObjectOpenHashMap<Class<?>, ObjectList<RecipeType<?>>>();
        var visited = new ObjectOpenHashSet<String>();

        for (var clazz : classes) {
            if (!MachineTypeUnwrapper.curClsValid(clazz)) continue;
            visited.clear();

            var recipeTypes = new ObjectArrayList<RecipeType<?>>();
            findRecipeTypeReferencesASM(clazz, visited, recipeTypes);
            if (!recipeTypes.isEmpty()) results.put(clazz, recipeTypes);
        }
        return results;
    }

    public static void findRecipeTypeReferencesASM(Class<?> clazz, ObjectSet<String> visitedClasses, ObjectList<RecipeType<?>> outRefs) {
        if (!MachineTypeUnwrapper.curClsValid(clazz) || !visitedClasses.add(clazz.getName())) return;
//хуй
        try (var is = getClassInputStream(clazz, clazz.getName())) {
            if (is != null) {
                var cn = new ClassNode();
                new ClassReader(is).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                var cl = clazz.getClassLoader();
                scanStaticFields(cl, cn, outRefs);
                scanClassMethodBytecode(cl, cn, outRefs);
                scanReferencedMenus(clazz, cn, visitedClasses, outRefs);
            }
        } catch (Throwable ignored) {
        }

        var superCls = clazz.getSuperclass();
        if (MachineTypeUnwrapper.curClsValid(superCls)) findRecipeTypeReferencesASM(superCls, visitedClasses, outRefs);
    }

    private static void scanStaticFields(ClassLoader cl, ClassNode cn, ObjectList<RecipeType<?>> outRefs) {
        for (var field : cn.fields) {
            if ((field.access & Modifier.STATIC) != 0 && isPotentialRecipeTypeDescriptor(field.desc)) {
                tryAddRecipeRef(cl, cn.name, field.name, outRefs);
            }
        }
    }

    private static void scanClassMethodBytecode(ClassLoader cl, ClassNode cn, ObjectList<RecipeType<?>> outRefs) {
        for (var method : cn.methods) {
            if (method.instructions == null) continue;
            for (var insn : method.instructions) {
                if (insn.getOpcode() == Opcodes.GETSTATIC && insn instanceof FieldInsnNode f && isPotentialRecipeTypeDescriptor(f.desc)) {
                    tryAddRecipeRef(cl, f.owner, f.name, outRefs);
                }
            }
        }
    }

    private static void scanReferencedMenus(Class<?> rootClass, ClassNode cn, ObjectSet<String> visitedClasses, ObjectList<RecipeType<?>> outRefs) {
        var referencedClasses = new ObjectOpenHashSet<String>();

        for (var method : cn.methods) {
            if (method.instructions == null) continue;
            for (var insn : method.instructions) collectClassFromInsn(insn, referencedClasses);
        }

        var cl = rootClass.getClassLoader();
        for (var refInternal : referencedClasses) {
            String refClassName = refInternal.replace('/', '.');
            if (!MachineTypeUnwrapper.curClsValidName(refClassName) || visitedClasses.contains(refClassName)) continue;

            try {
                var targetCls = Class.forName(refClassName, false, cl);
                if (AbstractContainerMenu.class.isAssignableFrom(targetCls)) {
                    visitedClasses.add(refClassName);
                    scanMenuBytecode(targetCls, refClassName, outRefs);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void collectClassFromInsn(AbstractInsnNode insn, ObjectSet<String> out) {
        switch (insn) {
            case TypeInsnNode tin when tin.getOpcode() == Opcodes.NEW && isModInternalClass(tin.desc) ->
                    out.add(tin.desc);
            case InvokeDynamicInsnNode idin -> {
                for (var arg : idin.bsmArgs) {
                    if (arg instanceof Handle h && isModInternalClass(h.getOwner())) out.add(h.getOwner());
                }
            }
            default -> {
            }
        }
    }

    private static void scanMenuBytecode(Class<?> menuClass, String menuClassName, ObjectList<RecipeType<?>> outRefs) {
        try (var is = getClassInputStream(menuClass, menuClassName)) {
            if (is == null) return;
            var cn = new ClassNode();
            new ClassReader(is).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            scanClassMethodBytecode(menuClass.getClassLoader(), cn, outRefs);
        } catch (Throwable ignored) {
        }
    }

    private static void tryAddRecipeRef(ClassLoader cl, String owner, String name, ObjectList<RecipeType<?>> outRecipes) {
        var rt = extractStaticRecipeType(cl, owner, name);
        if (rt != null && !outRecipes.contains(rt)) outRecipes.add(rt);
    }

    private static @Nullable InputStream getClassInputStream(Class<?> clazz, String className) {
        String classPath = className.replace('.', '/') + ".class";
        var is = clazz.getResourceAsStream("/" + classPath);
        if (is != null) return is;
        var cl = clazz.getClassLoader();
        return cl != null ? cl.getResourceAsStream(classPath) : null;
    }

    private static boolean isModInternalClass(@Nullable String internalName) {
        if (internalName == null || internalName.isEmpty() || internalName.startsWith("[")) return false;
        return MachineTypeUnwrapper.curClsValidName(internalName.replace('/', '.'));
    }

    private static boolean isPotentialRecipeTypeDescriptor(@Nullable String desc) {
        return desc != null && desc.startsWith("L");
    }

    @Nullable
    public static RecipeType<?> extractStaticRecipeType(ClassLoader cl, String ownerClass, String fieldName) {
        try {
            var cls = Class.forName(ownerClass.replace('/', '.'), false, cl);
            var f = cls.getDeclaredField(fieldName);
            f.setAccessible(true);
            if (!Modifier.isStatic(f.getModifiers())) return null;
            return MachineTypeUnwrapper.unwrapRecipeType(f.get(null));
        } catch (Throwable ignored) {
        }
        return null;
    }
}