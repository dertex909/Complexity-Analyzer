/*
 * Complexity Analyzer
 * Copyright (C) 2025 dertex909
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

package org.complexityanalyzer.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class ComplexityConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue MAX_DEPTH;
    public static final ModConfigSpec.IntValue MAX_RECURSION_DEPTH;
    public static final ModConfigSpec.IntValue MAX_INGREDIENT_VARIANTS;
    public static final ModConfigSpec.IntValue MAX_ITERATIONS;
    public static final ModConfigSpec.IntValue CYCLE_DETECTION_DEPTH;

    public static final ModConfigSpec.DoubleValue BASE_COMPLEXITY;
    public static final ModConfigSpec.DoubleValue DEPTH_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue INGREDIENT_MULTIPLIER;

    public static final ModConfigSpec.DoubleValue MOB_DIFFICULTY_SCALER;
    public static final ModConfigSpec.DoubleValue BOSS_RARITY_MULTIPLIER;

    public static final ModConfigSpec.DoubleValue TIME_COST_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue BASE_ACTION_COST;

    public static final ModConfigSpec.DoubleValue CONVERGENCE_THRESHOLD;
    public static final ModConfigSpec.DoubleValue SOURCE_BIAS_THRESHOLD;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("general");

        builder.push("limits");
        MAX_DEPTH = builder.defineInRange("maxDepth", 50, 1, 1000);
        MAX_RECURSION_DEPTH = builder.defineInRange("maxRecursionDepth", 100, 1, 1000);
        MAX_INGREDIENT_VARIANTS = builder.defineInRange("maxIngredientVariants", 20, 1, 100);
        MAX_ITERATIONS = builder.defineInRange("maxIterations", 1000, 10, 10000);
        CYCLE_DETECTION_DEPTH = builder.defineInRange("cycleDetectionDepth", 100, 10, 500);
        builder.pop();

        builder.push("crafting");
        BASE_COMPLEXITY = builder.defineInRange("baseComplexity", 1.0, 0.1, 1000.0);
        DEPTH_MULTIPLIER = builder.defineInRange("depthMultiplier", 2.0, 0.1, 100.0);
        INGREDIENT_MULTIPLIER = builder.defineInRange("ingredientMultiplier", 0.5, 0.0, 10.0);
        builder.pop();

        builder.push("mob_drops");
        MOB_DIFFICULTY_SCALER = builder.defineInRange("mobDifficultyScaler", 0.1, 0.0, 100.0);
        BOSS_RARITY_MULTIPLIER = builder.defineInRange("bossRarityMultiplier", 20.0, 1.0, 500.0);
        builder.pop();

        builder.push("passive_generation");
        TIME_COST_MULTIPLIER = builder.defineInRange("timeCostMultiplier", 0.01, 0.0, 1.0);
        BASE_ACTION_COST = builder.defineInRange("baseActionCost", 10.0, 0.0, 1000.0);
        builder.pop();

        builder.push("solver_internals");
        CONVERGENCE_THRESHOLD = builder.defineInRange("convergenceThreshold", 1.0E-9, 1.0E-12, 1.0E-3);
        SOURCE_BIAS_THRESHOLD = builder.defineInRange("sourceBiasThreshold", 1.01, 1.0, 2.0);
        builder.pop();

        builder.pop();

        SPEC = builder.build();
    }
}