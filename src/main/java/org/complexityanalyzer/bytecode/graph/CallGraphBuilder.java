package org.complexityanalyzer.bytecode.graph;

import it.unimi.dsi.fastutil.objects.*;
import org.complexityanalyzer.bytecode.BytecodeAnalyzer;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Map;

public final class CallGraphBuilder {

    private CallGraphBuilder() {
    }

    public static Object2ObjectMap<String, CallGraph> build(Map<String, BytecodeAnalyzer.AnalyzedClass> allClasses) {
        var graphs = new Object2ObjectOpenHashMap<String, CallGraph>();
        var builders = new Object2ObjectOpenHashMap<String, CallGraph.Builder>();
        var hierarchy = buildHierarchy(allClasses);
        var methodIndex = buildMethodIndex(allClasses);

        for (var entry : allClasses.entrySet()) {
            String className = entry.getKey();
            var clazz = entry.getValue();
            String modId = inferModId(className);
            CallGraph.Builder builder = builders.computeIfAbsent(modId, k -> new CallGraph.Builder().modId(modId));

            // Collect all internal method refs for this class
            for (var method : clazz.methods()) {
                MethodRef internalRef = new MethodRef(className, method.name(), method.descriptor());
                builder.addInternalNode(internalRef);
            }

            // Build edges
            for (var method : clazz.methods()) {
                if (method.instructions() == null) continue;
                MethodRef caller = new MethodRef(className, method.name(), method.descriptor());

                for (var insn : method.instructions()) {
                    if (insn instanceof MethodInsnNode min) {
                        MethodRef callee = new MethodRef(min.owner, min.name, min.desc);
                        addMethodEdge(builder, allClasses, caller, callee);

                        if (isVirtualDispatch(min.getOpcode())) {
                            for (MethodRef target : resolveVirtualTargets(callee, hierarchy, methodIndex)) {
                                addMethodEdge(builder, allClasses, caller, target);
                            }
                        }
                    } else if (insn instanceof InvokeDynamicInsnNode indy) {
                        for (MethodRef target : resolveInvokeDynamicTargets(indy)) {
                            addMethodEdge(builder, allClasses, caller, target);
                        }
                    }
                }
            }
        }

        for (var entry : builders.object2ObjectEntrySet()) graphs.put(entry.getKey(), entry.getValue().build());

        return graphs;
    }

    private static void addMethodEdge(CallGraph.Builder builder, Map<String, BytecodeAnalyzer.AnalyzedClass> allClasses,
                                      MethodRef caller, MethodRef callee) {
        if (allClasses.containsKey(callee.owner())) builder.addInternalNode(callee);
        else builder.addExternalNode(callee);
        builder.addEdge(caller, callee);
    }

    private static boolean isVirtualDispatch(int opcode) {
        return opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE;
    }

    private static Object2ObjectMap<String, ObjectSet<String>> buildHierarchy(Map<String, BytecodeAnalyzer.AnalyzedClass> allClasses) {
        var hierarchy = new Object2ObjectOpenHashMap<String, ObjectSet<String>>();
        for (var clazz : allClasses.values()) {
            if (clazz.superName() != null) hierarchy.computeIfAbsent(clazz.superName(), k -> new ObjectOpenHashSet<>()).add(clazz.className());
            for (var iface : clazz.interfaces()) hierarchy.computeIfAbsent(iface, k -> new ObjectOpenHashSet<>()).add(clazz.className());
        }
        return hierarchy;
    }

    private static Object2ObjectMap<String, ObjectSet<String>> buildMethodIndex(Map<String, BytecodeAnalyzer.AnalyzedClass> allClasses) {
        var methodIndex = new Object2ObjectOpenHashMap<String, ObjectSet<String>>();
        for (var clazz : allClasses.values()) {
            for (var method : clazz.methods()) {
                methodIndex.computeIfAbsent(method.name() + method.descriptor(), k -> new ObjectOpenHashSet<>()).add(clazz.className());
            }
        }
        return methodIndex;
    }

    private static ObjectSet<MethodRef> resolveVirtualTargets(MethodRef declared,
                                                              Object2ObjectMap<String, ObjectSet<String>> hierarchy,
                                                              Object2ObjectMap<String, ObjectSet<String>> methodIndex) {
        var targets = new ObjectOpenHashSet<MethodRef>();
        var descendants = new ObjectOpenHashSet<String>();
        collectDescendants(declared.owner(), hierarchy, descendants);

        var implementors = methodIndex.getOrDefault(declared.name() + declared.descriptor(), ObjectSets.emptySet());
        for (var impl : implementors) {
            if (descendants.contains(impl)) {
                targets.add(new MethodRef(impl, declared.name(), declared.descriptor()));
            }
        }
        return targets;
    }

    private static void collectDescendants(String owner, Object2ObjectMap<String, ObjectSet<String>> hierarchy, ObjectSet<String> out) {
        var direct = hierarchy.get(owner);
        if (direct == null) return;
        for (var child : direct) {
            if (out.add(child)) collectDescendants(child, hierarchy, out);
        }
    }

    private static ObjectSet<MethodRef> resolveInvokeDynamicTargets(InvokeDynamicInsnNode indy) {
        var targets = new ObjectOpenHashSet<MethodRef>();
        addHandleTarget(targets, indy.bsm);
        if (indy.bsmArgs != null) {
            for (Object arg : indy.bsmArgs) if (arg instanceof Handle h) addHandleTarget(targets, h);
        }
        return targets;
    }

    private static void addHandleTarget(ObjectSet<MethodRef> targets, Handle handle) {
        if (handle == null) return;
        targets.add(new MethodRef(handle.getOwner(), handle.getName(), handle.getDesc()));
    }

    private static String inferModId(String className) {
        String[] parts = className.split("/");
        return parts.length >= 1 ? parts[0] : "unknown";
    }
}
