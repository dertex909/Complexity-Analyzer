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

package org.complexityanalyzer.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.neoforged.neoforge.common.CommonHooks;
import org.complexityanalyzer.util.LootOptimizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicInteger;

@Mixin(value = CommonHooks.class, priority = 500)
public abstract class CommonHooksMixin {

    @Unique
    private static final ThreadLocal<ObjectArrayList<ItemStack>> complexity$snapshot = new ThreadLocal<>();

    @Inject(
            method = "modifyLoot(Lnet/minecraft/resources/ResourceLocation;Lit/unimi/dsi/fastutil/objects/ObjectArrayList;Lnet/minecraft/world/level/storage/loot/LootContext;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void onModifyLootHead(ResourceLocation lootTableId, ObjectArrayList<ItemStack> generatedLoot,
                                         LootContext context, CallbackInfoReturnable<ObjectArrayList<ItemStack>> cir) {
        if (LootOptimizer.isIdle() || lootTableId == null) return;
        var status = LootOptimizer.STATUS_CACHE.get(lootTableId);

        if (status == LootOptimizer.TableStatus.UNMODIFIED) {
            LootOptimizer.SKIPPED_CALLS.incrementAndGet();
            cir.setReturnValue(generatedLoot);
            return;
        }

        if (status == null || status == LootOptimizer.TableStatus.UNKNOWN) {
            var copy = new ObjectArrayList<ItemStack>(generatedLoot.size());
            for (var stack : generatedLoot) copy.add(stack.copy());
            complexity$snapshot.set(copy);
        }

        LootOptimizer.FULL_CALLS.incrementAndGet();
    }

    @Inject(
            method = "modifyLoot(Lnet/minecraft/resources/ResourceLocation;Lit/unimi/dsi/fastutil/objects/ObjectArrayList;Lnet/minecraft/world/level/storage/loot/LootContext;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",
            at = @At("RETURN")
    )
    private static void onModifyLootReturn(ResourceLocation lootTableId, ObjectArrayList<ItemStack> generatedLoot,
                                           LootContext context, CallbackInfoReturnable<ObjectArrayList<ItemStack>> cir) {
        if (LootOptimizer.isIdle() || lootTableId == null) return;
        var status = LootOptimizer.STATUS_CACHE.get(lootTableId);
        if (status == LootOptimizer.TableStatus.UNMODIFIED) return;

        var originalLoot = complexity$snapshot.get();
        complexity$snapshot.remove();
        if (originalLoot == null) return;

        if (status == null || status == LootOptimizer.TableStatus.UNKNOWN) {
            var resultLoot = cir.getReturnValue();

            if (!LootOptimizer.areListsIdentical(originalLoot, resultLoot)) {
                LootOptimizer.STATUS_CACHE.put(lootTableId, LootOptimizer.TableStatus.MODIFIED);
                return;
            }

            var counter = LootOptimizer.PROBE_COUNTERS.computeIfAbsent(lootTableId, k -> new AtomicInteger(0));
            if (counter.incrementAndGet() >= LootOptimizer.PROBE_THRESHOLD) {
                LootOptimizer.STATUS_CACHE.put(lootTableId, LootOptimizer.TableStatus.UNMODIFIED);
            }
        }
    }
}