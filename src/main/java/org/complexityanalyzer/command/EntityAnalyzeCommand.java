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

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import org.complexityanalyzer.analyzer.resource.data.MobDropData;
import org.complexityanalyzer.analyzer.resource.providers.MobPropertyProvider;
import org.complexityanalyzer.analyzer.resource.sources.MobDropSource;
import org.complexityanalyzer.command.util.OutputManager;
import org.complexityanalyzer.core.AnalysisEngine;
import org.complexityanalyzer.registry.GameRegistryManager;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;

import java.util.Comparator;

public class EntityAnalyzeCommand {

    public static int execute(CommandContext<CommandSourceStack> context, ResourceLocation entityId) {
        CommandSourceStack source = context.getSource();
        OutputManager output = new OutputManager(source.getServer());
        AnalysisEngine engine = AnalysisEngine.getInstance();

        if (!engine.isReady()) {
            output.sendFailure(source,
                    Component.literal("⚠ Analysis engine is not ready yet!")
                            .withStyle(ChatFormatting.RED));
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
            output.sendFailure(source, Component.literal("⚠ MobPropertyProvider is not initialized!")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        MobPropertyProvider.MobProperties props = mobProvider.getProperties(entityType);
        if (props == null) {
            output.sendFailure(source, Component.literal("⚠ This entity cannot be analyzed")
                    .withStyle(ChatFormatting.RED));
            output.sendInfo(source, Component.literal("Entity might not be a living creature: " + entityId)
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
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

        output.sendInfo(source, Component.literal(""));
        output.sendInfo(source, Component.literal("═══════════════════════════════")
                .withStyle(ChatFormatting.DARK_GRAY));

        String mobIcon = getMobIcon(type, props, mobProvider);
        output.sendInfo(source, Component.literal(mobIcon + " ").withStyle(ChatFormatting.RED)
                .append(Component.literal("Mob Analysis")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));

        output.sendInfo(source, Component.literal("═══════════════════════════════")
                .withStyle(ChatFormatting.DARK_GRAY));
        output.sendInfo(source, Component.literal(""));

        MutableComponent nameComponent = Component.literal("Entity: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(entityName).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));

        if (mobProvider.isBoss(type)) {
            nameComponent.append(Component.literal(" 👑").withStyle(ChatFormatting.GOLD));
        } else if (mobProvider.isMiniBoss(type)) {
            nameComponent.append(Component.literal(" ⭐").withStyle(ChatFormatting.YELLOW));
        }

        output.sendInfo(source, nameComponent);

        ChatFormatting categoryColor = getCategoryColor(props.classification().getName());
        output.sendInfo(source, Component.literal("Category: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(props.classification().getName())
                        .withStyle(categoryColor, ChatFormatting.BOLD)));

        output.sendInfo(source, Component.literal(""));

        displayBaseStats(source, props, output);

        displayCalculatedFactors(source, survivability, threat, combatPower, output);

        displayDifficultyRating(source, type, combatPower, mobProvider, output);

        displayDrops(source, drops, output);

        output.sendInfo(source, Component.literal("═══════════════════════════════")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void displayBaseStats(
            CommandSourceStack source,
            MobPropertyProvider.MobProperties props,
            OutputManager output
    ) {
        output.sendInfo(source, Component.literal("❤ ").withStyle(ChatFormatting.RED)
                .append(Component.literal("Base Stats")
                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));

        double health = props.maxHealth();
        ChatFormatting healthColor = getHealthColor(health);
        String healthBar = getStatBar(health, 100.0);

        output.sendInfo(source, Component.literal("  ❤ Max Health: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format("%.1f", health)).withStyle(healthColor, ChatFormatting.BOLD)));

        output.sendInfo(source, Component.literal("    " + healthBar).withStyle(ChatFormatting.DARK_GRAY));

        double attack = props.attackDamage();
        ChatFormatting attackColor = getAttackColor(attack);
        String attackBar = getStatBar(attack, 20.0);

        output.sendInfo(source, Component.literal("  ⚔ Attack Damage: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format("%.1f", attack)).withStyle(attackColor, ChatFormatting.BOLD)));

        output.sendInfo(source, Component.literal("    " + attackBar).withStyle(ChatFormatting.DARK_GRAY));

        double armor = props.armor();
        ChatFormatting armorColor = getArmorColor(armor);
        String armorBar = getStatBar(armor, 20.0);

        output.sendInfo(source, Component.literal("  🛡 Armor: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format("%.1f", armor)).withStyle(armorColor, ChatFormatting.BOLD)));

        if (armor > 0) output.sendInfo(source, Component.literal("    " + armorBar)
                .withStyle(ChatFormatting.DARK_GRAY));

        output.sendInfo(source, Component.literal(""));
    }

    private static void displayCalculatedFactors(
            CommandSourceStack source,
            double survivability,
            double threat,
            double combatPower,
            OutputManager output
    ) {
        output.sendInfo(source, Component.literal("⚡ ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Combat Analysis")
                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));

        ChatFormatting survColor = getFactorColor(survivability, 50.0);
        output.sendInfo(source, Component.literal("  🛡 Survivability: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format("%.2f", survivability)).withStyle(survColor)));

        ChatFormatting threatColor = getFactorColor(threat, 5.0);
        output.sendInfo(source, Component.literal("  ⚠ Threat Level: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format("%.2f", threat)).withStyle(threatColor)));

        ChatFormatting powerColor = getCombatPowerColor(combatPower);
        output.sendInfo(source, Component.literal("  ⚔ Combat Power: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format("%.2f", combatPower))
                        .withStyle(powerColor, ChatFormatting.BOLD)));

        String powerBar = getStatBar(combatPower, 200.0);
        output.sendInfo(source, Component.literal("    " + powerBar).withStyle(ChatFormatting.DARK_GRAY));

        output.sendInfo(source, Component.literal(""));
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

        output.sendInfo(source, Component.literal("📊 Difficulty Rating: ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(difficultyIcon + " " + difficulty)
                        .withStyle(difficultyColor, ChatFormatting.BOLD)));

        String recommendation = switch (difficulty) {
            case "BOSS" -> "Prepare thoroughly! Boss encounter.";
            case "MINI-BOSS" -> "Elite enemy! Strong gear recommended.";
            case "EXTREME" -> "Extreme danger! Full gear recommended.";
            case "HARD" -> "Dangerous! Good equipment needed.";
            case "MEDIUM" -> "Moderate threat. Stay cautious.";
            case "EASY" -> "Manageable with basic gear.";
            default -> "Low threat. Safe for beginners.";
        };

        output.sendInfo(source, Component.literal("  💡 " + recommendation)
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));

        output.sendInfo(source, Component.literal(""));
    }

    private static void displayDrops(
            CommandSourceStack source,
            ObjectList<MobDropData> drops,
            OutputManager output
    ) {
        output.sendInfo(source, Component.literal("💎 ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal("Notable Drops")
                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));

        if (drops.isEmpty()) {
            output.sendInfo(source, Component.literal("  No significant drops recorded")
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        } else {
            drops.sort(Comparator.comparingDouble(MobDropData::averageYield).reversed());

            for (MobDropData drop : drops) {
                String itemName = drop.item().getDescription().getString();
                double yield = drop.averageYield();

                String rarityIcon = getRarityIcon(yield);
                ChatFormatting rarityColor = getRarityColor(yield);

                MutableComponent dropComponent = Component.literal("  " + rarityIcon + " ")
                        .withStyle(rarityColor)
                        .append(Component.literal(itemName).withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(": ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(String.format("~%.2f", yield)).withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(" per kill").withStyle(ChatFormatting.GRAY));

                output.sendInfo(source, dropComponent);
            }

            output.sendInfo(source, Component.literal(""));
            output.sendInfo(source, Component.literal("  💡 Tip: Higher yield = more common drop")
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
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

    private static String getStatBar(double value, double max) {
        int percent = (int) Math.min(100, (value / max) * 100);
        int filled = percent / 10;
        return "[" + "██████████".substring(0, filled) + "░░░░░░░░░░".substring(filled) + "]";
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