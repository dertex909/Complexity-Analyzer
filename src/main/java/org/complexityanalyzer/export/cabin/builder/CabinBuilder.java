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

package org.complexityanalyzer.export.cabin.builder;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.SourceManager;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.sources.HardcodedSourcesProvider;
import org.complexityanalyzer.analyzer.solver.SolverResult;
import org.complexityanalyzer.api.IHardcodedSourceRegistry;
import org.complexityanalyzer.config.ComplexityConfig;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.export.cabin.api.*;
import org.complexityanalyzer.graph.RecipeGraph;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;

import static net.minecraft.world.entity.MobCategory.MISC;
import static net.minecraft.world.item.Items.AIR;

public final class CabinBuilder {

    private final AnalysisEngine engine;
    private final String serverName;
    private final String modVersion;
    private final long timestampEpochMs;
    private final HolderLookup.Provider registryAccess;

    private final StringPool strings = new StringPool(8192);

    private ObjectList<Item> orderedItems;
    private Reference2IntOpenHashMap<Item> itemIndex;
    private ObjectList<EntityType<?>> orderedMobs;
    private Reference2IntOpenHashMap<EntityType<?>> mobIndex;
    private ObjectList<Fluid> orderedFluids;
    private Reference2IntOpenHashMap<Fluid> fluidIndex;

    public CabinBuilder(AnalysisEngine engine, String serverName, String modVersion, HolderLookup.Provider registryAccess) {
        this.engine = engine;
        this.serverName = serverName != null ? serverName : "unknown";
        this.modVersion = modVersion != null ? modVersion : "unknown";
        this.timestampEpochMs = System.currentTimeMillis();
        this.registryAccess = registryAccess;
    }

