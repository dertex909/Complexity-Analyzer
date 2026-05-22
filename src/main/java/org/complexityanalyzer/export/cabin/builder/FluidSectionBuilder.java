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

import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.complexityanalyzer.analyzer.solver.SolverResult;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.export.cabin.api.CabinFormat;
import org.complexityanalyzer.export.cabin.api.LeBuf;
import org.complexityanalyzer.export.cabin.api.XxHash64;
import org.complexityanalyzer.graph.*;

import java.util.Arrays;

public final class FluidSectionBuilder {

    private final SectionBuilderContext ctx;

    public FluidSectionBuilder(SectionBuilderContext ctx) {
        this.ctx = ctx;
    }

    public static final class FluidRecipesResult {
        public final byte[] payload;
        public final byte[] outputIndex;
        public final int recipeCount;

        public FluidRecipesResult(byte[] payload, byte[] outputIndex, int recipeCount) {
            this.payload = payload;
            this.outputIndex = outputIndex;
            this.recipeCount = recipeCount;
        }
    }

    public byte[] buildFluidsSection() {
        SolverResult solverResult = ctx.engine().getSolverResult();
        int n = ctx.orderedFluids().size();
        LeBuf buf = new LeBuf(4 + n * CabinFormat.FLUID_RECORD_SIZE);
        buf.i32(n);
        for (int i = 0; i < n; i++) {
            Fluid fluid = ctx.orderedFluids().get(i);
            ResourceLocation id = GameRegistryManager.getFluidId(fluid);
            String idStr = id != null ? id.toString() : "minecraft:empty";
            String displayName = safeFluidDisplayName(fluid);

            double complexity = -1.0;
            ComplexityCategory category = ComplexityCategory.UNCALCULABLE;
            int flags = 0;
            int errorRef = ctx.strings().intern("not analyzed");

            if (solverResult != null) {
                Double compObj = solverResult.getFluidComplexity(fluid);
                if (compObj != null) {
                    complexity = compObj;
                    if (Double.isInfinite(complexity) || complexity < 0) {
                        complexity = -1.0;
                    }

                    category = ComplexityCategory.fromComplexity(complexity);

                    flags |= CabinFormat.FLUID_FLAG_IS_VALID;
                    if (Double.isInfinite(compObj)) {
                        flags |= CabinFormat.FLUID_FLAG_IS_INFINITE;
                    }

                    errorRef = ctx.strings().intern("");
                }
            }

            if (ctx.engine().getGraph() != null) {
                if (ctx.engine().getGraph().hasFluidRecipe(fluid)) {
                    flags |= CabinFormat.FLUID_FLAG_HAS_RECIPE;
                } else {
                    flags |= CabinFormat.FLUID_FLAG_NO_RECIPE_RESULT;
                }
            }

            if (fluid == Fluids.WATER || fluid == Fluids.LAVA) {
                flags |= CabinFormat.FLUID_FLAG_IS_PROTECTED;
            }

            int usageCount = ctx.engine().getGraph() != null ? ctx.engine().getGraph().getFluidUsageCount(fluid) : 0;
            int idRef = ctx.strings().intern(idStr);
            int nameRef = ctx.strings().intern(displayName);
            int categoryRef = ctx.strings().intern(category.getDisplayName());

            int recordStart = buf.position();
            buf.i32(idRef);
            buf.i32(nameRef);
            buf.f64(complexity);
            buf.i32(usageCount);
            buf.i32(categoryRef);
            buf.u8(category.ordinal());
            buf.u8(flags & 0xFF);
            buf.i32(errorRef);
            int recordEnd = buf.position();
            int written = recordEnd - recordStart;
            if (written != CabinFormat.FLUID_RECORD_SIZE) {
                throw new IllegalStateException("FLUID record size mismatch: " + written);
            }
        }
        return buf.toByteArray();
    }

