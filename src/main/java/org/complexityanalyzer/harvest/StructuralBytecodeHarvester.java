package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.complexityanalyzer.bytecode.BytecodeAnalyzer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public final class StructuralBytecodeHarvester {

    private StructuralBytecodeHarvester() {
    }

    public static ObjectList<ClassShape> analyze(Iterable<BytecodeAnalyzer.AnalyzedClass> classes) {
        var shapes = new ObjectArrayList<ClassShape>();
        if (classes == null) return shapes;
        for (var clazz : classes) {
            ClassShape shape = analyzeClass(clazz);
            if (shape.score() > 0) shapes.add(shape);
        }
        return shapes;
    }

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

        for (String desc : clazz.fieldTypes()) {
            StructuralTypeClassifier.Kind kind = StructuralTypeClassifier.classifyDescriptor(desc);
            if (kind == StructuralTypeClassifier.Kind.ITEM_STACK) stackFields++;
            else if (kind == StructuralTypeClassifier.Kind.INGREDIENT) ingredientFields++;
            else if (kind == StructuralTypeClassifier.Kind.FLUID_STACK) fluidFields++;
            else if (kind == StructuralTypeClassifier.Kind.RESOURCE_ID) resourceFields++;
            else if (kind == StructuralTypeClassifier.Kind.TAG) tagFields++;
            if (isCollectionDescriptor(desc)) collectionFields++;
            if (desc.contains("Codec") || desc.contains("MapCodec")) codecRefs++;
        }

        for (var method : clazz.methods()) {
            if (method.instructions() == null) continue;
            for (var insn : method.instructions()) {
                if (insn instanceof TypeInsnNode tin && tin.getOpcode() == Opcodes.NEW) {
                    StructuralTypeClassifier.Kind kind = StructuralTypeClassifier.classifyDescriptor(tin.desc);
                    if (kind == StructuralTypeClassifier.Kind.ITEM_STACK) stackCreations++;
                    else if (kind == StructuralTypeClassifier.Kind.INGREDIENT) ingredientCreations++;
                    else if (kind == StructuralTypeClassifier.Kind.FLUID_STACK) fluidCreations++;
                    if (tin.desc.contains("Codec") || tin.desc.contains("MapCodec")) codecRefs++;
                } else if (insn instanceof FieldInsnNode fin) {
                    fieldReads++;
                    StructuralTypeClassifier.Kind kind = StructuralTypeClassifier.classifyDescriptor(fin.desc);
                    if (kind == StructuralTypeClassifier.Kind.ITEM_STACK) stackFields++;
                    else if (kind == StructuralTypeClassifier.Kind.INGREDIENT) ingredientFields++;
                    else if (kind == StructuralTypeClassifier.Kind.FLUID_STACK) fluidFields++;
                    else if (kind == StructuralTypeClassifier.Kind.RESOURCE_ID) resourceFields++;
                    else if (kind == StructuralTypeClassifier.Kind.TAG) tagFields++;
                    if (isCollectionDescriptor(fin.desc)) collectionFields++;
                } else if (insn instanceof MethodInsnNode min) {
                    StructuralTypeClassifier.Kind returnKind = StructuralTypeClassifier.classifyDescriptor(returnDescriptor(min.desc));
                    if (returnKind == StructuralTypeClassifier.Kind.ITEM_STACK) stackCreations++;
                    else if (returnKind == StructuralTypeClassifier.Kind.INGREDIENT) ingredientCreations++;
                    else if (returnKind == StructuralTypeClassifier.Kind.FLUID_STACK) fluidCreations++;
                    if (min.desc.contains("Codec") || min.owner.contains("Codec")) codecRefs++;
                }
            }
        }

        int score = stackFields + ingredientFields * 2 + fluidFields + resourceFields + tagFields
                + collectionFields + stackCreations + ingredientCreations * 2 + fluidCreations + codecRefs;
        boolean recipeLike = (ingredientFields + ingredientCreations > 0) && (stackFields + stackCreations + fluidFields + fluidCreations > 0);
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
