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

package org.complexityanalyzer.data;

import net.minecraft.ChatFormatting;
import net.minecraft.world.entity.MobCategory;

/**
 * Difficulty tier and threat classification of a mob entity.
 * Single source of truth for entity difficulty categories, visual styling,
 * threat tier resolution, and mob property records.
 */
public enum MobDifficultyCategory {
    BOSS("boss", "👑", ChatFormatting.DARK_PURPLE),
    MINI_BOSS("mini_boss", "⭐", ChatFormatting.LIGHT_PURPLE),
    EXTREME("extreme", "💀", ChatFormatting.DARK_RED),
    HARD("hard", "🔥", ChatFormatting.RED),
    MEDIUM("medium", "⚠", ChatFormatting.GOLD),
    EASY("easy", "✓", ChatFormatting.YELLOW),
    TRIVIAL("trivial", "◆", ChatFormatting.GREEN);

    private final String key;
    private final String icon;
    private final ChatFormatting color;

    MobDifficultyCategory(String key, String icon, ChatFormatting color) {
        this.key = key;
        this.icon = icon;
        this.color = color;
    }

    /**
     * Resolves the {@link MobDifficultyCategory} based on entity boss flags and calculated combat power.
     *
     * @param isBoss      whether the mob is registered as a boss
     * @param isMiniBoss  whether the mob is registered as a mini-boss
     * @param combatPower calculated combat power metric
     * @return resolved difficulty tier
     */
    public static MobDifficultyCategory from(boolean isBoss, boolean isMiniBoss, double combatPower) {
        if (isBoss) return BOSS;
        if (isMiniBoss) return MINI_BOSS;
        if (combatPower > 500) return EXTREME;
        if (combatPower > 200) return HARD;
        if (combatPower > 100) return MEDIUM;
        if (combatPower > 50) return EASY;
        return TRIVIAL;
    }

    public String getKey() {
        return key;
    }

    public String getIcon() {
        return icon;
    }

    public ChatFormatting getColor() {
        return color;
    }

    /**
     * Data record encapsulating living entity attributes, combat metrics, and formatting getters.
     */
    public record MobProperties(
            double maxHealth,
            double attackDamage,
            double armor,
            MobCategory classification,
            MobDifficultyCategory difficultyCategory
    ) {
        public static final double ARMOR_COEFFICIENT = 0.04;

        public static ChatFormatting factorColor(double val, double threshold) {
            return val >= threshold * 2 ? ChatFormatting.DARK_RED : val >= threshold ? ChatFormatting.RED : val >= threshold / 2 ? ChatFormatting.GOLD : ChatFormatting.YELLOW;
        }

        public double calculateSurvivability() {
            return maxHealth * (1.0 + armor * ARMOR_COEFFICIENT);
        }

        public double calculateThreat() {
            return 1.0 + Math.log1p(attackDamage);
        }

        public double calculateCombatPower() {
            return calculateSurvivability() * calculateThreat();
        }

        public String getMobIcon() {
            if (difficultyCategory == BOSS) return "👑";
            if (difficultyCategory == MINI_BOSS) return "⭐";
            return switch (classification) {
                case MONSTER -> "⚔";
                case CREATURE, AXOLOTLS -> "🐾";
                case AMBIENT -> "🦋";
                case WATER_CREATURE, WATER_AMBIENT, UNDERGROUND_WATER_CREATURE -> "🐟";
                default -> "👾";
            };
        }

        public ChatFormatting getCategoryColor() {
            return switch (classification) {
                case MONSTER -> ChatFormatting.RED;
                case CREATURE, AXOLOTLS -> ChatFormatting.GREEN;
                case AMBIENT -> ChatFormatting.AQUA;
                case WATER_CREATURE, WATER_AMBIENT, UNDERGROUND_WATER_CREATURE -> ChatFormatting.BLUE;
                default -> ChatFormatting.WHITE;
            };
        }

        public ChatFormatting getHealthColor() {
            return maxHealth >= 100 ? ChatFormatting.DARK_RED : maxHealth >= 50 ? ChatFormatting.RED : maxHealth >= 20 ? ChatFormatting.GOLD : ChatFormatting.GREEN;
        }

        public ChatFormatting getAttackColor() {
            return attackDamage >= 20 ? ChatFormatting.DARK_RED : attackDamage >= 10 ? ChatFormatting.RED : attackDamage >= 5 ? ChatFormatting.GOLD : ChatFormatting.YELLOW;
        }

        public ChatFormatting getArmorColor() {
            return armor >= 15 ? ChatFormatting.DARK_AQUA : armor >= 10 ? ChatFormatting.AQUA : armor >= 5 ? ChatFormatting.BLUE : ChatFormatting.GRAY;
        }

        public ChatFormatting getCombatPowerColor() {
            double power = calculateCombatPower();
            return power >= 500 ? ChatFormatting.DARK_RED : power >= 200 ? ChatFormatting.RED : power >= 100 ? ChatFormatting.GOLD : power >= 50 ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
        }
    }
}
