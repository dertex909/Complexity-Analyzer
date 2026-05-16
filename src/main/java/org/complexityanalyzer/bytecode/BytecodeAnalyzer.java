package org.complexityanalyzer.bytecode;

import it.unimi.dsi.fastutil.objects.*;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;

public final class BytecodeAnalyzer {

    private BytecodeAnalyzer() {
    }

    public record AnalyzedMethod(
            String name, String descriptor, int access,
            ObjectList<String> calledMethods,
            ObjectList<String> calledOwners,
            ObjectList<AbstractInsnNode> instructions,
            ObjectList<AnnotationNode> visibleAnnotations) {
    }

    public record AnalyzedClass(
            String className,
            String superName,
            ObjectList<String> interfaces,
            ObjectList<AnalyzedMethod> methods,
            ObjectList<String> fieldTypes,
            ObjectList<String> fieldNames,
            ObjectList<AnnotationNode> visibleAnnotations) {
    }

    public static AnalyzedClass analyze(String internalName, byte[] bytecode) {
        try {
            var reader = new ClassReader(bytecode);
            var node = new ClassNode(Opcodes.ASM9);
            reader.accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);

            var methods = new ObjectArrayList<AnalyzedMethod>();
            for (var m : node.methods)
                methods.add(analyzeMethod(m));

            var fTypes = new ObjectArrayList<String>();
            var fNames = new ObjectArrayList<String>();
            for (var f : node.fields) {
                fTypes.add(f.desc);
                fNames.add(f.name);
            }

            return new AnalyzedClass(internalName, node.superName,
                    node.interfaces != null ? new ObjectArrayList<>(node.interfaces) : ObjectLists.emptyList(),
                    methods, fTypes, fNames, node.visibleAnnotations != null
                    ? new ObjectArrayList<>(node.visibleAnnotations) : ObjectLists.emptyList());
        } catch (Exception e) {
            ComplexityAnalyzer.LOGGER.debug("[BA] Failed {}: {}", internalName, e.getMessage());
            return null;
        }
    }

    private static AnalyzedMethod analyzeMethod(MethodNode m) {
        var called = new ObjectArrayList<String>();
        var owners = new ObjectArrayList<String>();
        var insns = new ObjectArrayList<AbstractInsnNode>();

        if (m.instructions != null) for (var i : m.instructions) {
            insns.add(i);
            if (i instanceof MethodInsnNode min) {
                called.add(min.name);
                owners.add(min.owner);
            }
        }

        ObjectList<AnnotationNode> anns = m.visibleAnnotations != null
                ? new ObjectArrayList<>(m.visibleAnnotations) : new ObjectArrayList<>();

        return new AnalyzedMethod(m.name, m.desc, m.access, called, owners, insns, anns);
    }

    public static boolean hasMethod(AnalyzedClass c, String name) {
        for (var m : c.methods()) if (m.name().equals(name)) return true;
        return false;
    }

    public static AnalyzedMethod findMethod(AnalyzedClass c, String name) {
        for (var m : c.methods()) if (m.name().equals(name)) return m;
        return null;
    }

    public static String dumpClassFields(AnalyzedClass clazz) {
        var sb = new StringBuilder();
        sb.append("\n--- FIELDS: ").append(clazz.className()).append(" ---\n");
        for (int i = 0; i < clazz.fieldNames().size(); i++) {
            sb.append("  ").append(clazz.fieldTypes().get(i)).append(" ").append(clazz.fieldNames().get(i)).append("\n");
        }
        sb.append("--- END FIELDS ---\n");
        return sb.toString();
    }

    public static String dumpMethod(String className, AnalyzedMethod method) {
        var sb = new StringBuilder();
        sb.append("\n========================================\n");
        sb.append("BYTECODE DUMP: ").append(className).append(".").append(method.name()).append(method.descriptor()).append("\n");
        sb.append("========================================\n");

        if (method.instructions() == null || method.instructions().isEmpty()) {
            sb.append("  (empty method)\n");
            return sb.toString();
        }

        var printer = new Textifier();
        var mp = new TraceMethodVisitor(printer);
        for (var insn : method.instructions()) insn.accept(mp);

        var sw = new StringWriter();
        printer.print(new PrintWriter(sw));
        sb.append(sw.toString());
        sb.append("========================================\n");
        return sb.toString();
    }
}