    private static IHardcodedSourceRegistry tryGetHardcodedRegistry() {
        try {
            return HardcodedSourcesProvider.getRegistry();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public ObjectList<CabinSection> build() {
        if (!engine.isReady())
            throw new IllegalStateException("AnalysisEngine is not READY (state=" + engine.getCurrentState() + ")");

        long t0 = System.currentTimeMillis();
        prepareIndices();

        var ctx = new SectionBuilderContext(engine, strings, orderedItems, itemIndex, orderedMobs, mobIndex, orderedFluids, fluidIndex, registryAccess);

        var itemBuilder = new ItemSectionBuilder(ctx);
        var recipeBuilder = new RecipeSectionBuilder(ctx);
        var mobBuilder = new MobSectionBuilder(ctx);
        var fluidBuilder = new FluidSectionBuilder(ctx);
        var hardcodedRegistry = tryGetHardcodedRegistry();
        var sourceManager = engine.getSourceManager();
        var mobProvider = engine.getMobPropertyProvider();
        var mobDropSource = engine.getMobDropSource();
        var solverResult = engine.getSolverResult();
        var graph = engine.getGraph();

        var tw = new TimingLog();
        var baseAcc = tw.run("baseData", () -> itemBuilder.buildBaseData(sourceManager));
        var sourcesAcc = tw.run("sources", () -> itemBuilder.buildSources(sourceManager));
        var itemResult = tw.run("items", () -> itemBuilder.buildItemsSection(hardcodedRegistry, baseAcc, sourcesAcc));

        var recipes = tw.run("recipes", () -> recipeBuilder.buildRecipes(graph));
        byte[] usageBytes = tw.run("usage", () -> recipeBuilder.buildUsage(graph));
        var mobsResult = tw.run("mobs", () -> mobBuilder.buildMobs(mobProvider, mobDropSource));
        byte[] dropsBytes = mobsResult.drops;
        byte[] sccBytes = tw.run("scc", () -> buildScc(solverResult));
        byte[] categoriesBytes = tw.run("categories", this::buildCategories);
        byte[] fluidsBytes = tw.run("fluids", fluidBuilder::buildFluidsSection);
        var fluidRecipes = tw.run("fluidRecipes", () -> fluidBuilder.buildFluidRecipes(graph));
        byte[] fluidUsageBytes = tw.run("fluidUsage", () -> fluidBuilder.buildFluidUsage(graph));
        byte[] idxItemHash = tw.run("idxItemHash", this::buildItemHashIndex);
        byte[] idxMobHash = tw.run("idxMobHash", this::buildMobHashIndex);
        byte[] idxFluidHash = tw.run("idxFluidHash", fluidBuilder::buildFluidHashIndex);
        byte[] machineIndexBytes = tw.run("machineIndex", () -> buildMachineIndex(graph));
        byte[] sourceTypeIndexBytes = tw.run("sourceTypeIndex", () -> buildSourceTypeIndex(sourceManager));
        byte[] modSummaryBytes = tw.run("modSummary", () -> buildModSummary(graph));
        byte[] meta = buildMeta(itemResult, mobsResult, recipes, machineIndexBytes, modSummaryBytes);
        byte[] stringsBytes = tw.run("encodeStrings", this::encodeStrings);
        tw.log();

        var out = new ObjectArrayList<CabinSection>(20);
        out.add(CabinSection.compressed(CabinFormat.SEC_META, meta));
        out.add(CabinSection.compressed(CabinFormat.SEC_STRINGS, stringsBytes));
        out.add(CabinSection.compressed(CabinFormat.SEC_ITEMS, itemResult.items));
        out.add(CabinSection.compressed(CabinFormat.SEC_BASE_DATA, baseAcc.bytes()));
        out.add(CabinSection.compressed(CabinFormat.SEC_SOURCES, sourcesAcc.bytes()));
        out.add(CabinSection.compressed(CabinFormat.SEC_RECIPES, recipes.payload));
        out.add(CabinSection.compressed(CabinFormat.SEC_USAGE, usageBytes));
        out.add(CabinSection.compressed(CabinFormat.SEC_FLUID_RECIPES, fluidRecipes.payload));
        out.add(CabinSection.compressed(CabinFormat.SEC_FLUID_USAGE, fluidUsageBytes));
        out.add(CabinSection.compressed(CabinFormat.SEC_MOBS, mobsResult.mobs));
        out.add(CabinSection.compressed(CabinFormat.SEC_DROPS, dropsBytes));
        out.add(CabinSection.compressed(CabinFormat.SEC_SCC, sccBytes));
        out.add(CabinSection.compressed(CabinFormat.SEC_CATEGORIES, categoriesBytes));
        out.add(CabinSection.compressed(CabinFormat.SEC_FLUIDS, fluidsBytes));
        out.add(CabinSection.raw(CabinFormat.SEC_IDX_ITEM_HASH, idxItemHash));
        out.add(CabinSection.raw(CabinFormat.SEC_IDX_RECIPES_BY_OUTPUT, recipes.outputIndex));
        out.add(CabinSection.raw(CabinFormat.SEC_IDX_MOB_HASH, idxMobHash));
        out.add(CabinSection.raw(CabinFormat.SEC_IDX_FLUID_HASH, idxFluidHash));
        out.add(CabinSection.raw(CabinFormat.SEC_IDX_FLUID_RECIPES_BY_OUTPUT, fluidRecipes.outputIndex));
        out.add(CabinSection.compressed(CabinFormat.SEC_MACHINE_INDEX, machineIndexBytes));
        out.add(CabinSection.compressed(CabinFormat.SEC_SOURCE_TYPE_INDEX, sourceTypeIndexBytes));
        out.add(CabinSection.compressed(CabinFormat.SEC_MOD_SUMMARY, modSummaryBytes));

        long elapsed = System.currentTimeMillis() - t0;
        ComplexityAnalyzer.LOGGER.info("[Cabin] Built {} sections (items={}, recipes={}, mobs={}, strings={}) in {} ms",
                out.size(), orderedItems.size(), recipes.recipeCount, orderedMobs.size(), strings.size(), elapsed);

        return out;
    }

    private void prepareIndices() {
        var items = GameRegistryManager.getAllItems();
        this.orderedItems = items;
        this.itemIndex = new Reference2IntOpenHashMap<>(items.size());
        this.itemIndex.defaultReturnValue(-1);
        for (int i = 0; i < items.size(); i++) itemIndex.put(items.get(i), i);

        var mobs = new ObjectArrayList<EntityType<?>>(GameRegistryManager.getAllEntityTypes().size());
        for (var t : GameRegistryManager.getAllEntityTypes()) {
            if (t.getCategory() == MISC) continue;
            mobs.add(t);
        }
        this.orderedMobs = mobs;
        this.mobIndex = new Reference2IntOpenHashMap<>(mobs.size());
        this.mobIndex.defaultReturnValue(-1);
        for (int i = 0; i < mobs.size(); i++) mobIndex.put(mobs.get(i), i);

        var fluids = new ObjectArrayList<Fluid>();
        for (var f : GameRegistryManager.getAllFluids()) {
            var id = GameRegistryManager.getFluidId(f);
            if (id != null && id.getPath().startsWith("flowing_")) continue;
            fluids.add(f);
        }
        this.orderedFluids = fluids;
        this.fluidIndex = new Reference2IntOpenHashMap<>(fluids.size());
        this.fluidIndex.defaultReturnValue(-1);
        for (int i = 0; i < fluids.size(); i++) fluidIndex.put(fluids.get(i), i);
    }

    private byte[] buildScc(SolverResult solverResult) {
        var buf = new LeBuf(64);

        if (solverResult == null) {
            buf.i32(0);
            buf.i32(0);
            buf.i64(0);
            buf.u8(0);
            return buf.toByteArray();
        }

        buf.i32(solverResult.optimalComplexities().size());
        buf.i32(solverResult.iterations());
        buf.i64(solverResult.executionTimeMs());
        buf.u8(solverResult.converged() ? 1 : 0);

        return buf.toByteArray();
    }

    private byte[] buildCategories() {
        ComplexityCategory[] cats = ComplexityCategory.values();
        int catCount = cats.length;
        int n = orderedItems.size();
        var buckets = new ObjectArrayList<LongArrayList>(catCount);
        for (int c = 0; c < catCount; c++) buckets.add(new LongArrayList());
        for (int i = 0; i < n; i++) {
            var item = orderedItems.get(i);
            var ic = engine.getComplexityResult(item);
            var cat = ic != null ? ic.getCategory() : ComplexityCategory.UNCALCULABLE;
            buckets.get(cat.ordinal()).add(i);
        }
        var out = new LeBuf(64 + catCount * 12);
        out.u8(catCount);
        for (int c = 0; c < catCount; c++) {
            var cat = cats[c];
            out.i32(strings.intern(cat.getDisplayName()));
            var list = buckets.get(c);
            out.i32(list.size());
            for (int j = 0; j < list.size(); j++) out.i32((int) list.getLong(j));
        }
        return out.toByteArray();
    }

    private byte[] buildItemHashIndex() {
        int n = orderedItems.size();
        long[] hashes = new long[n];
        for (int i = 0; i < n; i++) {
            var id = GameRegistryManager.getItemId(orderedItems.get(i));
            String s = id != null ? id.toString() : "";
            hashes[i] = XxHash64.hashString(s, CabinFormat.XXH64_SEED);
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Long.compareUnsigned(hashes[a], hashes[b]));
        var out = new LeBuf(4 + n * 12);
        out.i32(n);
        for (int i = 0; i < n; i++) {
            int idx = order[i];
            out.i64(hashes[idx]);
            out.i32(idx);
        }
        return out.toByteArray();
    }

    private byte[] buildMobHashIndex() {
        int n = orderedMobs.size();
        long[] hashes = new long[n];
        for (int i = 0; i < n; i++) {
            var id = GameRegistryManager.getEntityTypeId(orderedMobs.get(i));
            String s = id != null ? id.toString() : "";
            hashes[i] = XxHash64.hashString(s, CabinFormat.XXH64_SEED);
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Long.compareUnsigned(hashes[a], hashes[b]));
        var out = new LeBuf(4 + n * 12);
        out.i32(n);
        for (int i = 0; i < n; i++) {
            int idx = order[i];
            out.i64(hashes[idx]);
            out.i32(idx);
        }
        return out.toByteArray();
    }

    private byte[] buildMachineIndex(RecipeGraph graph) {
        if (graph == null) {
            var empty = new LeBuf(4);
            empty.i32(0);
            return empty.toByteArray();
        }
        var registry = engine.getMachineRegistry();
        var machineToOutputs = new Int2ObjectOpenHashMap<IntArrayList>();
        if (registry != null) for (var r : graph.getAllRecipes()) {
            var rt = r.getRecipeType();
            if (rt == null) continue;
            var machines = registry.getMachinesForRecipe(rt);
            if (machines == null || machines.isEmpty()) continue;

            IntArrayList outIdx = null;
            var primary = r.getResultItem();
            if (primary != null && primary != AIR) {
                int pi = itemIndex.getInt(primary);
                if (pi >= 0) {
                    outIdx = new IntArrayList(2);
                    outIdx.add(pi);
                }
            }
            for (var stack : r.getItemOutputs()) {
                if (stack == null || stack.isEmpty()) continue;
                int oi = itemIndex.getInt(stack.getItem());
                if (oi < 0) continue;
                if (outIdx == null) outIdx = new IntArrayList(2);
                outIdx.add(oi);
            }

            for (var machineItem : machines) {
                int mi = itemIndex.getInt(machineItem);
                if (mi < 0) continue;
                var list = machineToOutputs.computeIfAbsent(mi, k -> new IntArrayList());
                if (outIdx != null) list.addAll(outIdx);
            }
        }

        for (var list : machineToOutputs.values()) {
            var seen = new IntOpenHashSet(list);
            list.clear();
            list.addAll(seen);
        }
        int machineCount = machineToOutputs.size();
        int[] machineIds = machineToOutputs.keySet().toIntArray();
        Arrays.sort(machineIds);
        var out = new LeBuf(4 + machineCount * 10 + machineCount * 4);
        out.i32(machineCount);
        var payload = new LeBuf(64 * 1024);
        int[] offsets = new int[machineCount];
        int[] counts = new int[machineCount];
        for (int i = 0; i < machineCount; i++) {
            int mi = machineIds[i];
            var outputs = machineToOutputs.get(mi);
            offsets[i] = payload.position();
            counts[i] = outputs.size();
            for (int j = 0; j < outputs.size(); j++) payload.i32(outputs.getInt(j));
        }
        for (int i = 0; i < machineCount; i++) {
            out.i32(machineIds[i]);
            out.i32(offsets[i]);
            out.u16(counts[i]);
        }
        out.bytes(payload.array(), 0, payload.size());
        return out.toByteArray();
    }

    private byte[] buildSourceTypeIndex(SourceManager sourceManager) {
        if (sourceManager == null) {
            var empty = new LeBuf(4);
            empty.i32(0);
            return empty.toByteArray();
        }
        int sourceTypeCount = BaseResourceData.ResourceSourceType.values().length;
        IntArrayList[] buckets = new IntArrayList[sourceTypeCount];
        for (int i = 0; i < sourceTypeCount; i++) buckets[i] = new IntArrayList();
        int n = orderedItems.size();
        for (int i = 0; i < n; i++) {
            var item = orderedItems.get(i);
            var base = sourceManager.analyze(item);
            if (base != null) {
                int ord = base.getSourceType().ordinal();
                if (ord < sourceTypeCount) buckets[ord].add(i);
            }
            var all = sourceManager.findAllSources(item);
            for (var src : all) {
                int ord = src.getSourceType().ordinal();
                if (ord < sourceTypeCount && !buckets[ord].contains(i)) buckets[ord].add(i);
            }
        }
        var out = new LeBuf(4 + sourceTypeCount * 6);
        int nonEmpty = 0;
        for (var b : buckets) if (!b.isEmpty()) nonEmpty++;
        out.i32(nonEmpty);
        for (int i = 0; i < sourceTypeCount; i++) {
            if (buckets[i].isEmpty()) continue;
            out.u8(i);
            out.i32(buckets[i].size());
            for (int j = 0; j < buckets[i].size(); j++) out.i32(buckets[i].getInt(j));
        }
        return out.toByteArray();
    }

    private byte[] buildModSummary(RecipeGraph graph) {
        var stats = new HashMap<String, ModStats>();
        int n = orderedItems.size();
        for (int i = 0; i < n; i++) {
            var item = orderedItems.get(i);
            var id = GameRegistryManager.getItemId(item);
            String modId = id != null ? id.getNamespace() : "unknown";
            var s = stats.computeIfAbsent(modId, k -> new ModStats());
            s.itemCount++;
            var ic = engine.getComplexityResult(item);
            if (ic != null && ic.isValid() && !Double.isInfinite(ic.getComplexity())) {
                s.complexitySum += ic.getComplexity();
                s.complexityItems++;
            }
        }
        if (graph != null) for (int i = 0; i < n; i++) {
            var item = orderedItems.get(i);
            var recipes = graph.getRecipes(item);
            if (recipes.isEmpty()) continue;
            var id = GameRegistryManager.getItemId(item);
            String modId = id != null ? id.getNamespace() : "unknown";
            var s = stats.get(modId);
            if (s != null) s.recipeCount += recipes.size();
        }
        var out = new LeBuf(4 + stats.size() * 20);
        out.i32(stats.size());
        for (var e : stats.entrySet()) {
            var s = e.getValue();
            double avg = s.complexityItems > 0 ? s.complexitySum / s.complexityItems : 0.0;
            out.i32(strings.intern(e.getKey()));
            out.i32(s.itemCount);
            out.i32(s.recipeCount);
            out.f64(avg);
        }
        return out.toByteArray();
    }

    private byte[] buildMeta(ItemSectionBuilder.ItemSectionResult itemResult, MobSectionBuilder.MobsResult mobsResult,
                             RecipeSectionBuilder.RecipesResult recipes, byte[] machineIndexBytes, byte[] modSummaryBytes) {
        var buf = new LeBuf(512);
        buf.i32(strings.intern("complexityanalyzer"));
        buf.i32(strings.intern(modVersion));
        buf.i32(strings.intern(serverName));
        buf.i32(strings.intern(Instant.ofEpochMilli(timestampEpochMs).toString()));
        buf.i64(timestampEpochMs);
        buf.i32(orderedItems.size());
        buf.i32(mobsResult.mobCount);
        buf.i32(orderedFluids.size());
        buf.i32(recipes.recipeCount);
        buf.i32(itemResult.validItems);
        buf.i32(itemResult.infiniteItems);
        int machineCount = machineIndexBytes != null && machineIndexBytes.length >= 4
                ? LeBuf.readI32(machineIndexBytes, 0) : 0;
        int modCount = modSummaryBytes != null && modSummaryBytes.length >= 4
                ? LeBuf.readI32(modSummaryBytes, 0) : 0;
        buf.i32(machineCount);
        buf.i32(modCount);
        ComplexityCategory[] cats = ComplexityCategory.values();
        buf.u8(cats.length);
        for (var c : cats) {
            buf.i32(strings.intern(c.getDisplayName()));
            buf.f64(c.ordinal() == ComplexityCategory.UNCALCULABLE.ordinal() ? -1.0
                    : (c.ordinal() == ComplexityCategory.UNOBTAINABLE.ordinal() ? Double.POSITIVE_INFINITY
                       : (c.ordinal() == 0 ? 0.0 : Math.pow(10, c.ordinal()))));
        }
        buf.f64(ComplexityConfig.getMachineTaxMultiplier());
        return buf.toByteArray();
    }

    private byte[] encodeStrings() {
        var buf = new LeBuf(Math.toIntExact(Math.min(Integer.MAX_VALUE - 16, strings.bytesEstimate())));
        strings.writeTo(buf);
        return buf.toByteArray();
    }

    private static final class TimingLog {
        private final StringBuilder sb = new StringBuilder();

        <T> T run(String name, java.util.function.Supplier<T> step) {
            long s = System.nanoTime();
            var result = step.get();
            long ms = (System.nanoTime() - s) / 1_000_000L;
            sb.append(name).append('=').append(ms).append("ms ");
            return result;
        }

        void log() {
            ComplexityAnalyzer.LOGGER.debug("[Cabin] section timings: {}", sb.toString().trim());
        }
    }

    private static class ModStats {
        int itemCount;
        int recipeCount;
        double complexitySum;
        int complexityItems;
    }
}