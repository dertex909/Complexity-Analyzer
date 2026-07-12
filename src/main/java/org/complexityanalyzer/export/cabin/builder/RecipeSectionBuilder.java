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

package org.complexityanalyzer.export.cabin.builder;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.complexityanalyzer.analyzer.MachineRegistry;
import org.complexityanalyzer.export.cabin.api.LeBuf;
import org.complexityanalyzer.graph.IngredientSlot;
import org.complexityanalyzer.graph.RecipeGraph;
import org.complexityanalyzer.graph.RecipeNode;
import org.complexityanalyzer.harvest.ItemStackIdentity;

import java.util.Arrays;

import static org.complexityanalyzer.export.cabin.api.CabinFormat.NULL_OFFSET;

public final class RecipeSectionBuilder {

    private final SectionBuilderContext ctx;

    public RecipeSectionBuilder(SectionBuilderContext ctx) {
        this.ctx = ctx;
    }

    private static Reference2ObjectOpenHashMap<Item, ObjectArrayList<RecipeNode>> buildRecipesByOutput(RecipeGraph graph) {
        var map = new Reference2ObjectOpenHashMap<Item, ObjectArrayList<RecipeNode>>();
        for (var r : graph.getAllRecipes()) {
            var outs = r.getItemOutputs();
            if (outs.isEmpty()) {
                var res = r.getResultItem();
                if (res != null) map.computeIfAbsent(res, k -> new ObjectArrayList<>()).add(r);
                continue;
            }
            var seen = new ReferenceOpenHashSet<Item>(outs.size());
            for (var stack : outs) {
                var it = stack.getItem();
                if (seen.add(it)) map.computeIfAbsent(it, k -> new ObjectArrayList<>()).add(r);
            }
        }
        return map;
    }

    public static IntList machineIndices(SectionBuilderContext ctx, MachineRegistry registry, RecipeNode r) {
        var list = new IntArrayList(2);
        var rt = r.getRecipeType();
        var machineItems = (registry != null && rt != null) ? registry.getMachinesForRecipe(rt) : null;
        if (machineItems != null) {
            for (var mi : machineItems) {
                int idx = ctx.itemIndex().getInt(mi);
                if (idx >= 0) list.add(idx);
            }
        }
        return list;
    }

    private static ObjectList<MergedSlot> mergeIngredientSlots(SectionBuilderContext ctx, ObjectList<IngredientSlot> slots,
                                                               HolderLookup.Provider ra) {
        var order = new ObjectArrayList<MergedSlot>(slots.size());
        var sigToPos = new Object2IntOpenHashMap<String>(slots.size());
        sigToPos.defaultReturnValue(-1);
        for (var slot : slots) {
            String sig = slotSignature(ctx, slot, ra);
            int pos = sigToPos.getInt(sig);
            if (pos < 0) {
                sigToPos.put(sig, order.size());
                order.add(new MergedSlot(slot, slot.getCount()));
            } else {
                var existing = order.get(pos);
                order.set(pos, new MergedSlot(existing.slot(), existing.count() + slot.getCount()));
            }
        }
        return order;
    }

    private static String slotSignature(SectionBuilderContext ctx, IngredientSlot slot, HolderLookup.Provider ra) {
        var sb = new StringBuilder(24);
        for (var v : slot.getVariants()) {
            sb.append(ctx.itemIndex().getInt(v.getItem()));
            if (!v.isComponentsPatchEmpty()) sb.append('#').append(ItemStackIdentity.dataKey(v, ra));
            sb.append(',');
        }
        return sb.toString();
    }

