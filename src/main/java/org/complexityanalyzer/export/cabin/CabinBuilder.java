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

package org.complexityanalyzer.export.cabin;

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
import org.complexityanalyzer.analyzer.resource.data.MobDropData;
import org.complexityanalyzer.analyzer.resource.providers.MobPropertyProvider;
import org.complexityanalyzer.analyzer.resource.sources.HardcodedSourcesProvider;
import org.complexityanalyzer.analyzer.resource.sources.MobDropSource;
import org.complexityanalyzer.analyzer.solver.SolverResult;
import org.complexityanalyzer.api.IHardcodedSourceRegistry;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.data.ItemComplexity;
import org.complexityanalyzer.graph.FluidIngredientSlot;
import org.complexityanalyzer.graph.IngredientSlot;
import org.complexityanalyzer.graph.RecipeCategory;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;

import java.time.Instant;

public final class CabinBuilder {

    private final AnalysisEngine engine;
    private final String serverName;
    private final String modVersion;
    private final long timestampEpochMs;

    private final StringPool strings = new StringPool(8192);

    private ObjectList<Item> orderedItems;
    private Reference2IntOpenHashMap<Item> itemIndex;
    private ObjectList<EntityType<?>> orderedMobs;
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

        IHardcodedSourceRegistry hardcodedRegistry = tryGetHardcodedRegistry();
        SourceManager sourceManager = engine.getSourceManager();
        MobPropertyProvider mobProvider = engine.getMobPropertyProvider();
        MobDropSource mobDropSource = engine.getMobDropSource();
        SolverResult solverResult = engine.getSolverResult();
        RecipeGraph graph = engine.getGraph();

        BaseDataAccumulator baseAcc = buildBaseData(sourceManager);
        SourcesAccumulator sourcesAcc = buildSources(sourceManager);

        ItemSectionResult itemResult = buildItemsSection(hardcodedRegistry, baseAcc, sourcesAcc);

        RecipesResult recipes = buildRecipes(graph);
        byte[] usageBytes = buildUsage(graph);

        MobsResult mobsResult = buildMobs(mobProvider, mobDropSource);
        byte[] dropsBytes = mobsResult.drops;

        byte[] sccBytes = buildScc(solverResult);
        byte[] categoriesBytes = buildCategories();

        byte[] idxItemHash = buildItemHashIndex();
        byte[] idxMobHash = buildMobHashIndex();

        byte[] meta = buildMeta(itemResult, mobsResult, recipes);

        byte[] stringsBytes = encodeStrings();

        ObjectList<CabinSection> out = new ObjectArrayList<>(16);
        out.add(CabinSection.compressed(CabinFormat.SEC_META, meta));
        out.add(CabinSection.compressed(CabinFormat.SEC_STRINGS, stringsBytes));
        out.add(CabinSection.compressed(CabinFormat.SEC_ITEMS, itemResult.items));
        out.add(CabinSection.compressed(CabinFormat.SEC_BASE_DATA, baseAcc.bytes()));
        out.add(CabinSection.compressed(CabinFormat.SEC_SOURCES, sourcesAcc.bytes()));
        out.add(CabinSection.compressed(CabinFormat.SEC_RECIPES, recipes.payload));
        out.add(CabinSection.compressed(CabinFormat.SEC_USAGE, usageBytes));
        out.add(CabinSection.compressed(CabinFormat.SEC_MOBS, mobsResult.mobs));
        out.add(CabinSection.compressed(CabinFormat.SEC_DROPS, dropsBytes));
        out.add(CabinSection.compressed(CabinFormat.SEC_SCC, sccBytes));
        out.add(CabinSection.compressed(CabinFormat.SEC_CATEGORIES, categoriesBytes));
        out.add(CabinSection.raw(CabinFormat.SEC_IDX_ITEM_HASH, idxItemHash));
        out.add(CabinSection.raw(CabinFormat.SEC_IDX_RECIPES_BY_OUTPUT, recipes.outputIndex));
        out.add(CabinSection.raw(CabinFormat.SEC_IDX_MOB_HASH, idxMobHash));

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
        Reference2IntOpenHashMap<EntityType<?>> mobIndex = new Reference2IntOpenHashMap<>(mobs.size());
        mobIndex.defaultReturnValue(-1);
        for (int i = 0; i < mobs.size(); i++) mobIndex.put(mobs.get(i), i);

