package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.complexityanalyzer.bytecode.model.MachineNode;
import org.complexityanalyzer.bytecode.model.SemanticEdge;
import org.complexityanalyzer.bytecode.ConfidenceModel;

/**
 * Экспортёр структурных данных в семантические графы — v2.0.
 * Использует {@link AntivirusStyleDetector} для финальной классификации.
 */
public final class StructuralSemanticExporter {

    private StructuralSemanticExporter() {
    }

    /**
     * Создать SemanticEdge из ClassShape.
     */
    public static ObjectList<SemanticEdge> edgesFromShapes(ObjectList<StructuralBytecodeHarvester.ClassShape> shapes) {
        var edges = new ObjectArrayList<SemanticEdge>();
        if (shapes == null) return edges;
        for (var shape : shapes) {
            if (shape.recipeLike()) edges.add(edge(shape, "STRUCTURAL_RECIPE"));
            if (shape.codecLike()) edges.add(edge(shape, "STRUCTURAL_CODEC"));
            if (shape.machineLike()) edges.add(edge(shape, "STRUCTURAL_MACHINE"));
        }
        return edges;
    }

    /**
     * Создать MachineNode из ClassShape.
     */
    public static ObjectList<MachineNode> machinesFromShapes(ObjectList<StructuralBytecodeHarvester.ClassShape> shapes) {
        var machines = new ObjectArrayList<MachineNode>();
        if (shapes == null) return machines;
        for (var shape : shapes) {
            if (!shape.machineLike()) continue;

            var builder = new MachineNode.Builder()
                    .className(shape.className())
                    .modId(inferModId(shape.className()));

            if (shape.stackFields() > 0 || shape.stackCreations() > 0) {
                builder.addInput("structural:item_io");
            }
            if (shape.fluidFields() > 0 || shape.fluidCreations() > 0) {
                builder.addInput("structural:fluid_io");
            }
            if (shape.ingredientFields() > 0 || shape.ingredientCreations() > 0) {
                builder.addOutput("structural:recipe_logic");
            }
            builder.deterministic(shape.codecRefs() == 0);
            machines.add(builder.build());
        }
        return machines;
    }

    private static SemanticEdge edge(StructuralBytecodeHarvester.ClassShape shape, String action) {
        return new SemanticEdge.Builder()
                .from("Class:" + shape.className())
                .to(action)
                .action(action)
                .weight(ConfidenceModel.HEURISTIC)
                .source(SemanticEdge.EdgeSource.HEURISTIC)
                .putContext("score", shape.score())
                .putContext("recipeLike", shape.recipeLike())
                .putContext("machineLike", shape.machineLike())
                .putContext("codecLike", shape.codecLike())
                .build();
    }

    private static String inferModId(String className) {
        String[] parts = className.split("/");
        return parts.length >= 1 ? parts[0] : "unknown";
    }
}
