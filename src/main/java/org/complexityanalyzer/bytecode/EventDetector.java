package org.complexityanalyzer.bytecode;

import it.unimi.dsi.fastutil.objects.*;
import org.complexityanalyzer.bytecode.model.EventNode;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.AnnotationNode;

import java.util.List;

public final class EventDetector {

    private static final String SUB_DESC = "Lnet/neoforged/bus/api/SubscribeEvent;";
    private static final String SUBSCRIBER_DESC = "Lnet/neoforged/fml/common/EventBusSubscriber;";

    private static final Object2ObjectMap<String, String> EVENT_MAP = new Object2ObjectOpenHashMap<>();

    static {
        EVENT_MAP.put("Lnet/neoforged/neoforge/event/entity/player/PlayerInteractEvent$RightClickBlock;", "RIGHT_CLICK_BLOCK");
        EVENT_MAP.put("Lnet/neoforged/neoforge/event/entity/player/PlayerInteractEvent$RightClickItem;", "RIGHT_CLICK_ITEM");
        EVENT_MAP.put("Lnet/neoforged/neoforge/event/entity/player/PlayerInteractEvent$RightClickEmpty;", "RIGHT_CLICK_EMPTY");
        EVENT_MAP.put("Lnet/neoforged/neoforge/event/entity/player/PlayerInteractEvent$LeftClickBlock;", "LEFT_CLICK_BLOCK");
        EVENT_MAP.put("Lnet/neoforged/neoforge/event/entity/player/PlayerInteractEvent$EntityInteract;", "ENTITY_INTERACT");
        EVENT_MAP.put("Lnet/neoforged/neoforge/event/entity/living/LivingDeathEvent;", "ENTITY_DEATH");
        EVENT_MAP.put("Lnet/neoforged/neoforge/event/entity/living/LivingHurtEvent;", "ENTITY_HURT");
        EVENT_MAP.put("Lnet/neoforged/neoforge/event/entity/player/PlayerEvent$ItemPickupEvent;", "ITEM_PICKUP");
        EVENT_MAP.put("Lnet/neoforged/neoforge/event/entity/player/PlayerEvent$ItemCraftedEvent;", "ITEM_CRAFTED");
        EVENT_MAP.put("Lnet/neoforged/neoforge/event/level/BlockEvent$BreakEvent;", "BLOCK_BREAK");
        EVENT_MAP.put("Lnet/neoforged/neoforge/event/level/BlockEvent$EntityPlaceEvent;", "BLOCK_PLACE");
        EVENT_MAP.put("Lnet/neoforged/neoforge/event/tick/LevelTickEvent;", "LEVEL_TICK");
        EVENT_MAP.put("Lnet/neoforged/neoforge/event/tick/ServerTickEvent;", "SERVER_TICK");
    }

    private EventDetector() {
    }

    public static ObjectList<EventNode> detectEvents(BytecodeAnalyzer.AnalyzedClass clazz, String modId) {
        var events = new ObjectArrayList<EventNode>();
        if (clazz == null) return events;

        boolean classSub = clazz.visibleAnnotations() != null && hasAnn(clazz.visibleAnnotations(), SUBSCRIBER_DESC);

        for (var m : clazz.methods()) {
            if ((m.access() & Opcodes.ACC_STATIC) == 0 && !classSub) continue;
            if (m.visibleAnnotations() == null) continue;
            if (!hasAnn(m.visibleAnnotations(), SUB_DESC)) continue;

            String et = extractEventType(m.descriptor());
            if (et == null) continue;

            events.add(new EventNode.Builder().eventType(et).methodName(m.name()).className(clazz.className()).modId(modId).build());
        }
        return events;
    }

    static String extractEventType(String desc) {
        org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(desc);
        if (args.length == 0) return null;
        String d = args[0].getDescriptor();

        String known = EVENT_MAP.get(d);
        if (known != null) return known;

        if (d.contains("PlayerInteractEvent")) {
            if (d.contains("RightClickBlock")) return "RIGHT_CLICK_BLOCK";
            if (d.contains("RightClickItem")) return "RIGHT_CLICK_ITEM";
            if (d.contains("RightClickEmpty")) return "RIGHT_CLICK_EMPTY";
            if (d.contains("LeftClickBlock")) return "LEFT_CLICK_BLOCK";
            if (d.contains("EntityInteract")) return "ENTITY_INTERACT";
            return "PLAYER_INTERACT";
        }
        if (d.contains("LivingDeathEvent")) return "ENTITY_DEATH";
        if (d.contains("LivingHurtEvent")) return "ENTITY_HURT";
        if (d.contains("BlockEvent")) return "BLOCK_EVENT";
        if (d.contains("TickEvent")) return "TICK";

        String sn = d.substring(d.lastIndexOf('/') + 1).replace(";", "");
        return sn.replace("Event", "").toUpperCase();
    }

    private static boolean hasAnn(List<AnnotationNode> anns, String desc) {
        for (var a : anns) if (a.desc.equals(desc)) return true;
        return false;
    }
}