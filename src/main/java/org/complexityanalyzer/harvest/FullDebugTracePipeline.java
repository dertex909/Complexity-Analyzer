package org.complexityanalyzer.harvest;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.ComplexityAnalyzer;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class FullDebugTracePipeline {

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
        this.buffer = new StringBuilder(256 * 1024);
    }

    public void traceHarvested(String recipeId, String className, HarvestedItems items) {
        synchronized (this) {
            totalRecipes++;
            harvestedCount++;
            buffer.append("HARVESTED ").append(recipeId)
                    .append("  class=").append(className)
                    .append("  raw[items=").append(items.inputItems().size())
                    .append(" out=").append(items.outputItems().size())
                    .append(" ingr=").append(items.inputIngredients().size())
                    .append(" fluids=").append(items.inputFluids().size())
                    .append(" outFluids=").append(items.outputFluids().size())
                    .append("]\n");
        }
    }

    public void traceRejected(String recipeId, Object recipe, Level level,
                              String reason) {
        TraceBuilder tb = new TraceBuilder(this, recipe, recipeId);
        tb.classInfo();
        tb.fields();
        tb.methods();
        tb.accessors(level);
        tb.rejected(reason);
    }

    public void traceFailed(String recipeId, String className, Throwable t) {
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
            Path dir = worldDir.resolve("complexityanalyzer");
            Files.createDirectories(dir);
            Path file = dir.resolve("runtime_harvest.txt");
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

            Class<?> clazz = recipe.getClass();
            sb.append("INTERFACES:\n");
            for (Class<?> iface : clazz.getInterfaces()) {
                sb.append("  - ").append(iface.getName()).append('\n');
            }
            if (clazz.getSuperclass() != null && clazz.getSuperclass() != Object.class) {
                sb.append("  extends ").append(clazz.getSuperclass().getName()).append('\n');
            }

            PatternSignatureEngine.ClassProfile profile = PatternSignatureEngine.profile(clazz);
            sb.append("SIGNATURE: level=").append(profile.level())
                    .append(" score=").append(profile.totalScore())
                    .append(" isRecipe=").append(profile.isRecipe())
                    .append(" isMachine=").append(profile.isMachine())
                    .append('\n');

        }

        public void fields() {
            sb.append(MINOR_SEP).append('\n');
            sb.append("FIELDS:\n");

            UniversalAccessorResolver.ClassMeta meta = UniversalAccessorResolver.getMeta(recipe.getClass());

            for (Field f : meta.allFields()) {
                try {
                    Object val = f.get(recipe);
                    String valStr = formatValue(val);
                    HeuristicRoleClassifier.RoleClassification role = HeuristicRoleClassifier.classifyField(f);

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

            UniversalAccessorResolver.ClassMeta meta = UniversalAccessorResolver.getMeta(recipe.getClass());
            int shown = 0;
            for (int i = 0; i < meta.allMethods().length; i++) {
                var m = meta.allMethods()[i];
                var h = meta.allHandles()[i];
                if (h == null) continue;

                Class<?> rt = m.getReturnType();
                if (rt == void.class || rt == Void.class) continue;

                HeuristicRoleClassifier.RoleClassification role = HeuristicRoleClassifier.classifyMethod(m);

                if (role.role() == HeuristicRoleClassifier.Role.UNKNOWN
                        && !UniversalTypeResolver.isContainerType(rt)
                        && !ItemStack.class.isAssignableFrom(rt)
                        && !Ingredient.class.isAssignableFrom(rt)
                        && !FluidStack.class.isAssignableFrom(rt)) {
                    continue;
                }

                shown++;
                try {
                    Object val = h.invoke(recipe);
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

            UniversalAccessorResolver.ResolvedAccessors accessors = UniversalAccessorResolver.resolve(recipe, level);

            sb.append("  Input accessors:  ").append(accessors.inputAccessors().size()).append('\n');
            for (var acc : accessors.inputAccessors()) sb.append("    ").append(acc).append('\n');
            sb.append("  Output accessors: ").append(accessors.outputAccessors().size()).append('\n');
            for (var acc : accessors.outputAccessors()) sb.append("    ").append(acc).append('\n');
            sb.append("  Unknown accessors:").append(accessors.unknownAccessors().size()).append('\n');
            for (var acc : accessors.unknownAccessors()) sb.append("    ").append(acc).append('\n');

        }

        public void rejected(String reason) {
            sb.append(MINOR_SEP).append('\n');
            sb.append("STATUS: REJECTED\n");
            sb.append("REASON: ").append(reason).append('\n');
            sb.append(SEP).append('\n');

            synchronized (pipeline) {
                pipeline.rejectReasons.merge(reason, 1, Integer::sum);
            }
            finalizeTrace();
        }

        public void failed(Throwable t) {
            sb.append(MINOR_SEP).append('\n');
            sb.append("STATUS: FAILED\n");
            sb.append("ERROR:  ").append(t.getClass().getSimpleName())
                    .append(": ").append(t.getMessage()).append('\n');
            sb.append(SEP).append('\n');
            finalizeTrace();
        }

        private void finalizeTrace() {
            if (finalized) return;
            finalized = true;

            synchronized (pipeline) {
                pipeline.totalRecipes++;
                pipeline.rejectedCount++;

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
