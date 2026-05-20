package org.complexityanalyzer.harvest;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public final class AntivirusStyleDetector {
    private static final ConcurrentHashMap<Class<?>, CompositeDetection> DETECT_CACHE = new ConcurrentHashMap<>(512);

    public record CompositeDetection(
            PatternSignatureEngine.DetectionLevel level,
            int signatureConfidence,
            int heuristicConfidence,
            int behavioralConfidence,
            int totalConfidence,
            boolean isRecipe,
            boolean isMachine,
            boolean isCodec,
            String verdict,
            ObjectList<String> allEvidence
    ) {
        public static final CompositeDetection UNKNOWN = new CompositeDetection(
                PatternSignatureEngine.DetectionLevel.UNKNOWN,
                0, 0, 0, 0, false, false, false,
                "No patterns detected at any level",
                ObjectLists.emptyList()
        );

        @Override
        public @NotNull String toString() {
            return String.format(Locale.ROOT,
                    "CompositeDetection[level=%s sig=%d heur=%d behav=%d total=%d recipe=%s machine=%s codec=%s verdict=%s]",
                    level, signatureConfidence, heuristicConfidence, behavioralConfidence,
                    totalConfidence, isRecipe, isMachine, isCodec, verdict);
        }
    }

    private AntivirusStyleDetector() {
    }

    public static CompositeDetection detect(Class<?> clazz) {
        if (clazz == null) return CompositeDetection.UNKNOWN;
        CompositeDetection existing = DETECT_CACHE.get(clazz);
        if (existing != null) return existing;
        CompositeDetection result = performDetection(clazz);
        DETECT_CACHE.put(clazz, result);
        return result;
    }

    public static void clearCache() {
        DETECT_CACHE.clear();
    }

    private static CompositeDetection performDetection(Class<?> clazz) {
        ObjectList<String> allEvidence = new ObjectArrayList<>();
        int sigConfidence = 0;
        PatternSignatureEngine.DetectionLevel highestLevel = PatternSignatureEngine.DetectionLevel.UNKNOWN;
        if (Recipe.class.isAssignableFrom(clazz)) {
            sigConfidence = 100;
            highestLevel = PatternSignatureEngine.DetectionLevel.SIGNATURE;
            allEvidence.add("SIGNATURE[L1]: implements Recipe<?>");
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            String name = iface.getSimpleName();
            if (name.contains("Recipe") || name.contains("Crafting") || name.contains("Processing")) {
                if (sigConfidence < 90) {
                    sigConfidence = Math.max(sigConfidence, 90);
                    highestLevel = PatternSignatureEngine.DetectionLevel.SIGNATURE;
                }
                allEvidence.add("SIGNATURE[L1]: implements " + iface.getName());
            }
        }
        PatternSignatureEngine.ClassProfile profile = PatternSignatureEngine.profile(clazz);
        int heuristicConf = profile.heuristicScore();
        allEvidence.add("HEURISTIC[L2]: score=" + heuristicConf
                + " itemF=" + profile.itemStackFields() + " ingrF=" + profile.ingredientFields()
                + " fluidF=" + profile.fluidStackFields()
                + " itemM=" + profile.itemStackMethods() + " ingrM=" + profile.ingredientMethods()
                + " fluidM=" + profile.fluidStackMethods());
        if (heuristicConf > 0 && highestLevel == PatternSignatureEngine.DetectionLevel.UNKNOWN) {
            highestLevel = PatternSignatureEngine.DetectionLevel.HEURISTIC;
        }
        allEvidence.addAll(profile.evidence());
        int behavioralConf = 0;
        String pkgName = clazz.getPackage() != null ? clazz.getPackage().getName() : "";
        if (pkgName.contains("recipe") || pkgName.contains("crafting")) {
            behavioralConf += 10;
            allEvidence.add("BEHAVIORAL[L3]: package contains 'recipe/crafting'");
        }
        String simpleName = clazz.getSimpleName();
        if (simpleName.contains("Recipe")) {
            behavioralConf += 10;
            allEvidence.add("BEHAVIORAL[L3]: class name contains 'Recipe'");
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            String iname = iface.getSimpleName();
            if (iname.contains("Input") || iname.contains("Output")) {
                behavioralConf += 5;
                allEvidence.add("BEHAVIORAL[L3]: implements " + iname);
            }
        }
        int totalConf = Math.min(sigConfidence + heuristicConf + behavioralConf, 100);
        boolean isRecipe;
        boolean isMachine;
        boolean isCodec;
        if (sigConfidence >= 100) {
            isRecipe = true;
            isMachine = false;
            isCodec = false;
        } else if (profile.isRecipe()) {
            isRecipe = true;
            isMachine = profile.isMachine();
            isCodec = profile.isCodec();
        } else if (profile.isMachine()) {
            isRecipe = false;
            isMachine = true;
            isCodec = profile.isCodec();
        } else if (profile.isCodec()) {
            isRecipe = false;
            isMachine = false;
            isCodec = true;
        } else {
            isRecipe = (profile.ingredientFields() + profile.ingredientMethods() > 0)
                    && (profile.itemStackFields() + profile.itemStackMethods()
                    + profile.fluidStackFields() + profile.fluidStackMethods() > 0);
            isMachine = !isRecipe && (profile.itemStackFields() + profile.fluidStackFields() > 0)
                    && profile.heuristicScore() > 25;
            isCodec = profile.codecRefs() > 0 && !isRecipe && !isMachine;
        }
        String verdict;
        if (isRecipe) {
            verdict = "RECIPE (L" + (sigConfidence >= 100 ? "1:SIGNATURE" : "2:HEURISTIC") + ")";
        } else if (isMachine) {
            verdict = "MACHINE (L2:HEURISTIC)";
        } else if (isCodec) {
            verdict = "CODEC (L2:HEURISTIC)";
        } else {
            verdict = "UNKNOWN";
        }
        return new CompositeDetection(
                highestLevel,
                sigConfidence, heuristicConf, behavioralConf, totalConf,
                isRecipe, isMachine, isCodec,
                verdict, allEvidence
        );
    }
}