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
            if ((m.access() & Opcodes.ACC_STATIC) == 0 && !classSub) continue;
            if (m.visibleAnnotations() == null) continue;
            if (!hasAnn(m.visibleAnnotations(), SUB_DESC)) continue;

            MethodRef methodRef = new MethodRef(clazz.className(), m.name(), m.descriptor());
            SemanticProfile profile = profiles != null ? profiles.get(methodRef) : null;

            String et = inferEventType(m.descriptor(), profile);
            if (et == null) continue;

            events.add(new EventNode.Builder().eventType(et).methodName(m.name()).className(clazz.className()).modId(modId).build());
        }
        return events;
    }

    static String inferEventType(String descriptor, SemanticProfile profile) {
        // First: use argument type as fallback (kept for compatibility)
        org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(descriptor);
        if (args.length == 0) return null;
        String argDesc = args[0].getDescriptor();

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

        String sn = argDesc.substring(argDesc.lastIndexOf('/') + 1).replace(";", "");
        return sn.replace("Event", "").toUpperCase();
    }

    private static boolean hasAnn(List<AnnotationNode> anns, String desc) {
        for (var a : anns) if (a.desc.equals(desc)) return true;
        return false;
    }
}