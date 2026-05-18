package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectList;
import org.complexityanalyzer.ComplexityAnalyzer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class HarvestDebugWriter {

    private HarvestDebugWriter() {
    }

    public static void writeStructuralCandidates(Path worldDir, ObjectList<StructuralBytecodeHarvester.ClassShape> shapes) {
        if (worldDir == null || shapes == null) return;
        try {
            Path dir = worldDir.resolve("complexityanalyzer");
            Files.createDirectories(dir);
            Path file = dir.resolve("structural_candidates.txt");
            var sb = new StringBuilder();
            sb.append("=== STRUCTURAL BYTECODE CANDIDATES ===\n");
            for (var shape : shapes) {
                sb.append(shape.className())
                        .append(" score=").append(shape.score())
                        .append(" recipeLike=").append(shape.recipeLike())
                        .append(" machineLike=").append(shape.machineLike())
                        .append(" codecLike=").append(shape.codecLike())
                        .append(" stackFields=").append(shape.stackFields())
                        .append(" ingredientFields=").append(shape.ingredientFields())
                        .append(" fluidFields=").append(shape.fluidFields())
                        .append(" resourceFields=").append(shape.resourceFields())
                        .append(" tagFields=").append(shape.tagFields())
                        .append(" collectionFields=").append(shape.collectionFields())
                        .append(" stackCreations=").append(shape.stackCreations())
                        .append(" ingredientCreations=").append(shape.ingredientCreations())
                        .append(" fluidCreations=").append(shape.fluidCreations())
                        .append(" codecRefs=").append(shape.codecRefs())
                        .append('\n');
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[Harvest] Failed to write structural_candidates.txt: {}", t.getMessage());
        }
    }

    public static void appendRuntime(Path worldDir, String text) {
        if (worldDir == null || text == null) return;
        try {
            Path dir = worldDir.resolve("complexityanalyzer");
            Files.createDirectories(dir);
            Path file = dir.resolve("runtime_harvest.txt");
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            ComplexityAnalyzer.LOGGER.warn("[Harvest] Failed to write runtime_harvest.txt: {}", t.getMessage());
        }
    }
}
