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

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.SourceManager;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;
import org.complexityanalyzer.analyzer.resource.providers.MobPropertyProvider;
import org.complexityanalyzer.analyzer.resource.sources.HardcodedSourcesProvider;
import org.complexityanalyzer.analyzer.resource.sources.MobDropSource;
import org.complexityanalyzer.analyzer.solver.SolverResult;
import org.complexityanalyzer.api.IHardcodedSourceRegistry;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.export.cabin.api.*;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;
import org.complexityanalyzer.graph.IngredientSlot;
import org.complexityanalyzer.graph.FluidIngredientSlot;
import org.complexityanalyzer.graph.RecipeCategory;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class CabinBuilder {

    private final AnalysisEngine engine;
    private final String serverName;
    private final String modVersion;
    private final long timestampEpochMs;

    private final StringPool strings = new StringPool(8192);

    private ObjectList<Item> orderedItems;
    private Reference2IntOpenHashMap<Item> itemIndex;
    private ObjectList<EntityType<?>> orderedMobs;
    private Reference2IntOpenHashMap<EntityType<?>> mobIndex;
    private ObjectList<Fluid> orderedFluids;
    private Reference2IntOpenHashMap<Fluid> fluidIndex;

    public CabinBuilder(AnalysisEngine engine, String serverName, String modVersion) {
        this.engine = engine;
        this.serverName = serverName != null ? serverName : "unknown";
        this.modVersion = modVersion != null ? modVersion : "unknown";
        this.timestampEpochMs = System.currentTimeMillis();
    }

    public ObjectList<CabinSection> build() {
        if (!engine.isReady())
            throw new IllegalStateException("AnalysisEngine is not READY (state=" + engine.getCurrentState() + ")");

        long t0 = System.currentTimeMillis();
        prepareIndices();

        SectionBuilderContext ctx = new SectionBuilderContext(
                engine, strings, orderedItems, itemIndex, orderedMobs, mobIndex, orderedFluids, fluidIndex
        );

        ItemSectionBuilder itemBuilder = new ItemSectionBuilder(ctx);
        RecipeSectionBuilder recipeBuilder = new RecipeSectionBuilder(ctx);
        MobSectionBuilder mobBuilder = new MobSectionBuilder(ctx);
        FluidSectionBuilder fluidBuilder = new FluidSectionBuilder(ctx);
        IHardcodedSourceRegistry hardcodedRegistry = tryGetHardcodedRegistry();
        SourceManager sourceManager = engine.getSourceManager();
        MobPropertyProvider mobProvider = engine.getMobPropertyProvider();
        MobDropSource mobDropSource = engine.getMobDropSource();
        SolverResult solverResult = engine.getSolverResult();
        RecipeGraph graph = engine.getGraph();

        ItemSectionBuilder.BaseDataAccumulator baseAcc = itemBuilder.buildBaseData(sourceManager);
        ItemSectionBuilder.SourcesAccumulator sourcesAcc = itemBuilder.buildSources(sourceManager);
        ItemSectionBuilder.ItemSectionResult itemResult = itemBuilder.buildItemsSection(hardcodedRegistry, baseAcc, sourcesAcc);

        RecipeSectionBuilder.RecipesResult recipes = recipeBuilder.buildRecipes(graph);
        byte[] usageBytes = recipeBuilder.buildUsage(graph);
        MobSectionBuilder.MobsResult mobsResult = mobBuilder.buildMobs(mobProvider, mobDropSource);
        byte[] dropsBytes = mobsResult.drops;
        byte[] sccBytes = buildScc(solverResult);
        byte[] categoriesBytes = buildCategories();
        byte[] fluidsBytes = fluidBuilder.buildFluidsSection();
        FluidSectionBuilder.FluidRecipesResult fluidRecipes = fluidBuilder.buildFluidRecipes(graph);
        byte[] fluidUsageBytes = fluidBuilder.buildFluidUsage(graph);
        byte[] idxItemHash = buildItemHashIndex();
        byte[] idxMobHash = buildMobHashIndex();
        byte[] idxFluidHash = fluidBuilder.buildFluidHashIndex();
        byte[] machineIndexBytes = buildMachineIndex(graph);
        byte[] sourceTypeIndexBytes = buildSourceTypeIndex(sourceManager);
        byte[] modSummaryBytes = buildModSummary(graph);
        byte[] meta = buildMeta(itemResult, mobsResult, recipes, machineIndexBytes, modSummaryBytes);
        byte[] stringsBytes = encodeStrings();

        ObjectList<CabinSection> out = new ObjectArrayList<>(20);
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
        ObjectList<Item> items = GameRegistryManager.getAllItems();
        this.orderedItems = items;
        this.itemIndex = new Reference2IntOpenHashMap<>(items.size());
        this.itemIndex.defaultReturnValue(-1);
        for (int i = 0; i < items.size(); i++) itemIndex.put(items.get(i), i);

        ObjectList<EntityType<?>> mobs = new ObjectArrayList<>(GameRegistryManager.getAllEntityTypes().size());
        for (EntityType<?> t : GameRegistryManager.getAllEntityTypes()) {
            if (t.getCategory() == MobCategory.MISC) continue;
            mobs.add(t);
        }
        this.orderedMobs = mobs;
        this.mobIndex = new Reference2IntOpenHashMap<>(mobs.size());
        this.mobIndex.defaultReturnValue(-1);
        for (int i = 0; i < mobs.size(); i++) mobIndex.put(mobs.get(i), i);

        ObjectList<Fluid> fluids = new ObjectArrayList<>();
        for (Fluid f : GameRegistryManager.getAllFluids()) {
            ResourceLocation id = GameRegistryManager.getFluidId(f);
            if (id != null && id.getPath().startsWith("flowing_")) {
                continue;
            }
            fluids.add(f);
        }
        this.orderedFluids = fluids;
        this.fluidIndex = new Reference2IntOpenHashMap<>(fluids.size());
        this.fluidIndex.defaultReturnValue(-1);
        for (int i = 0; i < fluids.size(); i++) fluidIndex.put(fluids.get(i), i);
    }

    private static IHardcodedSourceRegistry tryGetHardcodedRegistry() {
        try {
            return HardcodedSourcesProvider.getRegistry();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private byte[] buildScc(SolverResult solverResult) {
        LeBuf buf = new LeBuf(64);

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
        ObjectList<LongArrayList> buckets = new ObjectArrayList<>(catCount);
        for (int c = 0; c < catCount; c++) buckets.add(new LongArrayList());
        for (int i = 0; i < n; i++) {
            Item item = orderedItems.get(i);
            ItemComplexity ic = engine.getComplexityResult(item);
            ComplexityCategory cat = ic != null ? ic.getCategory() : ComplexityCategory.UNCALCULABLE;
            buckets.get(cat.ordinal()).add(i);
        }
        LeBuf out = new LeBuf(64 + catCount * 12);
        out.u8(catCount);
        for (int c = 0; c < catCount; c++) {
            ComplexityCategory cat = cats[c];
            out.i32(strings.intern(cat.getDisplayName()));
            LongArrayList list = buckets.get(c);
            out.i32(list.size());
            for (int j = 0; j < list.size(); j++) out.i32((int) list.getLong(j));
        }
        return out.toByteArray();
    }

    private byte[] buildItemHashIndex() {
        int n = orderedItems.size();
        long[] hashes = new long[n];
        for (int i = 0; i < n; i++) {
            ResourceLocation id = GameRegistryManager.getItemId(orderedItems.get(i));
            String s = id != null ? id.toString() : "";
            hashes[i] = XxHash64.hashString(s, CabinFormat.XXH64_SEED);
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Long.compareUnsigned(hashes[a], hashes[b]));
        LeBuf out = new LeBuf(4 + n * 12);
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
            ResourceLocation id = GameRegistryManager.getEntityTypeId(orderedMobs.get(i));
            String s = id != null ? id.toString() : "";
            hashes[i] = XxHash64.hashString(s, CabinFormat.XXH64_SEED);
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Long.compareUnsigned(hashes[a], hashes[b]));
        LeBuf out = new LeBuf(4 + n * 12);
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
            LeBuf empty = new LeBuf(4);
            empty.i32(0);
            return empty.toByteArray();
        }
        var registry = engine.getMachineRegistry();
        Int2ObjectMap<IntArrayList> machineToOutputs = new Int2ObjectOpenHashMap<>();
        int n = orderedItems.size();
        for (int i = 0; i < n; i++) {
            Item item = orderedItems.get(i);
            var recipes = graph.getRecipes(item);
            if (recipes.isEmpty()) continue;
            for (RecipeNode r : recipes) {
                var rt = r.getRecipeType();
                if (rt == null || registry == null) continue;
                var machines = registry.getMachinesForRecipe(rt);
                if (machines == null) continue;
                for (Item machineItem : machines) {
                    int mi = itemIndex.getInt(machineItem);
                    if (mi < 0) continue;
                    machineToOutputs.computeIfAbsent(mi, k -> new IntArrayList()).add(i);
                }
            }
        }
        for (var list : machineToOutputs.values()) {
            IntOpenHashSet seen = new IntOpenHashSet(list);
            list.clear();
            list.addAll(seen);
        }
        int machineCount = machineToOutputs.size();
        int[] machineIds = machineToOutputs.keySet().toIntArray();
        Arrays.sort(machineIds);
        LeBuf out = new LeBuf(4 + machineCount * 10 + machineCount * 4);
        out.i32(machineCount);
        LeBuf payload = new LeBuf(64 * 1024);
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
            LeBuf empty = new LeBuf(4);
            empty.i32(0);
            return empty.toByteArray();
        }
        int sourceTypeCount = BaseResourceData.ResourceSourceType.values().length;
        IntArrayList[] buckets = new IntArrayList[sourceTypeCount];
        for (int i = 0; i < sourceTypeCount; i++) buckets[i] = new IntArrayList();
        int n = orderedItems.size();
        for (int i = 0; i < n; i++) {
            Item item = orderedItems.get(i);
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
        LeBuf out = new LeBuf(4 + sourceTypeCount * 6);
        int nonEmpty = 0;
        for (IntArrayList b : buckets) if (!b.isEmpty()) nonEmpty++;
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
        Map<String, ModStats> stats = new HashMap<>();
        int n = orderedItems.size();
        for (int i = 0; i < n; i++) {
            Item item = orderedItems.get(i);
            ResourceLocation id = GameRegistryManager.getItemId(item);
            String modId = id != null ? id.getNamespace() : "unknown";
            ModStats s = stats.computeIfAbsent(modId, k -> new ModStats());
            s.itemCount++;
            ItemComplexity ic = engine.getComplexityResult(item);
            if (ic != null && ic.isValid() && !Double.isInfinite(ic.getComplexity())) {
                s.complexitySum += ic.getComplexity();
                s.complexityItems++;
            }
        }
        if (graph != null) {
            for (int i = 0; i < n; i++) {
                Item item = orderedItems.get(i);
                var recipes = graph.getRecipes(item);
                if (recipes.isEmpty()) continue;
                ResourceLocation id = GameRegistryManager.getItemId(item);
                String modId = id != null ? id.getNamespace() : "unknown";
                ModStats s = stats.get(modId);
                if (s != null) s.recipeCount += recipes.size();
            }
        }
        LeBuf out = new LeBuf(4 + stats.size() * 20);
        out.i32(stats.size());
        for (Map.Entry<String, ModStats> e : stats.entrySet()) {
            ModStats s = e.getValue();
            double avg = s.complexityItems > 0 ? s.complexitySum / s.complexityItems : 0.0;
            out.i32(strings.intern(e.getKey()));
            out.i32(s.itemCount);
            out.i32(s.recipeCount);
            out.f64(avg);
        }
        return out.toByteArray();
    }

    private static class ModStats {
        int itemCount;
        int recipeCount;
        double complexitySum;
        int complexityItems;
    }

    private byte[] buildMeta(ItemSectionBuilder.ItemSectionResult itemResult, MobSectionBuilder.MobsResult mobsResult,
                             RecipeSectionBuilder.RecipesResult recipes, byte[] machineIndexBytes, byte[] modSummaryBytes) {
        LeBuf buf = new LeBuf(512);
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
        for (ComplexityCategory c : cats) {
            buf.i32(strings.intern(c.getDisplayName()));
            buf.f64(c.ordinal() == ComplexityCategory.UNCALCULABLE.ordinal() ? -1.0
                    : (c.ordinal() == ComplexityCategory.UNOBTAINABLE.ordinal() ? Double.POSITIVE_INFINITY
                       : (c.ordinal() == 0 ? 0.0 : Math.pow(10, c.ordinal()))));
        }
        return buf.toByteArray();
    }

    private byte[] encodeStrings() {
        LeBuf buf = new LeBuf(Math.toIntExact(Math.min(Integer.MAX_VALUE - 16, strings.bytesEstimate())));
        strings.writeTo(buf);
        return buf.toByteArray();
    }
}