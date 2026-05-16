package org.complexityanalyzer.bytecode.model;

public final class ActionNode {
    public enum ActionType {
        GIVE_ITEM,
        REMOVE_BLOCK,
        SPAWN_ENTITY,
        REMOVE_ITEM,
        SET_BLOCK,
        DAMAGE_ITEM,
        CONSUME_ITEM,
        PLAY_SOUND,
        SEND_MESSAGE,
        MODIFY_NBT
    }

    private final ActionType type;
    private final String target;
    private final int count;

    private ActionNode(Builder builder) {
        this.type = builder.type;
        this.target = builder.target;
        this.count = builder.count;
    }

    public ActionType type() {
        return type;
    }

    public String target() {
        return target;
    }

    public int count() {
        return count;
    }

    public static final class Builder {
        private ActionType type = ActionType.GIVE_ITEM;
        private String target = "";
        private int count = 1;

        public Builder type(ActionType v) {
            this.type = v;
            return this;
        }

        public Builder target(String v) {
            this.target = v;
            return this;
        }

        public Builder count(int v) {
            this.count = v;
            return this;
        }

        public ActionNode build() {
            return new ActionNode(this);
        }
    }
}