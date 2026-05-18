package org.complexityanalyzer.bytecode;

import it.unimi.dsi.fastutil.objects.*;
import org.complexityanalyzer.bytecode.graph.MethodRef;
import org.complexityanalyzer.bytecode.model.EventNode;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.AnnotationNode;

import java.util.List;

public final class EventDetector {

    private static final String SUB_DESC = "Lnet/neoforged/bus/api/SubscribeEvent;";
    private static final String SUBSCRIBER_DESC = "Lnet/neoforged/fml/common/EventBusSubscriber;";

    private EventDetector() {
    }

    public static ObjectList<EventNode> detectEvents(BytecodeAnalyzer.AnalyzedClass clazz, String modId,
                                                      Object2ObjectMap<MethodRef, SemanticProfile> profiles) {
        var events = new ObjectArrayList<EventNode>();
        if (clazz == null) return events;

        boolean classSub = clazz.visibleAnnotations() != null && hasAnn(clazz.visibleAnnotations(), SUBSCRIBER_DESC);

        for (var m : clazz.methods()) {
            MethodRef methodRef = new MethodRef(clazz.className(), m.name(), m.descriptor());
            SemanticProfile profile = profiles != null ? profiles.get(methodRef) : null;
            boolean methodSub = m.visibleAnnotations() != null && hasAnn(m.visibleAnnotations(), SUB_DESC);
            boolean annotatedHandler = methodSub && (((m.access() & Opcodes.ACC_STATIC) != 0) || classSub);
            boolean descriptorHandler = hasEventArgument(m.descriptor());
            boolean semanticHandler = isSemanticCallback(profile);

            if (!annotatedHandler && !descriptorHandler && !semanticHandler) continue;

            String et = inferEventType(m.descriptor(), profile, m.name());
            if (et == null) continue;

            events.add(new EventNode.Builder().eventType(et).methodName(m.name()).className(clazz.className()).modId(modId).build());
        }
        return events;
    }

