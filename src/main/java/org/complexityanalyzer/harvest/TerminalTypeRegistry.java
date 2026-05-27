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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.harvest;

import java.util.concurrent.ConcurrentHashMap;

public final class TerminalTypeRegistry {

    private static final ConcurrentHashMap<Class<?>, Boolean> CACHE = new ConcurrentHashMap<>(2048);

    private TerminalTypeRegistry() {
    }

    public static boolean isTerminalType(Class<?> type) {
        if (type == null) return true;
        while (type.isArray()) type = type.getComponentType();
        if (type.isPrimitive() || type == String.class || type.isEnum() || type == Boolean.class
                || type == Character.class || Number.class.isAssignableFrom(type)) return true;

        Boolean cached = CACHE.get(type);
        if (cached != null) return cached;

        boolean result = computeIsTerminal(type);
        CACHE.put(type, result);
        return result;
    }

    private static boolean computeIsTerminal(Class<?> c) {
        String name = c.getName();
        if (name.isEmpty()) return true;

        if (name.contains("RecipeType") || name.contains("RecipeBuilder") || name.contains("RecipeSerializer")
                || name.contains("EnergyStack") || name.contains("$$Lambda$") || name.contains("MethodHandle")
                || name.contains("VarHandle")) return true;

        int firstDot = name.indexOf('.');
        if (firstDot == -1) return false;

        String firstSeg = name.substring(0, firstDot);
        switch (firstSeg) {
            case "java": {
                return name.startsWith("java.lang.invoke.") || name.startsWith("java.lang.reflect.");
            }

            case "com": {
                int secondDot = name.indexOf('.', firstDot + 1);
                if (secondDot != -1) {
                    String secondSeg = name.substring(firstDot + 1, secondDot);
                    if (secondSeg.equals("mojang")) return true;
                }
                break;
            }

            case "net": {
                int secondDot = name.indexOf('.', firstDot + 1);
                if (secondDot == -1) break;
                String secondSeg = name.substring(firstDot + 1, secondDot);
                switch (secondSeg) {
                    case "neoforged": {
                        return name.startsWith("net.neoforged.neoforge.server.ServerLifecycleHooks")
                                || name.startsWith("net.neoforged.neoforge.registries.")
                                || name.startsWith("net.neoforged.fml.");
                    }

                    case "minecraft": {
                        int thirdDot = name.indexOf('.', secondDot + 1);
                        if (thirdDot == -1) return true;
                        String thirdSeg = name.substring(secondDot + 1, thirdDot);
                        switch (thirdSeg) {
                            case "client":
                            case "sounds":
                            case "gametest":
                            case "data":
                            case "commands":
                            case "stats":
                            case "advancements":
                            case "network":
                            case "server":
                                return true;
                            case "util":
                                if (name.startsWith("net.minecraft.util.profiling.")) return true;
                                break;
                            case "resources":
                                if (name.startsWith("net.minecraft.resources.ResourceLocation")
                                        || name.startsWith("net.minecraft.resources.ResourceKey")) {
                                    return true;
                                }
                                break;
                            case "tags":
                                if (name.startsWith("net.minecraft.tags.Tag")
                                        || name.startsWith("net.minecraft.tags.TagKey")) {
                                    return true;
                                }
                                break;
                            case "core":
                                if (name.startsWith("net.minecraft.core.Registry")
                                        || name.startsWith("net.minecraft.core.Holder")
                                        || name.startsWith("net.minecraft.core.RegistryAccess")
                                        || name.startsWith("net.minecraft.core.HolderLookup")) {
                                    return true;
                                }
                                break;
                            case "world": {
                                int fourthDot = name.indexOf('.', thirdDot + 1);
                                if (fourthDot == -1) return true;
                                String fourthSeg = name.substring(thirdDot + 1, fourthDot);
                                switch (fourthSeg) {
                                    case "phys":
                                    case "damagesource":
                                    case "scores":
                                        return true;
                                    case "inventory":
                                        return name.startsWith("net.minecraft.world.inventory.AbstractContainerMenu");
                                    case "level":
                                        if (name.startsWith("net.minecraft.world.level.Level")
                                                || name.startsWith("net.minecraft.world.level.block.entity.BlockEntity")
                                                || name.equals("net.minecraft.world.level.block.Block")
                                                || name.startsWith("net.minecraft.world.level.block.Block$")
                                                || name.equals("net.minecraft.world.level.material.Fluid")
                                                || name.startsWith("net.minecraft.world.level.material.Fluid$")
                                                || name.equals("net.minecraft.world.level.material.Fluids")) {
                                            return true;
                                        }
                                        break;
                                    case "entity":
                                        if (name.startsWith("net.minecraft.world.entity.Entity")
                                                || name.startsWith("net.minecraft.world.entity.player.Player")) {
                                            return true;
                                        }
                                        break;
                                    case "item":
                                        if (name.equals("net.minecraft.world.item.Item")
                                                || name.startsWith("net.minecraft.world.item.Item$")
                                                || name.startsWith("net.minecraft.world.item.crafting.RecipeManager")) {
                                            return true;
                                        }
                                        break;
                                }
                                break;
                            }
                        }
                        break;
                    }
                }
                break;
            }
        }

        return false;
    }

    public static void clearCache() {
        CACHE.clear();
    }
}