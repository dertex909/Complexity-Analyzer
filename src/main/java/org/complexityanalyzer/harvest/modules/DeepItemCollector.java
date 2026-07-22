package org.complexityanalyzer.harvest.modules;

import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.core.GameRegistryManager;

import static net.minecraft.world.item.Items.AIR;

public final class DeepItemCollector {

    private DeepItemCollector() {
    }

    public static void collect(Object obj, ObjectList<ItemStack> acc, int depth, ReferenceOpenHashSet<Object> visited) {
        DeepGraphTraverser.traverse(obj, depth, visited, (node, d) -> {
            switch (node) {
                case TagKey<?> tagKey -> {
                    if (tagKey.registry().equals(Registries.ITEM)) {
                        @SuppressWarnings("unchecked")
                        var itemTag = (TagKey<Item>) tagKey;
                        var optionalTag = BuiltInRegistries.ITEM.getTag(itemTag);
                        if (optionalTag.isPresent()) for (var holder : optionalTag.get()) {
                            var item = holder.value();
                            var id = BuiltInRegistries.ITEM.getKey(item);
                            var registeredItem = GameRegistryManager.getItem(id);
                            if (registeredItem != null && registeredItem != AIR) {
                                acc.add(new ItemStack(registeredItem));
                                break;
                            }
                        }
                    }
                    return true;
                }
                case SizedIngredient si when si.count() > 0 -> {
                    ItemStack[] stacks = si.ingredient().getItems();
                    if (stacks.length > 0) {
                        var stack = stacks[0].copy();
                        stack.setCount(si.count());
                        acc.add(stack);
                    }
                    return true;
                }
                case ItemStack stack when !stack.isEmpty() -> {
                    acc.add(stack.copy());
                    return true;
                }
                case Item item -> {
                    if (item != AIR) acc.add(new ItemStack(item));
                    return true;
                }
                case Block block -> {
                    var item = block.asItem();
                    if (item != AIR) acc.add(new ItemStack(item));
                    return true;
                }
                case Holder<?> holder -> {
                    if (visited.add(holder)) collect(holder.value(), acc, d + 1, visited);
                    return true;
                }
                default -> {
                }
            }
            return node instanceof FluidStack || node instanceof Ingredient;
        });
    }
}