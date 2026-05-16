package org.complexityanalyzer.bytecode.model;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

public final class EventNode {
    private final String eventType;
    private final String methodName;
    private final String className;
    private final String modId;
    private final ObjectList<ConditionNode> conditions;
    private final ObjectList<ActionNode> actions;

    private EventNode(Builder builder) {
        this.eventType = builder.eventType;
        this.methodName = builder.methodName;
        this.className = builder.className;
        this.modId = builder.modId;
        this.conditions = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.conditions));
        this.actions = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.actions));
    }

    public String eventType() {
        return eventType;
    }

    public String methodName() {
        return methodName;
    }

    public String className() {
        return className;
    }

    public String modId() {
        return modId;
    }

    public ObjectList<ConditionNode> conditions() {
        return conditions;
    }

    public ObjectList<ActionNode> actions() {
        return actions;
    }

    public static final class Builder {
        private String eventType = "";
        private String methodName = "";
        private String className = "";
        private String modId = "";
        private final ObjectList<ConditionNode> conditions = new ObjectArrayList<>();
        private final ObjectList<ActionNode> actions = new ObjectArrayList<>();

        public Builder eventType(String v) {
            this.eventType = v;
            return this;
        }

        public Builder methodName(String v) {
            this.methodName = v;
            return this;
        }

        public Builder className(String v) {
            this.className = v;
            return this;
        }

        public Builder modId(String v) {
            this.modId = v;
            return this;
        }

        public void addCondition(ConditionNode c) {
            this.conditions.add(c);
        }

        public void addAction(ActionNode a) {
            this.actions.add(a);
        }

        public EventNode build() {
            return new EventNode(this);
        }
    }
}
