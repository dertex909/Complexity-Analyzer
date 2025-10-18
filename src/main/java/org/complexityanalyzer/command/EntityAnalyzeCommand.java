package org.complexityanalyzer.command;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import org.complexityanalyzer.analyzer.resource.data.MobDropData;
import org.complexityanalyzer.analyzer.resource.providers.MobPropertyProvider;
import org.complexityanalyzer.analyzer.resource.sources.MobDropSource;
import org.complexityanalyzer.core.AnalysisEngine;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class EntityAnalyzeCommand {

    public static int execute(CommandContext<CommandSourceStack> context, ResourceLocation entityId) {
        CommandSourceStack source = context.getSource();
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            source.sendFailure(Component.literal("§cAnalysis engine is not ready yet!"));
            return 0;
        }

        Optional<EntityType<?>> entityTypeOpt = BuiltInRegistries.ENTITY_TYPE.getOptional(entityId);
        if (entityTypeOpt.isEmpty()) {
            source.sendFailure(Component.literal("§cEntity type not found: " + entityId));
            return 0;
        }
        EntityType<?> entityType = entityTypeOpt.get();

        Optional<MobPropertyProvider> mobProviderOpt = engine.getMobPropertyProvider();
        Optional<MobDropSource> mobDropSourceOpt = engine.getMobDropSource();

        if (mobProviderOpt.isEmpty()) {
            source.sendFailure(Component.literal("§cMobPropertyProvider is not initialized."));
            return 0;
        }

        Optional<MobPropertyProvider.MobProperties> propsOpt = mobProviderOpt.get().getProperties(entityType);
        if (propsOpt.isEmpty()) {
            source.sendFailure(Component.literal("§cEntity is not a living entity or could not be analyzed: " + entityId));
            return 0;
        }

        List<MobDropData> drops = mobDropSourceOpt.map(mds -> mds.getDropsForEntity(entityType)).orElse(List.of());

        displayAnalysis(source, entityType, propsOpt.get(), drops);
        return 1;
    }

    private static void displayAnalysis(CommandSourceStack source, EntityType<?> type, MobPropertyProvider.MobProperties props, List<MobDropData> drops) {
        String entityName = type.getDescription().getString();
        double survivability = props.maxHealth() * (1 + props.armor() / 5.0);
        double threat = 1 + Math.log1p(props.attackDamage());
        double combatPower = survivability * threat;

        source.sendSuccess(() -> Component.literal("§6§l=== Mob Analysis ==="), false);
        source.sendSuccess(() -> Component.literal("§7Entity: §f" + entityName), false);
        source.sendSuccess(() -> Component.literal(""), false);

        source.sendSuccess(() -> Component.literal("§e[Base Stats]"), false);
        source.sendSuccess(() -> Component.literal(String.format("§7  Max Health: §a%.1f", props.maxHealth())), false);
        source.sendSuccess(() -> Component.literal(String.format("§7  Attack Damage: §c%.1f", props.attackDamage())), false);
        source.sendSuccess(() -> Component.literal(String.format("§7  Armor: §9%.1f", props.armor())), false);
        source.sendSuccess(() -> Component.literal("§7  Category: §f" + props.classification().getName()), false);
        source.sendSuccess(() -> Component.literal("§7  Is Boss: §f" + (props.isBoss() ? "§cYes" : "No")), false);
        source.sendSuccess(() -> Component.literal(""), false);

        source.sendSuccess(() -> Component.literal("§e[Calculated Factors]"), false);
        source.sendSuccess(() -> Component.literal(String.format("§7  Survivability Factor: §a%.2f", survivability)), false);
        source.sendSuccess(() -> Component.literal(String.format("§7  Threat Factor: §c%.2f", threat)), false);
        source.sendSuccess(() -> Component.literal(String.format("§7  §lCombat Power: §e§l%.2f", combatPower)), false);
        source.sendSuccess(() -> Component.literal(""), false);

        source.sendSuccess(() -> Component.literal("§e[Notable Drops]"), false);
        if (drops.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7  No significant drops found."), false);
        } else {
            drops.sort(Comparator.comparingDouble(MobDropData::averageYield).reversed());

            for (MobDropData drop : drops) {
                String itemName = drop.item().getDescription().getString();
                source.sendSuccess(() -> Component.literal(String.format("§7  - §f%s: §a~%.2f per kill", itemName, drop.averageYield())), false);
            }
        }
    }
}