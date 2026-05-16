package org.complexityanalyzer.bytecode;

import it.unimi.dsi.fastutil.objects.*;
import org.complexityanalyzer.bytecode.graph.MethodRef;
import org.complexityanalyzer.bytecode.model.ConditionNode;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.EnumSet;

public final class ConditionExtractor {

    private static final EnumSet<SemanticTag> CONDITION_TAGS = EnumSet.of(
            SemanticTag.ITEM_HELD_CHECK,
            SemanticTag.TAG_CHECK,
            SemanticTag.DATA_COMPONENT_CHECK,
            SemanticTag.BLOCK_CHECK,
            SemanticTag.ENTITY_CHECK,
            SemanticTag.TIME_CHECK,
            SemanticTag.DIMENSION_CHECK,
            SemanticTag.BIOME_CHECK,
            SemanticTag.WEATHER_CHECK,
            SemanticTag.EXPERIENCE_CHECK,
            SemanticTag.STATUS_EFFECT_CHECK,
            SemanticTag.LOOT_TABLE_CHECK,
            SemanticTag.PLAYER_STATE_SPRINT,
            SemanticTag.PLAYER_STATE_JUMP,
            SemanticTag.PLAYER_STATE_SNEAK,
            SemanticTag.PLAYER_STATE_GROUND,
            SemanticTag.PLAYER_STATE_FLY,
            SemanticTag.PLAYER_STATE_SWIM,
            SemanticTag.PLAYER_STATE_BURN,
            SemanticTag.PLAYER_STATE_RIDING,
            SemanticTag.REDSTONE_CHECK,
            SemanticTag.CAPABILITY_CHECK
    );

    private ConditionExtractor() {
    }

    public static ObjectList<ConditionNode> extract(BytecodeAnalyzer.AnalyzedMethod method, SemanticAnchorRegistry registry) {
        var conds = new ObjectArrayList<ConditionNode>();
        if (method.instructions() == null || registry == null) return conds;

        var insns = method.instructions();
        for (int i = 0; i < insns.size(); i++) {
            var insn = insns.get(i);
            if (!isConditionalJump(insn)) continue;

            var ctx = traceConditionContext(insns, i, registry);
            if (ctx == null) continue;

            conds.add(ctx);
        }
        return conds;
    }

    private static boolean isConditionalJump(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return (op >= Opcodes.IFEQ && op <= Opcodes.IF_ACMPNE) || op == Opcodes.IFNULL || op == Opcodes.IFNONNULL
                || op == Opcodes.TABLESWITCH || op == Opcodes.LOOKUPSWITCH;
    }

    private static ConditionNode traceConditionContext(ObjectList<AbstractInsnNode> insns, int condIdx, SemanticAnchorRegistry registry) {
        var builder = new ConditionNode.Builder();

        for (int j = condIdx - 1; j >= Math.max(0, condIdx - 20); j--) {
            var insn = insns.get(j);

            if (insn instanceof MethodInsnNode min) {
                SemanticTag tag = registry.resolve(new MethodRef(min.owner, min.name, min.desc));
                if (tag != null && CONDITION_TAGS.contains(tag)) {
                    builder.type(mapTagToConditionType(tag));

                    String val = extractValue(insns, j);
                    if (val != null) builder.target(val);
                    return builder.build();
                }
            }

            if (insn instanceof FieldInsnNode fin) if (fin.name.contains("item") || fin.name.contains("Item")
                    || fin.name.contains("held")) {
                builder.type(ConditionNode.ConditionType.ITEM_HELD);
                String val = extractValue(insns, j);
                if (val != null) builder.target(val);
                return builder.build();
            }
        }
        return null;
    }

    private static ConditionNode.ConditionType mapTagToConditionType(SemanticTag tag) {
        return switch (tag) {
            case ITEM_HELD_CHECK -> ConditionNode.ConditionType.ITEM_HELD;
            case BLOCK_CHECK, TAG_CHECK, REDSTONE_CHECK -> ConditionNode.ConditionType.BLOCK_TARGET;
            case ENTITY_CHECK -> ConditionNode.ConditionType.ENTITY_TARGET;
            case INVENTORY_ACCESS -> ConditionNode.ConditionType.INVENTORY_STATE;
            default -> ConditionNode.ConditionType.GENERIC;
        };
    }

    private static String extractValue(ObjectList<AbstractInsnNode> insns, int start) {
        for (int j = start - 1; j >= Math.max(0, start - 10); j--) {
            var insn = insns.get(j);

            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) return s;

            if (insn instanceof FieldInsnNode fin) {
                String name = fin.name.toUpperCase();
                if (name.contains("ITEM") || name.contains("BLOCK") || name.contains("ENTITY"))
                    return fin.owner + ":" + fin.name;
            }

            if (insn instanceof MethodInsnNode min) if (min.name.equals("getName") || min.name.equals("getPath")
                    || min.name.equals("toString")) {
                String prev = extractValue(insns, j);
                if (prev != null) return prev;
            }
        }
        return null;
    }
}