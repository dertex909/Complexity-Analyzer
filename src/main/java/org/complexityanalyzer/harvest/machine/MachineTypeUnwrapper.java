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

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import org.complexityanalyzer.core.GameRegistryManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.Supplier;

public final class MachineTypeUnwrapper {

    private static final Method[] EMPTY_METHODS = new Method[0];
    private static final ClassValue<Method[]> CANDIDATE_METHODS = new ClassValue<>() {
        @Override
        protected Method[] computeValue(@NotNull Class<?> type) {
            var all = type.getMethods();
            int count = 0;
            for (var m : all) {
                if (m.getParameterCount() == 0) {
                    var rt = m.getReturnType();
                    if (RecipeType.class.isAssignableFrom(rt) || Holder.class.isAssignableFrom(rt)) count++;
                }
            }

            if (count == 0) return EMPTY_METHODS;

            var candidates = new Method[count];
            int idx = 0;
            for (var m : all) {
                if (m.getParameterCount() == 0) {
                    var rt = m.getReturnType();
                    if (RecipeType.class.isAssignableFrom(rt) || Holder.class.isAssignableFrom(rt)) try {
                        m.setAccessible(true);
                        candidates[idx++] = m;
                    } catch (Throwable ignored) {
                    }
                }
            }

            if (idx == 0) return EMPTY_METHODS;
            if (idx < count) {
                var trimmed = new Method[idx];
                System.arraycopy(candidates, 0, trimmed, 0, idx);
                return trimmed;
            }

            return candidates;
        }
    };

    private MachineTypeUnwrapper() {
    }

    public static boolean curClsValidName(String name) {
        if (name == null || name.isEmpty()) return false;
        return switch (name.charAt(0)) {
            case 'j' -> !name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("jdk.");
            case 's' -> !name.startsWith("sun.");
            case 'c' -> !name.startsWith("com.mojang.");
            case 'n' -> !name.startsWith("net.minecraft.");
            case 'o' -> !name.startsWith("org.objectweb.asm.") && !name.startsWith("org.joml.");
            case 'i' -> !name.startsWith("io.netty.");
            default -> true;
        };
    }

    public static boolean curClsValid(@Nullable Class<?> cls) {
        return cls != null && cls != Object.class && curClsValidName(cls.getName());
    }

    @Nullable
    public static RecipeType<?> unwrapRecipeType(@Nullable Object obj) {
        if (obj == null) return null;
        if (obj instanceof RecipeType<?> rt) return rt;
        if (obj instanceof Block || obj instanceof Item || obj instanceof BlockEntity || obj instanceof BlockEntityType
                || obj instanceof SoundEvent || obj instanceof Fluid || obj instanceof EntityType) return null;

        switch (obj) {
            case Holder<?> holder -> {
                try {
                    if (holder.isBound()) {
                        var rt = unwrapRecipeType(holder.value());
                        if (rt != null) return rt;
                    }
                    var keyOpt = holder.unwrapKey();
                    if (keyOpt.isPresent()) return GameRegistryManager.getRecipeType(keyOpt.get().location());
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
            case ResourceLocation loc -> {
                return GameRegistryManager.getRecipeType(loc);
            }
            case ResourceKey<?> key -> {
                return GameRegistryManager.getRecipeType(key.location());
            }
            case Optional<?> opt -> {
                return opt.map(MachineTypeUnwrapper::unwrapRecipeType).orElse(null);
            }
            default -> {
            }
        }

        var methods = CANDIDATE_METHODS.get(obj.getClass());
        for (var method : methods) {
            try {
                var res = method.invoke(obj);
                if (res == null || res == obj) continue;
                var type = unwrapRecipeType(res);
                if (type != null) return type;
            } catch (Throwable ignored) {
            }
        }

        return null;
    }
}