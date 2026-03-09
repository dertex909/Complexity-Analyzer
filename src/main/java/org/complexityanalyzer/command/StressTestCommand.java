/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
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

package org.complexityanalyzer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.ThreadPoolManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@EventBusSubscriber(modid = ComplexityAnalyzer.MODID)
public class StressTestCommand {

    private static final Random RANDOM = new Random();

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
                Commands.literal("stresstest")
                        .requires(source -> source.hasPermission(4))
                        .executes(ctx -> executeStressTest(ctx, 100))
                        .then(Commands.argument("iterations", IntegerArgumentType.integer(1, Integer.MAX_VALUE))
                                .executes(ctx -> executeStressTest(ctx, IntegerArgumentType.getInteger(ctx, "iterations")))
                        )
        );

        ComplexityAnalyzer.LOGGER.info("Registered /stresstest command (OP only)");
    }

    private static int executeStressTest(CommandContext<CommandSourceStack> context, int iterations) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source,
                    Component.literal("❌ Analysis engine is not ready!")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_RED));
        output.sendInfo(source,
                Component.literal("⚡ STRESS TEST STARTED")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_RED));
        output.sendInfo(source, Component.literal(""));

        output.sendInfo(source,
                Component.literal("  Iterations: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(iterations))
                                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("  Threads: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(ThreadPoolManager.getInstance().getParallelism()))
                                .withStyle(ChatFormatting.AQUA)));

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("  ⏳ Running tests in parallel...")
                        .withStyle(ChatFormatting.YELLOW));

        List<Item> testItems = getRandomItems(Math.min(iterations, 500));
        List<EntityType<?>> testEntities = getRandomEntities(Math.min(iterations / 5, 100));

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicLong totalTimeNs = new AtomicLong(0);

        long startTime = System.nanoTime();

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Item item : testItems) {
            futures.add(CompletableFuture.runAsync(() -> {
                long taskStart = System.nanoTime();
                try {
                    engine.getComplexityResult(item);
                    engine.findAllSourcesForItem(item);
                    engine.getBaseResourceData(item);
                    engine.hasRecipe(item);
                    engine.getUsageCount(item);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    ComplexityAnalyzer.LOGGER.debug("Stress test item fail: {}", e.getMessage());
                }
                totalTimeNs.addAndGet(System.nanoTime() - taskStart);
            }, ThreadPoolManager.getInstance().getComputePool()));
        }

        for (EntityType<?> entityType : testEntities) {
            futures.add(CompletableFuture.runAsync(() -> {
                long taskStart = System.nanoTime();
                try {
                    engine.getMobPropertyProvider().ifPresent(provider -> provider.getProperties(entityType));
                    engine.getMobDropSource().ifPresent(dropSource -> dropSource.getDropsForEntity(entityType));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    ComplexityAnalyzer.LOGGER.debug("Stress test entity fail: {}", e.getMessage());
                }
                totalTimeNs.addAndGet(System.nanoTime() - taskStart);
            }, ThreadPoolManager.getInstance().getComputePool()));
        }

        for (int i = 0; i < Math.min(iterations / 10, 50); i++) {
            futures.add(CompletableFuture.runAsync(() -> {
                long taskStart = System.nanoTime();
                try {
                    engine.getStats();
                    engine.getCurrentState();
                    engine.isReady();
                    engine.getGraph();
                    engine.getSourceManager();
                    engine.getDepthAnalyzer();
                    engine.getSolverResult();
                    ThreadPoolManager.getInstance().getStats();
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    ComplexityAnalyzer.LOGGER.debug("Stress test stats fail: {}", e.getMessage());
                }
                totalTimeNs.addAndGet(System.nanoTime() - taskStart);
            }, ThreadPoolManager.getInstance().getComputePool()));
        }

        for (int i = 0; i < Math.min(iterations / 5, 100); i++) {
            final Item randomItem = testItems.get(RANDOM.nextInt(testItems.size()));
            futures.add(CompletableFuture.runAsync(() -> {
                long taskStart = System.nanoTime();
                try {
                    engine.getComplexityCache().get(randomItem);
                    engine.getComplexityCache().contains(randomItem);
                    engine.getComplexityCache().getCategory(randomItem);
                    engine.getComplexityCache().size();
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    ComplexityAnalyzer.LOGGER.debug("Stress test cache fail: {}", e.getMessage());
                }
                totalTimeNs.addAndGet(System.nanoTime() - taskStart);
            }, ThreadPoolManager.getInstance().getComputePool()));
        }

        for (int i = 0; i < Math.min(iterations / 5, 100); i++) {
            final Item randomItem = testItems.get(RANDOM.nextInt(testItems.size()));
            futures.add(CompletableFuture.runAsync(() -> {
                long taskStart = System.nanoTime();
                try {
                    var graph = engine.getGraph();
                    if (graph != null) {
                        graph.hasRecipe(randomItem);
                        graph.getRecipes(randomItem);
                        graph.getUsageCount(randomItem);
                        graph.getAllItems();
                        graph.getTotalRecipeCount();
                    }
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    ComplexityAnalyzer.LOGGER.debug("Stress test graph fail: {}", e.getMessage());
                }
                totalTimeNs.addAndGet(System.nanoTime() - taskStart);
            }, ThreadPoolManager.getInstance().getComputePool()));
        }

        for (int i = 0; i < Math.min(iterations / 5, 100); i++) {
            final Item randomItem = testItems.get(RANDOM.nextInt(testItems.size()));
            futures.add(CompletableFuture.runAsync(() -> {
                long taskStart = System.nanoTime();
                try {
                    engine.getSourceManager().ifPresent(sm -> {
                        sm.analyze(randomItem);
                        sm.findAllSources(randomItem);
                        sm.getBaseFactor(randomItem);
                        sm.getSources();
                    });
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    ComplexityAnalyzer.LOGGER.debug("Stress test source fail: {}", e.getMessage());
                }
                totalTimeNs.addAndGet(System.nanoTime() - taskStart);
            }, ThreadPoolManager.getInstance().getComputePool()));
        }

        int totalTasks = futures.size();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRunAsync(() -> {
                    long endTime = System.nanoTime();
                    long wallTimeMs = (endTime - startTime) / 1_000_000;
                    long avgTaskTimeUs = totalTimeNs.get() / Math.max(1, successCount.get() + failCount.get()) / 1000;

                    output.sendInfo(source, Component.literal(""));
                    output.sendInfo(source,
                            Component.literal("═══════════════════════════════")
                                    .withStyle(ChatFormatting.DARK_GREEN));
                    output.sendInfo(source,
                            Component.literal("✅ STRESS TEST COMPLETE")
                                    .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
                    output.sendInfo(source,
                            Component.literal("═══════════════════════════════")
                                    .withStyle(ChatFormatting.DARK_GREEN));
                    output.sendInfo(source, Component.literal(""));

                    output.sendInfo(source,
                            Component.literal("  📊 Results:")
                                    .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));

                    output.sendInfo(source,
                            Component.literal("    Total Tasks: ")
                                    .withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(String.valueOf(totalTasks))
                                            .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)));

                    ChatFormatting successColor = failCount.get() == 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
                    output.sendInfo(source,
                            Component.literal("    Success: ")
                                    .withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(String.valueOf(successCount.get()))
                                            .withStyle(successColor, ChatFormatting.BOLD)));

                    if (failCount.get() > 0) {
                        output.sendInfo(source,
                                Component.literal("    Failed: ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(String.valueOf(failCount.get()))
                                                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
                    }

                    output.sendInfo(source, Component.literal(""));
                    output.sendInfo(source,
                            Component.literal("  ⏱ Timing:")
                                    .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

                    output.sendInfo(source,
                            Component.literal("    Wall Time: ")
                                    .withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(wallTimeMs + " ms")
                                            .withStyle(ChatFormatting.YELLOW)));

                    output.sendInfo(source,
                            Component.literal("    Avg Task Time: ")
                                    .withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(avgTaskTimeUs + " µs")
                                            .withStyle(ChatFormatting.AQUA)));

                    double tasksPerSecond = totalTasks / (wallTimeMs / 1000.0);
                    output.sendInfo(source,
                            Component.literal("    Throughput: ")
                                    .withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(String.format("%.0f tasks/sec", tasksPerSecond))
                                            .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));

                    output.sendInfo(source, Component.literal(""));

                    var poolStats = ThreadPoolManager.getInstance().getStats();
                    output.sendInfo(source,
                            Component.literal("  🔧 Thread Pool After Test:")
                                    .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));

                    output.sendInfo(source,
                            Component.literal("    Active: ")
                                    .withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(String.valueOf(poolStats.activeThreads()))
                                            .withStyle(ChatFormatting.WHITE)));

                    output.sendInfo(source,
                            Component.literal("    Queued: ")
                                    .withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(String.valueOf(poolStats.queuedTasks()))
                                            .withStyle(poolStats.queuedTasks() > 0 ? ChatFormatting.YELLOW : ChatFormatting.GREEN)));

                    output.sendInfo(source,
                            Component.literal("    Completed: ")
                                    .withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(String.valueOf(poolStats.completedTasks()))
                                            .withStyle(ChatFormatting.WHITE)));

                    output.sendInfo(source, Component.literal(""));
                    output.sendInfo(source,
                            Component.literal("═══════════════════════════════")
                                    .withStyle(ChatFormatting.DARK_GREEN));

                    ComplexityAnalyzer.LOGGER.info("Stress test complete: {} tasks, {} success, {} failed, {} ms",
                            totalTasks, successCount.get(), failCount.get(), wallTimeMs);

                }, source.getServer());

        return 1;
    }

    private static List<Item> getRandomItems(int count) {
        List<Item> allItems = new ArrayList<>(BuiltInRegistries.ITEM.stream().toList());
        List<Item> result = new ArrayList<>(count);

        for (int i = 0; i < count && !allItems.isEmpty(); i++) {
            int index = RANDOM.nextInt(allItems.size());
            result.add(allItems.get(index));
        }

        return result;
    }

    private static List<EntityType<?>> getRandomEntities(int count) {
        List<EntityType<?>> livingEntities = BuiltInRegistries.ENTITY_TYPE.stream()
                .filter(type -> type.getCategory() != MobCategory.MISC)
                .toList();

        List<EntityType<?>> result = new ArrayList<>(count);
        for (int i = 0; i < count && !livingEntities.isEmpty(); i++) {
            result.add(livingEntities.get(RANDOM.nextInt(livingEntities.size())));
        }

        return result;
    }

}