    static String inferEventType(String descriptor, SemanticProfile profile, String methodName) {
        // First: use argument type as fallback (kept for compatibility)
        org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(descriptor);
        String argDesc = args.length > 0 ? args[0].getDescriptor() : "";

        // Second: use semantic profile if available
        if (profile != null) {
            if (profile.hasTag(SemanticTag.PLAYER_INTERACT)) {
                if (profile.hasTag(SemanticTag.WORLD_MUTATION)) {
                    if (argDesc.contains("LeftClickBlock")) return "LEFT_CLICK_BLOCK";
                    if (argDesc.contains("RightClickBlock")) return "RIGHT_CLICK_BLOCK";
                    return "PLAYER_BLOCK_INTERACT";
                }
                if (profile.hasTag(SemanticTag.ITEM_GIVE) || profile.hasTag(SemanticTag.ITEM_CONSUME)) {
                    if (argDesc.contains("RightClickItem")) return "RIGHT_CLICK_ITEM";
                    return "PLAYER_ITEM_INTERACT";
                }
                if (argDesc.contains("EntityInteract")) return "ENTITY_INTERACT";
                return "PLAYER_INTERACT";
            }
            if (profile.hasTag(SemanticTag.ENTITY_KILL) || profile.hasTag(SemanticTag.ENTITY_SPAWN)) {
                return "ENTITY_DEATH";
            }
            if (profile.hasTag(SemanticTag.PLAYER_HURT)) {
                return "ENTITY_HURT";
            }
            if (profile.hasTag(SemanticTag.WORLD_MUTATION)) {
                return "BLOCK_EVENT";
            }
            if (profile.hasTag(SemanticTag.GUI_OPEN)) {
                return "GUI_OPEN";
            }
            if (profile.hasTag(SemanticTag.LOOT_TABLE_CHECK)) {
                return "LOOT_EVENT";
            }
            if (profile.hasTag(SemanticTag.MACHINE_DEFINITION) || profile.hasTag(SemanticTag.MACHINE_RECIPE_TYPE)
                    || profile.hasTag(SemanticTag.MACHINE_WORKABLE_LOGIC)) {
                return "MACHINE_CALLBACK";
            }
            if (profile.hasTag(SemanticTag.RECIPE_BUILDER_START) || profile.hasTag(SemanticTag.RECIPE_INPUT)
                    || profile.hasTag(SemanticTag.RECIPE_OUTPUT)) {
                return "RECIPE_REGISTRATION";
            }
            if (profile.hasTag(SemanticTag.CAPABILITY_CHECK) || profile.hasTag(SemanticTag.MACHINE_CAPABILITY)) {
                return "CAPABILITY_EVENT";
            }
            if (profile.hasTag(SemanticTag.TICK)) {
                return "TICK";
            }
        }

        // Fallback to argument type inference
        if (argDesc.contains("PlayerInteractEvent")) {
            if (argDesc.contains("RightClickBlock")) return "RIGHT_CLICK_BLOCK";
            if (argDesc.contains("RightClickItem")) return "RIGHT_CLICK_ITEM";
            if (argDesc.contains("RightClickEmpty")) return "RIGHT_CLICK_EMPTY";
            if (argDesc.contains("LeftClickBlock")) return "LEFT_CLICK_BLOCK";
            if (argDesc.contains("EntityInteract")) return "ENTITY_INTERACT";
            return "PLAYER_INTERACT";
        }
        if (argDesc.contains("LivingDeathEvent")) return "ENTITY_DEATH";
        if (argDesc.contains("LivingHurtEvent")) return "ENTITY_HURT";
        if (argDesc.contains("BlockEvent")) return "BLOCK_EVENT";
        if (argDesc.contains("TickEvent")) return "TICK";
        if (argDesc.contains("RegisterEvent")) return "REGISTRY_EVENT";
        if (argDesc.contains("LifecycleEvent") || argDesc.contains("FMLCommonSetupEvent") || argDesc.contains("FMLClientSetupEvent")) return "LIFECYCLE_EVENT";
        if (argDesc.contains("RecipesUpdatedEvent")) return "RECIPE_UPDATE";
        if (argDesc.contains("ScreenEvent") || argDesc.contains("ContainerScreenEvent")) return "GUI_EVENT";

        if (!argDesc.isEmpty()) {
            String sn = argDesc.substring(argDesc.lastIndexOf('/') + 1).replace(";", "");
            return sn.replace("Event", "").toUpperCase();
        }

        if (methodName != null) {
            String lower = methodName.toLowerCase();
            if (lower.contains("register")) return "REGISTRATION_CALLBACK";
            if (lower.contains("init") || lower.contains("setup")) return "LIFECYCLE_CALLBACK";
            if (lower.contains("tick")) return "TICK";
            if (lower.contains("open") || lower.contains("menu") || lower.contains("screen")) return "GUI_CALLBACK";
        }

        return null;
    }

    private static boolean hasEventArgument(String descriptor) {
        org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(descriptor);
        for (var arg : args) {
            String desc = arg.getDescriptor();
            if (desc.contains("Event") || desc.contains("Callback") || desc.contains("Listener")) return true;
        }
        return false;
    }

    private static boolean isSemanticCallback(SemanticProfile profile) {
        if (profile == null) return false;
        if (profile.confidence(SemanticTag.PLAYER_INTERACT) >= 0.7) return true;
        if (profile.confidence(SemanticTag.WORLD_MUTATION) >= 0.7) return true;
        if (profile.confidence(SemanticTag.GUI_OPEN) >= 0.7) return true;
        if (profile.confidence(SemanticTag.LOOT_TABLE_CHECK) >= 0.7) return true;
        if (profile.confidence(SemanticTag.RECIPE_BUILDER_START) >= 0.7) return true;
        if (profile.confidence(SemanticTag.MACHINE_DEFINITION) >= 0.7) return true;
        if (profile.confidence(SemanticTag.MACHINE_RECIPE_TYPE) >= 0.7) return true;
        if (profile.confidence(SemanticTag.MACHINE_WORKABLE_LOGIC) >= 0.7) return true;
        if (profile.confidence(SemanticTag.MACHINE_CAPABILITY) >= 0.7) return true;
        if (profile.confidence(SemanticTag.CAPABILITY_CHECK) >= 0.7) return true;
        return false;
    }

    private static boolean hasAnn(List<AnnotationNode> anns, String desc) {
        for (var a : anns) if (a.desc.equals(desc)) return true;
        return false;
    }
}