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

package org.complexityanalyzer.harvest.debug;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.harvest.collector.DeepItemCollector;
import org.complexityanalyzer.harvest.collector.HarvestUtility;
import org.complexityanalyzer.harvest.engine.HarvestedItems;
import org.complexityanalyzer.harvest.inspector.*;
import org.complexityanalyzer.util.ModFileManager;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Locale.ROOT;
import static net.minecraft.world.item.Items.AIR;

public final class FullDebugTracePipeline {
    public static final boolean DEBUG_ENABLED = true;
    private static final String SEP = "═".repeat(60);
    private static final String MINOR_SEP = "─".repeat(60);

    static {
        if (DEBUG_ENABLED) {
            ComplexityAnalyzer.LOGGER.info("[Harvest] FullDebugTracePipeline is ENABLED");
        } else {
            ComplexityAnalyzer.LOGGER.debug("[Harvest] FullDebugTracePipeline is DISABLED");
        }
    }

    private final Path worldDir;
    private final StringBuilder buffer;
    private final Map<String, Integer> recipeTypeStats;
    private final Map<String, Integer> rejectReasons;
    private int totalRecipes;
    private int harvestedCount;
    private int rejectedCount;
    private int failedCount;

    public FullDebugTracePipeline(Path worldDir) {
        this.worldDir = worldDir;
        if (DEBUG_ENABLED) {
            this.buffer = new StringBuilder(1024 * 1024);
            this.recipeTypeStats = new LinkedHashMap<>();
            this.rejectReasons = new LinkedHashMap<>();
        } else {
            this.buffer = null;
            this.recipeTypeStats = null;
            this.rejectReasons = null;
        }
    }

    private static String hexIdentity(Object obj) {
        if (obj == null) return "null";
        return "@" + Integer.toHexString(System.identityHashCode(obj));
    }

