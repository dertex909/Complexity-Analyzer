package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.complexityanalyzer.bytecode.BytecodeAnalyzer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Статический анализатор байткода — v2.0.
 * Использует {@link UniversalTypeResolver} для детекта типов
 * и {@link PatternSignatureEngine#fromStructuralData} для построения профилей.
 */
public final class StructuralBytecodeHarvester {

    private StructuralBytecodeHarvester() {
    }

    /**
     * Проанализировать набор классов и вернуть ClassShape для каждого.
     */
    public static ObjectList<ClassShape> analyze(Iterable<BytecodeAnalyzer.AnalyzedClass> classes) {
        var shapes = new ObjectArrayList<ClassShape>();
        if (classes == null) return shapes;
        for (var clazz : classes) {
            ClassShape shape = analyzeClass(clazz);
            if (shape.score() > 0) shapes.add(shape);
        }
        return shapes;
    }

    /**
     * Проанализировать один класс.
     */
    public static ClassShape analyzeClass(BytecodeAnalyzer.AnalyzedClass clazz) {
        int stackFields = 0;
        int ingredientFields = 0;
        int fluidFields = 0;
        int resourceFields = 0;
        int tagFields = 0;
        int collectionFields = 0;
        int stackCreations = 0;
        int ingredientCreations = 0;
        int fluidCreations = 0;
        int fieldReads = 0;
        int codecRefs = 0;

        // Анализируем поля через UniversalTypeResolver
        for (String desc : clazz.fieldTypes()) {
            UniversalTypeResolver.ResolvedType resolved = UniversalTypeResolver.resolveDescriptor(desc);
            switch (resolved.kind()) {
                case ITEM_STACK -> stackFields++;
                case INGREDIENT -> ingredientFields++;
                case FLUID_STACK -> fluidFields++;
                case RESOURCE_ID -> resourceFields++;
                case TAG -> tagFields++;
                default -> {
                }
            }
            if (isCollectionDescriptor(desc)) collectionFields++;
            if (desc.contains("Codec") || desc.contains("MapCodec")) codecRefs++;
        }

        // Анализируем инструкции методов
        for (var method : clazz.methods()) {
            if (method.instructions() == null) continue;
            for (var insn : method.instructions()) {
                if (insn instanceof TypeInsnNode tin && tin.getOpcode() == Opcodes.NEW) {
                    UniversalTypeResolver.ResolvedType resolved = UniversalTypeResolver.resolveDescriptor(tin.desc);
                    switch (resolved.kind()) {
                        case ITEM_STACK -> stackCreations++;
                        case INGREDIENT -> ingredientCreations++;
                        case FLUID_STACK -> fluidCreations++;
                        default -> {
                        }
                    }
                    if (tin.desc.contains("Codec") || tin.desc.contains("MapCodec")) codecRefs++;
                } else if (insn instanceof FieldInsnNode fin) {
                    fieldReads++;
                    UniversalTypeResolver.ResolvedType resolved = UniversalTypeResolver.resolveDescriptor(fin.desc);
                    switch (resolved.kind()) {
                        case ITEM_STACK -> stackFields++;
                        case INGREDIENT -> ingredientFields++;
                        case FLUID_STACK -> fluidFields++;
                        case RESOURCE_ID -> resourceFields++;
                        case TAG -> tagFields++;
                        default -> {
                        }
                    }
                    if (isCollectionDescriptor(fin.desc)) collectionFields++;
                } else if (insn instanceof MethodInsnNode min) {
                    String retDesc = returnDescriptor(min.desc);
                    UniversalTypeResolver.ResolvedType resolved = UniversalTypeResolver.resolveDescriptor(retDesc);
                    switch (resolved.kind()) {
                        case ITEM_STACK -> stackCreations++;
                        case INGREDIENT -> ingredientCreations++;
                        case FLUID_STACK -> fluidCreations++;
                        default -> {
                        }
                    }
                    if (min.desc.contains("Codec") || min.owner.contains("Codec")) codecRefs++;
                }
            }
        }

        int score = stackFields + ingredientFields * 2 + fluidFields + resourceFields + tagFields
                + collectionFields + stackCreations + ingredientCreations * 2 + fluidCreations + codecRefs;

        boolean recipeLike = (ingredientFields + ingredientCreations > 0)
                && (stackFields + stackCreations + fluidFields + fluidCreations > 0);
        boolean machineLike = (stackFields + fluidFields > 0) && fieldReads > 3;
        boolean codecLike = codecRefs > 0 && score > 1;

        return new ClassShape(clazz.className(), score, recipeLike, machineLike, codecLike,
                stackFields, ingredientFields, fluidFields, resourceFields, tagFields, collectionFields,
                stackCreations, ingredientCreations, fluidCreations, codecRefs);
    }

    private static boolean isCollectionDescriptor(String desc) {
        if (desc == null) return false;
        return desc.contains("java/util/List") || desc.contains("java/util/Map") || desc.contains("NonNullList")
                || desc.startsWith("[") || desc.contains("ObjectArrayList") || desc.contains("ReferenceArrayList");
    }

    private static String returnDescriptor(String methodDesc) {
        if (methodDesc == null) return "";
        int idx = methodDesc.lastIndexOf(')');
        return idx >= 0 && idx + 1 < methodDesc.length() ? methodDesc.substring(idx + 1) : "";
    }

    public record ClassShape(
            String className,
            int score,
            boolean recipeLike,
            boolean machineLike,
            boolean codecLike,
            int stackFields,
            int ingredientFields,
            int fluidFields,
            int resourceFields,
            int tagFields,
            int collectionFields,
            int stackCreations,
            int ingredientCreations,
            int fluidCreations,
            int codecRefs
    ) {
    }
}