        ObjectList<Fluid> fluids = GameRegistryManager.getAllFluids();
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

    private static final class ItemSectionResult {
        final byte[] items;
        final int validItems;
        final int infiniteItems;

        ItemSectionResult(byte[] items, int validItems, int infiniteItems) {
            this.items = items;
            this.validItems = validItems;
            this.infiniteItems = infiniteItems;
        }
    }

    private ItemSectionResult buildItemsSection(IHardcodedSourceRegistry hardcodedRegistry, BaseDataAccumulator baseAcc,
                                                SourcesAccumulator sourcesAcc) {
        int n = orderedItems.size();
        LeBuf buf = new LeBuf(CabinFormat.ITEM_RECORD_SIZE * n + 4);
        buf.i32(n);
        int validCount = 0;
        int infiniteCount = 0;
        for (int i = 0; i < n; i++) {
            Item item = orderedItems.get(i);
            ResourceLocation id = GameRegistryManager.getItemId(item);
            String idStr = id != null ? id.toString() : "minecraft:air";
            String displayName = safeDisplayName(item);

            ItemComplexity ic = engine.getComplexityResult(item);
            double complexity;
            int depth;
            int totalIngredients;
            ComplexityCategory category;
            int flags;
            int errorRef;
            if (ic == null) {
                complexity = -1.0;
                depth = 0;
                totalIngredients = 0;
                category = ComplexityCategory.UNCALCULABLE;
                flags = 0;
                errorRef = strings.intern("not analyzed");
            } else {
                complexity = ic.getComplexity();
                depth = ic.getDepth();
                totalIngredients = ic.getTotalIngredients();
                category = ic.getCategory();
                int f = 0;
                if (ic.hasRecipe()) f |= CabinFormat.ITEM_FLAG_HAS_RECIPE;
                if (ic.hasCycle()) f |= CabinFormat.ITEM_FLAG_HAS_CYCLE;
                if (ic.isValid()) f |= CabinFormat.ITEM_FLAG_IS_VALID;
                if (Double.isInfinite(complexity)) f |= CabinFormat.ITEM_FLAG_IS_INFINITE;
                if (!ic.hasRecipe()) f |= CabinFormat.ITEM_FLAG_NO_RECIPE_RESULT;
                flags = f;
                errorRef = strings.intern(ic.getErrorMessage() != null ? ic.getErrorMessage() : "");
                if (ic.isValid() && !Double.isInfinite(complexity)) validCount++;
                if (Double.isInfinite(complexity)) infiniteCount++;
            }
            if (hardcodedRegistry != null && hardcodedRegistry.isRegistered(item))
                flags |= CabinFormat.ITEM_FLAG_IS_HARDCODED;

            int usageCount = engine.getUsageCount(item);
            int idRef = strings.intern(idStr);
            int nameRef = strings.intern(displayName);
            int categoryRef = strings.intern(category.getDisplayName());

            BaseDataEntry baseEntry = baseAcc.entries[i];
            SourcesEntry sourcesEntry = sourcesAcc.entries[i];

            int recordStart = buf.position();
            buf.i32(idRef);
            buf.i32(nameRef);
            buf.f64(complexity);
            buf.i32(depth);
            buf.i32(totalIngredients);
            buf.i32(usageCount);
            buf.i32(categoryRef);
            buf.i32(baseEntry != null ? baseEntry.offset : CabinFormat.NULL_OFFSET);
            buf.i32(sourcesEntry != null ? sourcesEntry.offset : CabinFormat.NULL_OFFSET);
            buf.u16(sourcesEntry != null ? sourcesEntry.count : 0);
            buf.u8(category.ordinal());
            buf.u8(flags & 0xFF);
            buf.i32(errorRef);
            int recordEnd = buf.position();
            int written = recordEnd - recordStart;
            if (written != CabinFormat.ITEM_RECORD_SIZE)
                throw new IllegalStateException("ITEM record size mismatch: " + written);
        }
        return new ItemSectionResult(buf.toByteArray(), validCount, infiniteCount);
    }

    private static final class BaseDataAccumulator {
        final LeBuf payload = new LeBuf(64 * 1024);
        BaseDataEntry[] entries;

