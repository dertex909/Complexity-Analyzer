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
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.sources.UniversalLootSource;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.event.AnalysisBootstrap;

import java.util.Arrays;
import java.util.Objects;

@EventBusSubscriber(modid = ComplexityAnalyzer.MODID)
public class ComplexityCommand {
    private static final SuggestionProvider<CommandSourceStack> ITEM_SUGGESTIONS = (context, builder) ->
            SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet(), builder);

    private static final SuggestionProvider<CommandSourceStack> ENTITY_SUGGESTIONS = (context, builder) ->
            SharedSuggestionProvider.suggestResource(
                    BuiltInRegistries.ENTITY_TYPE.keySet().stream()
                            .filter(id -> {
                                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
                                return type.getCategory() != MobCategory.MISC;
                            }),
                    builder);

    private static final SuggestionProvider<CommandSourceStack> LOOT_TABLE_SUGGESTIONS = (context, builder) -> {
        AnalysisEngine engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) {
            return builder.buildFuture();
        }

        return engine.getSourceByType(UniversalLootSource.class)
                .map(uls -> {
                    uls.getAllLootData().values().stream()
                            .flatMap(map -> map.values().stream())
                            .map(data -> {
                                try {
                                    String details = data.getDetails();
                                    int start = details.indexOf("'") + 1;
                                    int end = details.indexOf("'", start);
                                    return details.substring(start, end);
                                } catch (Exception e) {
                                    return null;
                                }
                            })
                            .filter(Objects::nonNull)
                            .distinct()
                            .forEach(builder::suggest);
                    return builder.buildFuture();
                })
                .orElse(builder.buildFuture());
    };

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
                Commands.literal("complexity")
                        .then(Commands.literal("status")
                                .executes(ComplexityCommand::executeStatus))

                        .then(Commands.literal("tps")
                                .executes(ComplexityCommand::executeTps))

                        .then(Commands.literal("stats")
                                .executes(ComplexityCommand::executeStats))

                        .then(Commands.literal("threads")
                                .requires(source -> source.hasPermission(2))
                                .executes(ComplexityCommand::executeThreads))

                        .then(Commands.literal("analyze")
                                .then(Commands.literal("item")
                                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                                .suggests(ITEM_SUGGESTIONS)
                                                .executes(cmd -> AnalyzeCommand.execute(cmd, ResourceLocationArgument.getId(cmd, "item")))
                                        ))
                                .then(Commands.literal("entity")
                                        .then(Commands.argument("entity", ResourceLocationArgument.id())
                                                .suggests(ENTITY_SUGGESTIONS)
                                                .executes(cmd -> EntityAnalyzeCommand.execute(cmd, ResourceLocationArgument.getId(cmd, "entity")))
                                        ))
                                .then(Commands.literal("loot")
                                        .then(Commands.argument("loot_table", ResourceLocationArgument.id())
                                                .suggests(LOOT_TABLE_SUGGESTIONS)
                                                .executes(ctx -> LootAnalyzeCommand.execute(ctx, ResourceLocationArgument.getId(ctx, "loot_table")))
                                        )
                                )
                        )

                        .then(Commands.literal("resource")
                                .then(Commands.argument("item", ResourceLocationArgument.id())
                                        .suggests(ITEM_SUGGESTIONS)
                                        .executes(cmd -> ResourceCommand.execute(cmd, ResourceLocationArgument.getId(cmd, "item")))
                                ))

                        .then(Commands.literal("tree")
                                .then(Commands.argument("item", ResourceLocationArgument.id())
                                        .suggests(ITEM_SUGGESTIONS)
                                        .executes(ctx -> TreeCommand.execute(ctx, ResourceLocationArgument.getId(ctx, "item"), "player", TreeCommand.DEFAULT_MAX_DEPTH))
                                        .then(Commands.literal("depth")
                                                .then(Commands.argument("max_depth", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> TreeCommand.execute(
                                                                ctx,
                                                                ResourceLocationArgument.getId(ctx, "item"),
                                                                "player",
                                                                IntegerArgumentType.getInteger(ctx, "max_depth")
                                                        ))
                                                        .then(Commands.literal("mode")
                                                                .then(Commands.argument("mode_type", StringArgumentType.word())
                                                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(new String[]{"player", "economic"}, builder))
                                                                        .executes(ctx -> TreeCommand.execute(
                                                                                ctx,
                                                                                ResourceLocationArgument.getId(ctx, "item"),
                                                                                StringArgumentType.getString(ctx, "mode_type"),
                                                                                IntegerArgumentType.getInteger(ctx, "max_depth")
                                                                        ))
                                                                )
                                                        )
                                                )
                                        )
                                        .then(Commands.literal("mode")
                                                .then(Commands.argument("mode_type", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(new String[]{"player", "economic"}, builder))
                                                        .executes(ctx -> TreeCommand.execute(
                                                                ctx,
                                                                ResourceLocationArgument.getId(ctx, "item"),
                                                                StringArgumentType.getString(ctx, "mode_type"),
                                                                TreeCommand.DEFAULT_MAX_DEPTH
                                                        ))
                                                        .then(Commands.literal("depth")
                                                                .then(Commands.argument("max_depth", IntegerArgumentType.integer(1))
                                                                        .executes(ctx -> TreeCommand.execute(
                                                                                ctx,
                                                                                ResourceLocationArgument.getId(ctx, "item"),
                                                                                StringArgumentType.getString(ctx, "mode_type"),
                                                                                IntegerArgumentType.getInteger(ctx, "max_depth")
                                                                        ))
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )

                        .then(Commands.literal("reload")
                                .requires(source -> source.hasPermission(2))
                                .executes(ComplexityCommand::executeReload))

                        .then(Commands.literal("export")
                                .requires(source -> source.hasPermission(2))

                                .then(Commands.literal("items")
                                        .then(Commands.literal("all")
                                                .executes(ExportCommand::executeAllItems)
                                        )
                                        .then(Commands.literal("category")
                                                .then(Commands.argument("category_name", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                                new String[]{"Trivial", "Simple", "Moderate", "Complex", "Difficult", "Expert", "Master", "Mythical", "Transcendent", "Eternal"}, builder))
                                                        .executes(ctx -> ExportCommand.executeItemsByCategory(ctx, StringArgumentType.getString(ctx, "category_name")))
                                                )
                                        )
                                        .then(Commands.literal("top")
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 1000))
                                                        .executes(ctx -> ExportCommand.executeTopItems(ctx, IntegerArgumentType.getInteger(ctx, "count")))
                                                )
                                        )
                                        .then(Commands.literal("single")
                                                .then(Commands.argument("item_id", ResourceLocationArgument.id())
                                                        .suggests(ITEM_SUGGESTIONS)
                                                        .executes(ctx -> ExportCommand.executeSingleItem(ctx, ResourceLocationArgument.getId(ctx, "item_id").toString()))
                                                )
                                        )
                                        .then(Commands.literal("csv")
                                                .executes(ExportCommand::executeItemsCSV)
                                        )
                                )

                                .then(Commands.literal("mobs")
                                        .then(Commands.literal("all")
                                                .executes(ctx -> ExportCommand.executeAllMobs(ctx, "json"))
                                                .then(Commands.literal("format")
                                                        .then(Commands.argument("format_type", StringArgumentType.word())
                                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(new String[]{"csv", "json"}, builder))
                                                                .executes(ctx -> ExportCommand.executeAllMobs(ctx, StringArgumentType.getString(ctx, "format_type")))
                                                        )
                                                )
                                        )
                                        .then(Commands.literal("category")
                                                .then(Commands.argument("category_name", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Arrays.stream(MobCategory.values()).map(MobCategory::getName), builder))
                                                        .executes(ctx -> ExportCommand.executeMobsByCategory(ctx, StringArgumentType.getString(ctx, "category_name")))
                                                )
                                        )
                                        .then(Commands.literal("top")
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 1000))
                                                        .executes(ctx -> ExportCommand.executeTopMobs(ctx, IntegerArgumentType.getInteger(ctx, "count")))
                                                )
                                        )
                                        .then(Commands.literal("single")
                                                .then(Commands.argument("mob_id", ResourceLocationArgument.id())
                                                        .suggests(ENTITY_SUGGESTIONS)
                                                        .executes(ctx -> ExportCommand.executeSingleMob(ctx, ResourceLocationArgument.getId(ctx, "mob_id").toString()))
                                                )
                                        )
                                        .then(Commands.literal("csv")
                                                .executes(ctx -> ExportCommand.executeAllMobs(ctx, "csv"))
                                        )
                                )
                        )

                        .then(ChunkCommands.register())
        );
        ComplexityAnalyzer.LOGGER.info("Registered /complexity command with role-based permissions");
    }

    private static int executeStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisBootstrap.getEngine();

        if (engine == null) {
            output.sendFailure(source,
                    Component.literal("❌ Analysis Engine is not initialized!")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        AnalysisEngine.State state = engine.getCurrentState();

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));

        output.sendInfo(source,
                Component.literal("⚙ ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("Complexity Analyzer Status")
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));

        String stateIcon;
        ChatFormatting stateColor = switch (state.toString()) {
            case "READY" -> {
                stateIcon = "✓";
                yield ChatFormatting.GREEN;
            }
            case "LOADING", "INITIALIZING" -> {
                stateIcon = "⏳";
                yield ChatFormatting.YELLOW;
            }
            case "ERROR", "FAILED" -> {
                stateIcon = "✗";
                yield ChatFormatting.RED;
            }
            default -> {
                stateIcon = "◆";
                yield ChatFormatting.GRAY;
            }
        };

        output.sendInfo(source,
                Component.literal("  Engine State: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(stateIcon + " " + state)
                                .withStyle(stateColor, ChatFormatting.BOLD)));

        if (engine.isReady()) {
            var stats = engine.getStats();

            output.sendInfo(source, Component.literal(""));
            output.sendInfo(source,
                    Component.literal("  📊 Data Overview:")
                            .withStyle(ChatFormatting.AQUA));

            output.sendInfo(source,
                    Component.literal("    Items with recipes: ")
                            .withStyle(ChatFormatting.DARK_GRAY)
                            .append(Component.literal(String.valueOf(stats.itemCount()))
                                    .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)));

            output.sendInfo(source,
                    Component.literal("    Total recipes: ")
                            .withStyle(ChatFormatting.DARK_GRAY)
                            .append(Component.literal(String.valueOf(stats.recipeCount()))
                                    .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)));

            output.sendInfo(source,
                    Component.literal("    Base resources: ")
                            .withStyle(ChatFormatting.DARK_GRAY)
                            .append(Component.literal(String.valueOf(stats.baseResourceCount()))
                                    .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)));
        } else {
            output.sendInfo(source, Component.literal(""));
            output.sendInfo(source,
                    Component.literal("  ⚠ Engine not ready. Statistics unavailable.")
                            .withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));
        }

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));

        return 1;
    }

    private static int executeReload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (engine == null) {
            output.sendFailure(source,
                    Component.literal("❌ Engine not initialized!"));
            return 0;
        }

        String adminName = source.getTextName();

        output.broadcastWarning(
                Component.literal("⚠ Analysis system is reloading... Possible lag!"));

        output.sendToAdmins(
                Component.literal("System reload initiated by " + adminName));

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("🔄 Reloading Analysis Systems...")
                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));

        output.sendInfo(source,
                Component.literal("  This may take a few seconds and cause lag.")
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));

        output.sendInfo(source,
                Component.literal("  Running in background...")
                        .withStyle(ChatFormatting.DARK_GRAY));

        engine.reloadAsync(source.getLevel());

        return 1;
    }

    private static int executeTps(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        MinecraftServer server = source.getServer();

        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0D;
        double tps = 1000.0 / Math.max(50.0, mspt);
        double finalTps = Math.min(20.0, tps);

        ChatFormatting tpsColor = tps >= 19.0 ? ChatFormatting.GREEN :
                (tps >= 16.0 ? ChatFormatting.YELLOW : ChatFormatting.RED);
        ChatFormatting msptColor = mspt <= 40.0 ? ChatFormatting.GREEN :
                (mspt <= 50.0 ? ChatFormatting.YELLOW : ChatFormatting.RED);

        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / 1024 / 1024;
        long totalMemory = runtime.totalMemory() / 1024 / 1024;
        long freeMemory = runtime.freeMemory() / 1024 / 1024;
        long usedMemory = totalMemory - freeMemory;

        double memoryPercent = (double) usedMemory / totalMemory * 100;
        ChatFormatting memoryColor = memoryPercent < 60 ? ChatFormatting.GREEN :
                memoryPercent < 80 ? ChatFormatting.YELLOW : ChatFormatting.RED;

        double avgPing = server.getPlayerList().getPlayers().stream()
                .mapToInt(player -> player.connection.latency())
                .average()
                .orElse(0.0);
        ChatFormatting pingColor = avgPing < 100 ? ChatFormatting.GREEN :
                (avgPing < 200 ? ChatFormatting.YELLOW : ChatFormatting.RED);

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));

        output.sendInfo(source,
                Component.literal("📈 ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("Server Performance")
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));

        output.sendInfo(source,
                Component.literal("  ⚙ Tick Performance")
                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));

        MutableComponent tpsComponent = Component.literal("    TPS: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format("%.2f", finalTps))
                        .withStyle(tpsColor, ChatFormatting.BOLD));

        String tpsIcon = tps >= 19.0 ? " ✓" : tps >= 16.0 ? " ⚠" : " ✗";
        tpsComponent.append(Component.literal(tpsIcon).withStyle(tpsColor));

        output.sendInfo(source, tpsComponent);

        output.sendInfo(source,
                Component.literal("    MSPT: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.format("%.2f ms", mspt))
                                .withStyle(msptColor)));

        String msptBar = getPerformanceBar(mspt);
        output.sendInfo(source,
                Component.literal("    " + msptBar)
                        .withStyle(ChatFormatting.DARK_GRAY));

        output.sendInfo(source, Component.literal(""));

        output.sendInfo(source,
                Component.literal("  💾 Memory Usage")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));

        output.sendInfo(source,
                Component.literal("    Used: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(usedMemory + " MB")
                                .withStyle(memoryColor))
                        .append(Component.literal(" / ")
                                .withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(totalMemory + " MB")
                                .withStyle(ChatFormatting.WHITE)));

        output.sendInfo(source,
                Component.literal("    Max Available: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(maxMemory + " MB")
                                .withStyle(ChatFormatting.YELLOW)));

        String memoryBar = getMemoryBar(usedMemory, totalMemory);
        output.sendInfo(source,
                Component.literal("    " + memoryBar + " ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(String.format("%.1f%%", memoryPercent))
                                .withStyle(memoryColor)));

        output.sendInfo(source, Component.literal(""));

        output.sendInfo(source,
                Component.literal("  🌐 Network")
                        .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));

        output.sendInfo(source,
                Component.literal("    Players Online: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(server.getPlayerCount()))
                                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));

        if (server.getPlayerCount() > 0) {
            output.sendInfo(source,
                    Component.literal("    Avg Ping: ")
                            .withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(String.format("%.0f ms", avgPing))
                                    .withStyle(pingColor)));
        }

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));

        return 1;
    }

    private static int executeStats(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source,
                    Component.literal("⚠ Engine is not ready!")
                            .withStyle(ChatFormatting.RED));
            output.sendInfo(source,
                    Component.literal("Current state: " + engine.getCurrentState())
                            .withStyle(ChatFormatting.GRAY));
            return 0;
        }

        var stats = engine.getStats();

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));

        output.sendInfo(source,
                Component.literal("📊 ")
                        .withStyle(ChatFormatting.GREEN)
                        .append(Component.literal("Detailed Statistics")
                                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));

        output.sendInfo(source,
                Component.literal("  🔗 Recipe Graph")
                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));

        output.sendInfo(source,
                Component.literal("    Items: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(stats.itemCount()))
                                .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("    Recipes: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(stats.recipeCount()))
                                .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)));

        if (stats.itemCount() > 0) {
            double avgRecipesPerItem = (double) stats.recipeCount() / stats.itemCount();
            output.sendInfo(source,
                    Component.literal("    Avg Recipes/Item: ")
                            .withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(String.format("%.2f", avgRecipesPerItem))
                                    .withStyle(ChatFormatting.AQUA)));
        }

        output.sendInfo(source, Component.literal(""));

        output.sendInfo(source,
                Component.literal("  ⛏ Base Resources")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        output.sendInfo(source,
                Component.literal("    Cached Items: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(stats.baseResourceCount()))
                                .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("    (Mining, Loot, Mobs, etc.)")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));

        output.sendInfo(source, Component.literal(""));

        long totalEntries = stats.itemCount() + stats.baseResourceCount();
        output.sendInfo(source,
                Component.literal("  💿 Total Database Entries: ")
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(String.valueOf(totalEntries))
                                .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)));

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));

        return 1;
    }

    private static int executeThreads(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());

        org.complexityanalyzer.core.ThreadPoolManager.PoolStats stats =
                org.complexityanalyzer.core.ThreadPoolManager.getInstance().getStats();

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));

        output.sendInfo(source,
                Component.literal("⚙ ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("Thread Pool Statistics")
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));

        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));

        output.sendInfo(source,
                Component.literal("  Parallelism Target: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(stats.parallelism()))
                                .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)));

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("  🔹 Compute Pool (I/O & Tasks)")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));

        output.sendInfo(source,
                Component.literal("    Active Threads: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(stats.activeThreads()))
                                .withStyle(ChatFormatting.YELLOW)));

        output.sendInfo(source,
                Component.literal("    Queued Tasks: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(stats.queuedTasks()))
                                .withStyle(stats.queuedTasks() > 100 ? ChatFormatting.RED : ChatFormatting.GREEN)));

        output.sendInfo(source,
                Component.literal("    Completed: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(stats.completedTasks()))
                                .withStyle(ChatFormatting.WHITE)));

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("  🔹 ForkJoin Pool (Parallel Streams)")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));

        output.sendInfo(source,
                Component.literal("    Active Threads: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(stats.forkJoinActive()))
                                .withStyle(ChatFormatting.YELLOW)));

        output.sendInfo(source,
                Component.literal("    Task Steals: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.valueOf(stats.forkJoinSteals()))
                                .withStyle(ChatFormatting.WHITE)));

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source,
                Component.literal("═══════════════════════════════")
                        .withStyle(ChatFormatting.DARK_GRAY));

        return 1;
    }

    private static String getPerformanceBar(double current) {
        int percent = (int) Math.min(100, (current / 50.0) * 100);
        int filled = percent / 10;
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            if (i < filled) {
                bar.append("█");
            } else {
                bar.append("░");
            }
        }
        bar.append("]");
        return bar.toString();
    }

    private static String getMemoryBar(long used, long total) {
        int percent = (int) ((double) used / total * 100);
        int filled = percent / 10;
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            if (i < filled) {
                bar.append("█");
            } else {
                bar.append("░");
            }
        }
        bar.append("]");
        return bar.toString();
    }
}