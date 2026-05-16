package org.complexityanalyzer.bytecode;

import it.unimi.dsi.fastutil.objects.*;
import org.complexityanalyzer.bytecode.model.ActionNode;
import org.objectweb.asm.tree.*;

public final class EffectExtractor {

    private static final Object2ObjectMap<String, ActionNode.ActionType> EFFECT_METHODS = new Object2ObjectOpenHashMap<>();

    static {
        EFFECT_METHODS.put("setBlockState", ActionNode.ActionType.SET_BLOCK);
        EFFECT_METHODS.put("setBlock", ActionNode.ActionType.SET_BLOCK);
        EFFECT_METHODS.put("destroyBlock", ActionNode.ActionType.REMOVE_BLOCK);
        EFFECT_METHODS.put("removeBlock", ActionNode.ActionType.REMOVE_BLOCK);
        EFFECT_METHODS.put("spawnEntity", ActionNode.ActionType.SPAWN_ENTITY);
        EFFECT_METHODS.put("addFreshEntity", ActionNode.ActionType.SPAWN_ENTITY);
        EFFECT_METHODS.put("addItem", ActionNode.ActionType.GIVE_ITEM);
        EFFECT_METHODS.put("addItemStackToInventory", ActionNode.ActionType.GIVE_ITEM);
        EFFECT_METHODS.put("give", ActionNode.ActionType.GIVE_ITEM);
        EFFECT_METHODS.put("shrink", ActionNode.ActionType.REMOVE_ITEM);
        EFFECT_METHODS.put("consume", ActionNode.ActionType.CONSUME_ITEM);
        EFFECT_METHODS.put("hurtAndBreak", ActionNode.ActionType.DAMAGE_ITEM);
        EFFECT_METHODS.put("setDamage", ActionNode.ActionType.DAMAGE_ITEM);
        EFFECT_METHODS.put("playSound", ActionNode.ActionType.PLAY_SOUND);
        EFFECT_METHODS.put("sendSystemMessage", ActionNode.ActionType.SEND_MESSAGE);
        EFFECT_METHODS.put("displayClientMessage", ActionNode.ActionType.SEND_MESSAGE);
    }

    private static final String[] ITEM_CLASSES = {
            "net/minecraft/world/item/ItemStack",
            "net/minecraft/world/item/Item",
            "net/neoforged/neoforge/items/ItemStackHandler"
    };

    private static final String[] WORLD_CLASSES = {
            "net/minecraft/world/level/Level",
            "net/minecraft/server/level/ServerLevel",
            "net/minecraft/world/level/LevelAccessor"
    };

    private EffectExtractor() {
    }

    public static ObjectList<ActionNode> extract(BytecodeAnalyzer.AnalyzedMethod method) {
        var actions = new ObjectArrayList<ActionNode>();
        if (method.instructions() == null) return actions;

        for (var insn : method.instructions()) {
            if (!(insn instanceof MethodInsnNode min)) continue;

            var at = EFFECT_METHODS.get(min.name);
            if (at == null) continue;

            boolean isWorldCall = isWorldClass(min.owner);
            boolean isItemCall = isItemClass(min.owner);
            if (!isWorldCall && !isItemCall) continue;


            var builder = new ActionNode.Builder().type(at);
            String target = extractTargetFromDesc(min.desc, min.owner);
            if (target != null) builder.target(target);

            int count = estimateCount(min);
            builder.count(count);

            actions.add(builder.build());
        }
        return actions;
    }

    private static boolean isWorldClass(String owner) {
        for (var wc : WORLD_CLASSES) if (owner.equals(wc)) return true;
        return owner.contains("Level") || owner.contains("World");
    }

    private static boolean isItemClass(String owner) {
        for (var ic : ITEM_CLASSES) if (owner.equals(ic)) return true;
        return owner.contains("Item");
    }

    private static String extractTargetFromDesc(String desc, String owner) {
        if (desc.contains("Lnet/minecraft/world/level/block/state/BlockState")) return "block";
        if (desc.contains("Lnet/minecraft/world/item/ItemStack")) return "item";
        if (desc.contains("Lnet/minecraft/world/entity/Entity")) return "entity";
        return owner;
    }

    private static int estimateCount(MethodInsnNode min) {
        if (min.name.equals("shrink")) {
            var prev = min.getPrevious();
            if (prev instanceof IntInsnNode iin) return iin.operand;
            if (prev instanceof LdcInsnNode ldc && ldc.cst instanceof Integer ii) return ii;
        }
        return 1;
    }
}