        byte[] bytes() {
            return payload.toByteArray();
        }
    }

    private static final class BaseDataEntry {
        final int offset;

        BaseDataEntry(int offset) {
            this.offset = offset;
        }
    }

    private BaseDataAccumulator buildBaseData(SourceManager sourceManager) {
        BaseDataAccumulator acc = new BaseDataAccumulator();
        int n = orderedItems.size();
        acc.entries = new BaseDataEntry[n];
        if (sourceManager == null) return acc;
        for (int i = 0; i < n; i++) {
            Item item = orderedItems.get(i);
            BaseResourceData data = sourceManager.analyze(item);
            if (data == null) continue;
            int offset = acc.payload.position();
            writeBaseData(acc.payload, data);
            acc.entries[i] = new BaseDataEntry(offset);
        }
        return acc;
    }

    private void writeBaseData(LeBuf buf, BaseResourceData d) {
        buf.i32(strings.intern(d.getSourceType().getDisplayName()));
        buf.u8(d.getSourceType().ordinal());
        buf.i32(strings.intern(d.getDetails() != null ? d.getDetails() : ""));
        buf.i32(strings.intern(d.getSourceName() != null ? d.getSourceName() : ""));
        buf.i32(strings.intern(d.getSourceSpecifier() != null ? d.getSourceSpecifier() : ""));
        buf.f64(d.getBaseFactor());
        buf.u8(d.isOverride() ? 1 : 0);
        buf.i32(strings.intern(d.isOverride() ? d.getOverrideModId() : ""));
        var srcItems = d.getSourceItems();
        int siSize = srcItems.size();
        if (siSize > 0xFFFF) throw new IllegalStateException("Too many source items: " + siSize);
        buf.u16(siSize);
        for (var entry : Reference2ObjectMaps.fastIterable(toRefMap(srcItems))) {
            int idx = itemIndex.getInt(entry.getKey());
            buf.i32(idx >= 0 ? idx : -1);
            buf.f64(entry.getValue());
        }
        var meta = d.getMetadata();
        int metaSize = meta.size();
        if (metaSize > 0xFF) metaSize = 0xFF;
        buf.u8(metaSize);
        int written = 0;
        for (var entry : meta.entrySet()) {
            if (written >= metaSize) break;
            buf.i32(strings.intern(entry.getKey()));
            buf.i32(strings.intern(String.valueOf(entry.getValue())));
            written++;
        }
    }

    private static Reference2ObjectMap<Item, Double> toRefMap(Reference2DoubleMap<Item> in) {
        var out = new Reference2ObjectOpenHashMap<Item, Double>(in.size());
        for (var e : Reference2DoubleMaps.fastIterable(in)) out.put(e.getKey(), e.getDoubleValue());
        return out;
    }

    private static final class SourcesAccumulator {
        final LeBuf payload = new LeBuf(64 * 1024);
        SourcesEntry[] entries;

        byte[] bytes() {
            return payload.toByteArray();
        }
    }

    private static final class SourcesEntry {
        final int offset;
        final int count;

        SourcesEntry(int offset, int count) {
            this.offset = offset;
            this.count = count;
        }
    }

    private SourcesAccumulator buildSources(SourceManager sourceManager) {
        SourcesAccumulator acc = new SourcesAccumulator();
        int n = orderedItems.size();
        acc.entries = new SourcesEntry[n];
        if (sourceManager == null) return acc;
        for (int i = 0; i < n; i++) {
            Item item = orderedItems.get(i);
            ObjectList<BaseResourceData> all = sourceManager.findAllSources(item);
            if (all.isEmpty()) continue;
            int offset = acc.payload.position();
            int count = Math.min(all.size(), 0xFFFF);
            for (int j = 0; j < count; j++) writeAltSource(acc.payload, all.get(j));
            acc.entries[i] = new SourcesEntry(offset, count);
        }
        return acc;
    }

