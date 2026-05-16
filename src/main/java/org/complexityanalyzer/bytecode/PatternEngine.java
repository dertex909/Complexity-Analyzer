package org.complexityanalyzer.bytecode;

import it.unimi.dsi.fastutil.objects.*;
import org.complexityanalyzer.bytecode.model.MachineNode;

public final class PatternEngine {

    private static final String[] MACHINE_FIELD_PATTERNS = {
            "ItemStack", "NonNullList", "SimpleContainer",
            "ItemStackHandler", "IItemHandler", "Inventory"
    };

    private static final String[] TICK_METHOD_NAMES = {
            "tick", "serverTick", "clientTick", "onTick", "update",
            "blockEntityTick", "containerTick", "tickServer", "tickClient",
            "preTick", "postTick", "doTick", "onUpdate"
    };

    public static final String[] TICK_NAMES = TICK_METHOD_NAMES;

    private PatternEngine() {
    }

    public record MachineCandidate(
            BytecodeAnalyzer.AnalyzedClass clazz,
            String modId,
            boolean isMachine,
            ObjectList<String> reasons) {
    }

    public static MachineCandidate classifyMachine(BytecodeAnalyzer.AnalyzedClass clazz, String modId) {
        var reasons = new ObjectArrayList<String>();

        boolean hasItemFields = false;
        for (var ft : clazz.fieldTypes()) {
            for (var pat : MACHINE_FIELD_PATTERNS) {
                if (ft.contains(pat)) {
                    hasItemFields = true;
                    break;
                }
            }
            if (hasItemFields) break;
        }
        if (hasItemFields) reasons.add("has ItemStack/Inventory fields");

        boolean hasTickMethod = false;
        for (var tn : TICK_METHOD_NAMES) {
            if (BytecodeAnalyzer.hasMethod(clazz, tn)) {
                hasTickMethod = true;
                reasons.add("has " + tn + "() method");
                break;
            }
        }

        boolean isMachine = (hasItemFields && hasTickMethod) || (hasItemFields && clazz.methods().size() >= 4);

        return new MachineCandidate(clazz, modId, isMachine, reasons);
    }

    public static MachineNode extractMachineLogic(MachineCandidate candidate) {
        var builder = new MachineNode.Builder()
                .className(candidate.clazz().className())
                .modId(candidate.modId());

        var tickMethod = BytecodeAnalyzer.findMethod(candidate.clazz(), "tick");
        if (tickMethod == null) for (var tn : TICK_METHOD_NAMES) {
            tickMethod = BytecodeAnalyzer.findMethod(candidate.clazz(), tn);
            if (tickMethod != null) break;
        }

        if (tickMethod != null) {
            var logic = MachineLogicExtractor.extract(tickMethod);
            for (var in : logic.inputs()) builder.addInput(in);
            for (var out : logic.outputs()) builder.addOutput(out);
            builder.deterministic(logic.deterministic());
        }

        for (var fn : candidate.clazz().fieldNames()) {
            String fnl = fn.toLowerCase();
            if (fnl.contains("input") || fnl.startsWith("in_")) builder.addInput(fn);
            else if (fnl.contains("output") || fnl.startsWith("out_") || fnl.equals("result")) builder.addOutput(fn);
        }

        return builder.build();
    }
}