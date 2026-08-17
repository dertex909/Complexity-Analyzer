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
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Modifier;

public final class MachineAsmScanner {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private MachineAsmScanner() {
    }

    public static Object2ObjectMap<Class<?>, ObjectList<RecipeType<?>>> precomputeAsmResults(ReferenceSet<Class<?>> classes) {
        var results = new Object2ObjectOpenHashMap<Class<?>, ObjectList<RecipeType<?>>>();
        var visited = new ObjectOpenHashSet<String>();
        var refsBuffer = new ObjectArrayList<StaticFieldRef>();

        for (var clazz : classes) {
            if (!MachineTypeUnwrapper.curClsValid(clazz)) continue;
            refsBuffer.clear();
            visited.clear();

            findRecipeTypeReferencesASM(clazz, visited, refsBuffer);
            if (refsBuffer.isEmpty()) continue;

            var recipeTypes = new ObjectArrayList<RecipeType<?>>();
            for (var ref : refsBuffer) {
                try {
                    var rt = extractStaticRecipeType(ref.ownerClass(), ref.fieldName());
                    if (rt != null && !recipeTypes.contains(rt)) recipeTypes.add(rt);
                } catch (Throwable ignored) {
                }
            }

            if (!recipeTypes.isEmpty()) results.put(clazz, recipeTypes);
        }
        return results;
    }

    public static void findRecipeTypeReferencesASM(Class<?> clazz, ObjectSet<String> visitedClasses, ObjectList<StaticFieldRef> outRefs) {
        if (!MachineTypeUnwrapper.curClsValid(clazz) || !visitedClasses.add(clazz.getName())) return;

        try (var is = getClassInputStream(clazz, clazz.getName())) {
            if (is != null) {
                var cn = new ClassNode();
                new ClassReader(is).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                scanStaticFields(cn, outRefs);
                scanClassMethodBytecode(cn, outRefs);
                scanReferencedMenus(clazz, cn, visitedClasses, outRefs);
            }
        } catch (Throwable ignored) {
        }

        var superCls = clazz.getSuperclass();
        if (MachineTypeUnwrapper.curClsValid(superCls)) findRecipeTypeReferencesASM(superCls, visitedClasses, outRefs);
    }

    private static void scanStaticFields(ClassNode cn, ObjectList<StaticFieldRef> outRefs) {
        for (var field : cn.fields) {
            if ((field.access & Modifier.STATIC) != 0 && isPotentialRecipeTypeDescriptor(field.desc)) {
                tryAddRecipeRef(cn.name, field.name, outRefs);
            }
        }
    }

    private static void scanClassMethodBytecode(ClassNode cn, ObjectList<StaticFieldRef> outRefs) {
        for (var method : cn.methods) {
            if (method.instructions == null) continue;
            for (var insn : method.instructions) {
                if (insn instanceof FieldInsnNode f && isPotentialRecipeTypeDescriptor(f.desc)) {
                    tryAddRecipeRef(f.owner, f.name, outRefs);
                }
            }
        }
    }

    private static void scanReferencedMenus(Class<?> rootClass, ClassNode cn, ObjectSet<String> visitedClasses, ObjectList<StaticFieldRef> outRefs) {
        var referencedClasses = new ObjectOpenHashSet<String>();

        for (var method : cn.methods) {
            if (method.instructions == null) continue;
            for (var insn : method.instructions) collectClassFromInsn(insn, referencedClasses);
        }

        for (var refInternal : referencedClasses) {
            String refClassName = refInternal.replace('/', '.');
            if (!MachineTypeUnwrapper.curClsValidName(refClassName) || visitedClasses.contains(refClassName)) continue;

            try {
                var targetCls = Class.forName(refClassName, false, rootClass.getClassLoader());
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
            case TypeInsnNode t when t.getOpcode() == Opcodes.NEW && isModInternalClass(t.desc) -> out.add(t.desc);

            case MethodInsnNode m when m.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(m.name) && isModInternalClass(m.owner) ->
                    out.add(m.owner);

            case InvokeDynamicInsnNode dyn -> {
                for (var arg : dyn.bsmArgs) {
                    if (arg instanceof Handle h && isModInternalClass(h.getOwner())) out.add(h.getOwner());
                }
            }

            default -> {
            }
        }
    }

    private static void scanMenuBytecode(Class<?> menuClass, String menuClassName, ObjectList<StaticFieldRef> outRefs) {
        try (var is = getClassInputStream(menuClass, menuClassName)) {
            if (is == null) return;
            var cn = new ClassNode();
            new ClassReader(is).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            scanClassMethodBytecode(cn, outRefs);
        } catch (Throwable ignored) {
        }
    }

    private static void tryAddRecipeRef(String owner, String name, ObjectList<StaticFieldRef> outRefs) {
        if (extractStaticRecipeType(owner, name) != null) {
            var ref = new StaticFieldRef(owner, name);
            if (!outRefs.contains(ref)) outRefs.add(ref);
        }
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
        if (desc == null) return false;
        return desc.contains("RecipeType") || desc.contains("Holder") || desc.contains("Supplier")
                || desc.contains("DeferredHolder") || desc.contains("RegistryObject")
                || desc.contains("IRecipeTypeInfo") || desc.contains("AllRecipeTypes");
    }

    @Nullable
    public static RecipeType<?> extractStaticRecipeType(String ownerClass, String fieldName) {
        try {
            var cls = Class.forName(ownerClass.replace('/', '.'));
            var f = cls.getDeclaredField(fieldName);
            f.setAccessible(true);
            if (!Modifier.isStatic(f.getModifiers())) return null;
            var mh = LOOKUP.unreflectGetter(f);
            var raw = mh.invoke();
            return MachineTypeUnwrapper.unwrapRecipeType(raw);
        } catch (Throwable ignored) {
        }
        return null;
    }

    public record StaticFieldRef(String ownerClass, String fieldName) {
    }
}