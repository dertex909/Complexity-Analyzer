package org.complexityanalyzer.bytecode;

import it.unimi.dsi.fastutil.objects.*;
import org.complexityanalyzer.bytecode.graph.MethodRef;
import org.complexityanalyzer.bytecode.model.MachineNode;

import java.util.Set;

public final class PatternEngine {

    private static final String[] TICK_METHOD_NAMES = {
            "tick", "serverTick", "clientTick", "onTick", "update",
            "blockEntityTick", "containerTick", "tickServer", "tickClient",
            "preTick", "postTick", "doTick", "onUpdate"
    };

    public static final String[] TICK_NAMES = TICK_METHOD_NAMES;

    private static final SemanticProfile.SemanticProfileDefinition MACHINE_PROFILE =
            new SemanticProfile.SemanticProfileDefinition(
                    "MACHINE",
                    Set.of(SemanticTag.TICK, SemanticTag.ITEM_MOVE),
                    Set.of(SemanticTag.ENERGY_CONSUME, SemanticTag.FLUID_CONSUME, SemanticTag.ENERGY_PRODUCE,
                            SemanticTag.FLUID_PRODUCE, SemanticTag.MACHINE_CAPABILITY, SemanticTag.MACHINE_WORKABLE_LOGIC),
                    0.3
            );

    private static final SemanticProfile.SemanticProfileDefinition MACHINE_DEFINITION_PROFILE =
            new SemanticProfile.SemanticProfileDefinition(
                    "MACHINE_DEFINITION",
                    Set.of(SemanticTag.MACHINE_DEFINITION),
                    Set.of(SemanticTag.MACHINE_RECIPE_TYPE, SemanticTag.MACHINE_TIER, SemanticTag.MACHINE_MULTIBLOCK,
                            SemanticTag.MACHINE_WORKABLE_LOGIC, SemanticTag.MACHINE_CAPABILITY, SemanticTag.GUI_OPEN),
                    0.3
            );

    private PatternEngine() {
    }

    public record MachineCandidate(
            BytecodeAnalyzer.AnalyzedClass clazz,
            String modId,
            boolean isMachine,
            ObjectList<String> reasons) {
    }

    public static MachineCandidate classifyMachine(BytecodeAnalyzer.AnalyzedClass clazz, String modId,
                                                    Object2ObjectMap<MethodRef, SemanticProfile> profiles) {
        var reasons = new ObjectArrayList<String>();
        boolean hasMachineFields = false;

        for (var ft : clazz.fieldTypes()) {
            if (ft.contains("ItemStack") || ft.contains("NonNullList") || ft.contains("SimpleContainer")
                    || ft.contains("ItemStackHandler") || ft.contains("IItemHandler") || ft.contains("Inventory")
                    || ft.contains("Machine") || ft.contains("RecipeType") || ft.contains("MetaMachine")
                    || ft.contains("Multiblock") || ft.contains("Capability") || ft.contains("Fluid")) {
                hasMachineFields = true;
                break;
            }
        }
        if (hasMachineFields) reasons.add("has machine/inventory/capability fields");

        boolean hasTickMethod = false;
        for (var tn : TICK_METHOD_NAMES) {
            if (BytecodeAnalyzer.hasMethod(clazz, tn)) {
                hasTickMethod = true;
                reasons.add("has " + tn + "() method");
                break;
            }
        }

        boolean profileMatch = false;
        if (profiles != null) {
            for (var m : clazz.methods()) {
                MethodRef ref = new MethodRef(clazz.className(), m.name(), m.descriptor());
                SemanticProfile profile = profiles.get(ref);
                if (profile != null && (profile.matchesProfile(MACHINE_PROFILE, 0.3)
                        || profile.matchesProfile(MACHINE_DEFINITION_PROFILE, 0.3)
                        || looksLikeMachineProfile(profile))) {
                    profileMatch = true;
                    reasons.add("method " + m.name() + " matches MACHINE profile");
                    break;
                }
            }
        }

        boolean typeLooksMachine = looksLikeMachineClass(clazz.className(), clazz.superName(), clazz.interfaces());
        if (typeLooksMachine) reasons.add("class hierarchy/name looks like machine");

        boolean isMachine = profileMatch || typeLooksMachine || (hasMachineFields && hasTickMethod) || (hasMachineFields && clazz.methods().size() >= 4);

        return new MachineCandidate(clazz, modId, isMachine, reasons);
    }

    public static MachineNode extractMachineLogic(MachineCandidate candidate, SemanticAnchorRegistry registry) {
        var builder = new MachineNode.Builder()
                .className(candidate.clazz().className())
                .modId(candidate.modId());

        var tickMethod = BytecodeAnalyzer.findMethod(candidate.clazz(), "tick");
        if (tickMethod == null) for (var tn : TICK_METHOD_NAMES) {
            tickMethod = BytecodeAnalyzer.findMethod(candidate.clazz(), tn);
            if (tickMethod != null) break;
        }

        if (tickMethod != null) {
            var logic = MachineLogicExtractor.extract(tickMethod, registry);
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

    private static boolean looksLikeMachineProfile(SemanticProfile profile) {
        if (profile.hasTag(SemanticTag.MACHINE_RECIPE_TYPE) && profile.hasTag(SemanticTag.MACHINE_CAPABILITY)) return true;
        if (profile.hasTag(SemanticTag.MACHINE_RECIPE_TYPE) && profile.hasTag(SemanticTag.MACHINE_TIER)) return true;
        if (profile.hasTag(SemanticTag.MACHINE_MULTIBLOCK) && profile.hasTag(SemanticTag.MACHINE_WORKABLE_LOGIC)) return true;
        if (profile.hasTag(SemanticTag.RECIPE_BUILDER_START) && profile.hasTag(SemanticTag.MACHINE_RECIPE_TYPE)) return true;
        return false;
    }

    private static boolean looksLikeMachineClass(String className, String superName, ObjectList<String> interfaces) {
        if (containsMachineWords(className) || containsMachineWords(superName)) return true;
        for (var iface : interfaces) if (containsMachineWords(iface)) return true;
        return false;
    }

    private static boolean containsMachineWords(String value) {
        if (value == null) return false;
        return value.contains("Machine") || value.contains("MetaMachine") || value.contains("Multiblock")
                || value.contains("Workable") || value.contains("RecipeLogic") || value.contains("Generator");
    }
}