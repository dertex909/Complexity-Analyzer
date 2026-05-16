package org.complexityanalyzer.bytecode;

import it.unimi.dsi.fastutil.objects.*;
import org.complexityanalyzer.bytecode.graph.MethodRef;
import org.complexityanalyzer.bytecode.model.ActionNode;
import org.objectweb.asm.tree.*;

import java.util.EnumSet;

public final class EffectExtractor {

    private static final EnumSet<SemanticTag> EFFECT_TAGS = EnumSet.of(
            SemanticTag.WORLD_MUTATION,
            SemanticTag.ENTITY_SPAWN,
            SemanticTag.ENTITY_KILL,
            SemanticTag.ITEM_CONSUME,
            SemanticTag.ITEM_PRODUCE,
            SemanticTag.ITEM_GIVE,
            SemanticTag.ENERGY_CONSUME,
            SemanticTag.ENERGY_PRODUCE,
            SemanticTag.FLUID_CONSUME,
            SemanticTag.FLUID_PRODUCE,
            SemanticTag.PLAYER_HURT,
            SemanticTag.PLAYER_HEAL,
            SemanticTag.GUI_OPEN,
            SemanticTag.PLAY_SOUND,
            SemanticTag.SEND_MESSAGE
    );

    private EffectExtractor() {
    }

    public static ObjectList<ActionNode> extract(BytecodeAnalyzer.AnalyzedMethod method, SemanticAnchorRegistry registry) {
        var actions = new ObjectArrayList<ActionNode>();
        if (method.instructions() == null || registry == null) return actions;

        for (var insn : method.instructions()) {
            if (!(insn instanceof MethodInsnNode min)) continue;

            SemanticTag tag = registry.resolve(new MethodRef(min.owner, min.name, min.desc));
            if (tag == null || !EFFECT_TAGS.contains(tag)) continue;

            var builder = new ActionNode.Builder().type(mapTagToActionType(tag));
            String target = extractTargetFromDesc(min.desc, min.owner);
            if (target != null) builder.target(target);

            int count = estimateCount(min);
            builder.count(count);

            actions.add(builder.build());
        }
        return actions;
    }

    private static ActionNode.ActionType mapTagToActionType(SemanticTag tag) {
        return switch (tag) {
            case WORLD_MUTATION -> ActionNode.ActionType.SET_BLOCK;
            case ENTITY_SPAWN -> ActionNode.ActionType.SPAWN_ENTITY;
            case ENTITY_KILL -> ActionNode.ActionType.REMOVE_BLOCK;
            case ITEM_CONSUME -> ActionNode.ActionType.CONSUME_ITEM;
            case ITEM_PRODUCE, ITEM_GIVE -> ActionNode.ActionType.GIVE_ITEM;
            case ENERGY_CONSUME, ENERGY_PRODUCE -> ActionNode.ActionType.MODIFY_NBT;
            case FLUID_CONSUME, FLUID_PRODUCE -> ActionNode.ActionType.MODIFY_NBT;
            case PLAYER_HURT -> ActionNode.ActionType.DAMAGE_ITEM;
            case PLAYER_HEAL -> ActionNode.ActionType.CONSUME_ITEM;
            case GUI_OPEN -> ActionNode.ActionType.MODIFY_NBT;
            case PLAY_SOUND -> ActionNode.ActionType.PLAY_SOUND;
            case SEND_MESSAGE -> ActionNode.ActionType.SEND_MESSAGE;
            default -> ActionNode.ActionType.MODIFY_NBT;
        };
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