    private static String formatItemStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "EMPTY";
        var id = GameRegistryManager.getItemId(stack.getItem());
        var count = stack.getCount();
        var patch = stack.getComponentsPatch();
        var patchStr = patch.isEmpty() ? "" : " [components=" + patch + "]";
        return count + "x " + id + patchStr;
    }

    private static String formatFluidStack(FluidStack fs) {
        if (fs == null || fs.isEmpty()) return "EMPTY_FLUID";
        var id = GameRegistryManager.getFluidId(fs.getFluid());
        return fs.getAmount() + "mb " + id;
    }

    private static String formatValueDetailed(Object val) {
        if (val == null) return "null";
        return switch (val) {
            case ItemStack stack -> formatItemStack(stack) + " (" + hexIdentity(stack) + ")";
            case Item item -> "Item[" + GameRegistryManager.getItemId(item) + "] (" + hexIdentity(item) + ")";
            case Block block -> "Block[" + GameRegistryManager.getBlockId(block) + "] (" + hexIdentity(block) + ")";
            case Fluid fluid -> "Fluid[" + GameRegistryManager.getFluidId(fluid) + "] (" + hexIdentity(fluid) + ")";
            case FluidStack fs -> formatFluidStack(fs) + " (" + hexIdentity(fs) + ")";
            case TagKey<?> tag -> "TagKey[" + tag.registry().location() + " / " + tag.location() + "]";
            case Holder<?> holder -> {
                if (holder.isBound()) yield "Holder[Bound=" + formatValueDetailed(holder.value()) + "]";
                var keyOpt = holder.unwrapKey();
                yield "Holder[Key=" + keyOpt.map(k -> k.location().toString()).orElse("unbound") + "]";
            }
            case Ingredient ing -> {
                var items = ing.getItems();
                var sb = new StringBuilder("Ingredient[").append(items.length).append(" vars: ");
                for (int i = 0; i < Math.min(items.length, 3); i++) {
                    sb.append(formatItemStack(items[i])).append("; ");
                }
                if (items.length > 3) sb.append("... (+").append(items.length - 3).append(" more)");
                sb.append("] (").append(hexIdentity(ing)).append(")");
                yield sb.toString();
            }
            case Collection<?> c -> c.getClass().getSimpleName() + "[size=" + c.size() + "] (" + hexIdentity(c) + ")";
            case Map<?, ?> m -> m.getClass().getSimpleName() + "[size=" + m.size() + "] (" + hexIdentity(m) + ")";
            case Object[] arr ->
                    arr.getClass().getComponentType().getSimpleName() + "[len=" + arr.length + "] (" + hexIdentity(arr) + ")";
            case String s -> "\"" + (s.length() > 50 ? s.substring(0, 47) + "..." : s) + "\"";
            default -> val.getClass().getName() + " (" + hexIdentity(val) + ")";
        };
    }

    private static String buildRejectReason(HarvestedItems items) {
        var sb = new StringBuilder("No structural recipe node: ");
        sb.append("inputItems=").append(items.inputItems().size());
        sb.append(" outputItems=").append(items.outputItems().size());
        sb.append(" inputIngredients=").append(items.inputIngredients().size());
        sb.append(" inputFluids=").append(items.inputFluids().size());
        sb.append(" outputFluids=").append(items.outputFluids().size());
        sb.append(" rootType=").append(items.root() != null ? items.root().getClass().getSimpleName() : "null");
        if (items.root() != null) {
            var detection = AntivirusStyleDetector.detect(items.root().getClass());
            sb.append(" antivirus=").append(detection.verdict());
        }
        return sb.toString();
    }

    public void traceHarvested(ResourceLocation recipeId, Object recipe, Level level, HarvestedItems items) {
        if (!DEBUG_ENABLED) return;
        var tb = new TraceBuilder(this, recipe, recipeId.toString());
        tb.header();
        tb.fields();
        tb.methods();
        tb.accessors(level);
        tb.crossAccessorMatrix(level);
        tb.harvested(items);
    }

    public void traceRejected(ResourceLocation recipeId, Object recipe, Level level, HarvestedItems items) {
        if (!DEBUG_ENABLED) return;
        String reason = buildRejectReason(items);
        var tb = new TraceBuilder(this, recipe, recipeId.toString());
        tb.header();
        tb.fields();
        tb.methods();
        tb.accessors(level);
        tb.crossAccessorMatrix(level);
        tb.rejected(reason);
    }

    public void traceFailed(ResourceLocation recipeId, String className, Throwable t) {
        if (!DEBUG_ENABLED) return;
        synchronized (this) {
            totalRecipes++;
            failedCount++;
            buffer.append("FAILED recipe=").append(recipeId.toString())
                    .append(" class=").append(className)
                    .append(" error=").append(t.getClass().getSimpleName())
                    .append(": ").append(t.getMessage()).append('\n');
        }
    }

    public void flush() {
        if (!DEBUG_ENABLED || buffer == null) return;
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
                buffer.append(String.format(ROOT, "  %-50s: %d\n", entry.getKey(), entry.getValue()));
            }
            buffer.append('\n');
        }

        if (!rejectReasons.isEmpty()) {
            buffer.append("Reject reasons:\n");
            for (var entry : rejectReasons.entrySet()) {
                buffer.append(String.format(ROOT, "  %-50s: %d\n", entry.getKey(), entry.getValue()));
            }
        }

        try {
            var file = ModFileManager.resolve(worldDir, "runtime_harvest.txt");
            ModFileManager.writeStringAtomic(file, buffer.toString());
            ComplexityAnalyzer.LOGGER.info("[Harvest:Debug] Written {} recipes trace to {}", totalRecipes, file);
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[Harvest:Debug] Failed to write trace: {}", t.getMessage());
        }
    }

    public static final class TraceBuilder {
        private final FullDebugTracePipeline pipeline;
        private final Object recipe;
        private final String recipeId;
        private final StringBuilder sb = new StringBuilder(8192);
        private boolean finalized;

        TraceBuilder(FullDebugTracePipeline pipeline, Object recipe, String recipeId) {
            this.pipeline = pipeline;
            this.recipe = recipe;
            this.recipeId = recipeId;
        }

        public void header() {
            sb.append(SEP).append('\n');
            sb.append("RECIPE ID:   ").append(recipeId).append('\n');
            if (recipe instanceof Recipe<?> r) {
                var typeId = GameRegistryManager.getRecipeTypeId(r.getType());
                sb.append("RECIPE TYPE: ").append(typeId != null ? typeId : r.getType().toString()).append('\n');
            }
            sb.append("CLASS:       ").append(recipe.getClass().getName())
                    .append(" (").append(hexIdentity(recipe)).append(')').append('\n');

            var clazz = recipe.getClass();
            sb.append("INTERFACES:\n");
            for (var iface : clazz.getInterfaces()) sb.append("  - ").append(iface.getName()).append('\n');
            if (clazz.getSuperclass() != null && clazz.getSuperclass() != Object.class) {
                sb.append("  extends ").append(clazz.getSuperclass().getName()).append('\n');
            }

            var profile = PatternSignatureEngine.profile(clazz);
            sb.append("SIGNATURE:   level=").append(profile.level())
                    .append(" score=").append(profile.totalScore())
                    .append(" isRecipe=").append(profile.isRecipe())
                    .append(" isMachine=").append(profile.isMachine())
                    .append('\n');
        }

        public void fields() {
            sb.append(MINOR_SEP).append('\n');
            sb.append("FIELDS AUDIT:\n");

            var meta = RecipeMetadata.getMeta(recipe.getClass());

            for (var f : meta.allFields()) {
                try {
                    var val = f.get(recipe);
                    var valStr = formatValueDetailed(val);
                    var role = HeuristicRoleClassifier.classifyField(f);

                    sb.append(String.format(ROOT,
                            "  [%s] %-32s : %-35s = %s\n",
                            role.role().name().substring(0, 4),
                            f.getName(),
                            f.getType().getSimpleName(),
                            valStr));
                } catch (Throwable t) {
                    sb.append(String.format(ROOT,
                            "  [ERR] %-32s : %-35s (error: %s: %s)\n",
                            f.getName(),
                            f.getType().getSimpleName(),
                            t.getClass().getSimpleName(),
                            t.getMessage()));
                }
            }
        }

        public void methods() {
            sb.append(MINOR_SEP).append('\n');
            sb.append("METHODS AUDIT:\n");

            var meta = RecipeMetadata.getMeta(recipe.getClass());
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
                    String valStr = formatValueDetailed(val);
                    sb.append(String.format(ROOT,
                            "  [%s] %-32s() → %-35s = %s\n",
                            role.role().name().substring(0, 4),
                            m.getName(),
                            rt.getSimpleName(),
                            valStr));
                } catch (Throwable t) {
                    sb.append(String.format(ROOT,
                            "  [ERR] %-32s() → %-35s (error: %s)\n",
                            m.getName(),
                            rt.getSimpleName(),
                            t.getClass().getSimpleName()));
                }
            }
            sb.append(shown == 0 ? "  (no relevant methods found)\n" : "  (" + shown + " relevant methods shown)\n");
        }

        public void accessors(Level level) {
            sb.append(MINOR_SEP).append('\n');
            sb.append("ACCESSOR EXTRACTION & EVALUATION:\n");

            var accessors = RecipeMetadata.getUniversalAccessors(recipe, level);

            for (var acc : accessors.allAccessors()) {
                try {
                    var val = acc.extract(recipe, level);
                    String valStr = formatValueDetailed(val);
                    sb.append(String.format(ROOT, "  [%s] %-52s = %s\n", acc.type(), acc, valStr));
                } catch (Throwable t) {
                    sb.append(String.format(ROOT, "  [ERR] %-52s = %s: %s\n", acc, t.getClass().getSimpleName(), t.getMessage()));
                }
            }
        }

        public void crossAccessorMatrix(Level level) {
            sb.append(MINOR_SEP).append('\n');
            sb.append("CROSS-ACCESSOR SOURCE ANALYSIS (Duplicate Detector):\n");

            var fastAccessors = RecipeMetadata.getFastAccessors(recipe.getClass());
            var visited = new ReferenceOpenHashSet<>();

            ItemStack apiResult = ItemStack.EMPTY;
            if (recipe instanceof Recipe<?> r && level != null) {
                try {
                    apiResult = r.getResultItem(level.registryAccess());
                    sb.append("  [API_RESULT] r.getResultItem() -> ").append(formatItemStack(apiResult))
                            .append(" (").append(hexIdentity(apiResult)).append(')').append('\n');
                } catch (Throwable t) {
                    sb.append("  [API_RESULT_ERR] r.getResultItem() threw: ").append(t.getClass().getSimpleName()).append('\n');
                }
            }

            var recordedContainers = new Object2ObjectOpenHashMap<String, ObjectList<ItemStack>>();

            for (var acc : fastAccessors.probeAccessors()) {
                try {
                    var raw = acc.extract(recipe, level);
                    if (raw != null && !HarvestUtility.isEmptyContainer(raw)) {
                        var tempItems = new ObjectArrayList<ItemStack>();
                        visited.clear();
                        DeepItemCollector.collect(raw, tempItems, 0, visited);

                        var label = acc.type() + ":" + acc.name() + " (" + hexIdentity(raw) + ")";
                        recordedContainers.put(label, tempItems);

                        sb.append("  [PROBE_OUTPUT_CONTAINER] ").append(label)
                                .append(" -> Extracted ").append(tempItems.size()).append(" item(s): ");
                        for (var s : tempItems) sb.append(formatItemStack(s)).append("; ");
                        sb.append('\n');
                    }
                } catch (Throwable ignored) {
                }
            }

            var provider = level != null ? level.registryAccess() : null;
            if (!apiResult.isEmpty() && apiResult.getItem() != AIR) {
                for (var entry : recordedContainers.entrySet()) {
                    for (var stack : entry.getValue()) {
                        if (ItemStackIdentity.sameItemData(stack, apiResult, provider)) {
                            sb.append("    ⚠️ [OVERLAP DETECTED] Container '").append(entry.getKey())
                                    .append("' contains ").append(formatItemStack(stack))
                                    .append(", which is identical to apiResult (").append(formatItemStack(apiResult)).append(")!\n")
                                    .append("       -> If both apiResult and this container are added, this item will be DUPLICATED (x")
                                    .append(apiResult.getCount() + stack.getCount()).append(").\n");
                        }
                    }
                }
            }

            var keys = new ObjectArrayList<>(recordedContainers.keySet());
            for (int i = 0; i < keys.size(); i++) {
                for (int j = i + 1; j < keys.size(); j++) {
                    var keyA = keys.get(i);
                    var keyB = keys.get(j);
                    var listA = recordedContainers.get(keyA);
                    var listB = recordedContainers.get(keyB);

                    if (isSameContent(listA, listB, provider)) {
                        sb.append("    ⚠️ [DUPLICATE ACCESSORS DETECTED] '").append(keyA)
                                .append("' and '").append(keyB)
                                .append("' return the EXACT same payload list!\n")
                                .append("       -> Calling both will cause duplicate outputs.\n");
                    }
                }
            }
        }

        private boolean isSameContent(ObjectList<ItemStack> a, ObjectList<ItemStack> b, net.minecraft.core.HolderLookup.Provider provider) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) {
                if (!ItemStackIdentity.sameItemDataAndCount(a.get(i), b.get(i), provider)) return false;
            }
            return true;
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
                    for (var stack : subItems) sb.append(formatItemStack(stack)).append("; ");
                    if (subItems.length > 0) sb.setLength(sb.length() - 2);
                    sb.append("]\n");
                }
            }
            if (!items.inputFluids().isEmpty()) {
                sb.append("  Input fluids: ");
                for (var fs : items.inputFluids()) sb.append(formatFluidStack(fs)).append(", ");
                sb.setLength(sb.length() - 2);
                sb.append('\n');
            }
            if (!items.outputFluids().isEmpty()) {
                sb.append("  Output fluids: ");
                for (var fs : items.outputFluids()) sb.append(formatFluidStack(fs)).append(", ");
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
            sb.append("ERROR:  ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
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
}