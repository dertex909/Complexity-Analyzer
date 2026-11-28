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

package org.complexityanalyzer.command;

import com.mojang.brigadier.context.CommandContext;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.data.ComplexityCategory;
import org.complexityanalyzer.data.MobDifficultyCategory;
import org.complexityanalyzer.resource.data.MobDropData;
import org.complexityanalyzer.resource.sources.MobDropSource;
import org.complexityanalyzer.util.ComplexityComparators;

import static net.minecraft.ChatFormatting.*;

public final class EntityAnalyzeCommand {
    private static final ResourceLocation PIG_ID = ResourceLocation.withDefaultNamespace("pig");

    private EntityAnalyzeCommand() {
    }

    public static int execute(CommandContext<CommandSourceStack> context, ResourceLocation entityId) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.analyze.not_ready"));
            return 0;
        }

        var entityType = GameRegistryManager.getEntityType(entityId);
        if (entityType == null || (entityType == EntityType.PIG && !entityId.equals(PIG_ID))) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.entity.not_found", entityId.toString()));
            return 0;
        }

        var mobProvider = engine.getMobPropertyProvider();
        if (mobProvider == null) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.entity.provider_not_initialized"));
            return 0;
        }

        var props = mobProvider.getProperties(entityType);
        if (props == null) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.entity.not_analyzable"));
            output.sendTip(source, "complexityanalyzer.command.entity.not_living", entityId.toString());
            return 0;
        }

        var mobDropSource = engine.getSourceByType(MobDropSource.class);
        var drops = (mobDropSource != null) ? mobDropSource.getDropsForEntity(entityType) : ObjectLists.<MobDropData>emptyList();

        displayAnalysis(source, entityType, props, drops, engine, output);
        return 1;
    }

    private static void displayAnalysis(CommandSourceStack source, EntityType<?> type, MobDifficultyCategory.MobProperties props,
                                        ObjectList<MobDropData> drops, AnalysisEngine engine, OutputManager output) {
        output.sendEmptyLine(source);
        output.sendHeader(source, props.getMobIcon(), "complexityanalyzer.command.entity.header", RED);
        output.sendEmptyLine(source);
        output.sendEntry(source, "👤", "complexityanalyzer.command.entity.entity_label", type.getDescription(), GRAY, WHITE);
        output.sendEntry(source, "🗂", "complexityanalyzer.command.entity.category_label", props.classification().getName(), GRAY, props.getCategoryColor());
        output.sendEmptyLine(source);

        displayBaseStats(source, props, output);
        displayCalculatedFactors(source, props, output);
        displayDifficultyRating(source, props, output);
        displayDrops(source, drops, engine, output);

        output.sendEmptyLine(source);
        output.sendFooter(source);
    }

    private static void displayBaseStats(CommandSourceStack source, MobDifficultyCategory.MobProperties props, OutputManager output) {
        double health = props.maxHealth();
        double attack = props.attackDamage();
        double armor = props.armor();

        output.sendStatusLine(source, "❤", "complexityanalyzer.command.entity.stats_section", RED);
        output.sendSubEntry(source, "❤", "complexityanalyzer.command.entity.health", "%.1f".formatted(health), GRAY, props.getHealthColor());
        output.sendValueBar(source, (int) Math.clamp(health, 0.0, 100.0), DARK_GRAY, "", WHITE);
        output.sendSubEntry(source, "⚔", "complexityanalyzer.command.entity.attack", "%.1f".formatted(attack), GRAY, props.getAttackColor());
        output.sendValueBar(source, (int) Math.clamp(attack * 5, 0.0, 100.0), DARK_GRAY, "", WHITE);
        output.sendSubEntry(source, "🛡", "complexityanalyzer.command.entity.armor", "%.1f".formatted(armor), GRAY, props.getArmorColor());
        if (armor > 0) output.sendValueBar(source, (int) Math.clamp(armor * 5, 0.0, 100.0), DARK_GRAY, "", WHITE);
        output.sendEmptyLine(source);
    }

    private static void displayCalculatedFactors(CommandSourceStack source, MobDifficultyCategory.MobProperties props, OutputManager output) {
        double survivability = props.calculateSurvivability();
        double threat = props.calculateThreat();
        double combatPower = props.calculateCombatPower();

        output.sendStatusLine(source, "⚡", "complexityanalyzer.command.entity.combat_section", GOLD);
        output.sendSubEntry(source, "🛡", "complexityanalyzer.command.entity.survivability", "%.2f".formatted(survivability), GRAY, MobDifficultyCategory.MobProperties.factorColor(survivability, 50.0));
        output.sendSubEntry(source, "⚠", "complexityanalyzer.command.entity.threat", "%.2f".formatted(threat), GRAY, MobDifficultyCategory.MobProperties.factorColor(threat, 5.0));
        output.sendSubEntry(source, "⚔", "complexityanalyzer.command.entity.combat_power", "%.2f".formatted(combatPower), GRAY, props.getCombatPowerColor());
        output.sendValueBar(source, (int) Math.clamp(combatPower * 0.5, 0.0, 100.0), DARK_GRAY, "", WHITE);
        output.sendEmptyLine(source);
    }

    private static void displayDifficultyRating(CommandSourceStack source, MobDifficultyCategory.MobProperties props, OutputManager output) {
        var tier = props.difficultyCategory();
        var ratingText = Component.literal(tier.getIcon() + " ").append(Component.translatable("complexityanalyzer.command.entity.rating." + tier.getKey()));

        output.sendEntry(source, "📊", "complexityanalyzer.command.entity.rating_label", ratingText, AQUA, tier.getColor());
        output.sendTip(source, "complexityanalyzer.command.entity.recommendation." + tier.getKey());
        output.sendEmptyLine(source);
    }

    private static void displayDrops(CommandSourceStack source, ObjectList<MobDropData> drops, AnalysisEngine engine, OutputManager output) {
        output.sendStatusLine(source, "💎", "complexityanalyzer.command.entity.drops_section", GREEN);

        if (drops.isEmpty()) {
            output.sendTip(source, "complexityanalyzer.command.entity.no_drops");
            return;
        }

        var sortedDrops = new ObjectArrayList<>(drops);
        sortedDrops.sort(ComplexityComparators.MOB_DROP_BY_AVERAGE_YIELD_DESCENDING);

        for (var drop : sortedDrops) {
            var result = engine.getComplexityResult(drop.item());
            var category = (result != null && result.isValid()) ? result.getCategory() : ComplexityCategory.UNCALCULABLE;
            var perKill = Component.translatable("complexityanalyzer.command.entity.per_kill", "%.2f".formatted(drop.averageYield()));
            output.sendSubEntry(source, category.getIcon(), drop.item().getDescription(), perKill, WHITE, category.getColor());
        }

        output.sendEmptyLine(source);
        output.sendTip(source, "complexityanalyzer.command.entity.yield_tip");
    }
}