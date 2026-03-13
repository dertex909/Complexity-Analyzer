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

package org.complexityanalyzer.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Collections;
import java.util.List;

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

    public static final ModConfigSpec.BooleanValue MACHINE_TAX_ENABLED;
    public static final ModConfigSpec.DoubleValue MACHINE_TAX_PERCENTAGE;
    public static final ModConfigSpec.DoubleValue MACHINE_BASE_COMPLEXITY;

    public static final ModConfigSpec.BooleanValue ENABLE_JEI_INTEGRATION;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> JEI_PLUGIN_BLACKLIST;

    public static final ModConfigSpec.DoubleValue FLUID_BASE_COMPLEXITY;
    public static final ModConfigSpec.DoubleValue FLUID_NORMALIZATION_FACTOR;

    public static final ModConfigSpec.IntValue MAX_THREADS;

    private static volatile int resolvedMaxThreads = -1;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("general");

        builder.push("threading");
        MAX_THREADS = builder
                .comment(
                        "Maximum number of threads for analysis and geo-scanning.",
                        "0 = unlimited (uses all available CPU cores minus 2)",
                        "1-1024 = fixed thread limit for hosting environments",
                        "",
                        "WARNING: If set higher than available CPU cores, server will crash on startup!",
                        "Use 0 for local servers, set explicit limit (e.g. 4) for shared hosting."
                )
                .defineInRange("maxThreads", 0, 0, 1024);
        builder.pop();

        builder.push("jei_integration");
        ENABLE_JEI_INTEGRATION = builder
                .comment("Enable automatic recipe extraction from JEI plugins of other mods")
                .define("enableJeiIntegration", true);

        JEI_PLUGIN_BLACKLIST = builder
                .comment("List of mod IDs whose JEI plugins should be ignored")
                .defineList(
                        "jeiPluginBlacklist",
                        Collections.emptyList(),
                        () -> "",
                        obj -> obj instanceof String
                );
        builder.pop();

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

        builder.push("fluids");
        FLUID_BASE_COMPLEXITY = builder
                .comment("Base complexity for generic fluids (not water/lava)")
                .defineInRange("baseComplexity", 5.0, 0.1, 1000.0);

        FLUID_NORMALIZATION_FACTOR = builder
                .comment("Multiplier for fluid costs in recipes")
                .defineInRange("normalizationFactor", 1.0, 0.01, 10.0);
        builder.pop();


        builder.push("machine_tax");
        builder.comment(
                "Machine Tax - adds a percentage of machine/equipment complexity",
                "to the final complexity of crafted items.",
                "",
                "Example: If Furnace has complexity 10.0 and tax is 7.5%,",
                "then smelted items get +0.75 complexity added to their recipe cost."
        );

        MACHINE_TAX_ENABLED = builder
                .comment("Enable machine tax calculation")
                .define("enabled", true);

        MACHINE_TAX_PERCENTAGE = builder
                .comment(
                        "Tax percentage in user-friendly format (0.0 to 100.0)",
                        "Default: 7.5 means 7.5% of machine complexity is added as tax"
                )
                .defineInRange("percentage", 7.5, 0.0, 100.0);

        MACHINE_BASE_COMPLEXITY = builder
                .comment("Base complexity for machines when they are not yet calculated (used as fallback)")
                .defineInRange("baseComplexity", 100.0, 0.0, 10000.0);

        builder.pop();

        builder.pop();

        SPEC = builder.build();
    }

    public static double getMachineTaxMultiplier() {
        if (!MACHINE_TAX_ENABLED.get()) return 0.0;
        return MACHINE_TAX_PERCENTAGE.get() / 100.0;
    }

    public static double getFluidNormalizationFactor() {
        return FLUID_NORMALIZATION_FACTOR.get();
    }

    public static double getMachineBaseComplexity() {
        return MACHINE_BASE_COMPLEXITY.get();
    }

    public static int getMaxThreads() {
        if (resolvedMaxThreads >= 0) return resolvedMaxThreads;

        int configValue = MAX_THREADS.get();
        int availableCores = Runtime.getRuntime().availableProcessors();

        if (configValue == 0) {
            resolvedMaxThreads = Math.max(1, availableCores - 2);
        } else {
            if (configValue > availableCores) throw new IllegalStateException(String.format(
                    "[Complexity Analyzer] Config error: maxThreads=%d exceeds available CPU cores=%d. " +
                            "Set maxThreads to %d or less, or use 0 for unlimited.",
                    configValue, availableCores, availableCores
            ));

            resolvedMaxThreads = configValue;
        }

        return resolvedMaxThreads;
    }

    public static void resetThreadCache() {
        resolvedMaxThreads = -1;
    }
}