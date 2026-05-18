package org.complexityanalyzer.harvest;

import java.lang.reflect.Field;

import net.minecraft.world.item.Item;
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

/**
 * Полный дебаг-трейсинг каждого рецепта.
 */
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

    public TraceBuilder beginRecipe(Object recipe, String recipeId) {
        return new TraceBuilder(this, recipe, recipeId);
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

        buffer.append("Recipe types distribution:\n");
        for (var entry : recipeTypeStats.entrySet()) {
            buffer.append(String.format(Locale.ROOT, "  %-50s: %d\n", entry.getKey(), entry.getValue()));
        }
        buffer.append('\n');

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
        private final String className;
        private final StringBuilder sb = new StringBuilder(4096);
        private boolean finalized;

        TraceBuilder(FullDebugTracePipeline pipeline, Object recipe, String recipeId) {
            this.pipeline = pipeline;
            this.recipe = recipe;
            this.recipeId = recipeId;
            this.className = recipe.getClass().getName();
        }

        public void classInfo() {
            sb.append(SEP).append('\n');
            sb.append("RECIPE: ").append(recipeId).append('\n');
            sb.append("CLASS:  ").append(className).append('\n');

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
                    .append(" isCodec=").append(profile.isCodec())
                    .append('\n');

        }

        public void fields() {
            sb.append(MINOR_SEP).append('\n');
            sb.append("FIELDS (");

            UniversalAccessorResolver.ClassMeta meta = UniversalAccessorResolver.getMeta(recipe.getClass());
            sb.append(meta.allFields().length).append(" total, ")
                    .append(meta.scanFields().length).append(" scan):\n");

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

        public TraceBuilder methods() {
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

            return this;
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

        public void harvested(HarvestedItems items) {
            sb.append(MINOR_SEP).append('\n');
            sb.append("STATUS: HARVESTED\n");
            sb.append("RESULT:\n");
            sb.append("  inputItems:      ").append(items.inputItems().size()).append('\n');
            sb.append("  outputItems:     ").append(items.outputItems().size()).append('\n');
            sb.append("  inputIngredients:").append(items.inputIngredients().size()).append('\n');
            sb.append("  inputFluids:     ").append(items.inputFluids().size()).append('\n');
            sb.append("  outputFluids:    ").append(items.outputFluids().size()).append('\n');

            if (!items.outputItems().isEmpty()) {
                sb.append("  Output stacks:\n");
                for (var s : items.outputItems()) sb.append("    - ").append(formatItemStack(s)).append('\n');
            }
            sb.append(SEP).append('\n');

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

        public TraceBuilder failed(Throwable t) {
            sb.append(MINOR_SEP).append('\n');
            sb.append("STATUS: FAILED\n");
            sb.append("ERROR:  ").append(t.getClass().getSimpleName())
                    .append(": ").append(t.getMessage()).append('\n');
            sb.append(SEP).append('\n');

            finalizeTrace(false);
            return this;
        }

        private void finalizeTrace(boolean harvested) {
            if (finalized) return;
            finalized = true;

            synchronized (pipeline) {
                pipeline.totalRecipes++;
                if (harvested) pipeline.harvestedCount++;
                else pipeline.rejectedCount++;

                String typeKey;
                if (recipe instanceof Recipe<?> r) {
                    typeKey = r.getType().toString();
                } else {
                    typeKey = recipe.getClass().getSimpleName();
                }
                pipeline.recipeTypeStats.merge(typeKey, 1, Integer::sum);
                pipeline.buffer.append(sb);
            }
        }
    }

    // ======================== Formatting Helpers ========================

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
        Item item = stack.getItem();
        return stack.getCount() + "x " + item;
    }
}
