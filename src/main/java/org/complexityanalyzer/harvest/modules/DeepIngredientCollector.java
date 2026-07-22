package org.complexityanalyzer.harvest.modules;

import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.harvest.FastHarvester;
import org.complexityanalyzer.harvest.HarvestedItems;

import static net.minecraft.core.registries.Registries.ITEM;

public final class DeepIngredientCollector {

    private DeepIngredientCollector() {
    }

    public static void collect(Object obj, ObjectList<HarvestedItems.HarvestedIngredient> acc, int depth, ReferenceOpenHashSet<Object> visited) {
        DeepGraphTraverser.traverse(obj, depth, visited, (node, d) -> {
            switch (node) {
                case TagKey<?> tagKey -> {
                    if (tagKey.registry().equals(ITEM)) {
                        @SuppressWarnings("unchecked")
                        var itemTag = (TagKey<Item>) tagKey;
                        var ing = Ingredient.of(itemTag);
                        if (!ing.isEmpty() && FastHarvester.visitIngredient(ing)) {
                            acc.add(new HarvestedItems.HarvestedIngredient(ing, 1));
                        }
                    }
                    return true;
                }
                case SizedIngredient si when si.count() > 0 -> {
                    var ing = si.ingredient();
                    if (!ing.isEmpty() && FastHarvester.visitIngredient(ing)) {
                        acc.add(new HarvestedItems.HarvestedIngredient(ing, si.count()));
                    }
                    return true;
                }
                case Ingredient ing when !ing.isEmpty() -> {
                    if (FastHarvester.visitIngredient(ing)) acc.add(new HarvestedItems.HarvestedIngredient(ing, 1));
                    return true;
                }
                default -> {
                }
            }
            return node instanceof ItemStack || node instanceof FluidStack;
        });
    }
}