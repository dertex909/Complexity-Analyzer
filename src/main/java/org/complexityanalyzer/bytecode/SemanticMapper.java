package org.complexityanalyzer.bytecode;

import org.complexityanalyzer.bytecode.model.ConditionNode;
import org.complexityanalyzer.bytecode.model.ActionNode;

public final class SemanticMapper {

    private SemanticMapper() {
    }

    public record SemanticCondition(String category, String value, String humanReadable) {
    }

    public static SemanticCondition mapCondition(ConditionNode cond) {
        String cat = switch (cond.type()) {
            case ITEM_HELD -> "holding";
            case BLOCK_TARGET -> "block";
            case ENTITY_TARGET -> "entity";
            case INVENTORY_STATE -> "inventory";
            case NBT_CHECK -> "nbt";
            case GENERIC -> "generic";
        };
        String hr = cat + ":" + cond.target();
        if (cond.negated()) hr = "NOT(" + hr + ")";
        return new SemanticCondition(cat, cond.target(), hr);
    }

    public record SemanticAction(String category, String target, int count, String humanReadable) {
    }

    public static SemanticAction mapAction(ActionNode action) {
        String cat = switch (action.type()) {
            case GIVE_ITEM -> "give_item";
            case REMOVE_BLOCK -> "remove_block";
            case SPAWN_ENTITY -> "spawn_entity";
            case REMOVE_ITEM -> "remove_item";
            case SET_BLOCK -> "set_block";
            case DAMAGE_ITEM -> "damage_item";
            case CONSUME_ITEM -> "consume_item";
            case PLAY_SOUND -> "play_sound";
            case SEND_MESSAGE -> "send_message";
            case MODIFY_NBT -> "modify_nbt";
        };
        String hr = cat + ":" + action.target();
        if (action.count() > 1) hr += " x" + action.count();
        return new SemanticAction(cat, action.target(), action.count(), hr);
    }

    public static String normalizeItemId(String raw) {
        if (raw == null || raw.isEmpty()) return "unknown";
        raw = raw.toLowerCase().replace('/', ':').replace('.', ':');
        if (raw.contains(":")) return raw;
        return "minecraft:" + raw;
    }
}
