package org.complexityanalyzer.bytecode.model;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

public final class MachineNode {
    private final String className;
    private final String modId;
    private final ObjectList<String> inputItems;
    private final ObjectList<String> outputItems;
    private final boolean deterministic;

    private MachineNode(Builder builder) {
        this.className = builder.className;
        this.modId = builder.modId;
        this.inputItems = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.inputItems));
        this.outputItems = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.outputItems));
        this.deterministic = builder.deterministic;
    }

    public String className() {
        return className;
    }

    public String modId() {
        return modId;
    }

    public ObjectList<String> inputItems() {
        return inputItems;
    }

    public ObjectList<String> outputItems() {
        return outputItems;
    }

    public boolean deterministic() {
        return deterministic;
    }

    public static final class Builder {
        private String className = "";
        private String modId = "";
        private final ObjectList<String> inputItems = new ObjectArrayList<>();
        private final ObjectList<String> outputItems = new ObjectArrayList<>();
        private boolean deterministic = true;

        public Builder className(String v) {
            this.className = v;
            return this;
        }

        public Builder modId(String v) {
            this.modId = v;
            return this;
        }

        public void addInput(String v) {
            this.inputItems.add(v);
        }

        public void addOutput(String v) {
            this.outputItems.add(v);
        }

        public void deterministic(boolean v) {
            this.deterministic = v;
        }

        public MachineNode build() {
            return new MachineNode(this);
        }
    }
}
