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

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.ComplexityAnalyzer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class FullDebugTracePipeline {
    private static final boolean ENABLED = Boolean.getBoolean("complexityanalyzer.FullDebugTracePipeline");

    static {
        if (ENABLED) {
            ComplexityAnalyzer.LOGGER.info("[Complexity Analyzer] FullDebugTracePipeline is ENABLED via JVM option (-Dcomplexityanalyzer.FullDebugTracePipeline=true)");
        } else {
            ComplexityAnalyzer.LOGGER.info("[Complexity Analyzer] FullDebugTracePipeline is DISABLED (To enable, use JVM option: -Dcomplexityanalyzer.FullDebugTracePipeline=true)");
        }
    }

    private static final String SEP = "═".repeat(60);
    private static final String MINOR_SEP = "─".repeat(60);

    private final Path worldDir;
    private final StringBuilder buffer;
    private int totalRecipes;
    private int harvestedCount;
    private int rejectedCount;
    private int failedCount;
    private final Map<String, Integer> recipeTypeStats = new LinkedHashMap<>();
    private final Map<String, Integer> rejectReasons = new LinkedHashMap<>();

    public FullDebugTracePipeline(Path worldDir) {
        this.worldDir = worldDir;
        if (ENABLED) {
            this.buffer = new StringBuilder(256 * 1024);
        } else {
            this.buffer = null;
        }
    }

    public void traceHarvested(String recipeId, Object recipe, Level level, HarvestedItems items) {
        if (!ENABLED) return;
        var tb = new TraceBuilder(this, recipe, recipeId);
        tb.classInfo();
        tb.fields();
        tb.methods();
        tb.accessors(level);
        tb.harvested(items);
    }

    public void traceRejected(String recipeId, Object recipe, Level level, String reason) {
        if (!ENABLED) return;
        var tb = new TraceBuilder(this, recipe, recipeId);
        tb.classInfo();
        tb.fields();
        tb.methods();
        tb.accessors(level);
        tb.rejected(reason);
    }

    public void traceFailed(String recipeId, String className, Throwable t) {
        if (!ENABLED) return;
        synchronized (this) {
            totalRecipes++;
            failedCount++;
            buffer.append("FAILED recipe=").append(recipeId)
                    .append(" class=").append(className)
                    .append(" error=").append(t.getClass().getSimpleName())
                    .append(": ").append(t.getMessage()).append('\n');
        }
    }

    public void flush() {
        if (!ENABLED) return;
        buffer.append('\n').append(SEP).append('\n');
        buffer.append("FINAL STATISTICS\n");
        buffer.append(SEP).append('\n');
        buffer.append("Total recipes scanned: ").append(totalRecipes).append('\n');
        buffer.append("Harvested: ").append(harvestedCount).append('\n');
        buffer.append("Rejected:  ").append(rejectedCount).append('\n');
        buffer.append("Failed:    ").append(failedCount).append('\n');
        buffer.append('\n');

        if (!recipeTypeStats.isEmpty()) {
            buffer.append("Recipe types distribution:\n");
            for (var entry : recipeTypeStats.entrySet()) {
                buffer.append(String.format(Locale.ROOT, "  %-50s: %d\n", entry.getKey(), entry.getValue()));
            }
            buffer.append('\n');
        }

        if (!rejectReasons.isEmpty()) {
            buffer.append("Reject reasons:\n");
            for (var entry : rejectReasons.entrySet()) {
                buffer.append(String.format(Locale.ROOT, "  %-50s: %d\n", entry.getKey(), entry.getValue()));
            }
        }

        try {
            var dir = worldDir.resolve("complexityanalyzer");
            Files.createDirectories(dir);
            var file = dir.resolve("runtime_harvest.txt");
            Files.writeString(file, buffer.toString(), StandardCharsets.UTF_8);
            ComplexityAnalyzer.LOGGER.info("[Harvest:Debug] Written {} recipes trace to {}", totalRecipes, file);
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[Harvest:Debug] Failed to write trace: {}", t.getMessage());
        }
    }

    public static final class TraceBuilder {
        private final FullDebugTracePipeline pipeline;
        private final Object recipe;
        private final String recipeId;
        private final StringBuilder sb = new StringBuilder(4096);
        private boolean finalized;

        TraceBuilder(FullDebugTracePipeline pipeline, Object recipe, String recipeId) {
            this.pipeline = pipeline;
            this.recipe = recipe;
            this.recipeId = recipeId;
        }

        public void classInfo() {
            sb.append(SEP).append('\n');
            sb.append("RECIPE: ").append(recipeId).append('\n');
            sb.append("CLASS:  ").append(recipe.getClass().getName()).append('\n');

            var clazz = recipe.getClass();
            sb.append("INTERFACES:\n");
            for (var iface : clazz.getInterfaces()) sb.append("  - ").append(iface.getName()).append('\n');
            if (clazz.getSuperclass() != null && clazz.getSuperclass() != Object.class) {
                sb.append("  extends ").append(clazz.getSuperclass().getName()).append('\n');
            }

            var profile = PatternSignatureEngine.profile(clazz);
            sb.append("SIGNATURE: level=").append(profile.level())
                    .append(" score=").append(profile.totalScore())
                    .append(" isRecipe=").append(profile.isRecipe())
                    .append(" isMachine=").append(profile.isMachine())
                    .append('\n');
        }

        public void fields() {
            sb.append(MINOR_SEP).append('\n');
            sb.append("FIELDS:\n");

            var meta = UniversalAccessorResolver.getMeta(recipe.getClass());

            for (var f : meta.allFields()) {
                try {
                    var val = f.get(recipe);
                    var valStr = formatValue(val);
                    var role = HeuristicRoleClassifier.classifyField(f);

                    sb.append(String.format(Locale.ROOT,
                            "  [%s] %-30s : %-40s = %s\n",
                            role.role().name().substring(0, 4),
                            f.getName(),
                            f.getType().getSimpleName(),
                            valStr));
                } catch (Throwable t) {
                    sb.append(String.format(Locale.ROOT,
                            "  [ERR] %-30s : %-40s (error: %s)\n",
                            f.getName(),
                            f.getType().getSimpleName(),
                            t.getClass().getSimpleName()));
                }
            }
        }

        public void methods() {
            sb.append(MINOR_SEP).append('\n');
            sb.append("METHODS:\n");

            var meta = UniversalAccessorResolver.getMeta(recipe.getClass());
            int shown = 0;
            for (int i = 0; i < meta.allMethods().length; i++) {
                var m = meta.allMethods()[i];
                var h = meta.allHandles()[i];
                if (h == null) continue;

                var rt = m.getReturnType();
                if (rt == void.class || rt == Void.class) continue;

                var role = HeuristicRoleClassifier.classifyMethod(m);

                if (role.role() == HeuristicRoleClassifier.Role.UNKNOWN
                        && !UniversalTypeResolver.isContainerType(rt)
                        && !ItemStack.class.isAssignableFrom(rt)
                        && !Ingredient.class.isAssignableFrom(rt)
                        && !FluidStack.class.isAssignableFrom(rt)) {
                    continue;
                }

                shown++;
                try {
                    var val = h.invoke(recipe);
                    String valStr = formatValue(val);
                    sb.append(String.format(Locale.ROOT,
                            "  [%s] %-30s() → %-40s = %s\n",
                            role.role().name().substring(0, 4),
                            m.getName(),
                            rt.getSimpleName(),
                            valStr));
                } catch (Throwable t) {
                    sb.append(String.format(Locale.ROOT,
                            "  [ERR] %-30s() → %-40s (error: %s)\n",
                            m.getName(),
                            rt.getSimpleName(),
                            t.getClass().getSimpleName()));
                }
            }
            sb.append(shown == 0 ? "  (no relevant methods found)\n" : "  (" + shown + " relevant methods shown)\n");

        }

        public void accessors(Level level) {
            sb.append(MINOR_SEP).append('\n');
            sb.append("ACCESSOR EXTRACTION:\n");

            var accessors = UniversalAccessorResolver.resolve(recipe, level);

            for (var acc : accessors.allAccessors()) {
                try {
                    var val = acc.extract(recipe, level);
                    String valStr = formatValue(val);
                    sb.append(String.format(Locale.ROOT, "  [%s] %-50s = %s\n",
                            acc.type(), acc, valStr));
                } catch (Throwable t) {
                    sb.append(String.format(Locale.ROOT, "  [ERR] %-50s = %s\n",
                            acc, t.getClass().getSimpleName()));
                }
            }
        }

        public void harvested(HarvestedItems items) {
            sb.append(MINOR_SEP).append('\n');
            sb.append("STATUS: HARVESTED\n");
            sb.append("Harvested detail:\n");
            if (!items.inputItems().isEmpty()) {
                sb.append("  Input items: ");
                for (var stack : items.inputItems()) sb.append(formatItemStack(stack)).append(", ");
                sb.setLength(sb.length() - 2);
                sb.append('\n');
            }
            if (!items.outputItems().isEmpty()) {
                sb.append("  Output items: ");
                for (var stack : items.outputItems()) sb.append(formatItemStack(stack)).append(", ");
                sb.setLength(sb.length() - 2);
                sb.append('\n');
            }
            if (!items.inputIngredients().isEmpty()) {
                sb.append("  Input ingredients (size=").append(items.inputIngredients().size()).append("):\n");
                for (var hi : items.inputIngredients()) {
                    sb.append("    - Ingredient[x").append(hi.count()).append(", items=");
                    var subItems = hi.ingredient().getItems();
                    for (var stack : subItems) {
                        sb.append(formatItemStack(stack)).append("; ");
                    }
                    if (subItems.length > 0) sb.setLength(sb.length() - 2);
                    sb.append("]\n");
                }
            }
            if (!items.inputFluids().isEmpty()) {
                sb.append("  Input fluids: ");
                for (var fs : items.inputFluids()) sb.append(formatValue(fs)).append(", ");
                sb.setLength(sb.length() - 2);
                sb.append('\n');
            }
            if (!items.outputFluids().isEmpty()) {
                sb.append("  Output fluids: ");
                for (var fs : items.outputFluids()) sb.append(formatValue(fs)).append(", ");
                sb.setLength(sb.length() - 2);
                sb.append('\n');
            }
            sb.append(SEP).append('\n');

            synchronized (pipeline) {
                pipeline.harvestedCount++;
            }
            finalizeTrace(true);
        }

        public void rejected(String reason) {
            sb.append(MINOR_SEP).append('\n');
            sb.append("STATUS: REJECTED\n");
            sb.append("REASON: ").append(reason).append('\n');
            sb.append(SEP).append('\n');

            synchronized (pipeline) {
                pipeline.rejectReasons.merge(reason, 1, Integer::sum);
            }
            finalizeTrace(false);
        }

        public void failed(Throwable t) {
            sb.append(MINOR_SEP).append('\n');
            sb.append("STATUS: FAILED\n");
            sb.append("ERROR:  ").append(t.getClass().getSimpleName())
                    .append(": ").append(t.getMessage()).append('\n');
            sb.append(SEP).append('\n');
            finalizeTrace(false);
        }

        private void finalizeTrace(boolean harvested) {
            if (finalized) return;
            finalized = true;

            synchronized (pipeline) {
                pipeline.totalRecipes++;
                if (!harvested) pipeline.rejectedCount++;

                if (recipe instanceof Recipe<?> r) {
                    pipeline.recipeTypeStats.merge(r.getType().toString(), 1, Integer::sum);
                } else {
                    pipeline.recipeTypeStats.merge(recipe.getClass().getSimpleName(), 1, Integer::sum);
                }
                pipeline.buffer.append(sb);
            }
        }
    }

    private static String formatValue(Object val) {
        return switch (val) {
            case null -> "null";
            case ItemStack stack -> formatItemStack(stack);
            case Ingredient ing -> "Ingredient[" + ing.getItems().length + " variants]";
            case FluidStack fs -> fs.getAmount() + "mb " + fs.getFluid();
            case Collection<?> c -> c.getClass().getSimpleName() + "[" + c.size() + "]";
            case Map<?, ?> m -> m.getClass().getSimpleName() + "[" + m.size() + "]";
            case Object[] arr -> arr.getClass().getComponentType().getSimpleName() + "[" + arr.length + "]";
            case String s -> "\"" + (s.length() > 50 ? s.substring(0, 47) + "..." : s) + "\"";
            default -> val.getClass().getSimpleName();
        };
    }

    private static String formatItemStack(ItemStack stack) {
        if (stack.isEmpty()) return "EMPTY";
        return stack.getCount() + "x " + stack.getItem();
    }
}