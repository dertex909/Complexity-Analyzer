package org.complexityanalyzer.bytecode;

import it.unimi.dsi.fastutil.objects.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public final class MachineLogicExtractor {

    private MachineLogicExtractor() {
    }

    public record ExtractedLogic(
            ObjectList<String> inputs,
            ObjectList<String> outputs,
            boolean deterministic) {
    }

    public static ExtractedLogic extract(BytecodeAnalyzer.AnalyzedMethod tickMethod) {
        var inputs = new ObjectArrayList<String>();
        var outputs = new ObjectArrayList<String>();
        int itemStackCreations = 0;
        int conditionalBranches = 0;

        if (tickMethod.instructions() == null) return new ExtractedLogic(inputs, outputs, true);

        for (var insn : tickMethod.instructions()) {
            if (insn instanceof MethodInsnNode min) {
                String name = min.name;
                if (name.equals("getStackInSlot") || name.equals("getItem") || name.equals("extractItem") || name.equals("getInput")) {
                    String slot = extractSlotIndex(insn);
                    inputs.add(slot != null ? "slot_" + slot : "item_input");
                }
                if (name.equals("setStackInSlot") || name.equals("setItem") || name.equals("insertItem") || name.equals("setResult")) {
                    outputs.add("item_output");
                }
                if (name.equals("<init>") && min.owner.contains("ItemStack")) itemStackCreations++;
            }

            if (insn instanceof TypeInsnNode tin && tin.desc.contains("ItemStack") && insn.getOpcode() == Opcodes.NEW)
                itemStackCreations++;

            int op = insn.getOpcode();
            if ((op >= Opcodes.IFEQ && op <= Opcodes.IF_ACMPNE) || op == Opcodes.IFNULL || op == Opcodes.IFNONNULL)
                conditionalBranches++;
        }

        boolean det = conditionalBranches == 0 || itemStackCreations <= 2;
        return new ExtractedLogic(inputs, outputs, det);
    }

    private static String extractSlotIndex(AbstractInsnNode insn) {
        var prev = insn.getPrevious();
        if (prev instanceof IntInsnNode iin) return String.valueOf(iin.operand);
        if (prev instanceof LdcInsnNode ldc && ldc.cst instanceof Integer ii) return String.valueOf(ii);
        return null;
    }
}