    private void writeAltSource(LeBuf buf, BaseResourceData d) {
        buf.i32(strings.intern(d.getSourceType().getDisplayName()));
        buf.u8(d.getSourceType().ordinal());
        buf.i32(strings.intern(d.getDetails() != null ? d.getDetails() : ""));
        double base = d.getBaseFactor();
        double estimated = base;
        var src = d.getSourceItems();
        if (!src.isEmpty()) for (var e : Reference2DoubleMaps.fastIterable(src)) {
            ItemComplexity child = engine.getComplexityResult(e.getKey());
            if (child != null && child.isValid()) {
                estimated += child.getComplexity() * e.getDoubleValue();
            } else {
                estimated = Double.POSITIVE_INFINITY;
                break;
            }
        }
        buf.f64(base);
        buf.f64(estimated);
        int srcSize = src.size();
        if (srcSize > 0xFFFF) srcSize = 0xFFFF;
        buf.u16(srcSize);
        int written = 0;
        for (var e : Reference2DoubleMaps.fastIterable(src)) {
            if (written >= srcSize) break;
            int idx = itemIndex.getInt(e.getKey());
            buf.i32(idx >= 0 ? idx : -1);
            buf.f64(e.getDoubleValue());
            written++;
        }
    }

    private static final class RecipesResult {
        final byte[] payload;
        final byte[] outputIndex;
        final int recipeCount;

        RecipesResult(byte[] payload, byte[] outputIndex, int recipeCount) {
            this.payload = payload;
            this.outputIndex = outputIndex;
            this.recipeCount = recipeCount;
        }
    }

    private RecipesResult buildRecipes(RecipeGraph graph) {
        int n = orderedItems.size();
        int[] firstOffset = new int[n];
        int[] count = new int[n];
        java.util.Arrays.fill(firstOffset, CabinFormat.NULL_OFFSET);

        if (graph == null)
            return new RecipesResult(new byte[]{0, 0, 0, 0}, encodeRecipeOutputIndex(firstOffset, count), 0);

        LeBuf out = new LeBuf(256 * 1024);
        out.i32(0);
        int totalRecipes = 0;

        for (int i = 0; i < n; i++) {
            Item item = orderedItems.get(i);
            ObjectList<RecipeNode> recipes = graph.getRecipes(item);
            if (recipes.isEmpty()) continue;
            firstOffset[i] = out.position();
            int written = 0;
            for (RecipeNode r : recipes) {
                writeRecipe(out, i, r);
                written++;
                if (written == 0xFFFF) break;
            }
            count[i] = written;
            totalRecipes += written;
        }
        out.putI32At(0, totalRecipes);

        byte[] outputIndex = encodeRecipeOutputIndex(firstOffset, count);
        return new RecipesResult(out.toByteArray(), outputIndex, totalRecipes);
    }

    private void writeRecipe(LeBuf buf, int outputItemIndex, RecipeNode r) {
        buf.i32(outputItemIndex);
        var rt = r.getRecipeType();
        String rtStr = rt != null ? rt.toString() : "minecraft:custom";
        buf.i32(strings.intern(rtStr));
        RecipeCategory cat = r.getCategory();
        buf.u8(cat != null ? cat.ordinal() : 0);
        buf.i32(r.getPriority());
        buf.i32(r.getResultCount());
        buf.f64(r.getRecipeMultiplier());
        int flags = 0;
        if (r.isPlaceholder()) flags |= 0x01;
        if (r.hasFluidIngredients()) flags |= 0x02;
        if (r.isBaseRecipe()) flags |= 0x04;
        buf.u8(flags);
        buf.i32(strings.intern(r.getPlaceholderId() != null ? r.getPlaceholderId() : ""));

        var ings = r.getIngredients();
        buf.u8(Math.min(ings.size(), 0xFF));
        for (int s = 0; s < Math.min(ings.size(), 0xFF); s++) {
            IngredientSlot slot = ings.get(s);
            var variants = slot.getVariants();
            int vc = Math.min(variants.size(), 0xFF);
            buf.u8(vc);
            buf.i32(slot.getCount());
            for (int v = 0; v < vc; v++) {
                int vi = itemIndex.getInt(variants.get(v));
                buf.i32(vi);
            }
        }

        var fings = r.getFluidIngredients();
        buf.u8(Math.min(fings.size(), 0xFF));
        for (int s = 0; s < Math.min(fings.size(), 0xFF); s++) {
            FluidIngredientSlot slot = fings.get(s);
            var variants = slot.getFluidVariants();
            int vc = Math.min(variants.size(), 0xFF);
            buf.u8(vc);
            buf.i32(slot.getAmount());
            for (int v = 0; v < vc; v++) {
                Fluid fluid = variants.get(v);
                int fi = fluidIndex.getInt(fluid);
                buf.i32(fi);
            }
        }

        var chems = r.getChemicalIngredients();
        buf.u8(Math.min(chems.size(), 0xFF));
        for (int c = 0; c < Math.min(chems.size(), 0xFF); c++) {
            var ci = chems.get(c);
            buf.i32(strings.intern(ci.id() != null ? ci.id().toString() : ""));
            buf.i32(ci.amount());
        }

        var iouts = r.getItemOutputs();
        buf.u8(Math.min(iouts.size(), 0xFF));
        for (int o = 0; o < Math.min(iouts.size(), 0xFF); o++) {
            var stack = iouts.get(o);
            int idx = itemIndex.getInt(stack.getItem());
            buf.i32(idx);
            buf.i32(stack.getCount());
        }

        var fouts = r.getFluidOutputs();
        buf.u8(Math.min(fouts.size(), 0xFF));
        for (int o = 0; o < Math.min(fouts.size(), 0xFF); o++) {
            var fs = fouts.get(o);
            int fi = fluidIndex.getInt(fs.getFluid());
            buf.i32(fi);
            buf.i32(fs.getAmount());
        }

        var cOuts = r.getChemicalOutputs();
        buf.u8(Math.min(cOuts.size(), 0xFF));
        for (int o = 0; o < Math.min(cOuts.size(), 0xFF); o++) {
            var co = cOuts.get(o);
            String cid = co.id() != null ? co.id().toString() : "";
            buf.i32(strings.intern(cid));
            buf.i64(co.amount());
        }
    }

