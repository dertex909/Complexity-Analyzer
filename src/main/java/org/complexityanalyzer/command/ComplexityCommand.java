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
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.analyzer.resource.sources.UniversalLootSource;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.event.DatapackSyncHandler;

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

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
                Commands.literal("complexity")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status").executes(ComplexityCommand::executeStatus))
                        .then(Commands.literal("tps").executes(ComplexityCommand::executeTps))
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
                        .then(Commands.literal("reload").executes(ComplexityCommand::executeReload))
                        .then(Commands.literal("stats").executes(ComplexityCommand::executeStats))
                        .then(Commands.literal("export")
                                .then(Commands.literal("all")
                                        .executes(ExportCommand::executeAll)
                                )
                                .then(Commands.literal("item")
                                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                                .suggests(ITEM_SUGGESTIONS)
                                                .executes(ctx -> ExportCommand.executeSingle(
                                                        ctx,
                                                        ResourceLocationArgument.getId(ctx, "item").toString()
                                                ))
                                        )
                                )
                                .then(Commands.literal("category")
                                        .then(Commands.argument("category", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                        new String[]{"Trivial", "Simple", "Moderate", "Complex",
                                                                "Difficult", "Expert", "Master", "Mythical",
                                                                "Transcendent", "Eternal"},
                                                        builder
                                                ))
                                                .executes(ctx -> ExportCommand.executeCategory(
                                                        ctx,
                                                        StringArgumentType.getString(ctx, "category")
                                                ))
                                        )
                                )
                                .then(Commands.literal("top")
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 1000))
                                                .executes(ctx -> ExportCommand.executeTop(
                                                        ctx,
                                                        IntegerArgumentType.getInteger(ctx, "count")
                                                ))
                                        )
                                )
                                .then(Commands.literal("csv")
                                        .executes(ExportCommand::executeCSV)
                                )
                        )
                        .then(ChunkCommands.register())
                        .then(ChunkCommands.register())
        );
        ComplexityAnalyzer.LOGGER.info("Registered /complexity command");
    }

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

    private static int executeStatus(CommandContext<CommandSourceStack> context) {
        AnalysisEngine engine = DatapackSyncHandler.getEngine();
        if (engine == null) {
            context.getSource().sendFailure(Component.literal("§cEngine not initialized."));
            return 0;
        }
        AnalysisEngine.State state = engine.getCurrentState();
        context.getSource().sendSuccess(() -> Component.literal("§a=== Complexity Analyzer Status ==="), false);
        context.getSource().sendSuccess(() -> Component.literal("§7Engine State: §f" + state), false);

        if (engine.isReady()) {
            var stats = engine.getStats();
            context.getSource().sendSuccess(() -> Component.literal("§7Items with recipes: §f" + stats.itemCount()), false);
            context.getSource().sendSuccess(() -> Component.literal("§7Total recipes: §f" + stats.recipeCount()), false);
        }
        return 1;
    }

    private static int executeReload(CommandContext<CommandSourceStack> context) {
        AnalysisEngine engine = AnalysisEngine.getInstance();
        if (engine == null) {
            context.getSource().sendFailure(Component.literal("§cEngine not initialized."));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("§eReloading all analysis systems in the background..."), true);
        engine.reloadAsync(context.getSource().getLevel());
        return 1;
    }

    private static int executeTps(CommandContext<CommandSourceStack> context) {
        MinecraftServer server = context.getSource().getServer();

        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0D;
        double tps = 1000.0 / Math.max(50.0, mspt);
        double finalTps = Math.min(20.0, tps);

        ChatFormatting tpsColor = tps >= 19.0 ? ChatFormatting.GREEN : (tps >= 16.0 ? ChatFormatting.YELLOW : ChatFormatting.RED);
        ChatFormatting msptColor = mspt <= 40.0 ? ChatFormatting.GREEN : (mspt <= 50.0 ? ChatFormatting.YELLOW : ChatFormatting.RED);

        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / 1024 / 1024;
        long totalMemory = runtime.totalMemory() / 1024 / 1024;
        long freeMemory = runtime.freeMemory() / 1024 / 1024;
        long usedMemory = totalMemory - freeMemory;

        double avgPing = server.getPlayerList().getPlayers().stream()
                .mapToInt(player -> player.connection.latency())
                .average()
                .orElse(0.0);
        ChatFormatting pingColor = avgPing < 100 ? ChatFormatting.GREEN : (avgPing < 200 ? ChatFormatting.YELLOW : ChatFormatting.RED);

        context.getSource().sendSuccess(() -> Component.literal("§6§l=== Server Performance ==="), false);
        context.getSource().sendSuccess(() -> Component.literal(""), false);

        context.getSource().sendSuccess(() -> Component.literal("§e[Tick Performance]§r ")
                .append(Component.literal("TPS: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.format("%.2f", finalTps)).withStyle(tpsColor))
                .append(Component.literal(" MSPT: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.format("%.2f", mspt)).withStyle(msptColor)), false);

        context.getSource().sendSuccess(() -> Component.literal("§e[Memory Usage (MB)]§r ")
                .append(Component.literal("Used: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(usedMemory)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" / Total: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(totalMemory)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" / Max: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(maxMemory)).withStyle(ChatFormatting.RED)), false);

        context.getSource().sendSuccess(() -> Component.literal("§e[Network]§r ")
                .append(Component.literal("Players: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(server.getPlayerCount())).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" Avg Ping: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.format("%.0fms", avgPing)).withStyle(pingColor)), false);

        return 1;
    }

    private static int executeStats(CommandContext<CommandSourceStack> context) {
        AnalysisEngine engine = AnalysisEngine.getInstance();
        if (!engine.isReady()) {
            context.getSource().sendFailure(Component.literal("§cEngine is not ready! Current state: " + engine.getCurrentState()));
            return 0;
        }
        var stats = engine.getStats();
        context.getSource().sendSuccess(() -> Component.literal("§a=== Detailed Statistics ==="), false);
        context.getSource().sendSuccess(() -> Component.literal("§e[Graph]"), false);
        context.getSource().sendSuccess(() -> Component.literal("§7  Items: §f" + stats.itemCount()), false);
        context.getSource().sendSuccess(() -> Component.literal("§7  Recipes: §f" + stats.recipeCount()), false);
        context.getSource().sendSuccess(() -> Component.literal("§e[Base Resources]"), false);
        context.getSource().sendSuccess(() -> Component.literal("§7  Cached items: §f" + stats.baseResourceCount()), false);
        return 1;
    }
}