package org.complexityanalyzer.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.neoforged.neoforge.common.CommonHooks;
import org.complexityanalyzer.util.LootOptimizer;
import org.complexityanalyzer.util.ProbeScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CommonHooks.class, priority = 500, remap = false)
public abstract class CommonHooksMixin {

    @Inject(
            method = "modifyLoot(Lnet/minecraft/resources/ResourceLocation;Lit/unimi/dsi/fastutil/objects/ObjectArrayList;Lnet/minecraft/world/level/storage/loot/LootContext;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void onModifyLootHead(ResourceLocation lootTableId, ObjectArrayList<ItemStack> generatedLoot,
                                         LootContext context, CallbackInfoReturnable<ObjectArrayList<ItemStack>> cir) {
        if (!ProbeScope.isProbing()) return;
        if (!LootOptimizer.hasModifiers(lootTableId)) cir.setReturnValue(generatedLoot);
    }
}