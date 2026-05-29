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

package org.complexityanalyzer.harvest;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.complexityanalyzer.core.GameRegistryManager;
import org.complexityanalyzer.graph.RecipeNode;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class XkDecoRecipeDebugger {

    private XkDecoRecipeDebugger() {
    }

    public static String runDebug(Level level, Path worldDir) {
        StringBuilder sb = new StringBuilder(512 * 1024);
        sb.append("============================================================\n");
        sb.append("             XKDECO & KIWI RECIPE DIAGNOSTIC REPORT         \n");
        sb.append("============================================================\n");
        sb.append("Timestamp: ").append(new Date()).append("\n");

        if (level == null) {
            sb.append("ERROR: Level is null. Cannot perform diagnostics.\n");
            return saveReport(sb.toString(), worldDir);
        }

        try {
            var recipeManager = level.getRecipeManager();
            var recipes = recipeManager.getRecipes();
            int totalRecipes = recipes.size();

            sb.append("Total recipes registered in RecipeManager: ").append(totalRecipes).append("\n\n");

            // Section 1: Breakdown Analysis
            sb.append("------------------------------------------------------------\n");
            sb.append("1. RECIPE TYPE & CLASS DISTRIBUTION\n");
            sb.append("------------------------------------------------------------\n");

            Map<String, Integer> typeCount = new TreeMap<>();
            Map<String, Integer> classCount = new TreeMap<>();
            int xkDecoNamespaceCount = 0;
            int kiwiNamespaceCount = 0;
            int snowneeClassCount = 0;

            for (var holder : recipes) {
                var recipe = holder.value();
                var id = holder.id();
                String ns = id.getNamespace();

                if ("xkdeco".equals(ns)) {
                    xkDecoNamespaceCount++;
                } else if ("kiwi".equals(ns)) {
                    kiwiNamespaceCount++;
                }

                String className = recipe.getClass().getName();
                if (className.contains("snownee") || className.contains("kiwi")) {
                    snowneeClassCount++;
                }

                typeCount.merge(recipe.getType().toString(), 1, Integer::sum);
                classCount.merge(className, 1, Integer::sum);
            }

            sb.append("Namespace distribution:\n");
            sb.append("  xkdeco namespace: ").append(xkDecoNamespaceCount).append("\n");
            sb.append("  kiwi namespace:   ").append(kiwiNamespaceCount).append("\n");
            sb.append("Class signature distribution:\n");
            sb.append("  Classes in snownee/kiwi package: ").append(snowneeClassCount).append("\n\n");

            sb.append("Recipe Types count breakdown:\n");
            for (var entry : typeCount.entrySet()) {
                sb.append("  - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            sb.append("\n");

            sb.append("Recipe Classes count breakdown:\n");
            for (var entry : classCount.entrySet()) {
                sb.append("  - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            sb.append("\n");


            // Section 2: Filter and inspect XKDeco related recipes
            sb.append("------------------------------------------------------------\n");
            sb.append("2. DETAILED INSPECTION OF XKDECO/KIWI RELATED RECIPES\n");
            sb.append("------------------------------------------------------------\n");

            FastHarvester harvester = new FastHarvester();
            int matchingRecipesCount = 0;

            for (var holder : recipes) {
                var recipe = holder.value();
                var id = holder.id();
                String ns = id.getNamespace();
                String className = recipe.getClass().getName();

                boolean isXkDecoRelated = "xkdeco".equals(ns) || "kiwi".equals(ns)
                        || className.contains("snownee") || className.contains("kiwi") || className.contains("xkdeco") || className.contains("teacon");

                if (!isXkDecoRelated) {
                    continue;
                }

                matchingRecipesCount++;
                sb.append("════════════════════════════════════════════════════════════\n");
                sb.append("RECIPE #").append(matchingRecipesCount).append("\n");
                sb.append("ID:        ").append(id).append("\n");
                sb.append("Type:      ").append(recipe.getType()).append("\n");
                sb.append("Class:     ").append(className).append("\n");

                // Antivirus Style Detector diagnostics
                var detection = AntivirusStyleDetector.detect(recipe.getClass());
                sb.append("AntivirusVerdict: ").append(detection.verdict()).append("\n");
                sb.append("  Confidence Scores: Signature=").append(detection.signatureConfidence())
                        .append(", Heuristic=").append(detection.heuristicConfidence())
                        .append(", Behavioral=").append(detection.behavioralConfidence())
                        .append(", Total=").append(detection.totalConfidence()).append("\n");
                sb.append("  IsRecipe: ").append(detection.isRecipe())
                        .append(", IsMachine: ").append(detection.isMachine())
                        .append(", IsCodec: ").append(detection.isCodec()).append("\n");
                sb.append("  Evidence Listed:\n");
                for (String evidence : detection.allEvidence()) {
                    sb.append("    - ").append(evidence).append("\n");
                }

                // Execute Harvest step-by-step
                sb.append("\nExecution of FastHarvester.harvest():\n");
                HarvestedItems harvested = null;
                try {
                    harvested = harvester.harvest(recipe, level);
                    sb.append("  SUCCESSFULLY HARVESTED\n");
                    sb.append("  - Input Items (").append(harvested.inputItems().size()).append("): ");
                    for (ItemStack stack : harvested.inputItems()) {
                        sb.append(ItemStackInfo(stack)).append(", ");
                    }
                    sb.append("\n  - Output Items (").append(harvested.outputItems().size()).append("): ");
                    for (ItemStack stack : harvested.outputItems()) {
                        sb.append(ItemStackInfo(stack)).append(", ");
                    }
                    sb.append("\n  - Input Ingredients (").append(harvested.inputIngredients().size()).append("):\n");
                    for (var hi : harvested.inputIngredients()) {
                        sb.append("      Variant count = ").append(hi.count()).append(", Items: ");
                        for (ItemStack s : hi.ingredient().getItems()) {
                            sb.append(ItemStackInfo(s)).append(" | ");
                        }
                        sb.append("\n");
                    }
                    sb.append("  - Input Fluids (").append(harvested.inputFluids().size()).append("): ");
                    for (FluidStack f : harvested.inputFluids()) {
                        sb.append(f.getAmount()).append("mb ").append(f.getFluid().toString()).append(", ");
                    }
                    sb.append("\n  - Output Fluids (").append(harvested.outputFluids().size()).append("): ");
                    for (FluidStack f : harvested.outputFluids()) {
                        sb.append(f.getAmount()).append("mb ").append(f.getFluid().toString()).append(", ");
                    }
                    sb.append("\n");
                } catch (Throwable t) {
                    sb.append("  FAILED TO HARVEST WITH EXCEPTION!\n");
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    t.printStackTrace(pw);
                    sb.append("  StackTrace:\n").append(sw).append("\n");
                }

                // Execute Converter step-by-step
                if (harvested != null) {
                    sb.append("Execution of HarvestedRecipeConverter.convert():\n");
                    try {
                        RecipeNode node = HarvestedRecipeConverter.convert(harvested, level);
                        if (node == null) {
                            sb.append("  REJECTED: Converter returned null!\n");
                            sb.append("    Diagnosing null return reason:\n");
                            ItemStack declaredResult = ItemStack.EMPTY;
                            try {
                                declaredResult = recipe.getResultItem(level.registryAccess());
                            } catch (Throwable ignored) {
                            }
                            sb.append("      - declaredResult isEmpty: ").append(declaredResult.isEmpty()).append("\n");
                            sb.append("      - outputItems isEmpty:  ").append(harvested.outputItems().isEmpty()).append("\n");
                            sb.append("      - outputFluids isEmpty: ").append(harvested.outputFluids().isEmpty()).append("\n");
                            sb.append("      - inputIngredients isEmpty: ").append(harvested.inputIngredients().isEmpty()).append("\n");
                        } else {
                            sb.append("  SUCCESS: Converted to RecipeNode:\n");
                            sb.append("    Result Item:   ").append(node.getResultItem())
                                    .append(", count=").append(node.getResultCount())
                                    .append(", isPlaceholder=").append(node.isPlaceholder())
                                    .append(", placeholderId=").append(node.getPlaceholderId()).append("\n");
                            sb.append("    Ingredients count in node: ").append(node.getIngredients().size()).append("\n");
                            for (var slot : node.getIngredients()) {
                                sb.append("      - Variants: ");
                                for (var item : slot.getVariants()) {
                                    sb.append(GameRegistryManager.getItemId(item)).append(" ");
                                }
                                sb.append(" -> count: ").append(slot.getCount()).append("\n");
                            }
                        }
                    } catch (Throwable t) {
                        sb.append("  FAILED TO CONVERT WITH EXCEPTION!\n");
                        StringWriter sw = new StringWriter();
                        PrintWriter pw = new PrintWriter(sw);
                        t.printStackTrace(pw);
                        sb.append("  StackTrace:\n").append(sw).append("\n");
                    }
                }
                sb.append("\n");
            }

            if (matchingRecipesCount == 0) {
                sb.append("No recipes found directly under xkdeco/kiwi/snownee namespaces or class names.\n\n");
            } else {
                sb.append("Found ").append(matchingRecipesCount).append(" direct matching mod recipes.\n\n");
            }


            // Section 3: Any recipe (Minecraft or other mods) involving XKDeco items
            sb.append("------------------------------------------------------------\n");
            sb.append("3. INDIRECT/VANILLA RECIPES INVOLVING XKDECO ITEMS\n");
            sb.append("------------------------------------------------------------\n");

            int indirectCount = 0;
            for (var holder : recipes) {
                var recipe = holder.value();
                var id = holder.id();
                String ns = id.getNamespace();

                // Skip directly harvested ones to avoid duplication
                String className = recipe.getClass().getName();
                if ("xkdeco".equals(ns) || "kiwi".equals(ns) || className.contains("snownee") || className.contains("kiwi")) {
                    continue;
                }

                // Check output
                boolean involvesXkDeco = false;
                ItemStack result = ItemStack.EMPTY;
                try {
                    result = recipe.getResultItem(level.registryAccess());
                    if (!result.isEmpty() && "xkdeco".equals(GameRegistryManager.getItemId(result.getItem()).getNamespace())) {
                        involvesXkDeco = true;
                    }
                } catch (Throwable ignored) {
                }

                // Check ingredients
                if (!involvesXkDeco) {
                    for (Ingredient ing : recipe.getIngredients()) {
                        for (ItemStack stack : ing.getItems()) {
                            if (!stack.isEmpty() && "xkdeco".equals(GameRegistryManager.getItemId(stack.getItem()).getNamespace())) {
                                involvesXkDeco = true;
                                break;
                            }
                        }
                        if (involvesXkDeco) break;
                    }
                }

                if (involvesXkDeco) {
                    indirectCount++;
                    sb.append("INDIRECT #").append(indirectCount).append("\n");
                    sb.append("  ID:        ").append(id).append("\n");
                    sb.append("  Type:      ").append(recipe.getType()).append("\n");
                    sb.append("  Class:     ").append(className).append("\n");
                    sb.append("  Output:    ").append(!result.isEmpty() ? GameRegistryManager.getItemId(result.getItem()) + " x" + result.getCount() : "EMPTY").append("\n");

                    // Let's test harvesting on this indirect one!
                    try {
                        var harvested = harvester.harvest(recipe, level);
                        var node = HarvestedRecipeConverter.convert(harvested, level);
                        if (node == null) {
                            sb.append("  - HARVESTED STATUS: CONVERTER REJECTED (returned null)\n");
                        } else {
                            sb.append("  - HARVESTED STATUS: SUCCESS! Result=").append(node.getResultItem())
                                    .append(", isPlaceholder=").append(node.isPlaceholder())
                                    .append(", Ingredients=").append(node.getIngredients().size()).append("\n");
                        }
                    } catch (Throwable t) {
                        sb.append("  - HARVESTED STATUS: FAILED WITH EXCEPTION: ").append(t.getMessage()).append("\n");
                    }
                    sb.append("\n");
                }
            }

            if (indirectCount == 0) {
                sb.append("No indirect/vanilla recipes found involving xkdeco items.\n");
            } else {
                sb.append("Found ").append(indirectCount).append(" indirect/vanilla recipes involving xkdeco items in ingredients or outputs.\n");
            }

        } catch (Throwable t) {
            sb.append("\nCRITICAL EXCEPTION RUNNING DIAGNOSTICS!\n");
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            sb.append(sw).append("\n");
        }

        sb.append("============================================================\n");
        sb.append("                  END OF DIAGNOSTIC REPORT                  \n");
        sb.append("============================================================\n");

        return saveReport(sb.toString(), worldDir);
    }

    private static String ItemStackInfo(ItemStack stack) {
        if (stack.isEmpty()) return "EMPTY";
        return stack.getCount() + "x " + GameRegistryManager.getItemId(stack.getItem());
    }

    private static String saveReport(String reportContent, Path worldDir) {
        try {
            Path dir = worldDir.resolve("complexityanalyzer");
            Files.createDirectories(dir);
            Path file = dir.resolve("xkdeco_diagnostic.txt");
            Files.writeString(file, reportContent, StandardCharsets.UTF_8);
            System.out.println("[XkDecoRecipeDebugger] Report written to " + file.toAbsolutePath());
            return file.toAbsolutePath().toString();
        } catch (Throwable t) {
            System.err.println("[XkDecoRecipeDebugger] Failed to write report: " + t.getMessage());
            return "FAILED TO WRITE FILE: " + t.getMessage();
        }
    }
}
