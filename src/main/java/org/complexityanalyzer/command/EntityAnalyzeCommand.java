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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.command;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import org.complexityanalyzer.analyzer.resource.data.MobDropData;
import org.complexityanalyzer.analyzer.resource.providers.MobPropertyProvider;
import org.complexityanalyzer.analyzer.resource.sources.MobDropSource;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.core.GameRegistryManager;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;

import java.util.Comparator;

public class EntityAnalyzeCommand {

    public static int execute(CommandContext<CommandSourceStack> context, ResourceLocation entityId) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source, Component.literal("⚠ Analysis engine is not ready yet!"));
            return 0;
        }

        EntityType<?> entityType = GameRegistryManager.getEntityType(entityId);
        if (entityType == EntityType.PIG && !entityId.equals(ResourceLocation.parse("minecraft:pig"))) {
            output.sendFailure(source, Component.literal("❌ Entity type not found: ")
                    .append(Component.literal(entityId.toString()).withStyle(ChatFormatting.YELLOW)));
            return 0;
        }

        MobPropertyProvider mobProvider = engine.getMobPropertyProvider();
        MobDropSource mobDropSource = engine.getMobDropSource();

        if (mobProvider == null) {
            output.sendFailure(source, Component.literal("⚠ MobPropertyProvider is not initialized!"));
            return 0;
        }

        MobPropertyProvider.MobProperties props = mobProvider.getProperties(entityType);
        if (props == null) {
            output.sendFailure(source, Component.literal("⚠ This entity cannot be analyzed"));
            output.sendTip(source, "Entity might not be a living creature: " + entityId);
            return 0;
        }

        ObjectList<MobDropData> drops = (mobDropSource != null) ? mobDropSource.getDropsForEntity(entityType) : new ObjectArrayList<>();

        displayAnalysis(source, entityType, props, drops, mobProvider, output);
        return 1;
    }

    private static void displayAnalysis(
            CommandSourceStack source,
            EntityType<?> type,
            MobPropertyProvider.MobProperties props,
            ObjectList<MobDropData> drops,
            MobPropertyProvider mobProvider,
            OutputManager output
    ) {
        String entityName = type.getDescription().getString();

        double survivability = props.calculateSurvivability();
        double threat = props.calculateThreat();
        double combatPower = props.calculateCombatPower();

        String mobIcon = getMobIcon(type, props, mobProvider);
        output.sendEmptyLine(source);
        output.sendHeader(source, mobIcon, "Mob Analysis", ChatFormatting.RED);
        output.sendEmptyLine(source);

        output.sendEntry(source, "👤", "Entity", entityName, ChatFormatting.GRAY, ChatFormatting.WHITE);
        output.sendEntry(source, "🗂", "Category", props.classification().getName(), ChatFormatting.GRAY, getCategoryColor(props.classification().getName()));

        output.sendEmptyLine(source);

        displayBaseStats(source, props, output);

        displayCalculatedFactors(source, survivability, threat, combatPower, output);

        displayDifficultyRating(source, type, combatPower, mobProvider, output);

        displayDrops(source, drops, output);

        output.sendEmptyLine(source);
        output.sendFooter(source);
    }

    private static void displayBaseStats(
            CommandSourceStack source,
            MobPropertyProvider.MobProperties props,
            OutputManager output
    ) {
        output.sendStatusLine(source, "❤", "Base Stats", ChatFormatting.RED);

        double health = props.maxHealth();
        ChatFormatting healthColor = getHealthColor(health);
        output.sendSubEntry(source, "❤", "Max Health", String.format("%.1f", health), ChatFormatting.GRAY, healthColor);
        output.sendValueBar(source, (int) Math.min(100, health), ChatFormatting.DARK_GRAY, "", ChatFormatting.WHITE);

        double attack = props.attackDamage();
        ChatFormatting attackColor = getAttackColor(attack);
        output.sendSubEntry(source, "⚔", "Attack Damage", String.format("%.1f", attack), ChatFormatting.GRAY, attackColor);
        output.sendValueBar(source, (int) Math.min(100, attack * 5), ChatFormatting.DARK_GRAY, "", ChatFormatting.WHITE);

        double armor = props.armor();
        ChatFormatting armorColor = getArmorColor(armor);
        output.sendSubEntry(source, "🛡", "Armor", String.format("%.1f", armor), ChatFormatting.GRAY, armorColor);
        if (armor > 0) {
            output.sendValueBar(source, (int) Math.min(100, armor * 5), ChatFormatting.DARK_GRAY, "", ChatFormatting.WHITE);
        }

        output.sendEmptyLine(source);
    }

    private static void displayCalculatedFactors(
            CommandSourceStack source,
            double survivability,
            double threat,
            double combatPower,
            OutputManager output
    ) {
        output.sendStatusLine(source, "⚡", "Combat Analysis", ChatFormatting.GOLD);

        ChatFormatting survColor = getFactorColor(survivability, 50.0);
        output.sendSubEntry(source, "🛡", "Survivability", String.format("%.2f", survivability), ChatFormatting.GRAY, survColor);

        ChatFormatting threatColor = getFactorColor(threat, 5.0);
        output.sendSubEntry(source, "⚠", "Threat Level", String.format("%.2f", threat), ChatFormatting.GRAY, threatColor);

        ChatFormatting powerColor = getCombatPowerColor(combatPower);
        output.sendSubEntry(source, "⚔", "Combat Power", String.format("%.2f", combatPower), ChatFormatting.GRAY, powerColor);

        output.sendValueBar(source, (int) Math.min(100, combatPower * 0.5), ChatFormatting.DARK_GRAY, "", ChatFormatting.WHITE);

        output.sendEmptyLine(source);
    }

    private static void displayDifficultyRating(
            CommandSourceStack source,
            EntityType<?> type,
            double combatPower,
            MobPropertyProvider mobProvider,
            OutputManager output
    ) {
        String difficulty;
        String difficultyIcon;
        ChatFormatting difficultyColor;

        if (mobProvider.isBoss(type)) {
            difficulty = "BOSS";
            difficultyIcon = "👑";
            difficultyColor = ChatFormatting.DARK_PURPLE;
        } else if (mobProvider.isMiniBoss(type)) {
            difficulty = "MINI-BOSS";
            difficultyIcon = "⭐";
            difficultyColor = ChatFormatting.LIGHT_PURPLE;
        } else if (combatPower > 500) {
            difficulty = "EXTREME";
            difficultyIcon = "💀";
            difficultyColor = ChatFormatting.DARK_RED;
        } else if (combatPower > 200) {
            difficulty = "HARD";
            difficultyIcon = "🔥";
            difficultyColor = ChatFormatting.RED;
        } else if (combatPower > 100) {
            difficulty = "MEDIUM";
            difficultyIcon = "⚠";
            difficultyColor = ChatFormatting.GOLD;
        } else if (combatPower > 50) {
            difficulty = "EASY";
            difficultyIcon = "✓";
            difficultyColor = ChatFormatting.YELLOW;
        } else {
            difficulty = "TRIVIAL";
            difficultyIcon = "◆";
            difficultyColor = ChatFormatting.GREEN;
        }

        output.sendEntry(source, "📊", "Difficulty Rating", difficultyIcon + " " + difficulty, ChatFormatting.AQUA, difficultyColor);

        String recommendation = switch (difficulty) {
            case "BOSS" -> "Prepare thoroughly! Boss encounter.";
            case "MINI-BOSS" -> "Elite enemy! Strong gear recommended.";
            case "EXTREME" -> "Extreme danger! Full gear recommended.";
            case "HARD" -> "Dangerous! Good equipment needed.";
            case "MEDIUM" -> "Moderate threat. Stay cautious.";
            case "EASY" -> "Manageable with basic gear.";
            default -> "Low threat. Safe for beginners.";
        };

        output.sendTip(source, recommendation);
        output.sendEmptyLine(source);
    }

    private static void displayDrops(
            CommandSourceStack source,
            ObjectList<MobDropData> drops,
            OutputManager output
    ) {
        output.sendStatusLine(source, "💎", "Notable Drops", ChatFormatting.GREEN);

        if (drops.isEmpty()) {
            output.sendTip(source, "No significant drops recorded");
        } else {
            drops.sort(Comparator.comparingDouble(MobDropData::averageYield).reversed());

            for (MobDropData drop : drops) {
                String itemName = drop.item().getDescription().getString();
                double yield = drop.averageYield();

                String rarityIcon = getRarityIcon(yield);
                ChatFormatting rarityColor = getRarityColor(yield);

                output.sendSubEntry(source, rarityIcon, itemName, String.format("~%.2f per kill", yield), ChatFormatting.WHITE, rarityColor);
            }

            output.sendEmptyLine(source);
            output.sendTip(source, "Higher yield = more common drop");
        }
    }

    private static String getMobIcon(
            EntityType<?> type,
            MobPropertyProvider.MobProperties props,
            MobPropertyProvider mobProvider
    ) {
        if (mobProvider.isBoss(type)) return "👑";
        if (mobProvider.isMiniBoss(type)) return "⭐";

        String category = props.classification().getName().toLowerCase();
        return switch (category) {
            case "monster", "hostile" -> "⚔";
            case "creature", "passive" -> "🐾";
            case "ambient" -> "🦋";
            case "water_creature" -> "🐟";
            default -> "👾";
        };
    }

    private static ChatFormatting getCategoryColor(String category) {
        return switch (category.toLowerCase()) {
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