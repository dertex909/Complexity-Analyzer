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

package org.complexityanalyzer.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class ComplexityConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue MAX_INGREDIENT_VARIANTS;
    public static final ModConfigSpec.IntValue MAX_ITERATIONS;

    public static final ModConfigSpec.DoubleValue BASE_COMPLEXITY;

    public static final ModConfigSpec.DoubleValue MOB_DIFFICULTY_SCALER;
    public static final ModConfigSpec.DoubleValue BOSS_RARITY_MULTIPLIER;

    public static final ModConfigSpec.DoubleValue TIME_COST_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue FARMING_TIME_COST_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue BASE_ACTION_COST;

    public static final ModConfigSpec.DoubleValue CONVERGENCE_THRESHOLD;

    public static final ModConfigSpec.BooleanValue MACHINE_TAX_ENABLED;
    public static final ModConfigSpec.DoubleValue MACHINE_TAX_PERCENTAGE;
    public static final ModConfigSpec.DoubleValue MACHINE_BASE_COMPLEXITY;

    public static final ModConfigSpec.DoubleValue FLUID_NORMALIZATION_FACTOR;

    public static final ModConfigSpec.IntValue MAX_THREADS;

    public static final ModConfigSpec.ConfigValue<String> WEB_SERVER_IP;
    public static final ModConfigSpec.ConfigValue<Integer> WEB_SERVER_PORT;
    public static final ModConfigSpec.ConfigValue<String> WEB_SERVER_TOKEN;

    public static final ModConfigSpec.BooleanValue ENABLE_CACHE;
    public static final ModConfigSpec.IntValue DETECTION_SAMPLE_SIZE;

    private static volatile int resolvedMaxThreads = -1;

    static {
        var builder = new ModConfigSpec.Builder();

        builder.push("threading");
        MAX_THREADS = builder.comment(
                " Maximum number of threads for analysis and geo-scanning.",
                " 0 = unlimited (uses all available CPU cores minus 2)",
                " 1-1024 = fixed thread limit for hosting environments",
                " ",
                " WARNING: If set higher than available CPU cores, server will crash on startup!",
                " Use 0 for local servers, set explicit limit (e.g. 4) for shared hosting."
        ).defineInRange("maxThreads", 0, 0, 1024);
        builder.pop();

        builder.push("limits");
        MAX_INGREDIENT_VARIANTS = builder.comment(
                " Max item variants kept per ingredient slot (e.g. for tag ingredients like 'any plank').",
                " Higher = more accurate cost for broad tags, at a bit more solver work."
        ).defineInRange("maxIngredientVariants", 100, 1, 1000);
        MAX_ITERATIONS = builder.defineInRange("maxIterations", 1000, 10, 10000);
        builder.pop();

        builder.push("crafting");
        BASE_COMPLEXITY = builder.defineInRange("baseComplexity", 1.0, 0.1, 1000.0);
        builder.pop();

        builder.push("mob_drops");
        MOB_DIFFICULTY_SCALER = builder.defineInRange("mobDifficultyScaler", 0.1, 0.0, 100.0);
        BOSS_RARITY_MULTIPLIER = builder.defineInRange("bossRarityMultiplier", 20.0, 1.0, 500.0);
        builder.pop();

        builder.push("passive_generation");
        TIME_COST_MULTIPLIER = builder.defineInRange("timeCostMultiplier", 0.01, 0.0, 1.0);
        FARMING_TIME_COST_MULTIPLIER = builder.defineInRange("farmingTimeCostMultiplier", 0.005, 0.0, 1.0);
        BASE_ACTION_COST = builder.defineInRange("baseActionCost", 10.0, 0.0, 1000.0);
        builder.pop();

        builder.push("solver_internals");
        CONVERGENCE_THRESHOLD = builder.defineInRange("convergenceThreshold", 1.0E-9, 1.0E-12, 1.0E-3);
        builder.pop();

        builder.push("fluids");
        FLUID_NORMALIZATION_FACTOR = builder
                .comment(" Multiplier for fluid costs in recipes")
                .defineInRange("normalizationFactor", 1.0, 0.01, 10.0);
        builder.pop();

        builder.push("machine_tax");
        builder.comment(
                " Machine Tax - adds a percentage of machine/equipment complexity",
                " to the final complexity of crafted items.",
                " ",
                " Example: If Furnace has complexity 10.0 and tax is 7.5%,",
                " then smelted items get +0.75 complexity added to their recipe cost."
        );

        MACHINE_TAX_ENABLED = builder.comment(" Enable machine tax calculation").define("enabled", true);

        MACHINE_TAX_PERCENTAGE = builder.comment(
                " Tax percentage in user-friendly format (0.0 to 100.0)",
                " Default: 5.0 means 5.0% of machine complexity is added as tax"
        ).defineInRange("percentage", 5.0, 0.0, 100.0);

        MACHINE_BASE_COMPLEXITY = builder
                .comment(" Base complexity for machines when they are not yet calculated (used as fallback)")
                .defineInRange("baseComplexity", 100.0, 0.0, 1000000.0);
        builder.pop();

        builder.push("web_server");
        WEB_SERVER_IP = builder.comment(
                " IP address or domain of the server for the web dashboard.",
                " If set to 127.0.0.1, players will see a tip suggesting to change it to a public IP for remote connections."
        ).define("ip", "127.0.0.1");

        WEB_SERVER_PORT = builder.comment(
                " Dedicated port for the web dashboard server.",
                " 0 = multiplex on the main Minecraft server port (default).",
                " 1024-65535 = bind a separate standalone HTTP/WebSocket server to this port."
        ).define("port", 0, obj -> {
            if (obj instanceof Number num) {
                int port = num.intValue();
                return port == 0 || (port >= 1024 && port <= 65535);
            }
            return false;
        });

        WEB_SERVER_TOKEN = builder.comment(
                " Secret access token for the web dashboard URL.",
                " Automatically generated on first launch and saved to config.",
                " You can change this to any custom secret string (e.g. 'my-secret-key') to keep a permanent URL."
        ).define("token", "");
        builder.pop();

        builder.push("harvest");
        ENABLE_CACHE = builder.comment(
                " Cache expensive analysis results to disk (per-world). Covers:",
                "   - the harvested recipe graph (static scan + dynamic probe + fluid scan)",
                "   - the machine registry (block/BlockEntity reflective scan)",
                "   - block-break drops, loot tables, crop farming and mob drops",
                " When enabled, those scans run only once per recipe/mod set; later loads restore",
                " the data instantly. The caches auto-invalidate when recipes, blocks, items, mods,",
                " entities, the geo-scan or graph-affecting config change. Disable to always rebuild.",
                " Manage with /complexity system cache info|clear."
        ).define("enableCache", true);

        DETECTION_SAMPLE_SIZE = builder.comment(
                " Items probed per recipe type when detecting dynamically generated recipes,",
                " spread evenly across the item registry. Higher = better chance to catch generators",
                " that fire on only a few items, at slightly more startup cost."
        ).defineInRange("detectionSampleSize", 1024, 16, 1048576);
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