package org.complexityanalyzer.bytecode;

import it.unimi.dsi.fastutil.objects.*;
import org.complexityanalyzer.bytecode.graph.MethodRef;
import org.complexityanalyzer.bytecode.model.SemanticEdge;
import org.objectweb.asm.tree.*;

import java.util.EnumSet;

public final class RecipeDetector {

    private static final EnumSet<SemanticTag> RECIPE_TAGS = EnumSet.of(
            SemanticTag.RECIPE_INPUT,
            SemanticTag.RECIPE_OUTPUT,
            SemanticTag.RECIPE_PROCESS,
            SemanticTag.RECIPE_FLUID_INPUT,
            SemanticTag.RECIPE_FLUID_OUTPUT,
            SemanticTag.RECIPE_ENERGY_COST,
            SemanticTag.RECIPE_DURATION,
            SemanticTag.RECIPE_CIRCUIT,
            SemanticTag.RECIPE_BUILDER_START
    );

    private RecipeDetector() {
    }

    public static ObjectList<SemanticEdge> detect(BytecodeAnalyzer.AnalyzedClass clazz, String modId,
                                                   SemanticAnchorRegistry registry) {
        var edges = new ObjectArrayList<SemanticEdge>();
        if (clazz == null || registry == null) return edges;

        for (var method : clazz.methods()) {
            if (method.instructions() == null) continue;

            var inputs = new ObjectArrayList<String>();
            var outputs = new ObjectArrayList<String>();
            Integer duration = null;
            Integer eut = null;
            Integer circuit = null;

            for (var insn : method.instructions()) {
                if (!(insn instanceof MethodInsnNode min)) continue;

                SemanticTag tag = registry.resolve(new MethodRef(min.owner, min.name, min.desc));
                if (tag == null || !RECIPE_TAGS.contains(tag)) continue;

                String value = extractLiteralArgument(insn);

                switch (tag) {
                    case RECIPE_INPUT, RECIPE_FLUID_INPUT -> {
                        if (value != null) inputs.add(kindPrefix(tag) + normalize(value));
                    }
                    case RECIPE_OUTPUT, RECIPE_FLUID_OUTPUT -> {
                        if (value != null) outputs.add(kindPrefix(tag) + normalize(value));
                    }
                    case RECIPE_DURATION -> {
                        duration = extractIntArgument(insn);
                    }
                    case RECIPE_ENERGY_COST -> {
                        eut = extractIntArgument(insn);
                    }
                    case RECIPE_CIRCUIT -> {
                        circuit = extractIntArgument(insn);
                    }
                    default -> {
                    }
                }
            }

            if (!inputs.isEmpty() && !outputs.isEmpty()) {
                for (String input : inputs) for (String output : outputs) {
                    var builder = new SemanticEdge.Builder()
                            .from(input)
                            .to(output)
                            .action("RECIPE_BUILDER")
                            .weight(ConfidenceModel.DIRECT_CALL)
                            .source(SemanticEdge.EdgeSource.DIRECT_CALL)
                            .putContext("class", clazz.className())
                            .putContext("method", method.name())
                            .putContext("mod", modId);
                    if (duration != null) builder.putContext("duration", String.valueOf(duration));
                    if (eut != null) builder.putContext("eut", String.valueOf(eut));
                    if (circuit != null) builder.putContext("circuit", String.valueOf(circuit));
                    edges.add(builder.build());
                }
            }
        }

        return edges;
    }

    private static String extractLiteralArgument(AbstractInsnNode insn) {
        // Walk backwards up to 12 instructions looking for LDC string or field ref
        for (int i = 0; i < 12; i++) {
            insn = insn.getPrevious();
            if (insn == null) break;
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) return s;
            if (insn instanceof FieldInsnNode fin) return fin.owner + ":" + fin.name;
            if (insn instanceof TypeInsnNode tin) return tin.desc;
        }
        return null;
    }

    private static Integer extractIntArgument(AbstractInsnNode insn) {
        for (int i = 0; i < 8; i++) {
            insn = insn.getPrevious();
            if (insn == null) break;
            if (insn instanceof IntInsnNode iin) return iin.operand;
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer ii) return ii;
            int op = insn.getOpcode();
            if (op >= 2 && op <= 8) return op - 3; // ICONST_M1..ICONST_5
        }
        return null;
    }

    private static String kindPrefix(SemanticTag tag) {
        return switch (tag) {
            case RECIPE_FLUID_INPUT, RECIPE_FLUID_OUTPUT -> "Fluid:";
            default -> "Item:";
        };
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) return "unknown";
        raw = raw.toLowerCase().replace('/', ':').replace('.', ':');
        if (raw.contains(":")) return raw;
        return "minecraft:" + raw;
    }
}