    public FluidRecipesResult buildFluidRecipes(RecipeGraph graph) {
        int n = ctx.orderedFluids().size();
        int[] firstOffset = new int[n];
        int[] count = new int[n];
        Arrays.fill(firstOffset, CabinFormat.NULL_OFFSET);

        if (graph == null)
            return new FluidRecipesResult(new byte[]{0, 0, 0, 0}, encodeRecipeOutputIndex(firstOffset, count), 0);

        LeBuf out = new LeBuf(64 * 1024);
        out.i32(0);
        int totalRecipes = 0;

        for (int i = 0; i < n; i++) {
            Fluid fluid = ctx.orderedFluids().get(i);
            ObjectList<RecipeNode> recipes = graph.getFluidRecipes(fluid);
            if (recipes.isEmpty()) continue;
            firstOffset[i] = out.position();
            int written = 0;
            for (RecipeNode r : recipes) {
                writeFluidRecipe(out, i, r);
                written++;
                if (written == 0xFFFF) break;
            }
            count[i] = written;
            totalRecipes += written;
        }
        out.putI32At(0, totalRecipes);

        byte[] outputIndex = encodeRecipeOutputIndex(firstOffset, count);
        return new FluidRecipesResult(out.toByteArray(), outputIndex, totalRecipes);
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

    private void writeFluidRecipe(LeBuf buf, int outputFluidIndex, RecipeNode r) {
        buf.i32(outputFluidIndex);
        var rt = r.getRecipeType();
        String rtStr = rt != null ? rt.toString() : "minecraft:custom";
        buf.i32(ctx.strings().intern(rtStr));
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
        buf.i32(ctx.strings().intern(r.getPlaceholderId() != null ? r.getPlaceholderId() : ""));

        var registry = ctx.engine().getMachineRegistry();
        ObjectList<Item> machineItems = (registry != null) ? registry.getMachinesForRecipe(r.getRecipeType()) : null;
        Item cheapestMachine = null;
        double minComplexity = Double.POSITIVE_INFINITY;
        if (machineItems != null) for (Item machineItem : machineItems) {
            double c = ctx.engine().getComplexity(machineItem);
            if (c < minComplexity) {
                minComplexity = c;
                cheapestMachine = machineItem;
            }
        }
        if (cheapestMachine == null && machineItems != null && !machineItems.isEmpty())
            cheapestMachine = machineItems.getFirst();
        int machineItemIdx = (cheapestMachine != null) ? ctx.itemIndex().getInt(cheapestMachine) : -1;
        buf.i32(machineItemIdx);

        var ings = r.getIngredients();
        buf.u8(Math.min(ings.size(), 0xFF));
        for (int s = 0; s < Math.min(ings.size(), 0xFF); s++) {
            IngredientSlot slot = ings.get(s);
            var variants = slot.getVariants();
            int vc = Math.min(variants.size(), 0xFF);
            buf.u8(vc);
            buf.i32(slot.getCount());
            for (int v = 0; v < vc; v++) {
                int vi = ctx.itemIndex().getInt(variants.get(v));
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

    public byte[] buildFluidUsage(RecipeGraph graph) {
        int n = ctx.orderedFluids().size();
        if (graph == null) {
            LeBuf empty = new LeBuf(8);
            empty.i32(n);
            empty.i32(0);
            return empty.toByteArray();
        }
        int[] firstOffset = new int[n];
        int[] count = new int[n];
        LeBuf flat = new LeBuf(16 * 1024);
        for (int i = 0; i < n; i++) {
            Fluid fluid = ctx.orderedFluids().get(i);
            ReferenceSet<Item> users = graph.getItemsUsingFluid(fluid);
            if (users.isEmpty()) {
                firstOffset[i] = CabinFormat.NULL_OFFSET;
                continue;
            }
            firstOffset[i] = flat.position();
            int written = 0;
            for (Item u : users) {
                int ui = ctx.itemIndex().getInt(u);
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

    public byte[] buildFluidHashIndex() {
        int n = ctx.orderedFluids().size();
        long[] hashes = new long[n];
        for (int i = 0; i < n; i++) {
            ResourceLocation id = GameRegistryManager.getFluidId(ctx.orderedFluids().get(i));
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

    private static String safeFluidDisplayName(Fluid fluid) {
        try {
            return fluid.getFluidType().getDescription().getString();
        } catch (Throwable t) {
            ResourceLocation id = GameRegistryManager.getFluidId(fluid);
            return id != null ? id.toString() : "unknown";
        }
    }
}