    private byte[] encodeRecipeOutputIndex(int[] firstOffset, int[] count) {
        int n = firstOffset.length;
        LeBuf buf = new LeBuf(4 + n * 6);
        buf.i32(n);
        for (int i = 0; i < n; i++) {
            buf.i32(firstOffset[i]);
            buf.u16(Math.min(count[i], 0xFFFF));
        }
        return buf.toByteArray();
    }

    private byte[] buildUsage(RecipeGraph graph) {
        int n = orderedItems.size();
        if (graph == null) {
            LeBuf empty = new LeBuf(8);
            empty.i32(n);
            empty.i32(0);
            return empty.toByteArray();
        }
        int[] firstOffset = new int[n];
        int[] count = new int[n];
        LeBuf flat = new LeBuf(64 * 1024);
        for (int i = 0; i < n; i++) {
            Item item = orderedItems.get(i);
            ReferenceSet<Item> users = graph.getItemsUsingIngredient(item);
            if (users.isEmpty()) {
                firstOffset[i] = CabinFormat.NULL_OFFSET;
                continue;
            }
            firstOffset[i] = flat.position();
            int written = 0;
            for (Item u : users) {
                int ui = itemIndex.getInt(u);
                if (ui < 0) continue;
                flat.i32(ui);
                written++;
            }
            count[i] = written;
        }
        LeBuf out = new LeBuf(8 + n * 8 + flat.size());
        out.i32(n);
        out.i32(flat.size());
        for (int i = 0; i < n; i++) {
            out.i32(firstOffset[i]);
            out.i32(count[i]);
        }
        out.bytes(flat.array(), 0, flat.size());
        return out.toByteArray();
    }

    private static final class MobsResult {
        final byte[] mobs;
        final byte[] drops;
        final int mobCount;

        MobsResult(byte[] mobs, byte[] drops, int mobCount) {
            this.mobs = mobs;
            this.drops = drops;
            this.mobCount = mobCount;
        }
    }

