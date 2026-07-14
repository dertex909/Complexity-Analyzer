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
import org.complexityanalyzer.analyzer.resource.data.MobDropData;
import org.complexityanalyzer.analyzer.resource.providers.MobPropertyProvider;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.GameRegistryManager;

import java.util.Comparator;

import static java.util.Locale.ROOT;

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

        displayAnalysis(source, entityType, props, drops, mobProvider, output);
        return 1;
    }

    private static void displayAnalysis(CommandSourceStack source, EntityType<?> type,
                                        MobPropertyProvider.MobProperties props, ObjectList<MobDropData> drops,
                                        MobPropertyProvider mobProvider, OutputManager output) {
        var entityName = type.getDescription();

        double survivability = props.calculateSurvivability();
        double threat = props.calculateThreat();
        double combatPower = props.calculateCombatPower();

        String mobIcon = getMobIcon(type, props, mobProvider);
        output.sendEmptyLine(source);
        output.sendHeader(source, mobIcon, "complexityanalyzer.command.entity.header", ChatFormatting.RED);
        output.sendEmptyLine(source);
        output.sendEntry(source, "👤", "complexityanalyzer.command.entity.entity_label", entityName, ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendEntry(source, "🗂", "complexityanalyzer.command.entity.category_label", props.classification().getName(), ChatFormatting.GRAY, getCategoryColor(props.classification().getName()));
        output.sendEmptyLine(source);

        displayBaseStats(source, props, output);
        displayCalculatedFactors(source, survivability, threat, combatPower, output);
        displayDifficultyRating(source, type, combatPower, mobProvider, output);
        displayDrops(source, drops, output);

        output.sendEmptyLine(source);
        output.sendFooter(source);
    }

    private static void displayBaseStats(CommandSourceStack source, MobPropertyProvider.MobProperties props, OutputManager output) {
        output.sendStatusLine(source, "❤", "complexityanalyzer.command.entity.stats_section", ChatFormatting.RED);

        double health = props.maxHealth();
        var healthColor = getHealthColor(health);
        output.sendSubEntry(source, "❤", "complexityanalyzer.command.entity.health", String.format("%.1f", health), ChatFormatting.GRAY, healthColor);
        output.sendValueBar(source, (int) Math.min(100, health), ChatFormatting.DARK_GRAY, "", ChatFormatting.WHITE);

        double attack = props.attackDamage();
        var attackColor = getAttackColor(attack);
        output.sendSubEntry(source, "⚔", "complexityanalyzer.command.entity.attack", String.format("%.1f", attack), ChatFormatting.GRAY, attackColor);
        output.sendValueBar(source, (int) Math.min(100, attack * 5), ChatFormatting.DARK_GRAY, "", ChatFormatting.WHITE);

        double armor = props.armor();
        var armorColor = getArmorColor(armor);
        output.sendSubEntry(source, "🛡", "complexityanalyzer.command.entity.armor", String.format("%.1f", armor), ChatFormatting.GRAY, armorColor);
        if (armor > 0) output.sendValueBar(
                source, (int) Math.min(100, armor * 5), ChatFormatting.DARK_GRAY, "", ChatFormatting.WHITE);

        output.sendEmptyLine(source);
    }

    private static void displayCalculatedFactors(CommandSourceStack source, double survivability, double threat,
                                                 double combatPower, OutputManager output) {
        output.sendStatusLine(source, "⚡", "complexityanalyzer.command.entity.combat_section", ChatFormatting.GOLD);

        var survColor = getFactorColor(survivability, 50.0);
        output.sendSubEntry(source, "🛡", "complexityanalyzer.command.entity.survivability", String.format("%.2f", survivability), ChatFormatting.GRAY, survColor);

        var threatColor = getFactorColor(threat, 5.0);
        output.sendSubEntry(source, "⚠", "complexityanalyzer.command.entity.threat", String.format("%.2f", threat), ChatFormatting.GRAY, threatColor);

        var powerColor = getCombatPowerColor(combatPower);
        output.sendSubEntry(source, "⚔", "complexityanalyzer.command.entity.combat_power", String.format("%.2f", combatPower), ChatFormatting.GRAY, powerColor);

        output.sendValueBar(source, (int) Math.min(100, combatPower * 0.5), ChatFormatting.DARK_GRAY, "", ChatFormatting.WHITE);
        output.sendEmptyLine(source);
    }

    private static void displayDifficultyRating(CommandSourceStack source, EntityType<?> type, double combatPower,
                                                MobPropertyProvider mobProvider, OutputManager output) {
        String difficulty;
        String difficultyIcon;
        ChatFormatting difficultyColor;

        if (mobProvider.isBoss(type)) {
            difficulty = "boss";
            difficultyIcon = "👑";
            difficultyColor = ChatFormatting.DARK_PURPLE;
        } else if (mobProvider.isMiniBoss(type)) {
            difficulty = "mini_boss";
            difficultyIcon = "⭐";
            difficultyColor = ChatFormatting.LIGHT_PURPLE;
        } else if (combatPower > 500) {
            difficulty = "extreme";
            difficultyIcon = "💀";
            difficultyColor = ChatFormatting.DARK_RED;
        } else if (combatPower > 200) {
            difficulty = "hard";
            difficultyIcon = "🔥";
            difficultyColor = ChatFormatting.RED;
        } else if (combatPower > 100) {
            difficulty = "medium";
            difficultyIcon = "⚠";
            difficultyColor = ChatFormatting.GOLD;
        } else if (combatPower > 50) {
            difficulty = "easy";
            difficultyIcon = "✓";
            difficultyColor = ChatFormatting.YELLOW;
        } else {
            difficulty = "trivial";
            difficultyIcon = "◆";
            difficultyColor = ChatFormatting.GREEN;
        }

        output.sendEntry(source, "📊", "complexityanalyzer.command.entity.rating_label", Component.literal(difficultyIcon + " ")
                .append(Component.translatable("complexityanalyzer.command.entity.rating." + difficulty)), ChatFormatting.AQUA, difficultyColor);

        output.sendTip(source, "complexityanalyzer.command.entity.recommendation." + difficulty);
        output.sendEmptyLine(source);
    }

    private static void displayDrops(CommandSourceStack source, ObjectList<MobDropData> drops, OutputManager output) {
        output.sendStatusLine(source, "💎", "complexityanalyzer.command.entity.drops_section", ChatFormatting.GREEN);

        if (drops.isEmpty()) {
            output.sendTip(source, "complexityanalyzer.command.entity.no_drops");
        } else {
            drops.sort(Comparator.comparingDouble(MobDropData::averageYield).reversed());

            for (MobDropData drop : drops) {
                Component itemName = drop.item().getDescription();
                double yield = drop.averageYield();
                String rarityIcon = getRarityIcon(yield);
                ChatFormatting rarityColor = getRarityColor(yield);
                output.sendSubEntry(source, rarityIcon, itemName, Component.translatable("complexityanalyzer.command.entity.per_kill",
                        String.format("%.2f", yield)), ChatFormatting.WHITE, rarityColor);
            }

            output.sendEmptyLine(source);
            output.sendTip(source, "complexityanalyzer.command.entity.yield_tip");
        }
    }

    private static String getMobIcon(EntityType<?> type, MobPropertyProvider.MobProperties props,
                                     MobPropertyProvider mobProvider) {
        if (mobProvider.isBoss(type)) return "👑";
        if (mobProvider.isMiniBoss(type)) return "⭐";

        String category = props.classification().getName().toLowerCase(ROOT);
        return switch (category) {
            case "monster", "hostile" -> "⚔";
            case "creature", "passive" -> "🐾";
            case "ambient" -> "🦋";
            case "water_creature" -> "🐟";
            default -> "👾";
        };
    }

    private static ChatFormatting getCategoryColor(String category) {
        return switch (category.toLowerCase(ROOT)) {
            case "monster", "hostile" -> ChatFormatting.RED;
            case "creature", "passive" -> ChatFormatting.GREEN;
            case "ambient" -> ChatFormatting.AQUA;
            case "water_creature" -> ChatFormatting.BLUE;
            default -> ChatFormatting.WHITE;
        };
    }

    private static ChatFormatting getHealthColor(double health) {
        if (health >= 100) return ChatFormatting.DARK_RED;
        if (health >= 50) return ChatFormatting.RED;
        if (health >= 20) return ChatFormatting.GOLD;
        return ChatFormatting.GREEN;
    }

    private static ChatFormatting getAttackColor(double attack) {
        if (attack >= 20) return ChatFormatting.DARK_RED;
        if (attack >= 10) return ChatFormatting.RED;
        if (attack >= 5) return ChatFormatting.GOLD;
        return ChatFormatting.YELLOW;
    }

    private static ChatFormatting getArmorColor(double armor) {
        if (armor >= 15) return ChatFormatting.DARK_AQUA;
        if (armor >= 10) return ChatFormatting.AQUA;
        if (armor >= 5) return ChatFormatting.BLUE;
        return ChatFormatting.GRAY;
    }

    private static ChatFormatting getFactorColor(double value, double threshold) {
        if (value >= threshold * 2) return ChatFormatting.DARK_RED;
        if (value >= threshold) return ChatFormatting.RED;
        if (value >= threshold / 2) return ChatFormatting.GOLD;
        return ChatFormatting.YELLOW;
    }

    private static ChatFormatting getCombatPowerColor(double power) {
        if (power >= 500) return ChatFormatting.DARK_RED;
        if (power >= 200) return ChatFormatting.RED;
        if (power >= 100) return ChatFormatting.GOLD;
        if (power >= 50) return ChatFormatting.YELLOW;
        return ChatFormatting.GREEN;
    }

    private static String getRarityIcon(double yield) {
        if (yield >= 2.0) return "🟢";
        if (yield >= 1.0) return "🟡";
        if (yield >= 0.5) return "🟠";
        if (yield >= 0.1) return "🔵";
        return "🟣";
    }

    private static ChatFormatting getRarityColor(double yield) {
        if (yield >= 2.0) return ChatFormatting.GREEN;
        if (yield >= 1.0) return ChatFormatting.YELLOW;
        if (yield >= 0.5) return ChatFormatting.GOLD;
        if (yield >= 0.1) return ChatFormatting.AQUA;
        return ChatFormatting.LIGHT_PURPLE;
    }
}