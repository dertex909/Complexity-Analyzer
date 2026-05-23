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

        var registry = ctx.engine().getMachineRegistry();

        for (int i = 0; i < n; i++) {
            Fluid fluid = ctx.orderedFluids().get(i);
            ObjectList<RecipeNode> recipes = graph.getFluidRecipes(fluid);
            if (recipes.isEmpty()) continue;
            firstOffset[i] = out.position();
            int written = 0;
            for (RecipeNode r : recipes) {
                var recipeType = r.getRecipeType();
                ObjectList<Item> machineItems = (registry != null && recipeType != null) ? registry.getMachinesForRecipe(recipeType) : null;
                if (machineItems != null && !machineItems.isEmpty()) {
                    for (Item machineItem : machineItems) {
                        int machineItemIdx = ctx.itemIndex().getInt(machineItem);
                        if (machineItemIdx >= 0) {
                            RecipeSectionBuilder.writeRecipe(out, ctx, i, r, machineItemIdx);
                            written++;
                            if (written == 0xFFFF) break;
                        }
                    }
                } else {
                    RecipeSectionBuilder.writeRecipe(out, ctx, i, r, -1);
                    written++;
                }
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