    private MobsResult buildMobs(MobPropertyProvider mobProvider, MobDropSource mobDropSource) {
        int n = orderedMobs.size();
        LeBuf mobsBuf = new LeBuf(CabinFormat.MOB_RECORD_SIZE * n + 4);
        LeBuf dropsBuf = new LeBuf(64 * 1024);
        dropsBuf.i32(0);
        int totalDrops = 0;
        mobsBuf.i32(n);
        for (int i = 0; i < n; i++) {
            EntityType<?> type = orderedMobs.get(i);
            ResourceLocation id = GameRegistryManager.getEntityTypeId(type);
            String idStr = id != null ? id.toString() : "minecraft:unknown";
            String displayName = type.getDescription().getString();
            String catName = type.getCategory().getName();

            MobPropertyProvider.MobProperties props = (mobProvider != null) ? mobProvider.getProperties(type) : null;
            double health = props != null ? props.maxHealth() : 0.0;
            double damage = props != null ? props.attackDamage() : 0.0;
            double armor = props != null ? props.armor() : 0.0;
            double survivability = props != null ? props.calculateSurvivability() : 0.0;
            double threat = props != null ? props.calculateThreat() : 0.0;
            double combat = props != null ? props.calculateCombatPower() : 0.0;
            double rarity = (mobProvider != null) ? mobProvider.getRarity(type) : 1.0;
            boolean boss = mobProvider != null && mobProvider.isBoss(type);
            boolean miniBoss = mobProvider != null && mobProvider.isMiniBoss(type);

            int dropOffset = CabinFormat.NULL_OFFSET;
            int dropCount = 0;
            if (mobDropSource != null) {
                ObjectList<MobDropData> drops = mobDropSource.getDropsForEntity(type);
                if (!drops.isEmpty()) {
                    dropOffset = dropsBuf.position();
                    int written = 0;
                    for (MobDropData drop : drops) {
                        if (written == 0xFFFF) break;
                        Item it = drop.item();
                        int itIdx = itemIndex.getInt(it);
                        ResourceLocation itId = it != null ? GameRegistryManager.getItemId(it) : null;
                        String itIdStr = itId != null ? itId.toString() : "minecraft:air";
                        String itName = it != null ? safeDisplayName(it) : "";
                        dropsBuf.i32(itIdx);
                        dropsBuf.i32(strings.intern(itName));
                        dropsBuf.f64(drop.averageYield());
                        dropsBuf.i32(strings.intern(drop.killMethod() != null ? drop.killMethod() : ""));
                        dropsBuf.i32(strings.intern(itIdStr));
                        written++;
                        totalDrops++;
                    }
                    dropCount = written;
                }
            }

            int recordStart = mobsBuf.position();
            mobsBuf.i32(strings.intern(idStr));
            mobsBuf.i32(strings.intern(displayName));
            mobsBuf.i32(strings.intern(catName));
            mobsBuf.i32(0);
            mobsBuf.f64(health);
            mobsBuf.f64(damage);
            mobsBuf.f64(armor);
            mobsBuf.f64(survivability);
            mobsBuf.f64(threat);
            mobsBuf.f64(combat);
            mobsBuf.f64(rarity);
            mobsBuf.i32(dropOffset);
            mobsBuf.u16(dropCount);
            int flags = 0;
            if (boss) flags |= CabinFormat.MOB_FLAG_IS_BOSS;
            if (miniBoss) flags |= CabinFormat.MOB_FLAG_IS_MINIBOSS;
            mobsBuf.u8(flags);
            mobsBuf.u8(type.getCategory().ordinal());
            int written = mobsBuf.position() - recordStart;
            if (written != CabinFormat.MOB_RECORD_SIZE)
                throw new IllegalStateException("MOB record size mismatch: " + written);
        }
        dropsBuf.putI32At(0, totalDrops);
        return new MobsResult(mobsBuf.toByteArray(), dropsBuf.toByteArray(), n);
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
        java.util.Arrays.sort(order, (a, b) -> Long.compareUnsigned(hashes[a], hashes[b]));
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
        java.util.Arrays.sort(order, (a, b) -> Long.compareUnsigned(hashes[a], hashes[b]));
        LeBuf out = new LeBuf(4 + n * 12);
        out.i32(n);
        for (int i = 0; i < n; i++) {
            int idx = order[i];
            out.i64(hashes[idx]);
            out.i32(idx);
        }
        return out.toByteArray();
    }

    private byte[] buildMeta(ItemSectionResult itemResult, MobsResult mobsResult, RecipesResult recipes) {
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

    private static String safeDisplayName(Item item) {
        try {
            return item.getDescription().getString();
        } catch (Throwable t) {
            ResourceLocation id = GameRegistryManager.getItemId(item);
            return id != null ? id.toString() : "unknown";
        }
    }

    public static long computeFileHash(byte[] file) {
        return XxHash64.hash(file, 0, file.length, CabinFormat.XXH64_SEED);
    }
}