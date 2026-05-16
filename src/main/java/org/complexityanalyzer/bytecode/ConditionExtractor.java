package org.complexityanalyzer.bytecode;

import it.unimi.dsi.fastutil.objects.*;
import org.complexityanalyzer.bytecode.model.ConditionNode;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public final class ConditionExtractor {

    private static final Object2ObjectMap<String, ConditionNode.ConditionType> METHOD_TO_COND = new Object2ObjectOpenHashMap<>();

    static {
        METHOD_TO_COND.put("getHeldItem", ConditionNode.ConditionType.ITEM_HELD);
        METHOD_TO_COND.put("getItem", ConditionNode.ConditionType.ITEM_HELD);
        METHOD_TO_COND.put("getMainHandItem", ConditionNode.ConditionType.ITEM_HELD);
        METHOD_TO_COND.put("getOffhandItem", ConditionNode.ConditionType.ITEM_HELD);
        METHOD_TO_COND.put("getBlockState", ConditionNode.ConditionType.BLOCK_TARGET);
        METHOD_TO_COND.put("getBlock", ConditionNode.ConditionType.BLOCK_TARGET);
        METHOD_TO_COND.put("getEntity", ConditionNode.ConditionType.ENTITY_TARGET);
    }

    private ConditionExtractor() {
    }

    public static ObjectList<ConditionNode> extract(BytecodeAnalyzer.AnalyzedMethod method) {
        var conds = new ObjectArrayList<ConditionNode>();
        if (method.instructions() == null) return conds;

        var insns = method.instructions();
        for (int i = 0; i < insns.size(); i++) {
            var insn = insns.get(i);
            if (!isConditionalJump(insn)) continue;

            var ctx = traceConditionContext(insns, i);
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

    private static ConditionNode traceConditionContext(ObjectList<AbstractInsnNode> insns, int condIdx) {
        var builder = new ConditionNode.Builder();

        for (int j = condIdx - 1; j >= Math.max(0, condIdx - 20); j--) {
            var insn = insns.get(j);

            if (insn instanceof MethodInsnNode min) {
                var ct = METHOD_TO_COND.get(min.name);
                if (ct != null) {
                    builder.type(ct);

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