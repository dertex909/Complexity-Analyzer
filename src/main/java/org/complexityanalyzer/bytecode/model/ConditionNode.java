package org.complexityanalyzer.bytecode.model;

public final class ConditionNode {
    public enum ConditionType {
        ITEM_HELD,
        BLOCK_TARGET,
        ENTITY_TARGET,
        INVENTORY_STATE,
        NBT_CHECK,
        GENERIC
    }

    private final ConditionType type;
    private final String target;
    private final boolean negated;

    private ConditionNode(Builder builder) {
        this.type = builder.type;
        this.target = builder.target;
        this.negated = builder.negated;
    }

    public ConditionType type() {
        return type;
    }

    public String target() {
        return target;
    }

    public boolean negated() {
        return negated;
    }

    public static final class Builder {
        private ConditionType type = ConditionType.GENERIC;
        private String target = "";
        private final boolean negated = false;

        public Builder type(ConditionType v) {
            this.type = v;
            return this;
        }

        public Builder target(String v) {
            this.target = v;
            return this;
        }

        public ConditionNode build() {
            return new ConditionNode(this);
        }
    }
}