    public static void writeRecipe(LeBuf buf, SectionBuilderContext ctx, int outputItemIndex, RecipeNode r, IntList machineIdxs) {
        buf.i32(outputItemIndex);
        var rt = r.getRecipeType();
        String rtStr = rt != null ? rt.toString() : "minecraft:custom";
        buf.i32(ctx.strings().intern(rtStr));
        var cat = r.getCategory();
        buf.u8(cat != null ? cat.ordinal() : 0);
        buf.i32(r.getPriority());
        buf.i32(r.getResultCount());
        buf.f64(r.getRecipeMultiplier());
        int flags = 0;
        if (r.isPlaceholder()) flags |= 0x01;
        if (r.hasFluidIngredients()) flags |= 0x02;
        if (r.isBaseRecipe()) flags |= 0x04;
        buf.u8(flags);
        buf.i32(ctx.strings().intern(r.getPlaceholderId() != null ? r.getPlaceholderId() : ""));

        int mc = Math.min(machineIdxs.size(), 0xFF);
        buf.u8(mc);
        for (int k = 0; k < mc; k++) buf.i32(machineIdxs.getInt(k));

        var registryAccess = ctx.registryAccess();
        var mergedSlots = mergeIngredientSlots(ctx, r.getIngredients(), registryAccess);
        buf.u8(Math.min(mergedSlots.size(), 0xFF));
        for (int s = 0; s < Math.min(mergedSlots.size(), 0xFF); s++) {
            var slot = mergedSlots.get(s);
            var variants = slot.slot().getVariants();
            int vc = Math.min(variants.size(), 0xFF);
            buf.u8(vc);
            buf.i32(slot.count());
            for (int v = 0; v < vc; v++) {
                var variant = variants.get(v);
                buf.i32(ctx.itemIndex().getInt(variant.getItem()));
                writeVariantStrings(buf, ctx, variant, registryAccess);
            }
        }

        var fings = r.getFluidIngredients();
        buf.u8(Math.min(fings.size(), 0xFF));
        for (int s = 0; s < Math.min(fings.size(), 0xFF); s++) {
            var slot = fings.get(s);
            var variants = slot.getFluidVariants();
            int vc = Math.min(variants.size(), 0xFF);
            buf.u8(vc);
            buf.i32(slot.getAmount());
            for (int v = 0; v < vc; v++) {
                var fluid = variants.get(v);
                int fi = ctx.fluidIndex().getInt(fluid);
                buf.i32(fi);
            }
        }

        var chems = r.getChemicalIngredients();
        buf.u8(Math.min(chems.size(), 0xFF));
        for (int c = 0; c < Math.min(chems.size(), 0xFF); c++) {
            var ci = chems.get(c);
            buf.i32(ctx.strings().intern(ci.id() != null ? ci.id().toString() : ""));
            buf.i32(ci.amount());
        }

        var iouts = r.getItemOutputs();
        buf.u8(Math.min(iouts.size(), 0xFF));
        for (int o = 0; o < Math.min(iouts.size(), 0xFF); o++) {
            var stack = iouts.get(o);
            int idx = ctx.itemIndex().getInt(stack.getItem());
            buf.i32(idx);
            buf.i32(stack.getCount());

            int hoverRef = ctx.hoverNameIdCache().getInt(stack);
            if (hoverRef < 0) {
                hoverRef = ctx.strings().intern(safeHoverName(stack));
                ctx.hoverNameIdCache().put(stack, hoverRef);
            }
            buf.i32(hoverRef);

            int keyRef = ctx.dataKeyIdCache().getInt(stack);
            if (keyRef < 0) {
                keyRef = ctx.strings().intern(ItemStackIdentity.dataKey(stack, registryAccess));
                ctx.dataKeyIdCache().put(stack, keyRef);
            }
            buf.i32(keyRef);
        }

        var fouts = r.getFluidOutputs();
        buf.u8(Math.min(fouts.size(), 0xFF));
        for (int o = 0; o < Math.min(fouts.size(), 0xFF); o++) {
            var fs = fouts.get(o);
            int fi = ctx.fluidIndex().getInt(fs.getFluid());
            buf.i32(fi);
            buf.i32(fs.getAmount());
        }

        var cOuts = r.getChemicalOutputs();
        buf.u8(Math.min(cOuts.size(), 0xFF));
        for (int o = 0; o < Math.min(cOuts.size(), 0xFF); o++) {
            var co = cOuts.get(o);
            String cid = co.id() != null ? co.id().toString() : "";
            buf.i32(ctx.strings().intern(cid));
            buf.i64(co.amount());
        }
    }

    private static String safeHoverName(ItemStack stack) {
        try {
            String n = stack.getHoverName().getString();
            if (!n.isBlank() && !n.contains("cannot be bound")) return n;
        } catch (Throwable ignored) {
        }
        return stack.getItem().getDescription().getString();
    }

