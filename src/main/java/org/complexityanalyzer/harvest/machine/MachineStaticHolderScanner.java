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

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import org.complexityanalyzer.core.GameRegistryManager;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Modifier;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static net.minecraft.world.item.Items.AIR;

public final class MachineStaticHolderScanner {

    private MachineStaticHolderScanner() {
    }

    public static int scanModStaticHoldersAndRegistries(BiPredicate<RecipeType<?>, Item> registrar) {
        int count = 0;
        var candidateClasses = new ReferenceOpenHashSet<Class<?>>();

        for (var block : GameRegistryManager.getAllBlocks()) {
            if (block.asItem() != AIR) candidateClasses.add(block.getClass());
        }

        for (var rt : GameRegistryManager.getAllRecipeTypes()) candidateClasses.add(rt.getClass());

        var initialList = new ObjectArrayList<>(candidateClasses);
        for (var cls : initialList) addClassAndNeighbors(cls, candidateClasses);

        var visitedObjects = new ReferenceOpenHashSet<>();
        for (var clazz : candidateClasses) {
            if (!MachineTypeUnwrapper.curClsValid(clazz)) continue;
            for (var field : clazz.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    var val = field.get(null);
                    if (val == null) continue;

                    var item = findItemInObject(val, visitedObjects);
                    visitedObjects.clear();
                    if (item != null && item != AIR) {
                        var rt = findRecipeTypeInObject(val, visitedObjects);
                        visitedObjects.clear();
                        if (rt != null && registrar.test(rt, item)) count++;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return count;
    }

    private static void addClassAndNeighbors(Class<?> cls, ReferenceOpenHashSet<Class<?>> set) {
        if (!MachineTypeUnwrapper.curClsValid(cls)) return;
        set.add(cls);
        var enc = cls.getEnclosingClass();
        if (MachineTypeUnwrapper.curClsValid(enc)) set.add(enc);
        var decl = cls.getDeclaringClass();
        if (MachineTypeUnwrapper.curClsValid(decl)) set.add(decl);
        for (var iface : cls.getInterfaces()) if (MachineTypeUnwrapper.curClsValid(iface)) set.add(iface);
        var superCls = cls.getSuperclass();
        if (MachineTypeUnwrapper.curClsValid(superCls)) set.add(superCls);
    }

    @Nullable
    private static <T> T findMemberInObject(@Nullable Object obj, ReferenceSet<Object> visited, Function<Object, T> extractor, Predicate<Class<?>> returnTypeFilter) {
        if (obj == null || !visited.add(obj)) return null;
        T direct = extractor.apply(obj);
        if (direct != null) return direct;

        var cls = obj.getClass();
        if (!MachineTypeUnwrapper.curClsValid(cls)) return null;

        for (var m : cls.getMethods()) {
            if (m.getParameterCount() == 0 && returnTypeFilter.test(m.getReturnType())) try {
                m.setAccessible(true);
                var res = m.invoke(obj);
                var found = findMemberInObject(res, visited, extractor, returnTypeFilter);
                if (found != null) return found;
            } catch (Throwable ignored) {
            }
        }

        for (var f : cls.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && !f.getType().isPrimitive()) try {
                f.setAccessible(true);
                var res = f.get(obj);
                var found = findMemberInObject(res, visited, extractor, returnTypeFilter);
                if (found != null) return found;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @Nullable
    private static Item findItemInObject(@Nullable Object obj, ReferenceSet<Object> visited) {
        return findMemberInObject(obj, visited, o -> switch (o) {
            case Item item -> item != AIR ? item : null;
            case Block block -> block.asItem() != AIR ? block.asItem() : null;
            case ItemStack stack -> !stack.isEmpty() ? stack.getItem() : null;
            default -> null;
        }, rt -> Item.class.isAssignableFrom(rt) || Block.class.isAssignableFrom(rt) || ItemStack.class.isAssignableFrom(rt));
    }

    @Nullable
    private static RecipeType<?> findRecipeTypeInObject(@Nullable Object obj, ReferenceSet<Object> visited) {
        return findMemberInObject(obj, visited, MachineTypeUnwrapper::unwrapRecipeType, rt -> RecipeType.class.isAssignableFrom(rt) || Holder.class.isAssignableFrom(rt) || Supplier.class.isAssignableFrom(rt));
    }
}