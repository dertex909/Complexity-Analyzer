package org.complexityanalyzer.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class ComplexityConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue MAX_DEPTH;
    public static final ModConfigSpec.IntValue MAX_RECURSION_DEPTH;
    public static final ModConfigSpec.IntValue MAX_INGREDIENT_VARIANTS;

    public static final ModConfigSpec.DoubleValue BASE_COMPLEXITY;
    public static final ModConfigSpec.DoubleValue DEPTH_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue INGREDIENT_MULTIPLIER;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Complexity Analyzer Configuration").push("general");

        builder.comment("Limits and Safety").push("limits");

        MAX_DEPTH = builder
                .comment("Maximum crafting depth to prevent infinite recursion")
                .defineInRange("maxDepth", 50, 1, 1000);

        MAX_RECURSION_DEPTH = builder
                .comment("Maximum recursion depth during analysis")
                .defineInRange("maxRecursionDepth", 100, 1, 1000);

        MAX_INGREDIENT_VARIANTS = builder
                .comment("Maximum number of ingredient variants to consider (for tags)")
                .defineInRange("maxIngredientVariants", 20, 1, 100);

        builder.pop();

        builder.comment("Base complexity calculation values").push("calculation");

        BASE_COMPLEXITY = builder
                .comment("Base complexity for items without recipes")
                .defineInRange("baseComplexity", 1.0, 0.1, 1000.0);

        DEPTH_MULTIPLIER = builder
                .comment("Multiplier for each depth level")
                .defineInRange("depthMultiplier", 2.0, 0.1, 100.0);

        INGREDIENT_MULTIPLIER = builder
                .comment("Multiplier for ingredient count")
                .defineInRange("ingredientMultiplier", 0.5, 0.0, 10.0);

        builder.pop();
        builder.pop();

        SPEC = builder.build();
    }
}