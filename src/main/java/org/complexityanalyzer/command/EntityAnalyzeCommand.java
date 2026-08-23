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
import net.minecraft.ChatFormatting;
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

import java.util.Comparator;

public class EntityAnalyzeCommand {

    public static int execute(CommandContext<CommandSourceStack> context, ResourceLocation entityId) {
        var source = context.getSource();
        var output = new OutputManager(source.getServer());
        var engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.analyze.not_ready"));
            return 0;
        }

        var entityType = GameRegistryManager.getEntityType(entityId);
        if (entityType == EntityType.PIG && !entityId.equals(ResourceLocation.parse("minecraft:pig"))) {
            output.sendFailure(source, Component.translatable("complexityanalyzer.command.entity.not_found", entityId.toString()));
            return 0;
        }

        var mobProvider = engine.getMobPropertyProvider();
        var mobDropSource = engine.getMobDropSource();

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

        var drops = (mobDropSource != null) ? mobDropSource.getDropsForEntity(entityType) : new ObjectArrayList<MobDropData>();

        displayAnalysis(source, entityType, props, drops, output);
        return 1;
    }

    private static void displayAnalysis(CommandSourceStack source, EntityType<?> type, MobDifficultyCategory.MobProperties props, ObjectList<MobDropData> drops, OutputManager output) {
        var entityName = type.getDescription();

        double survivability = props.calculateSurvivability();
        double threat = props.calculateThreat();
        double combatPower = props.calculateCombatPower();

        output.sendEmptyLine(source);
        output.sendHeader(source, props.getMobIcon(), "complexityanalyzer.command.entity.header", ChatFormatting.RED);
        output.sendEmptyLine(source);
        output.sendEntry(source, "👤", "complexityanalyzer.command.entity.entity_label", entityName, ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendEntry(source, "🗂", "complexityanalyzer.command.entity.category_label", props.classification().getName(), ChatFormatting.GRAY, props.getCategoryColor());
        output.sendEmptyLine(source);

        displayBaseStats(source, props, output);
        displayCalculatedFactors(source, props, survivability, threat, combatPower, output);
        displayDifficultyRating(source, props, output);
        displayDrops(source, drops, output);

        output.sendEmptyLine(source);
        output.sendFooter(source);
    }

    private static void displayBaseStats(CommandSourceStack source, MobDifficultyCategory.MobProperties props, OutputManager output) {
        output.sendStatusLine(source, "❤", "complexityanalyzer.command.entity.stats_section", ChatFormatting.RED);

        double health = props.maxHealth();
        output.sendSubEntry(source, "❤", "complexityanalyzer.command.entity.health", "%.1f".formatted(health), ChatFormatting.GRAY, props.getHealthColor());
        output.sendValueBar(source, (int) Math.min(100, health), ChatFormatting.DARK_GRAY, "", ChatFormatting.WHITE);

        double attack = props.attackDamage();
        output.sendSubEntry(source, "⚔", "complexityanalyzer.command.entity.attack", "%.1f".formatted(attack), ChatFormatting.GRAY, props.getAttackColor());
        output.sendValueBar(source, (int) Math.min(100, attack * 5), ChatFormatting.DARK_GRAY, "", ChatFormatting.WHITE);

        double armor = props.armor();
        output.sendSubEntry(source, "🛡", "complexityanalyzer.command.entity.armor", "%.1f".formatted(armor), ChatFormatting.GRAY, props.getArmorColor());
        if (armor > 0) {
            output.sendValueBar(source, (int) Math.min(100, armor * 5), ChatFormatting.DARK_GRAY, "", ChatFormatting.WHITE);
        }
        output.sendEmptyLine(source);
    }

    private static void displayCalculatedFactors(CommandSourceStack source, MobDifficultyCategory.MobProperties props, double survivability, double threat, double combatPower, OutputManager output) {
        output.sendStatusLine(source, "⚡", "complexityanalyzer.command.entity.combat_section", ChatFormatting.GOLD);

        output.sendSubEntry(source, "🛡", "complexityanalyzer.command.entity.survivability", "%.2f".formatted(survivability), ChatFormatting.GRAY, MobDifficultyCategory.MobProperties.factorColor(survivability, 50.0));
        output.sendSubEntry(source, "⚠", "complexityanalyzer.command.entity.threat", "%.2f".formatted(threat), ChatFormatting.GRAY, MobDifficultyCategory.MobProperties.factorColor(threat, 5.0));
        output.sendSubEntry(source, "⚔", "complexityanalyzer.command.entity.combat_power", "%.2f".formatted(combatPower), ChatFormatting.GRAY, props.getCombatPowerColor());

        output.sendValueBar(source, (int) Math.min(100, combatPower * 0.5), ChatFormatting.DARK_GRAY, "", ChatFormatting.WHITE);
        output.sendEmptyLine(source);
    }

    private static void displayDifficultyRating(CommandSourceStack source, MobDifficultyCategory.MobProperties props, OutputManager output) {
        var tier = props.difficultyCategory();

        output.sendEntry(source, "📊", "complexityanalyzer.command.entity.rating_label", Component.literal(tier.getIcon() + " ")
                .append(Component.translatable("complexityanalyzer.command.entity.rating." + tier.getKey())), ChatFormatting.AQUA, tier.getColor());

        output.sendTip(source, "complexityanalyzer.command.entity.recommendation." + tier.getKey());
        output.sendEmptyLine(source);
    }

    private static void displayDrops(CommandSourceStack source, ObjectList<MobDropData> drops, OutputManager output) {
        output.sendStatusLine(source, "💎", "complexityanalyzer.command.entity.drops_section", ChatFormatting.GREEN);

        if (drops.isEmpty()) {
            output.sendTip(source, "complexityanalyzer.command.entity.no_drops");
        } else {
            drops.sort(Comparator.comparingDouble(MobDropData::averageYield).reversed());
            var engine = AnalysisEngine.getInstance();

            for (var drop : drops) {
                var itemName = drop.item().getDescription();
                double yield = drop.averageYield();
                var result = engine.getComplexityResult(drop.item());
                var category = (result != null && result.isValid()) ? result.getCategory() : ComplexityCategory.UNCALCULABLE;
                var perKill = Component.translatable("complexityanalyzer.command.entity.per_kill", "%.2f".formatted(yield));
                output.sendSubEntry(source, category.getIcon(), itemName, perKill, ChatFormatting.WHITE, category.getColor());
            }

            output.sendEmptyLine(source);
            output.sendTip(source, "complexityanalyzer.command.entity.yield_tip");
        }
    }
}