    private static void writeVariantStrings(LeBuf buf, SectionBuilderContext ctx, ItemStack variant,
                                            HolderLookup.Provider registryAccess) {
        int hoverRef, keyRef;
        if (variant.isComponentsPatchEmpty()) {
            var item = variant.getItem();
            hoverRef = ctx.plainHoverByItem().getInt(item);
            if (hoverRef < 0) {
                hoverRef = ctx.strings().intern(safeHoverName(variant));
                ctx.plainHoverByItem().put(item, hoverRef);
            }
            keyRef = ctx.plainDataKeyByItem().getInt(item);
            if (keyRef < 0) {
                keyRef = ctx.strings().intern(ItemStackIdentity.dataKey(variant, registryAccess));
                ctx.plainDataKeyByItem().put(item, keyRef);
            }
        } else {
            hoverRef = ctx.hoverNameIdCache().getInt(variant);
            if (hoverRef < 0) {
                hoverRef = ctx.strings().intern(safeHoverName(variant));
                ctx.hoverNameIdCache().put(variant, hoverRef);
            }
            keyRef = ctx.dataKeyIdCache().getInt(variant);
            if (keyRef < 0) {
                keyRef = ctx.strings().intern(ItemStackIdentity.dataKey(variant, registryAccess));
                ctx.dataKeyIdCache().put(variant, keyRef);
            }
        }
        buf.i32(hoverRef);
        buf.i32(keyRef);
    }

    public RecipesResult buildRecipes(RecipeGraph graph) {
        int n = ctx.orderedItems().size();
        int[] firstOffset = new int[n];
        int[] count = new int[n];
        Arrays.fill(firstOffset, NULL_OFFSET);

        if (graph == null)
            return new RecipesResult(new byte[]{0, 0, 0, 0}, encodeRecipeOutputIndex(firstOffset, count), 0);

        var out = new LeBuf(256 * 1024);
        out.i32(0);
        int totalRecipes = 0;

        var registry = ctx.engine().getMachineRegistry();
        var recipesByOutput = buildRecipesByOutput(graph);
        for (int i = 0; i < n; i++) {
            var item = ctx.orderedItems().get(i);
            var recipes = recipesByOutput.get(item);
            if (recipes == null || recipes.isEmpty()) continue;
            firstOffset[i] = out.position();
            int written = 0;
            for (var r : recipes) {
                if (written == 0xFFFF) break;
                writeRecipe(out, ctx, i, r, machineIndices(ctx, registry, r));
                written++;
            }
            count[i] = written;
            totalRecipes += written;
        }
        out.putI32At(0, totalRecipes);

        byte[] outputIndex = encodeRecipeOutputIndex(firstOffset, count);
        return new RecipesResult(out.toByteArray(), outputIndex, totalRecipes);
    }

    private byte[] encodeRecipeOutputIndex(int[] firstOffset, int[] count) {
        int n = firstOffset.length;
        var buf = new LeBuf(4 + n * 6);
        buf.i32(n);
        for (int i = 0; i < n; i++) {
            buf.i32(firstOffset[i]);
            buf.u16(Math.min(count[i], 0xFFFF));
        }
        return buf.toByteArray();
    }

    public byte[] buildUsage(RecipeGraph graph) {
        int n = ctx.orderedItems().size();
        if (graph == null) {
            var empty = new LeBuf(8);
            empty.i32(n);
            empty.i32(0);
            return empty.toByteArray();
        }
        int[] firstOffset = new int[n];
        int[] count = new int[n];
        var flat = new LeBuf(64 * 1024);
        for (int i = 0; i < n; i++) {
            var item = ctx.orderedItems().get(i);
            var users = graph.getItemsUsingIngredient(item);
            if (users.isEmpty()) {
                firstOffset[i] = NULL_OFFSET;
                continue;
            }
            firstOffset[i] = flat.position();
            int written = 0;
            for (var u : users) {
                int ui = ctx.itemIndex().getInt(u);
                if (ui < 0) continue;
                flat.i32(ui);
                written++;
            }
            count[i] = written;
        }
        var out = new LeBuf(8 + n * 8 + flat.size());
        out.i32(n);
        out.i32(flat.size());
        for (int i = 0; i < n; i++) {
            out.i32(firstOffset[i]);
            out.i32(count[i]);
        }
        out.bytes(flat.array(), 0, flat.size());
        return out.toByteArray();
    }

    public static final class RecipesResult {
        public final byte[] payload;
        public final byte[] outputIndex;
        public final int recipeCount;

        public RecipesResult(byte[] payload, byte[] outputIndex, int recipeCount) {
            this.payload = payload;
            this.outputIndex = outputIndex;
            this.recipeCount = recipeCount;
        }
    }

    private record MergedSlot(IngredientSlot slot, int count) {
    }
}