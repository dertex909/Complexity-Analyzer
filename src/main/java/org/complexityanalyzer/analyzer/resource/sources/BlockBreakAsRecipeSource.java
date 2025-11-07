/*
 * Complexity Analyzer
 * Copyright (C) 2025 dertex909
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

package org.complexityanalyzer.analyzer.resource.sources;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.IMultiSourceProvider;
import org.complexityanalyzer.analyzer.resource.IResourceSource;
import org.complexityanalyzer.analyzer.resource.data.BaseResourceData;

import java.lang.reflect.Field;
import java.util.*;

public class BlockBreakAsRecipeSource implements IResourceSource, IMultiSourceProvider {

    private static final int SAMPLE_COUNT = 100;
    private static final double BASE_MINING_COST = 2.0;

    private final Map<Item, List<BaseResourceData>> allPaths = new HashMap<>();
    private static Field randomField = null;

    @Override
    public void initialize(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            ComplexityAnalyzer.LOGGER.warn("[{}] requires a ServerLevel. Skipping.", getName());
            return;
        }

        this.allPaths.clear();

        ComplexityAnalyzer.LOGGER.info("[{}] Initializing... Analyzing all block drop recipes.", getName());
        long startTime = System.currentTimeMillis();
        int pathsFound = 0;
        int blocksSkipped = 0;

        MinecraftServer server = serverLevel.getServer();
        List<ItemStack> toolsToTest = createTestTools(serverLevel);

        for (Block blockToMine : BuiltInRegistries.BLOCK) {
            if (blockToMine == Blocks.AIR || blockToMine == Blocks.CAVE_AIR || blockToMine == Blocks.VOID_AIR) {
                continue;
            }

            if (blockToMine.defaultDestroyTime() < 0) {
                blocksSkipped++;
                continue;
            }

            BlockState defaultState = blockToMine.defaultBlockState();
            Item blockAsItem = blockToMine.asItem();

            for (ItemStack toolStack : toolsToTest) {
                if (defaultState.requiresCorrectToolForDrops() && !toolStack.isCorrectToolForDrops(defaultState)) {
                    continue;
                }

                try {
                    LootTable lootTable = server.reloadableRegistries().getLootTable(blockToMine.getLootTable());
                    if (lootTable == LootTable.EMPTY) continue;

                    Map<Item, Double> averageDrop = getStableDrop(lootTable, serverLevel, defaultState, toolStack, blockToMine);
                    if (averageDrop.isEmpty()) continue;

                    for (Map.Entry<Item, Double> entry : averageDrop.entrySet()) {
                        Item droppedItem = entry.getKey();
                        double itemsPerAction = entry.getValue();
                        if (itemsPerAction <= 0) continue;

                        if (blockAsItem != Items.AIR && droppedItem == blockAsItem) {
                            continue;
                        }

                        Map<Item, Double> sourceItems = new HashMap<>();

                        if (blockAsItem != Items.AIR) {
                            sourceItems.put(blockAsItem, 1.0 / itemsPerAction);
                        }

                        Item toolItem = toolStack.getItem();
                        if (toolItem != Items.AIR) {
                            double durability = toolStack.getMaxDamage();
                            if (durability > 0) {
                                double toolWearPerDrop = (1.0 / durability) / itemsPerAction;
                                sourceItems.put(toolItem, toolWearPerDrop);
                            }
                        }

                        String toolName = toolStack.isEmpty() ? "Hand" : toolStack.getDisplayName().getString();
                        String avgFormatted = formatAverage(itemsPerAction);

                        BaseResourceData data = new BaseResourceData.Builder(droppedItem, this)
                                .sourceType(getSourceType())
                                .sourceSpecifier(blockToMine.getName().getString())
                                .details(String.format("Mined from %s with %s (avg: %s)",
                                        blockToMine.getName().getString(), toolName, avgFormatted))
                                .baseFactor(BASE_MINING_COST)
                                .sourceItems(sourceItems)
                                .build();

                        allPaths.computeIfAbsent(droppedItem, k -> new ArrayList<>()).add(data);
                        pathsFound++;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        for (List<BaseResourceData> paths : allPaths.values()) {
            paths.sort((a, b) -> {
                int typeCompare = Double.compare(
                        a.getSourceType().getBaseMultiplier(),
                        b.getSourceType().getBaseMultiplier()
                );
                if (typeCompare != 0) return typeCompare;

                int factorCompare = Double.compare(a.getBaseFactor(), b.getBaseFactor());
                if (factorCompare != 0) return factorCompare;

                int sizeCompare = Integer.compare(
                        a.getSourceItems().size(),
                        b.getSourceItems().size()
                );
                if (sizeCompare != 0) return sizeCompare;

                double sumA = a.getSourceItems().values().stream().mapToDouble(Double::doubleValue).sum();
                double sumB = b.getSourceItems().values().stream().mapToDouble(Double::doubleValue).sum();
                int sumCompare = Double.compare(sumA, sumB);
                if (sumCompare != 0) return sumCompare;

                return a.getDetails().compareTo(b.getDetails());
            });
        }

        ComplexityAnalyzer.LOGGER.info("[{}] Initialization complete in {}ms. Found {} block drop paths for {} unique items. Skipped {} indestructible blocks.",
                getName(), (System.currentTimeMillis() - startTime), pathsFound, allPaths.size(), blocksSkipped);
    }

    private List<ItemStack> createTestTools(ServerLevel serverLevel) {
        List<ItemStack> tools = new ArrayList<>();
        tools.add(ItemStack.EMPTY);
        tools.add(new ItemStack(Items.WOODEN_PICKAXE));
        tools.add(new ItemStack(Items.WOODEN_AXE));
        tools.add(new ItemStack(Items.WOODEN_SHOVEL));
        tools.add(new ItemStack(Items.STONE_PICKAXE));
        tools.add(new ItemStack(Items.STONE_AXE));
        tools.add(new ItemStack(Items.STONE_SHOVEL));
        tools.add(new ItemStack(Items.IRON_PICKAXE));
        tools.add(new ItemStack(Items.IRON_AXE));
        tools.add(new ItemStack(Items.IRON_SHOVEL));
        tools.add(new ItemStack(Items.GOLDEN_PICKAXE));
        tools.add(new ItemStack(Items.GOLDEN_AXE));
        tools.add(new ItemStack(Items.GOLDEN_SHOVEL));
        tools.add(new ItemStack(Items.DIAMOND_PICKAXE));
        tools.add(new ItemStack(Items.DIAMOND_AXE));
        tools.add(new ItemStack(Items.DIAMOND_SHOVEL));
        tools.add(new ItemStack(Items.NETHERITE_PICKAXE));
        tools.add(new ItemStack(Items.NETHERITE_AXE));
        tools.add(new ItemStack(Items.NETHERITE_SHOVEL));
        tools.add(new ItemStack(Items.SHEARS));

        ItemStack silkTouchPickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        Optional<Holder.Reference<Enchantment>> silkTouchHolder = serverLevel.registryAccess()
                .registryOrThrow(Registries.ENCHANTMENT)
                .getHolder(Enchantments.SILK_TOUCH);
        silkTouchHolder.ifPresent(holder -> silkTouchPickaxe.enchant(holder, 1));
        tools.add(silkTouchPickaxe);

        return tools;
    }

    private Map<Item, Double> getStableDrop(LootTable lootTable, ServerLevel level,
                                            BlockState blockState, ItemStack tool, Block block) {
        long baseSeed = generateStableSeed(block, tool);

        Map<Item, Long> totalCounts = new HashMap<>();
        boolean injectionWorked = false;

        for (int i = 0; i < SAMPLE_COUNT; i++) {
            RandomSource deterministicRandom = RandomSource.create(baseSeed + i);

            ObjectArrayList<ItemStack> drops = new ObjectArrayList<>();

            LootParams params = new LootParams.Builder(level)
                    .withParameter(LootContextParams.BLOCK_STATE, blockState)
                    .withParameter(LootContextParams.TOOL, tool)
                    .withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                    .create(LootContextParamSets.BLOCK);

            LootContext context = new LootContext.Builder(params).create(Optional.empty());

            if (i == 0) {
                injectionWorked = injectRandomIntoContext(context, deterministicRandom);
            } else if (injectionWorked) {
                injectRandomSilently(context, deterministicRandom);
            }

            lootTable.getRandomItems(context, drops::add);

            for (ItemStack stack : drops) {
                if (!stack.isEmpty()) {
                    totalCounts.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
                }
            }
        }

        Map<Item, Double> averages = new HashMap<>();
        for (Map.Entry<Item, Long> entry : totalCounts.entrySet()) {
            averages.put(entry.getKey(), (double) entry.getValue() / SAMPLE_COUNT);
        }

        return averages;
    }

    private boolean injectRandomIntoContext(LootContext context, RandomSource random) {
        if (randomField == null) {
            randomField = findRandomFieldAggressively(context);

            if (randomField != null) {
                ComplexityAnalyzer.LOGGER.info("[{}] Successfully found RandomSource field: {}",
                        getName(), randomField.getName());
            } else {
                ComplexityAnalyzer.LOGGER.warn("[{}] Could not find RandomSource field", getName());
            }
        }

        return injectRandomSilently(context, random);
    }

    private boolean injectRandomSilently(LootContext context, RandomSource random) {
        if (randomField == null) return false;

        try {
            randomField.set(context, random);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Field findRandomFieldAggressively(LootContext context) {
        Class<?> clazz = context.getClass();

        while (clazz != null) {
            for (Field field : clazz.getDeclaredFields()) {
                if (RandomSource.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return field;
                }
            }
            clazz = clazz.getSuperclass();
        }

        clazz = context.getClass();
        String[] possibleNames = {"random", "rand", "randomSource", "rng", "f_79024_"};

        while (clazz != null) {
            for (String name : possibleNames) {
                try {
                    Field field = clazz.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }

        return null;
    }

    private long generateStableSeed(Block block, ItemStack tool) {
        long seed = BuiltInRegistries.BLOCK.getKey(block).toString().hashCode();

        if (!tool.isEmpty()) {
            seed = seed * 31L + BuiltInRegistries.ITEM.getKey(tool.getItem()).toString().hashCode();

            ItemEnchantments enchantments = tool.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            if (!enchantments.isEmpty()) {
                seed = seed * 31L + enchantments.hashCode();
            }
        } else {
            seed = seed * 31L + "empty_hand".hashCode();
        }

        return seed;
    }

    private String formatAverage(double value) {
        if (value < 0.0001) return String.format("%.6f", value);
        if (value < 0.01) return String.format("%.4f", value);
        return String.format("%.2f", value);
    }

    @Override
    public boolean canProvide(Item item) {
        return allPaths.containsKey(item);
    }

    @Override
    public Optional<BaseResourceData> analyze(Item item) {
        List<BaseResourceData> paths = allPaths.get(item);
        if (paths == null || paths.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(paths.getFirst());
    }

    @Override
    public List<BaseResourceData> findAllSources(Item item) {
        return allPaths.getOrDefault(item, Collections.emptyList());
    }

    @Override
    public int getPriority() {
        return 25;
    }

    @Override
    public String getName() {
        return "Block Break as Recipe";
    }

    @Override
    public BaseResourceData.ResourceSourceType getSourceType() {
        return BaseResourceData.ResourceSourceType.BLOCK_TRANSFORMATION;
    }
}