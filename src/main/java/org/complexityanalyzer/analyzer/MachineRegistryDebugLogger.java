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

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.GameRegistryManager;
import org.jetbrains.annotations.Nullable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class MachineRegistryDebugLogger {

    public static final boolean DEBUG_ENABLED = false;

    static {
        if (DEBUG_ENABLED) {
            ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Debug logging is ENABLED (-Dcomplexityanalyzer.MachineRegistryDebug=true)");
        } else {
            ComplexityAnalyzer.LOGGER.info("[MachineRegistry] Debug logging is DISABLED (To enable, set JVM flag: -Dcomplexityanalyzer.MachineRegistryDebug=true)");
        }
    }

    private final MinecraftServer server;
    private final StringBuilder dump;

    public MachineRegistryDebugLogger(MinecraftServer server, int totalBlocks) {
        this.server = server;
        if (DEBUG_ENABLED) {
            this.dump = new StringBuilder(1024 * 1024);
            dump.append("=================================================================\n");
            dump.append("MACHINE REGISTRY FULL DEBUG DUMP\n");
            dump.append("Total Blocks to scan: ").append(totalBlocks).append("\n");
            dump.append("=================================================================\n\n");
        } else {
            this.dump = null;
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
                if (holder.isBound()) return "Holder[Value=" + formatValue(holder.value()) + "]";
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
        var c = obj.getClass();
        if (c.isPrimitive() || Number.class.isAssignableFrom(c) || Boolean.class.isAssignableFrom(c)
                || Character.class.isAssignableFrom(c)) return String.valueOf(obj);
        return c.getName() + "@" + Integer.toHexString(System.identityHashCode(obj));
    }

    public void logBlockHeader(ResourceLocation blockId, Item machineItem, Class<?> blockClass) {
        if (!DEBUG_ENABLED) return;
        dump.append("\n-----------------------------------------------------------------\n");
        dump.append("BLOCK: ").append(blockId)
                .append(" | Item: ").append(GameRegistryManager.getItemId(machineItem))
                .append("\nBlock Class: ").append(blockClass.getName())
                .append("\nClass Hierarchy: ").append(getClassHierarchyString(blockClass)).append("\n");
    }

    public void logBeCreated(BlockEntity be) {
        if (!DEBUG_ENABLED) return;
        dump.append("  [BE_CREATE] SUCCESS: Created BlockEntity instance -> ").append(be.getClass().getName()).append("\n");
        dump.append("  [BE_HIERARCHY] ").append(getClassHierarchyString(be.getClass())).append("\n");
    }

    public void logBeCreateNull() {
        if (!DEBUG_ENABLED) return;
        dump.append("  [BE_CREATE] NULL: newBlockEntity returned null\n");
    }

    public void logBeCreateFailed(Throwable t) {
        if (!DEBUG_ENABLED) return;
        dump.append("  [BE_CREATE] FAILED: ").append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
        dump.append("  [BE_CREATE_STACKTRACE]:\n").append(getStackTraceString(t)).append("\n");
    }

    public void logNonBeBlock() {
        if (!DEBUG_ENABLED) return;
        dump.append("  [NON_BE_BLOCK] Block has no BlockEntity (Stonecutter/Sawmill style)\n");
    }

    public void logAsmScanStart(Class<?> clazz, int refCount) {
        if (!DEBUG_ENABLED) return;
        dump.append("  [ASM_SCAN] Target Class: ").append(clazz.getName())
                .append(" -> Found ").append(refCount).append(" potential static field ref(s)\n");
    }

    public void logAsmRef(String ownerClass, String fieldName, @Nullable RecipeType<?> rt, boolean matched, @Nullable Throwable err) {
        if (!DEBUG_ENABLED) return;
        dump.append("    [ASM_REF] ").append(ownerClass).append("#").append(fieldName);
        if (err != null) {
            dump.append(" -> ERROR: ").append(err.getClass().getName()).append(": ").append(err.getMessage());
        } else if (rt != null) {
            var typeId = GameRegistryManager.getRecipeTypeId(rt);
            dump.append(" -> Unwrapped RT: ").append(typeId);
            if (matched) dump.append(" [MATCH VIA ASM]");
        } else {
            dump.append(" -> Unwrapped RT: null");
        }
        dump.append("\n");
    }

    public void logBeMethodScanStart(Class<?> beClass) {
        if (!DEBUG_ENABLED) return;
        dump.append("  [BE_METHOD_SCAN] Inspecting zero-arg methods on ").append(beClass.getName()).append(":\n");
    }

    public void logBeMethod(Method method, Object rawResult, @Nullable RecipeType<?> rt, boolean matched, @Nullable Throwable err) {
        if (!DEBUG_ENABLED) return;
        if (err != null) {
            dump.append("    [BE_METHOD_ERR] ").append(method.getName()).append("(): ")
                    .append(err.getClass().getName()).append(": ").append(err.getMessage()).append("\n")
                    .append("    [BE_METHOD_ERR_STACKTRACE]:\n").append(getStackTraceString(err)).append("\n");
        } else {
            dump.append("    [BE_METHOD] ").append(method.getName()).append("()")
                    .append(" [Returns: ").append(method.getReturnType().getName()).append("] = ")
                    .append(formatValue(rawResult));
            if (rt != null) {
                var typeId = GameRegistryManager.getRecipeTypeId(rt);
                dump.append(" -> Unwrapped RT: ").append(typeId);
                if (matched) dump.append(" [MATCH]");
            }
            dump.append("\n");
        }
    }

    public void logBeHierarchyClass(Class<?> cls) {
        if (!DEBUG_ENABLED) return;
        dump.append("  [BE_FIELD_HIERARCHY] Class: ").append(cls.getName()).append("\n");
    }

    public void logBeField(Field field, Object val, @Nullable RecipeType<?> rt, boolean matched, @Nullable Throwable err) {
        if (!DEBUG_ENABLED) return;
        if (err != null) {
            dump.append("    [BE_FIELD_ERR] ").append(field.getName()).append(": ")
                    .append(err.getClass().getName()).append(": ").append(err.getMessage()).append("\n")
                    .append("    [BE_FIELD_ERR_STACKTRACE]:\n").append(getStackTraceString(err)).append("\n");
        } else {
            dump.append("    [BE_FIELD] ").append(field.getName())
                    .append(" (").append(field.getType().getName()).append(") = ")
                    .append(formatValue(val));
            if (rt != null) {
                var typeId = GameRegistryManager.getRecipeTypeId(rt);
                dump.append(" -> Unwrapped RT: ").append(typeId);
                if (matched) dump.append(" [MATCH]");
            }
            dump.append("\n");
        }
    }

    public void logStaticScanStart(Class<?> clazz) {
        if (!DEBUG_ENABLED) return;
        dump.append("  [STATIC_FIELD_SCAN] Inspecting static fields for class: ").append(clazz.getName()).append("\n");
    }

    public void logStaticHierarchyClass(Class<?> cls) {
        if (!DEBUG_ENABLED) return;
        dump.append("  [STATIC_HIERARCHY] Class: ").append(cls.getName()).append("\n");
    }

    public void logStaticField(Field field, Object val, @Nullable RecipeType<?> rt, boolean matched, @Nullable Throwable err) {
        if (!DEBUG_ENABLED) return;
        if (err != null) {
            dump.append("    [STATIC_FIELD_ERR] ").append(field.getName()).append(": ")
                    .append(err.getClass().getName()).append(": ").append(err.getMessage()).append("\n")
                    .append("    [STATIC_FIELD_ERR_STACKTRACE]:\n").append(getStackTraceString(err)).append("\n");
        } else {
            dump.append("    [STATIC_FIELD] ").append(field.getName())
                    .append(" (").append(field.getType().getName()).append(") = ")
                    .append(formatValue(val));
            if (rt != null) {
                var typeId = GameRegistryManager.getRecipeTypeId(rt);
                dump.append(" -> Unwrapped RT: ").append(typeId);
                if (matched) dump.append(" [MATCH]");
            }
            dump.append("\n");
        }
    }

    public void logDeepScanStart() {
        if (!DEBUG_ENABLED) return;
        dump.append("  [DEEP_SCAN_START] Initial scans found no RecipeType, starting deep object traversal...\n");
    }

    public void logDeepMatch(Class<?> cls, RecipeType<?> rt, int depth) {
        if (!DEBUG_ENABLED) return;
        String indent = "    " + "  ".repeat(depth);
        dump.append(indent).append("[DEEP_MATCH] Direct unwrap of ").append(cls.getName())
                .append(" -> RecipeType: ").append(GameRegistryManager.getRecipeTypeId(rt)).append("\n");
    }

    public void logDeepIter(Class<?> itemCls, int idx, int depth) {
        if (!DEBUG_ENABLED) return;
        String indent = "    " + "  ".repeat(depth);
        dump.append(indent).append("[DEEP_ITER] Traversing Iterable item #").append(idx)
                .append(" (").append(itemCls.getName()).append(")\n");
    }

    public void logDeepMap(Object key, Class<?> valCls, int depth) {
        if (!DEBUG_ENABLED) return;
        String indent = "    " + "  ".repeat(depth);
        dump.append(indent).append("[DEEP_MAP] Traversing Map value for key '").append(key)
                .append("' (").append(valCls.getName()).append(")\n");
    }

    public void logDeepMethod(Method method, Object val, @Nullable RecipeType<?> rt, @Nullable Throwable err, boolean recurse, int depth) {
        if (!DEBUG_ENABLED) return;
        String indent = "    " + "  ".repeat(depth);
        if (err != null) {
            dump.append(indent).append("[DEEP_METHOD_ERR] ").append(method.getName()).append("(): ")
                    .append(err.getClass().getName()).append(": ").append(err.getMessage()).append("\n");
        } else {
            dump.append(indent).append("[DEEP_METHOD] ").append(method.getName()).append("() -> ")
                    .append(formatValue(val));
            if (rt != null) {
                dump.append(" -> Unwrapped RT: ").append(GameRegistryManager.getRecipeTypeId(rt)).append("\n");
            } else {
                dump.append("\n");
                if (recurse && val != null) {
                    dump.append(indent).append("  -> Recursing into method return '").append(method.getName()).append("' (").append(val.getClass().getName()).append(")\n");
                }
            }
        }
    }

    public void logDeepField(Field field, Object val, @Nullable RecipeType<?> rt, @Nullable Throwable err, boolean recurse, int depth) {
        if (!DEBUG_ENABLED) return;
        String indent = "    " + "  ".repeat(depth);
        if (err != null) {
            dump.append(indent).append("[DEEP_FIELD_ERR] ").append(field.getName()).append(": ")
                    .append(err.getClass().getName()).append(": ").append(err.getMessage()).append("\n");
        } else {
            dump.append(indent).append("[DEEP_FIELD] ").append(field.getName()).append(" (").append(field.getType().getSimpleName())
                    .append(") = ").append(formatValue(val));
            if (rt != null) {
                dump.append(" -> Unwrapped RT: ").append(GameRegistryManager.getRecipeTypeId(rt)).append("\n");
            } else {
                dump.append("\n");
                if (recurse && val != null) {
                    dump.append(indent).append("  -> Recursing into field '").append(field.getName()).append("' (").append(val.getClass().getName()).append(")\n");
                }
            }
        }
    }

    public void logDeepResult(RecipeType<?> rt, Item machineItem) {
        if (!DEBUG_ENABLED) return;
        if (rt != null) {
            var typeId = GameRegistryManager.getRecipeTypeId(rt);
            dump.append("  [MATCH:DEEP] >>> MATCH: RecipeType '").append(typeId)
                    .append("' -> Item '").append(GameRegistryManager.getItemId(machineItem)).append("' <<<\n");
        } else {
            dump.append("  [DEEP_SCAN_END] Deep traversal completed. No RecipeType found.\n");
        }
    }

    public void logBlockResult(ResourceLocation blockId, int matchCount) {
        if (!DEBUG_ENABLED) return;
        if (matchCount > 0) {
            dump.append("  [RESULT] SUCCESS: Mapped block ").append(blockId).append(" with ").append(matchCount).append(" match(es)\n");
        } else {
            dump.append("  [RESULT] NO_MATCH: No RecipeType mapped for block ").append(blockId).append("\n");
        }
    }

    public void logFatalBlockError(Throwable t) {
        if (!DEBUG_ENABLED) return;
        dump.append("  [FATAL_BLOCK_ERROR] ").append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
        dump.append("  [FATAL_STACKTRACE]:\n").append(getStackTraceString(t)).append("\n");
    }

    public void finishAndSave(int totalBlocks, int entityBlocks, int registeredCount, int errors) {
        if (!DEBUG_ENABLED || dump == null) return;
        dump.append("\n=================================================================\n");
        dump.append("SCAN SUMMARY:\n");
        dump.append("Total Blocks: ").append(totalBlocks).append("\n");
        dump.append("Entity Blocks: ").append(entityBlocks).append("\n");
        dump.append("Registered Machines: ").append(registeredCount).append("\n");
        dump.append("Errors: ").append(errors).append("\n");
        dump.append("=================================================================\n");

        saveDumpToDisk(dump.toString());
    }

    private void saveDumpToDisk(String content) {
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
}